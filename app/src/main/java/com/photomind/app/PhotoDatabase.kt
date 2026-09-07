package com.photomind.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PhotoDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    data class ScoredPhoto(val photo: PhotoItem, val score: Int)
    data class ClusterVector(
        val id: Long,
        val name: String,
        val centroid: FloatArray,
        val sampleCount: Int,
        val representativeUri: String
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE photos (
                media_id INTEGER PRIMARY KEY,
                uri TEXT NOT NULL,
                display_name TEXT NOT NULL DEFAULT '',
                bucket TEXT NOT NULL DEFAULT '',
                date_taken INTEGER NOT NULL DEFAULT 0,
                date_modified INTEGER NOT NULL DEFAULT 0,
                labels TEXT NOT NULL DEFAULT '',
                ocr TEXT NOT NULL DEFAULT '',
                user_tags TEXT NOT NULL DEFAULT '',
                available INTEGER NOT NULL DEFAULT 1,
                search_text TEXT NOT NULL DEFAULT '',
                analysis_complete INTEGER NOT NULL DEFAULT 0,
                analysis_error TEXT NOT NULL DEFAULT '',
                scene_description TEXT NOT NULL DEFAULT '',
                scene_complete INTEGER NOT NULL DEFAULT 0,
                face_count INTEGER NOT NULL DEFAULT 0,
                faces_complete INTEGER NOT NULL DEFAULT 0,
                people TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        createFaceTables(db)
        createIndexes(db)
    }

    private fun createFaceTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS person_clusters (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL DEFAULT '',
                centroid BLOB NOT NULL,
                sample_count INTEGER NOT NULL DEFAULT 1,
                representative_uri TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS faces (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                photo_id INTEGER NOT NULL,
                cluster_id INTEGER NOT NULL,
                embedding BLOB NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_faces_photo ON faces(photo_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_faces_cluster ON faces(cluster_id)")
    }

    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_photos_date ON photos(date_taken DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_photos_available ON photos(available)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_photos_analysis ON photos(analysis_complete)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_photos_scene ON photos(scene_complete)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_photos_faces ON photos(faces_complete)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE photos ADD COLUMN available INTEGER NOT NULL DEFAULT 1")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE photos ADD COLUMN search_text TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE photos ADD COLUMN analysis_complete INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE photos ADD COLUMN analysis_error TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE photos ADD COLUMN scene_description TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE photos ADD COLUMN scene_complete INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE photos ADD COLUMN face_count INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE photos ADD COLUMN faces_complete INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE photos ADD COLUMN people TEXT NOT NULL DEFAULT ''")
            createFaceTables(db)
        }
        createIndexes(db)
    }

    fun isUpToDate(mediaId: Long, dateModified: Long): Boolean {
        readableDatabase.query(
            "photos",
            arrayOf("date_modified", "analysis_complete", "faces_complete"),
            "media_id = ?",
            arrayOf(mediaId.toString()),
            null, null, null, "1"
        ).use { cursor ->
            return cursor.moveToFirst() &&
                cursor.getLong(0) == dateModified &&
                cursor.getInt(1) == 1 &&
                cursor.getInt(2) == 1
        }
    }

    fun syncAvailability(accessibleMediaIds: List<Long>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE photos SET available = 0")
            val statement = db.compileStatement("UPDATE photos SET available = 1 WHERE media_id = ?")
            accessibleMediaIds.forEach { id ->
                statement.clearBindings()
                statement.bindLong(1, id)
                statement.executeUpdateDelete()
            }
            statement.close()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        backfillNormalizedSearchText()
    }

    fun upsert(
        photo: PhotoItem,
        analysisComplete: Boolean,
        analysisError: String? = null,
        facesComplete: Boolean = false
    ) {
        val existing = findById(photo.mediaId)
        val finalTags = existing?.userTags?.ifBlank { photo.userTags } ?: photo.userTags
        val scene = existing?.sceneDescription.orEmpty()
        val people = existing?.people.orEmpty()
        val values = ContentValues().apply {
            put("media_id", photo.mediaId)
            put("uri", photo.uri)
            put("display_name", photo.displayName)
            put("bucket", photo.bucket)
            put("date_taken", photo.dateTaken)
            put("date_modified", photo.dateModified)
            put("labels", photo.labels)
            put("ocr", photo.ocr)
            put("user_tags", finalTags)
            put("available", 1)
            put("analysis_complete", if (analysisComplete) 1 else 0)
            put("analysis_error", analysisError.orEmpty().take(500))
            put("scene_description", scene)
            put("scene_complete", if (scene.isNotBlank()) 1 else 0)
            put("face_count", photo.faceCount)
            put("faces_complete", if (facesComplete) 1 else 0)
            put("people", people)
            put("search_text", buildSearchText(photo.copy(userTags = finalTags, sceneDescription = scene, people = people)))
        }
        writableDatabase.insertWithOnConflict("photos", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun updateUserTags(mediaId: Long, tags: String) {
        val normalizedTags = tags.trim()
        val photo = findById(mediaId)
        val values = ContentValues().apply {
            put("user_tags", normalizedTags)
            if (photo != null) put("search_text", buildSearchText(photo.copy(userTags = normalizedTags)))
        }
        writableDatabase.update("photos", values, "media_id = ?", arrayOf(mediaId.toString()))
    }

    fun updateScene(mediaId: Long, description: String, complete: Boolean) {
        val photo = findById(mediaId) ?: return
        val normalized = description.trim().replace(Regex("\\s+"), " ").take(1000)
        val values = ContentValues().apply {
            put("scene_description", normalized)
            put("scene_complete", if (complete) 1 else 0)
            put("search_text", buildSearchText(photo.copy(sceneDescription = normalized)))
        }
        writableDatabase.update("photos", values, "media_id = ?", arrayOf(mediaId.toString()))
    }

    fun photosNeedingScene(limit: Int): List<PhotoItem> = queryItems(
        "SELECT * FROM photos WHERE available = 1 AND scene_complete = 0 ORDER BY date_taken DESC LIMIT ?",
        arrayOf(limit.toString())
    )

    fun sceneProgress(): Pair<Int, Int> {
        readableDatabase.rawQuery(
            "SELECT SUM(CASE WHEN scene_complete = 1 THEN 1 ELSE 0 END), COUNT(*) FROM photos WHERE available = 1",
            null
        ).use { cursor ->
            if (!cursor.moveToFirst()) return 0 to 0
            return cursor.getInt(0) to cursor.getInt(1)
        }
    }

    fun loadClusterVectors(): MutableList<ClusterVector> {
        val result = mutableListOf<ClusterVector>()
        readableDatabase.rawQuery(
            "SELECT id, name, centroid, sample_count, representative_uri FROM person_clusters",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += ClusterVector(
                    id = cursor.getLong(0),
                    name = cursor.getString(1).orEmpty(),
                    centroid = blobToFloats(cursor.getBlob(2)),
                    sampleCount = cursor.getInt(3),
                    representativeUri = cursor.getString(4).orEmpty()
                )
            }
        }
        return result
    }

    fun createCluster(centroid: FloatArray, representativeUri: String): Long {
        val values = ContentValues().apply {
            put("centroid", floatsToBlob(centroid))
            put("sample_count", 1)
            put("representative_uri", representativeUri)
        }
        return writableDatabase.insert("person_clusters", null, values)
    }

    fun updateClusterCentroid(clusterId: Long, centroid: FloatArray, sampleCount: Int) {
        val values = ContentValues().apply {
            put("centroid", floatsToBlob(centroid))
            put("sample_count", sampleCount)
        }
        writableDatabase.update("person_clusters", values, "id = ?", arrayOf(clusterId.toString()))
    }

    fun replacePhotoFaces(photoId: Long, assignments: List<Pair<Long, FloatArray>>, facesComplete: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("faces", "photo_id = ?", arrayOf(photoId.toString()))
            assignments.forEach { (clusterId, embedding) ->
                val values = ContentValues().apply {
                    put("photo_id", photoId)
                    put("cluster_id", clusterId)
                    put("embedding", floatsToBlob(embedding))
                }
                db.insert("faces", null, values)
            }
            val status = ContentValues().apply { put("faces_complete", if (facesComplete) 1 else 0) }
            db.update("photos", status, "media_id = ?", arrayOf(photoId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        refreshPeopleForPhoto(photoId)
    }

    fun listPersonClusters(minSamples: Int = 2): List<PersonCluster> {
        val result = mutableListOf<PersonCluster>()
        readableDatabase.rawQuery(
            "SELECT id, name, representative_uri, sample_count FROM person_clusters WHERE sample_count >= ? ORDER BY sample_count DESC",
            arrayOf(minSamples.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += PersonCluster(
                    id = cursor.getLong(0),
                    name = cursor.getString(1).orEmpty(),
                    representativeUri = cursor.getString(2).orEmpty(),
                    sampleCount = cursor.getInt(3)
                )
            }
        }
        return result
    }

    fun nameCluster(clusterId: Long, name: String) {
        val normalized = name.trim()
        val values = ContentValues().apply { put("name", normalized) }
        writableDatabase.update("person_clusters", values, "id = ?", arrayOf(clusterId.toString()))

        val photoIds = mutableListOf<Long>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT photo_id FROM faces WHERE cluster_id = ?",
            arrayOf(clusterId.toString())
        ).use { cursor -> while (cursor.moveToNext()) photoIds += cursor.getLong(0) }
        photoIds.forEach(::refreshPeopleForPhoto)
    }

    private fun refreshPeopleForPhoto(photoId: Long) {
        val names = mutableListOf<String>()
        readableDatabase.rawQuery(
            """
            SELECT DISTINCT pc.name
            FROM faces f JOIN person_clusters pc ON pc.id = f.cluster_id
            WHERE f.photo_id = ? AND pc.name <> ''
            """.trimIndent(),
            arrayOf(photoId.toString())
        ).use { cursor -> while (cursor.moveToNext()) names += cursor.getString(0) }

        val photo = findById(photoId) ?: return
        val people = names.joinToString(" ")
        val values = ContentValues().apply {
            put("people", people)
            put("search_text", buildSearchText(photo.copy(people = people)))
        }
        writableDatabase.update("photos", values, "media_id = ?", arrayOf(photoId.toString()))
    }

    fun count(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM photos WHERE available = 1", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun recent(limit: Int = 300): List<PhotoItem> = queryItems(
        "SELECT * FROM photos WHERE available = 1 ORDER BY date_taken DESC LIMIT ?",
        arrayOf(limit.toString())
    )

    fun searchScored(query: String, limit: Int = 300): List<ScoredPhoto> {
        backfillNormalizedSearchText()
        val scoreTokens = SearchText.tokens(query)
        if (scoreTokens.isEmpty()) return recent(limit).map { ScoredPhoto(it, 0) }

        val candidateTokens = (SearchText.rawTokens(query) + scoreTokens).distinct()
        val where = candidateTokens.joinToString(" OR ") { "search_text LIKE ?" }
        val args = mutableListOf<String>()
        candidateTokens.forEach { token -> args += "%${SearchText.fold(token)}%" }
        args += MAX_CANDIDATES.toString()

        val candidates = queryItems(
            "SELECT * FROM photos WHERE available = 1 AND ($where) ORDER BY date_taken DESC LIMIT ?",
            args.toTypedArray()
        )

        return candidates
            .map { ScoredPhoto(it, SearchText.score(it, scoreTokens)) }
            .filter { it.score > 0 }
            .sortedWith(compareByDescending<ScoredPhoto> { it.score }.thenByDescending { it.photo.dateTaken })
            .take(limit)
    }

    private fun findById(mediaId: Long): PhotoItem? = queryItems(
        "SELECT * FROM photos WHERE media_id = ? LIMIT 1",
        arrayOf(mediaId.toString())
    ).firstOrNull()

    private fun buildSearchText(photo: PhotoItem): String = SearchText.fold(
        listOf(
            photo.labels,
            photo.ocr,
            photo.displayName,
            photo.bucket,
            photo.userTags,
            photo.sceneDescription,
            photo.people
        ).joinToString(" ")
    ).ifBlank { " " }

    @Synchronized
    private fun backfillNormalizedSearchText() {
        while (true) {
            val batch = queryItems(
                "SELECT * FROM photos WHERE search_text = '' LIMIT ?",
                arrayOf(BACKFILL_BATCH.toString())
            )
            if (batch.isEmpty()) return
            val db = writableDatabase
            db.beginTransaction()
            try {
                val statement = db.compileStatement("UPDATE photos SET search_text = ? WHERE media_id = ?")
                batch.forEach { photo ->
                    statement.clearBindings()
                    statement.bindString(1, buildSearchText(photo))
                    statement.bindLong(2, photo.mediaId)
                    statement.executeUpdateDelete()
                }
                statement.close()
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
    }

    private fun queryItems(sql: String, args: Array<String>): List<PhotoItem> {
        val result = ArrayList<PhotoItem>()
        readableDatabase.rawQuery(sql, args).use { cursor ->
            val mediaId = cursor.getColumnIndexOrThrow("media_id")
            val uri = cursor.getColumnIndexOrThrow("uri")
            val displayName = cursor.getColumnIndexOrThrow("display_name")
            val bucket = cursor.getColumnIndexOrThrow("bucket")
            val dateTaken = cursor.getColumnIndexOrThrow("date_taken")
            val dateModified = cursor.getColumnIndexOrThrow("date_modified")
            val labels = cursor.getColumnIndexOrThrow("labels")
            val ocr = cursor.getColumnIndexOrThrow("ocr")
            val userTags = cursor.getColumnIndexOrThrow("user_tags")
            val scene = cursor.getColumnIndexOrThrow("scene_description")
            val people = cursor.getColumnIndexOrThrow("people")
            val faceCount = cursor.getColumnIndexOrThrow("face_count")
            while (cursor.moveToNext()) {
                result += PhotoItem(
                    mediaId = cursor.getLong(mediaId),
                    uri = cursor.getString(uri),
                    displayName = cursor.getString(displayName),
                    bucket = cursor.getString(bucket),
                    dateTaken = cursor.getLong(dateTaken),
                    dateModified = cursor.getLong(dateModified),
                    labels = cursor.getString(labels),
                    ocr = cursor.getString(ocr),
                    userTags = cursor.getString(userTags),
                    sceneDescription = cursor.getString(scene),
                    people = cursor.getString(people),
                    faceCount = cursor.getInt(faceCount)
                )
            }
        }
        return result
    }

    private fun floatsToBlob(values: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach(buffer::putFloat)
        return buffer.array()
    }

    private fun blobToFloats(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buffer.float }
    }

    companion object {
        private const val DB_NAME = "photomind.db"
        private const val DB_VERSION = 5
        private const val MAX_CANDIDATES = 5000
        private const val BACKFILL_BATCH = 250
    }
}
