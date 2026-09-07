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
        val aiProblems: Int,
        val saveFailed: Int,
        val cancelled: Boolean,
        val errorMessage: String? = null,
        val firstAiError: String? = null
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
        fun onProgress(
            done: Int,
            total: Int,
            indexed: Int,
            skipped: Int,
            aiProblems: Int,
            saveFailed: Int
        )
        fun onFinished(summary: IndexSummary)
    }

    fun indexAll(callback: IndexCallback) {
        cancelled.set(false)
        indexExecutor.execute {
            var indexed = 0
            var skipped = 0
            var aiProblems = 0
            var saveFailed = 0
            var done = 0
            var firstAiError: String? = null

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
                        val analysis = try {
                            analyzer.analyze(uri)
                        } catch (error: Exception) {
                            PhotoAnalyzer.Analysis(
                                labels = "",
                                ocr = "",
                                complete = false,
                                error = compactError(error)
                            )
                        }

                        if (!analysis.complete) {
                            aiProblems++
                            if (firstAiError == null) firstAiError = analysis.error ?: "Nieznany błąd analizy AI"
                        }

                        try {
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
                                ),
                                analysisComplete = analysis.complete,
                                analysisError = analysis.error
                            )
                            indexed++
                        } catch (_: Exception) {
                            saveFailed++
                        }
                    }

                    done++
                    if (done == entries.size || done % 5 == 0) {
                        callback.onProgress(
                            done,
                            entries.size,
                            indexed,
                            skipped,
                            aiProblems,
                            saveFailed
                        )
                    }
                }

                callback.onFinished(
                    IndexSummary(
                        indexed = indexed,
                        skipped = skipped,
                        aiProblems = aiProblems,
                        saveFailed = saveFailed,
                        cancelled = cancelled.get(),
                        firstAiError = firstAiError
                    )
                )
            } catch (security: SecurityException) {
                callback.onFinished(
                    IndexSummary(
                        indexed = indexed,
                        skipped = skipped,
                        aiProblems = aiProblems,
                        saveFailed = saveFailed,
                        cancelled = false,
                        errorMessage = "Android zmienił dostęp do zdjęć. Wybierz zdjęcia ponownie i uruchom indeksowanie.",
                        firstAiError = firstAiError
                    )
                )
            } catch (error: Exception) {
                callback.onFinished(
                    IndexSummary(
                        indexed = indexed,
                        skipped = skipped,
                        aiProblems = aiProblems,
                        saveFailed = saveFailed,
                        cancelled = false,
                        errorMessage = error.message ?: "Nie udało się odczytać galerii.",
                        firstAiError = firstAiError
                    )
                )
            }
        }
    }

    private fun compactError(error: Throwable): String {
        val type = error.javaClass.simpleName.ifBlank { "błąd" }
        val message = error.message
            ?.replace(Regex("\\s+"), " ")
            ?.take(180)
            ?.trim()
            .orEmpty()
        return if (message.isBlank()) type else "$type — $message"
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
