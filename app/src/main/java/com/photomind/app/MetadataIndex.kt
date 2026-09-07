package com.photomind.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.location.Geocoder
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos

/** Location and time are derived from file/media metadata, never guessed from image pixels. */
class MetadataIndex(private val context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    data class Meta(
        val year: Int?,
        val latitude: Double?,
        val longitude: Double?
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE photo_meta (
                media_id INTEGER PRIMARY KEY,
                date_modified INTEGER NOT NULL DEFAULT 0,
                year INTEGER,
                latitude REAL,
                longitude REAL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_meta_year ON photo_meta(year)")
        db.execSQL("CREATE INDEX idx_meta_geo ON photo_meta(latitude, longitude)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun ensure(mediaId: Long, uri: Uri, dateTaken: Long, dateModified: Long) {
        if (isCurrent(mediaId, dateModified)) return
        val meta = readMetadata(uri, dateTaken)
        val values = ContentValues().apply {
            put("media_id", mediaId)
            put("date_modified", dateModified)
            if (meta.year != null) put("year", meta.year) else putNull("year")
            if (meta.latitude != null) put("latitude", meta.latitude) else putNull("latitude")
            if (meta.longitude != null) put("longitude", meta.longitude) else putNull("longitude")
        }
        writableDatabase.insertWithOnConflict("photo_meta", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun isCurrent(mediaId: Long, dateModified: Long): Boolean {
        readableDatabase.query(
            "photo_meta", arrayOf("date_modified"), "media_id = ?", arrayOf(mediaId.toString()),
            null, null, null, "1"
        ).use { c -> return c.moveToFirst() && c.getLong(0) == dateModified }
    }

    fun matchingIds(year: Int?, place: String?): Set<Long>? {
        if (year == null && place.isNullOrBlank()) return null
        val center = place?.takeIf { it.isNotBlank() }?.let(::geocode)
        if (!place.isNullOrBlank() && center == null) return emptySet()

        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (year != null) {
            where += "year = ?"
            args += year.toString()
        }
        if (center != null) {
            val radiusKm = 25.0
            val latDelta = radiusKm / 111.0
            val lonScale = cos(Math.toRadians(center.first)).coerceAtLeast(0.15)
            val lonDelta = radiusKm / (111.0 * lonScale)
            where += "latitude BETWEEN ? AND ?"
            args += (center.first - latDelta).toString()
            args += (center.first + latDelta).toString()
            where += "longitude BETWEEN ? AND ?"
            args += (center.second - lonDelta).toString()
            args += (center.second + lonDelta).toString()
        }

        val result = linkedSetOf<Long>()
        readableDatabase.rawQuery(
            "SELECT media_id FROM photo_meta WHERE ${where.joinToString(" AND ")}",
            args.toTypedArray()
        ).use { c -> while (c.moveToNext()) result += c.getLong(0) }
        return result
    }

    private fun readMetadata(uri: Uri, dateTaken: Long): Meta {
        var latitude: Double? = null
        var longitude: Double? = null
        var exifYear: Int? = null
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                exif.latLong?.let { pair ->
                    if (pair.size >= 2) {
                        latitude = pair[0]
                        longitude = pair[1]
                    }
                }
                val rawDate = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                if (!rawDate.isNullOrBlank()) {
                    val parsed = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(rawDate)
                    exifYear = parsed?.let(::yearOf)
                }
            }
        }
        val year = when {
            dateTaken > 0L -> yearOf(Date(dateTaken))
            else -> exifYear
        }
        return Meta(year, latitude, longitude)
    }

    private fun yearOf(date: Date): Int = SimpleDateFormat("yyyy", Locale.US).format(date).toInt()

    @Suppress("DEPRECATION")
    private fun geocode(place: String): Pair<Double, Double>? {
        return try {
            if (!Geocoder.isPresent()) return null
            val geocoder = Geocoder(context, Locale("pl", "PL"))
            val result = geocoder.getFromLocationName(place, 1)
            val address = result?.firstOrNull() ?: return null
            address.latitude to address.longitude
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val DB_NAME = "photomind_metadata.db"
        private const val DB_VERSION = 1
    }
}
