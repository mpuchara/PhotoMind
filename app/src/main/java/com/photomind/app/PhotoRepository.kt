package com.photomind.app

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class PhotoRepository(context: Context) {
    private val appContext = context.applicationContext
    private val database = PhotoDatabase(appContext)
    private val analyzer = PhotoAnalyzer(appContext)
    private val indexExecutor = Executors.newSingleThreadExecutor()
    private val queryExecutor = Executors.newFixedThreadPool(2)
    private val cancelled = AtomicBoolean(false)

    data class IndexSummary(
        val indexed: Int,
        val skipped: Int,
        val failed: Int,
        val cancelled: Boolean,
        val errorMessage: String? = null
    )

    private data class MediaEntry(
        val id: Long,
        val displayName: String,
        val bucket: String,
        val dateTaken: Long,
        val dateModified: Long
    )

    interface IndexCallback {
        fun onStarted(total: Int)
        fun onProgress(done: Int, total: Int, indexed: Int, skipped: Int, failed: Int)
        fun onFinished(summary: IndexSummary)
    }

    fun indexAll(callback: IndexCallback) {
        cancelled.set(false)
        indexExecutor.execute {
            var indexed = 0
            var skipped = 0
            var failed = 0
            var done = 0

            try {
                val entries = readAccessibleMedia()
                database.syncAvailability(entries.map { it.id })
                callback.onStarted(entries.size)

                for (entry in entries) {
                    if (cancelled.get()) break

                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, entry.id)
                    if (database.isUpToDate(entry.id, entry.dateModified)) {
                        skipped++
                    } else {
                        try {
                            val analysis = analyzer.analyze(uri)
                            database.upsert(
                                PhotoItem(
                                    mediaId = entry.id,
                                    uri = uri.toString(),
                                    displayName = entry.displayName,
                                    bucket = entry.bucket,
                                    dateTaken = entry.dateTaken,
                                    dateModified = entry.dateModified,
                                    labels = analysis.labels,
                                    ocr = analysis.ocr,
                                    userTags = ""
                                )
                            )
                            indexed++
                        } catch (_: Exception) {
                            failed++
                        }
                    }

                    done++
                    if (done == entries.size || done % 5 == 0) {
                        callback.onProgress(done, entries.size, indexed, skipped, failed)
                    }
                }

                callback.onFinished(IndexSummary(indexed, skipped, failed, cancelled.get()))
            } catch (security: SecurityException) {
                callback.onFinished(
                    IndexSummary(
                        indexed = indexed,
                        skipped = skipped,
                        failed = failed,
                        cancelled = false,
                        errorMessage = "Android zmienił dostęp do zdjęć. Wybierz zdjęcia ponownie i uruchom indeksowanie."
                    )
                )
            } catch (error: Exception) {
                callback.onFinished(
                    IndexSummary(
                        indexed = indexed,
                        skipped = skipped,
                        failed = failed,
                        cancelled = false,
                        errorMessage = error.message ?: "Nie udało się odczytać galerii."
                    )
                )
            }
        }
    }

    private fun readAccessibleMedia(): List<MediaEntry> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED
        )

        val entries = ArrayList<MediaEntry>()
        appContext.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)

            while (cursor.moveToNext()) {
                entries += MediaEntry(
                    id = cursor.getLong(idCol),
                    displayName = cursor.getString(nameCol).orEmpty(),
                    bucket = cursor.getString(bucketCol).orEmpty(),
                    dateTaken = cursor.getLong(takenCol),
                    dateModified = cursor.getLong(modifiedCol)
                )
            }
        } ?: throw IllegalStateException("Android nie udostępnił listy zdjęć.")

        return entries
    }

    fun cancelIndexing() {
        cancelled.set(true)
    }

    fun search(original: String, translated: String?, callback: (List<PhotoItem>) -> Unit) {
        queryExecutor.execute {
            if (original.isBlank()) {
                callback(database.recent())
                return@execute
            }

            val merged = LinkedHashMap<Long, PhotoDatabase.ScoredPhoto>()
            fun merge(hit: PhotoDatabase.ScoredPhoto) {
                val current = merged[hit.photo.mediaId]
                if (current == null || hit.score > current.score) {
                    merged[hit.photo.mediaId] = hit
                }
            }

            database.searchScored(original).forEach(::merge)
            if (!translated.isNullOrBlank() && !translated.equals(original, ignoreCase = true)) {
                database.searchScored(translated).forEach(::merge)
            }

            val ranked = merged.values
                .sortedWith(
                    compareByDescending<PhotoDatabase.ScoredPhoto> { it.score }
                        .thenByDescending { it.photo.dateTaken }
                )
                .take(300)
                .map { it.photo }

            callback(ranked)
        }
    }

    fun updateTags(photo: PhotoItem, tags: String, callback: () -> Unit) {
        queryExecutor.execute {
            database.updateUserTags(photo.mediaId, tags)
            photo.userTags = tags.trim()
            callback()
        }
    }

    fun indexedCount(): Int = database.count()

    fun close() {
        cancelIndexing()
        indexExecutor.shutdownNow()
        queryExecutor.shutdownNow()
        analyzer.close()
        database.close()
    }
}
