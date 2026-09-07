package com.photomind.app

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class PhotoRepository(context: Context) {
    private val appContext = context.applicationContext
    private val database = PhotoDatabase(appContext)
    private val analyzer = PhotoAnalyzer(appContext)
    private val indexExecutor = Executors.newSingleThreadExecutor()
    private val sceneExecutor = Executors.newSingleThreadExecutor()
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

    data class SceneSummary(
        val described: Int,
        val attempted: Int,
        val totalDone: Int,
        val totalPhotos: Int,
        val supported: Boolean,
        val retryLater: Boolean,
        val message: String? = null
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
        fun onProgress(done: Int, total: Int, indexed: Int, skipped: Int, aiProblems: Int, saveFailed: Int)
        fun onFinished(summary: IndexSummary)
    }

    interface SceneCallback {
        fun onProgress(done: Int, globalDone: Int, globalTotal: Int)
        fun onFinished(summary: SceneSummary)
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
                val clusterer = FaceClusterer(database)
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
                                error = compactError(error),
                                faceCount = 0,
                                faceEmbeddings = emptyList(),
                                facesComplete = false,
                                faceError = compactError(error)
                            )
                        }

                        val faceProblem = analysis.faceCount > 0 && !analysis.facesComplete
                        if (!analysis.complete || faceProblem) {
                            aiProblems++
                            if (firstAiError == null) {
                                firstAiError = analysis.error ?: analysis.faceError ?: "Nieznany błąd analizy AI"
                            }
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
                                    userTags = "",
                                    faceCount = analysis.faceCount
                                ),
                                analysisComplete = analysis.complete,
                                analysisError = listOfNotNull(analysis.error, analysis.faceError).joinToString("; ").ifBlank { null },
                                facesComplete = analysis.facesComplete
                            )
                            clusterer.assignPhoto(
                                photoId = entry.id,
                                photoUri = uri.toString(),
                                embeddings = analysis.faceEmbeddings,
                                complete = analysis.facesComplete
                            )
                            indexed++
                        } catch (_: Exception) {
                            saveFailed++
                        }
                    }

                    done++
                    if (done == entries.size || done % 5 == 0) {
                        callback.onProgress(done, entries.size, indexed, skipped, aiProblems, saveFailed)
                    }
                }

                callback.onFinished(
                    IndexSummary(indexed, skipped, aiProblems, saveFailed, cancelled.get(), firstAiError = firstAiError)
                )
            } catch (security: SecurityException) {
                callback.onFinished(
                    IndexSummary(
                        indexed, skipped, aiProblems, saveFailed, false,
                        errorMessage = "Android zmienił dostęp do zdjęć. Wybierz zdjęcia ponownie.",
                        firstAiError = firstAiError
                    )
                )
            } catch (error: Exception) {
                callback.onFinished(
                    IndexSummary(
                        indexed, skipped, aiProblems, saveFailed, false,
                        errorMessage = error.message ?: "Nie udało się odczytać galerii.",
                        firstAiError = firstAiError
                    )
                )
            }
        }
    }

    /**
     * Progressive foreground enrichment with Gemini Nano. We intentionally stop at a bounded batch:
     * AICore has per-app battery/usage quotas and blocks background GenAI inference.
     */
    fun enrichScenes(maxPerSession: Int = 60, callback: SceneCallback) {
        sceneExecutor.execute {
            val candidates = database.photosNeedingScene(maxPerSession)
            if (candidates.isEmpty()) {
                val progress = database.sceneProgress()
                callback.onFinished(SceneSummary(0, 0, progress.first, progress.second, true, false))
                return@execute
            }

            val describer = SceneDescriber(appContext)
            var described = 0
            var attempted = 0
            var supported = true
            var retryLater = false
            var message: String? = null
            try {
                for (photo in candidates) {
                    if (cancelled.get()) break
                    attempted++
                    val result = describer.describe(Uri.parse(photo.uri))
                    when {
                        result.description.isNotBlank() -> {
                            database.updateScene(photo.mediaId, result.description, complete = true)
                            described++
                        }
                        !result.supported -> {
                            supported = false
                            message = result.error
                            break
                        }
                        result.retryLater -> {
                            retryLater = true
                            message = result.error
                            break
                        }
                        else -> {
                            // Permanent problem for this image: don't let it block the queue forever.
                            database.updateScene(photo.mediaId, "", complete = true)
                            message = result.error ?: message
                        }
                    }
                    val progress = database.sceneProgress()
                    callback.onProgress(attempted, progress.first, progress.second)
                }
            } finally {
                describer.close()
            }
            val progress = database.sceneProgress()
            callback.onFinished(
                SceneSummary(described, attempted, progress.first, progress.second, supported, retryLater, message)
            )
        }
    }

    fun listPersonClusters(callback: (List<PersonCluster>) -> Unit) {
        queryExecutor.execute { callback(database.listPersonClusters()) }
    }

    fun namePersonCluster(clusterId: Long, name: String, callback: () -> Unit) {
        queryExecutor.execute {
            database.nameCluster(clusterId, name)
            callback()
        }
    }

    fun sceneProgress(): Pair<Int, Int> = database.sceneProgress()

    private fun compactError(error: Throwable): String {
        val type = error.javaClass.simpleName.ifBlank { "błąd" }
        val message = error.message?.replace(Regex("\\s+"), " ")?.take(180)?.trim().orEmpty()
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

    fun cancelIndexing() { cancelled.set(true) }

    fun search(original: String, translated: String?, callback: (List<PhotoItem>) -> Unit) {
        queryExecutor.execute {
            if (original.isBlank()) {
                callback(database.recent())
                return@execute
            }
            val merged = LinkedHashMap<Long, PhotoDatabase.ScoredPhoto>()
            fun merge(hit: PhotoDatabase.ScoredPhoto) {
                val current = merged[hit.photo.mediaId]
                if (current == null || hit.score > current.score) merged[hit.photo.mediaId] = hit
            }
            database.searchScored(original).forEach(::merge)
            if (!translated.isNullOrBlank() && !translated.equals(original, ignoreCase = true)) {
                database.searchScored(translated).forEach(::merge)
            }
            callback(
                merged.values
                    .sortedWith(compareByDescending<PhotoDatabase.ScoredPhoto> { it.score }.thenByDescending { it.photo.dateTaken })
                    .take(300)
                    .map { it.photo }
            )
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
        sceneExecutor.shutdownNow()
        queryExecutor.shutdownNow()
        analyzer.close()
        database.close()
    }
}
