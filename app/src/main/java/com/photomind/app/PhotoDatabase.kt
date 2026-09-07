package com.photomind.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class PhotoDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    data class ScoredPhoto(val photo: PhotoItem, val score: Int)

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
                search_text TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_photos_date ON photos(date_taken DESC)")
        db.execSQL("CREATE INDEX idx_photos_available ON photos(available)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE photos ADD COLUMN available INTEGER NOT NULL DEFAULT 1")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_photos_available ON photos(available)")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE photos ADD COLUMN search_text TEXT NOT NULL DEFAULT ''")
        }
    }

    fun isUpToDate(mediaId: Long, dateModified: Long): Boolean {
        readableDatabase.query(
            "photos",
            arrayOf("date_modified"),
            "media_id = ?",
            arrayOf(mediaId.toString()),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            return cursor.moveToFirst() && cursor.getLong(0) == dateModified
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

    fun upsert(photo: PhotoItem) {
        val existingTags = getUserTags(photo.mediaId)
        val finalTags = existingTags.ifBlank { photo.userTags }
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
            put("search_text", buildSearchText(photo, finalTags))
        }
        writableDatabase.insertWithOnConflict("photos", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun updateUserTags(mediaId: Long, tags: String) {
        val normalizedTags = tags.trim()
        val photo = findById(mediaId)
        val values = ContentValues().apply {
            put("user_tags", normalizedTags)
            if (photo != null) put("search_text", buildSearchText(photo, normalizedTags))
        }
        writableDatabase.update("photos", values, "media_id = ?", arrayOf(mediaId.toString()))
    }

    fun getUserTags(mediaId: Long): String {
        readableDatabase.query(
            "photos",
            arrayOf("user_tags"),
            "media_id = ?",
            arrayOf(mediaId.toString()),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
        }
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
        val where = candidateTokens.joinToString(" OR ") {
            "(search_text LIKE ? OR LOWER(labels) LIKE ? OR LOWER(ocr) LIKE ? OR LOWER(display_name) LIKE ? OR LOWER(bucket) LIKE ? OR LOWER(user_tags) LIKE ?)"
        }
        val args = mutableListOf<String>()
        candidateTokens.forEach { token ->
            repeat(6) { args += "%$token%" }
        }
        args += MAX_CANDIDATES.toString()

        val candidates = queryItems(
            "SELECT * FROM photos WHERE available = 1 AND ($where) ORDER BY date_taken DESC LIMIT ?",
            args.toTypedArray()
        )

        return candidates
            .map { ScoredPhoto(it, SearchText.score(it, scoreTokens)) }
            .filter { it.score > 0 }
            .sortedWith(
                compareByDescending<ScoredPhoto> { it.score }
                    .thenByDescending { it.photo.dateTaken }
            )
            .take(limit)
    }

    private fun findById(mediaId: Long): PhotoItem? {
        return queryItems(
            "SELECT * FROM photos WHERE media_id = ? LIMIT 1",
            arrayOf(mediaId.toString())
        ).firstOrNull()
    }

    private fun buildSearchText(photo: PhotoItem, tags: String = photo.userTags): String {
        return SearchText.fold(
            listOf(photo.labels, photo.ocr, photo.displayName, photo.bucket, tags)
                .joinToString(" ")
        ).ifBlank { " " }
    }

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
            } finally {
                db.endTransaction()
            }
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
                    userTags = cursor.getString(userTags)
                )
            }
        }
        return result
    }

    companion object {
        private const val DB_NAME = "photomind.db"
        private const val DB_VERSION = 3
        private const val MAX_CANDIDATES = 5000
        private const val BACKFILL_BATCH = 250
    }
}
