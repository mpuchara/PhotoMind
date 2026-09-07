package com.photomind.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Detects faces with ML Kit and, when the FaceNet model is available, creates
 * local 128D embeddings used only for grouping the same person across photos.
 * The model is downloaded once to app-private storage; photos/embeddings never leave the device.
 */
class FaceEngine(private val context: Context) {
    data class Result(
        val faceCount: Int,
        val embeddings: List<FloatArray>,
        val modelReady: Boolean,
        val error: String? = null
    )

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setMinFaceSize(0.08f)
            .build()
    )

    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var modelAttempted = false

    fun analyze(bitmap: Bitmap): Result {
        val faces = try {
            Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)))
        } catch (error: Exception) {
            return Result(0, emptyList(), false, compactError(error))
        }

        if (faces.isEmpty()) return Result(0, emptyList(), interpreter != null)

        val model = ensureInterpreter()
        if (model == null) {
            return Result(faces.size, emptyList(), false, "Model grupowania twarzy nie jest jeszcze dostępny")
        }

        val embeddings = ArrayList<FloatArray>()
        for (face in faces) {
            val crop = cropFace(bitmap, face.boundingBox) ?: continue
            try {
                embeddings += embed(crop, model)
            } catch (_: Exception) {
                // One difficult face must not fail the whole photo.
            } finally {
                if (!crop.isRecycled) crop.recycle()
            }
        }
        return Result(faces.size, embeddings, true)
    }

    @Synchronized
    private fun ensureInterpreter(): Interpreter? {
        interpreter?.let { return it }
        if (modelAttempted) return null
        modelAttempted = true

        return try {
            val modelFile = File(context.filesDir, MODEL_FILE)
            if (!modelFile.exists() || modelFile.length() < MIN_MODEL_BYTES) {
                downloadModel(modelFile)
            }
            Interpreter(
                modelFile,
                Interpreter.Options().apply {
                    numThreads = 4
                    setUseXNNPACK(true)
                }
            ).also { interpreter = it }
        } catch (_: Exception) {
            null
        }
    }

    private fun downloadModel(destination: File) {
        val temp = File(destination.parentFile, "${destination.name}.part")
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "PhotoMind/0.2")
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode} podczas pobierania modelu twarzy")
            }
            connection.inputStream.use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output) }
            }
            if (temp.length() < MIN_MODEL_BYTES) throw IllegalStateException("Pobrany model twarzy jest niepełny")
            if (destination.exists()) destination.delete()
            if (!temp.renameTo(destination)) {
                temp.copyTo(destination, overwrite = true)
                temp.delete()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun cropFace(source: Bitmap, rect: Rect): Bitmap? {
        val padX = (rect.width() * 0.18f).toInt()
        val padY = (rect.height() * 0.18f).toInt()
        val left = max(0, rect.left - padX)
        val top = max(0, rect.top - padY)
        val right = min(source.width, rect.right + padX)
        val bottom = min(source.height, rect.bottom + padY)
        if (right <= left || bottom <= top) return null
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }

    private fun embed(face: Bitmap, model: Interpreter): FloatArray {
        val scaled = Bitmap.createScaledBitmap(face, INPUT_SIZE, INPUT_SIZE, true)
        try {
            val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
            scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

            // FaceNet reference preprocessing: per-image standardization.
            val values = FloatArray(INPUT_SIZE * INPUT_SIZE * 3)
            var index = 0
            var sum = 0.0
            pixels.forEach { color ->
                val r = ((color shr 16) and 0xFF).toFloat()
                val g = ((color shr 8) and 0xFF).toFloat()
                val b = (color and 0xFF).toFloat()
                values[index++] = r; values[index++] = g; values[index++] = b
                sum += r + g + b
            }
            val mean = (sum / values.size).toFloat()
            var variance = 0.0
            values.forEach { value ->
                val d = value - mean
                variance += d * d
            }
            val std = max(sqrt(variance / values.size).toFloat(), 1f / sqrt(values.size.toFloat()))

            val input = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
            values.forEach { input.putFloat((it - mean) / std) }
            input.rewind()

            val output = Array(1) { FloatArray(EMBEDDING_SIZE) }
            model.run(input, output)
            return l2Normalize(output[0])
        } finally {
            if (scaled !== face && !scaled.isRecycled) scaled.recycle()
        }
    }

    private fun l2Normalize(vector: FloatArray): FloatArray {
        var sum = 0.0
        vector.forEach { sum += it * it }
        val norm = sqrt(sum).toFloat().coerceAtLeast(1e-12f)
        return FloatArray(vector.size) { vector[it] / norm }
    }

    private fun compactError(error: Throwable): String {
        val message = error.message?.replace(Regex("\\s+"), " ")?.take(160).orEmpty()
        return if (message.isBlank()) error.javaClass.simpleName else "${error.javaClass.simpleName}: $message"
    }

    fun close() {
        detector.close()
        interpreter?.close()
        interpreter = null
    }

    companion object {
        private const val INPUT_SIZE = 160
        private const val EMBEDDING_SIZE = 128
        private const val MODEL_FILE = "facenet.tflite"
        private const val MIN_MODEL_BYTES = 20_000_000L
        private const val MODEL_URL =
            "https://raw.githubusercontent.com/shubham0204/FaceRecognition_With_FaceNet_Android/master/app/src/main/assets/facenet.tflite"
    }
}
