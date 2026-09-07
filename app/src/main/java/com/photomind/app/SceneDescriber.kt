package com.photomind.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Size
import com.google.mlkit.genai.common.DownloadCallback
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
            return Result(supported = false, error = compactError(unwrap(error)))
        }

        if (status == FeatureStatus.UNAVAILABLE) {
            return Result(supported = false, error = "Gemini Nano Image Description nie jest dostępny na tym urządzeniu")
        }

        // Google exposes a downloadable state in addition to AVAILABLE/UNAVAILABLE.
        // Fetch the on-device feature automatically; this is model download only, not photo upload.
        if (status != FeatureStatus.AVAILABLE) {
            val downloadError = arrayOfNulls<GenAiException>(1)
            try {
                describer.downloadFeature(
                    object : DownloadCallback {
                        override fun onDownloadStarted(bytesToDownload: Long) = Unit
                        override fun onDownloadProgress(totalBytesDownloaded: Long) = Unit
                        override fun onDownloadCompleted() = Unit
                        override fun onDownloadFailed(e: GenAiException) {
                            downloadError[0] = e
                        }
                    }
                ).get()
            } catch (error: Exception) {
                return Result(retryLater = true, error = compactError(unwrap(error)))
            }
            downloadError[0]?.let {
                return Result(retryLater = true, error = compactError(it))
            }
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
            // AICore errors such as busy/quota/background restrictions are transient in
            // practice. The beta API's exact enum set changes between releases, so retry
            // GenAI failures in a later foreground session instead of binding to enum names.
            if (cause is GenAiException) {
                return Result(retryLater = true, error = compactError(cause))
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
