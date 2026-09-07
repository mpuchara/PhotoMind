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
    private val metadataIndex = MetadataIndex(appContext)
    private val analyzer = PhotoAnalyzer(appContext)
    private val indexExecutor = Executors.newSingleThreadExecutor()
    private val sceneExecutor = Executors.newSingleThreadExecutor()
    private val queryExecutor = Executors.newFixedThreadPool(2)
    private val cancelled = AtomicBoolean(false)

    init {
        database.prepareStructuredSceneMigration(appContext)
    }

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

                    // Time and GPS are read from file/media metadata independently from visual AI.
                    runCatching {
                        metadataIndex.ensure(entry.id, uri, entry.dateTaken, entry.dateModified)
                    }

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
                                analysisError = listOfNotNull(analysis.error, analysis.faceError)
                                    .joinToString("; ").ifBlank { null },
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

    /** Progressive foreground Gemini Nano tagging using the same canonical scene vocabulary as search. */
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
            database.nameCluster(clusterId, name.trim())
            // A changed identity label triggers an incremental re-pass so local face patterns are checked again.
            database.invalidateClusterForRename(clusterId)
            AutoIndexWorker.runNow(appContext)
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

            val intent = translated?.let(SearchIntent::decode)
            if (intent != null) {
                callback(searchStructured(original, intent))
                return@execute
            }

            // Compatibility fallback for old/non-structured callers.
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

    private fun searchStructured(original: String, rawIntent: SearchIntent): List<PhotoItem> {
        val knownNames = database.knownPersonNames()
        val people = rawIntent.people.mapNotNull { requested ->
            knownNames.firstOrNull { SearchText.fold(it) == SearchText.fold(requested) }
        }.distinctBy { SearchText.fold(it) }
        val sceneTags = rawIntent.sceneTags.filter { it in SceneTagCatalog.allowed }.distinct()
        val intent = rawIntent.copy(people = people, sceneTags = sceneTags)

        val metadataIds = metadataIndex.matchingIds(intent.year, intent.place)
        val textParts = intent.people + intent.sceneTags

        var candidates: List<PhotoItem> = when {
            textParts.isNotEmpty() -> database.searchScored(textParts.joinToString(" "), 1500).map { it.photo }
            metadataIds != null -> database.photosByIds(metadataIds, 5000)
            else -> database.searchScored(original, 1500).map { it.photo }
        }

        if (metadataIds != null) {
            candidates = candidates.filter { it.mediaId in metadataIds }
        }

        if (intent.people.isNotEmpty()) {
            candidates = candidates.filter { photo ->
                val haystack = SearchText.fold(photo.people)
                intent.people.all { name -> haystack.contains(SearchText.fold(name)) }
            }
        }

        if (intent.sceneTags.isNotEmpty()) {
            candidates = candidates.filter { photo ->
                val indexedScenes = SceneTagCatalog.sanitizeModelOutput(
                    listOf(photo.labels, photo.sceneDescription, photo.userTags).joinToString(" ")
                ).toSet()
                intent.sceneTags.all { it in indexedScenes }
            }
        }

        // If a place was requested but the file has no GPS, allow a narrow metadata-text fallback
        // (e.g. album/folder named "Rożnów") rather than inventing a visual location.
        if (candidates.isEmpty() && !intent.place.isNullOrBlank()) {
            val placeHits = database.searchScored(intent.place, 1000).map { it.photo }
            candidates = placeHits.filter { photo ->
                val yearOk = intent.year == null || yearFromMediaTimestamp(photo.dateTaken) == intent.year
                val peopleOk = intent.people.all { SearchText.fold(photo.people).contains(SearchText.fold(it)) }
                val scenes = SceneTagCatalog.sanitizeModelOutput(
                    listOf(photo.labels, photo.sceneDescription, photo.userTags).joinToString(" ")
                ).toSet()
                val sceneOk = intent.sceneTags.all { it in scenes }
                yearOk && peopleOk && sceneOk
            }
        }

        // When the model found no structured dimension, preserve OCR/custom-tag search behavior.
        if (intent.people.isEmpty() && intent.sceneTags.isEmpty() && intent.place == null && intent.year == null) {
            return database.searchScored(original).map { it.photo }.take(300)
        }

        return candidates.distinctBy { it.mediaId }.sortedByDescending { it.dateTaken }.take(300)
    }

    private fun yearFromMediaTimestamp(timestamp: Long): Int? {
        if (timestamp <= 0L) return null
        return java.text.SimpleDateFormat("yyyy", java.util.Locale.US)
            .format(java.util.Date(timestamp)).toIntOrNull()
    }

    fun updateTags(photo: PhotoItem, tags: String, callback: () -> Unit) {
        queryExecutor.execute {
            val normalized = SceneTagCatalog.normalizeUserTags(tags)
            database.updateUserTags(photo.mediaId, normalized)
            photo.userTags = normalized
            // Any manual correction invalidates this photo and starts a fresh incremental pattern pass.
            database.invalidatePhotoForRetag(photo.mediaId)
            AutoIndexWorker.runNow(appContext)
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
        metadataIndex.close()
        database.close()
    }
}
