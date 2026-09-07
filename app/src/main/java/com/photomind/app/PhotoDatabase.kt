package com.photomind.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale

class PhotoDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

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
                user_tags TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_photos_date ON photos(date_taken DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

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

    fun upsert(photo: PhotoItem) {
        val existingTags = getUserTags(photo.mediaId)
        val values = ContentValues().apply {
            put("media_id", photo.mediaId)
            put("uri", photo.uri)
            put("display_name", photo.displayName)
            put("bucket", photo.bucket)
            put("date_taken", photo.dateTaken)
            put("date_modified", photo.dateModified)
            put("labels", photo.labels)
            put("ocr", photo.ocr)
            put("user_tags", existingTags.ifBlank { photo.userTags })
        }
        writableDatabase.insertWithOnConflict("photos", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun updateUserTags(mediaId: Long, tags: String) {
        val values = ContentValues().apply { put("user_tags", tags.trim()) }
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
        readableDatabase.rawQuery("SELECT COUNT(*) FROM photos", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun recent(limit: Int = 300): List<PhotoItem> = queryItems(
        "SELECT * FROM photos ORDER BY date_taken DESC LIMIT ?",
        arrayOf(limit.toString())
    )

    fun search(query: String, limit: Int = 300): List<PhotoItem> {
        val tokens = query
            .trim()
            .lowercase(Locale.getDefault())
            .split(Regex("\\s+"))
            .map { it.trim().trim(',', '.', ';', ':', '!', '?', '"', '\'', '(', ')') }
            .filter { it.length >= 2 }
            .distinct()

        if (tokens.isEmpty()) return recent(limit)

        val where = tokens.joinToString(" AND ") {
            "(LOWER(labels) LIKE ? OR LOWER(ocr) LIKE ? OR LOWER(display_name) LIKE ? OR LOWER(bucket) LIKE ? OR LOWER(user_tags) LIKE ?)"
        }
        val args = mutableListOf<String>()
        tokens.forEach { token ->
            repeat(5) { args += "%$token%" }
        }
        args += limit.toString()

        return queryItems(
            "SELECT * FROM photos WHERE $where ORDER BY date_taken DESC LIMIT ?",
            args.toTypedArray()
        )
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
        private const val DB_VERSION = 1
    }
}
