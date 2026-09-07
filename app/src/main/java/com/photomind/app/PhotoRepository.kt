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
    private val executor = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean(false)

    data class IndexSummary(val indexed: Int, val skipped: Int, val failed: Int, val cancelled: Boolean)

    interface IndexCallback {
        fun onStarted(total: Int)
        fun onProgress(done: Int, total: Int, indexed: Int, skipped: Int, failed: Int)
        fun onFinished(summary: IndexSummary)
    }

    fun indexAll(callback: IndexCallback) {
        cancelled.set(false)
        executor.execute {
            var indexed = 0
            var skipped = 0
            var failed = 0
            var done = 0

            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_MODIFIED
            )

            appContext.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_TAKEN} DESC"
            )?.use { cursor ->
                val total = cursor.count
                callback.onStarted(total)

                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)

                while (cursor.moveToNext() && !cancelled.get()) {
                    val id = cursor.getLong(idCol)
                    val modified = cursor.getLong(modifiedCol)
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                    if (database.isUpToDate(id, modified)) {
                        skipped++
                    } else {
                        try {
                            val analysis = analyzer.analyze(uri)
                            database.upsert(
                                PhotoItem(
                                    mediaId = id,
                                    uri = uri.toString(),
                                    displayName = cursor.getString(nameCol).orEmpty(),
                                    bucket = cursor.getString(bucketCol).orEmpty(),
                                    dateTaken = cursor.getLong(takenCol),
                                    dateModified = modified,
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
                    if (done == total || done % 5 == 0) {
                        callback.onProgress(done, total, indexed, skipped, failed)
                    }
                }

                callback.onFinished(IndexSummary(indexed, skipped, failed, cancelled.get()))
            } ?: callback.onFinished(IndexSummary(0, 0, 1, false))
        }
    }

    fun cancelIndexing() {
        cancelled.set(true)
    }

    fun search(original: String, translated: String?, callback: (List<PhotoItem>) -> Unit) {
        executor.execute {
            if (original.isBlank()) {
                callback(database.recent())
                return@execute
            }
            val merged = LinkedHashMap<Long, PhotoItem>()
            database.search(original).forEach { merged[it.mediaId] = it }
            if (!translated.isNullOrBlank() && !translated.equals(original, ignoreCase = true)) {
                database.search(translated).forEach { merged[it.mediaId] = it }
            }
            callback(merged.values.sortedByDescending { it.dateTaken }.take(300))
        }
    }

    fun updateTags(photo: PhotoItem, tags: String, callback: () -> Unit) {
        executor.execute {
            database.updateUserTags(photo.mediaId, tags)
            photo.userTags = tags.trim()
            callback()
        }
    }

    fun indexedCount(): Int = database.count()

    fun close() {
        cancelIndexing()
        analyzer.close()
        database.close()
        executor.shutdownNow()
    }
}
