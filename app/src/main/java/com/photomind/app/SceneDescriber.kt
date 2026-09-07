package com.photomind.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Size
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking

/**
 * Foreground-only Gemini Nano scene tagger.
 * It is intentionally prohibited from returning people, locations or dates.
 */
class SceneDescriber(private val context: Context) {
    data class Result(
        val description: String = "",
        val supported: Boolean = true,
        val retryLater: Boolean = false,
        val error: String? = null
    )

    private val model = Generation.getClient()

    fun describe(uri: Uri): Result {
        if (!ensureReady()) {
            return Result(supported = false, error = "Gemini Nano Prompt API nie jest dostępny na tym urządzeniu")
        }
        val bitmap = try {
            loadBitmap(uri)
        } catch (error: Exception) {
            return Result(error = compactError(error))
        }

        try {
            val prompt = """
                Classify ONLY the visible scene, environment, objects and activities in this photo.
                Return ONLY comma-separated tags from this exact list:
                ${SceneTagCatalog.promptList()}

                Important:
                - Never return people, person, man, woman, child, boy, girl, family, face or names.
                - Never infer or return a geographic place name.
                - Never infer or return a date, year or time from context.
                - Do not output OCR text.
                - Pick only tags clearly supported by the pixels. Maximum 12 tags.
                - No explanation, no sentences.
            """.trimIndent()
            val request = generateContentRequest(ImagePart(bitmap), TextPart(prompt)) {
                temperature = 0.1f
                topK = 8
                candidateCount = 1
            }
            val response = runBlocking { model.generateContent(request) }.text.orEmpty()
            val tags = SceneTagCatalog.sanitizeModelOutput(response)
            return Result(description = tags.joinToString(", "))
        } catch (error: Exception) {
            val text = compactError(error)
            val retry = text.contains("BUSY", true) ||
                text.contains("BATTERY", true) ||
                text.contains("BACKGROUND", true) ||
                text.contains("QUOTA", true)
            return Result(retryLater = retry, error = text)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun ensureReady(): Boolean = runBlocking {
        when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> true
            FeatureStatus.DOWNLOADABLE -> {
                model.download().collect { }
                model.checkStatus() == FeatureStatus.AVAILABLE
            }
            else -> false
        }
    }

    private fun loadBitmap(uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(uri, Size(768, 768), null)?.let { return it }
        }
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream)
        } ?: throw IllegalStateException("Nie udało się wczytać zdjęcia do tagowania scenerii")
    }

    private fun compactError(error: Throwable): String {
        var current = error
        while (current.cause != null && current.cause !== current) current = current.cause!!
        val message = current.message?.replace(Regex("\\s+"), " ")?.take(180).orEmpty()
        return if (message.isBlank()) current.javaClass.simpleName else "${current.javaClass.simpleName}: $message"
    }

    fun close() = model.close()
}
