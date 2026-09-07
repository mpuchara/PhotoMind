package com.photomind.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Size
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.imagedescription.ImageDescription
import com.google.mlkit.genai.imagedescription.ImageDescriptionRequest
import com.google.mlkit.genai.imagedescription.ImageDescriber
import com.google.mlkit.genai.imagedescription.ImageDescriberOptions

/** Foreground-only Gemini Nano enrichment for rich scene descriptions. */
class SceneDescriber(private val context: Context) {
    data class Result(
        val description: String = "",
        val supported: Boolean = true,
        val retryLater: Boolean = false,
        val error: String? = null
    )

    private val describer: ImageDescriber = ImageDescription.getClient(
        ImageDescriberOptions.builder(context).build()
    )

    fun describe(uri: Uri): Result {
        val status = try {
            describer.checkFeatureStatus().get()
        } catch (error: Exception) {
            return Result(supported = false, error = compactError(error))
        }

        if (status == FeatureStatus.UNAVAILABLE) {
            return Result(supported = false, error = "Gemini Nano Image Description nie jest dostępny na tym urządzeniu")
        }

        val bitmap = try {
            loadBitmap(uri)
        } catch (error: Exception) {
            return Result(error = compactError(error))
        }

        try {
            val request = ImageDescriptionRequest.builder(bitmap).build()
            val result = describer.runInference(request).get()
            return Result(description = result.description.trim())
        } catch (error: Exception) {
            val cause = unwrap(error)
            if (cause is GenAiException) {
                val retry = cause.errorCode == GenAiException.ErrorCode.BUSY ||
                    cause.errorCode == GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED ||
                    cause.errorCode == GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED
                return Result(retryLater = retry, error = compactError(cause))
            }
            return Result(error = compactError(cause))
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun loadBitmap(uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(uri, Size(768, 768), null)?.let { return it }
        }
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream)
        } ?: throw IllegalStateException("Nie udało się wczytać zdjęcia do opisu sceny")
    }

    private fun unwrap(error: Throwable): Throwable {
        var current = error
        while (current.cause != null && current.cause !== current) current = current.cause!!
        return current
    }

    private fun compactError(error: Throwable): String {
        val message = error.message?.replace(Regex("\\s+"), " ")?.take(180).orEmpty()
        return if (message.isBlank()) error.javaClass.simpleName else "${error.javaClass.simpleName}: $message"
    }

    fun close() = describer.close()
}
