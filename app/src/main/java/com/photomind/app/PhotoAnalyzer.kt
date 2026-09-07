package com.photomind.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.Size
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
        ImageLabelerOptions.Builder().setConfidenceThreshold(0.55f).build()
    )
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val faceEngine = FaceEngine(context)

    fun analyze(uri: Uri): Analysis {
        val bitmapResult = runCatching { loadBitmapForAnalysis(uri) }
        if (bitmapResult.isFailure) {
            return Analysis(
                labels = "", ocr = "", complete = false,
                error = compactError("dekodowanie", bitmapResult.exceptionOrNull()),
                faceCount = 0, faceEmbeddings = emptyList(), facesComplete = false
            )
        }

        val bitmap = bitmapResult.getOrThrow()
        try {
            val image = InputImage.fromBitmap(bitmap, 0)

            // The fast image model is allowed to contribute only canonical SCENE tags.
            // Any label describing a human/person is silently discarded because it is not in the catalog.
            val labelsResult = runCatching {
                val raw = Tasks.await(labeler.process(image))
                    .sortedByDescending { it.confidence }
                    .take(24)
                    .map { it.text }
                SceneTagCatalog.canonicalizeImageLabels(raw).joinToString(", ")
            }

            val ocrResult = runCatching {
                Tasks.await(recognizer.process(image)).text
                    .replace(Regex("\\s+"), " ")
                    .take(5000)
            }

            // Identity never comes from scene AI. Faces are handled locally and separately.
            val faceResult = runCatching { faceEngine.analyze(bitmap) }.getOrElse {
                FaceEngine.Result(
                    faceCount = 0,
                    embeddings = emptyList(),
                    modelReady = false,
                    error = compactError("twarze", it)
                )
            }

            val errors = buildList {
                labelsResult.exceptionOrNull()?.let { add(compactError("etykiety sceny", it)) }
                ocrResult.exceptionOrNull()?.let { add(compactError("OCR", it)) }
            }

            return Analysis(
                labels = labelsResult.getOrDefault(""),
                ocr = ocrResult.getOrDefault(""),
                complete = errors.isEmpty(),
                error = errors.joinToString("; ").ifBlank { null },
                faceCount = faceResult.faceCount,
                faceEmbeddings = faceResult.embeddings,
                facesComplete = faceResult.faceCount == 0 ||
                    (faceResult.modelReady && faceResult.embeddings.isNotEmpty()),
                faceError = faceResult.error
            )
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun loadBitmapForAnalysis(uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val thumbnail = runCatching {
                context.contentResolver.loadThumbnail(
                    uri, Size(THUMBNAIL_MAX_DIMENSION, THUMBNAIL_MAX_DIMENSION), null
                )
            }.getOrNull()
            if (thumbnail != null) return thumbnail
        }
        return decodeForAnalysis(uri)
    }

    private fun decodeForAnalysis(uri: Uri): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { stream -> BitmapFactory.decodeStream(stream, null, bounds) }
            ?: throw IllegalStateException("Nie udało się otworzyć zdjęcia")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalStateException("Nie udało się odczytać rozmiaru zdjęcia")
        }

        var sampleSize = 1
        val largest = max(bounds.outWidth, bounds.outHeight)
        while (largest / sampleSize > TARGET_MAX_DIMENSION * 2) sampleSize *= 2
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
        } else decoded

        val rotation = readRotation(uri)
        if (rotation == 0) return scaled
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, matrix, true)
            .also { if (it !== scaled) scaled.recycle() }
    }

    private fun readRotation(uri: Uri): Int = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
    } catch (_: Exception) { 0 }

    private fun compactError(stage: String, error: Throwable?): String {
        if (error == null) return stage
        val type = error.javaClass.simpleName.ifBlank { "błąd" }
        val message = error.message?.replace(Regex("\\s+"), " ")?.take(140)?.trim().orEmpty()
        return if (message.isBlank()) "$stage: $type" else "$stage: $type — $message"
    }

    fun close() {
        labeler.close()
        recognizer.close()
        faceEngine.close()
    }

    data class Analysis(
        val labels: String,
        val ocr: String,
        val complete: Boolean,
        val error: String?,
        val faceCount: Int,
        val faceEmbeddings: List<FloatArray>,
        val facesComplete: Boolean,
        val faceError: String? = null
    )

    companion object {
        private const val THUMBNAIL_MAX_DIMENSION = 1280
        private const val TARGET_MAX_DIMENSION = 2048
    }
}
