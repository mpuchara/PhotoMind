package com.photomind.app

import android.content.Context

fun PhotoDatabase.knownPersonNames(): List<String> {
    val names = mutableListOf<String>()
    readableDatabase.rawQuery(
        "SELECT DISTINCT name FROM person_clusters WHERE TRIM(name) <> '' ORDER BY name COLLATE NOCASE",
        null
    ).use { c -> while (c.moveToNext()) names += c.getString(0).orEmpty() }
    return names
}

fun PhotoDatabase.invalidatePhotoForRetag(mediaId: Long) {
    writableDatabase.execSQL(
        "UPDATE photos SET analysis_complete = 0, faces_complete = 0, scene_complete = 0 WHERE media_id = ?",
        arrayOf(mediaId)
    )
}

fun PhotoDatabase.invalidateClusterForRename(clusterId: Long) {
    writableDatabase.execSQL(
        """
        UPDATE photos
        SET faces_complete = 0, scene_complete = 0
        WHERE media_id IN (SELECT DISTINCT photo_id FROM faces WHERE cluster_id = ?)
        """.trimIndent(),
        arrayOf(clusterId)
    )
}

/** v0.3 changes scene data from free captions to a canonical tag vocabulary. */
fun PhotoDatabase.prepareStructuredSceneMigration(context: Context) {
    val prefs = context.getSharedPreferences("photomind_migrations", Context.MODE_PRIVATE)
    if (prefs.getBoolean("scene_tags_v3", false)) return
    writableDatabase.beginTransaction()
    try {
        writableDatabase.execSQL("UPDATE photos SET scene_description = '', scene_complete = 0, search_text = ''")
        writableDatabase.setTransactionSuccessful()
    } finally {
        writableDatabase.endTransaction()
    }
    prefs.edit().putBoolean("scene_tags_v3", true).apply()
}

fun PhotoDatabase.photosByIds(ids: Collection<Long>, limit: Int = 5000): List<PhotoItem> {
    if (ids.isEmpty()) return emptyList()
    val result = mutableListOf<PhotoItem>()
    ids.take(limit).chunked(700).forEach { chunk ->
        val placeholders = chunk.joinToString(",") { "?" }
        readableDatabase.rawQuery(
            "SELECT * FROM photos WHERE available = 1 AND media_id IN ($placeholders)",
            chunk.map { it.toString() }.toTypedArray()
        ).use { cursor ->
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
    }
    return result.sortedByDescending { it.dateTaken }
}
