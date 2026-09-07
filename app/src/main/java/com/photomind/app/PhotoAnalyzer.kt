package com.photomind.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.max

class PhotoAnalyzer(private val context: Context) {
    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.55f)
            .build()
    )
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun analyze(uri: Uri): Analysis {
        val bitmap = decodeForAnalysis(uri)
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val labels = Tasks.await(labeler.process(image))
                .sortedByDescending { it.confidence }
                .take(12)
                .joinToString(" ") { it.text }
            val ocr = Tasks.await(recognizer.process(image)).text
                .replace(Regex("\\s+"), " ")
                .take(5000)
            return Analysis(labels = labels, ocr = ocr)
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeForAnalysis(uri: Uri): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        } ?: throw IllegalStateException("Nie udało się otworzyć zdjęcia")

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalStateException("Nie udało się odczytać rozmiaru zdjęcia")
        }

        var sampleSize = 1
        val largest = max(bounds.outWidth, bounds.outHeight)
        while (largest / sampleSize > TARGET_MAX_DIMENSION * 2) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val decoded = resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: throw IllegalStateException("Nie udało się zdekodować zdjęcia")

        val decodedLargest = max(decoded.width, decoded.height)
        val scaled = if (decodedLargest > TARGET_MAX_DIMENSION) {
            val scale = TARGET_MAX_DIMENSION.toFloat() / decodedLargest.toFloat()
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true
            ).also { if (it !== decoded) decoded.recycle() }
        } else {
            decoded
        }

        val rotation = readRotation(uri)
        if (rotation == 0) return scaled

        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, matrix, true)
            .also { if (it !== scaled) scaled.recycle() }
    }

    private fun readRotation(uri: Uri): Int {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                when (ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (_: Exception) {
            0
        }
    }

    fun close() {
        labeler.close()
        recognizer.close()
    }

    data class Analysis(val labels: String, val ocr: String)

    companion object {
        private const val TARGET_MAX_DIMENSION = 2048
    }
}
