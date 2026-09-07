package com.photomind.app

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class PhotoAnalyzer(private val context: Context) {
    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.55f)
            .build()
    )
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun analyze(uri: Uri): Analysis {
        val image = InputImage.fromFilePath(context, uri)
        val labels = Tasks.await(labeler.process(image))
            .sortedByDescending { it.confidence }
            .take(12)
            .joinToString(" ") { it.text }
        val ocr = Tasks.await(recognizer.process(image)).text
            .replace(Regex("\\s+"), " ")
            .take(5000)
        return Analysis(labels = labels, ocr = ocr)
    }

    fun close() {
        labeler.close()
        recognizer.close()
    }

    data class Analysis(val labels: String, val ocr: String)
}
