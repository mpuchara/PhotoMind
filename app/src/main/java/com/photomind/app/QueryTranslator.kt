package com.photomind.app

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

class QueryTranslator {
    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.POLISH)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build()
    )

    fun translatePolishToEnglish(
        query: String,
        onPreparing: () -> Unit,
        onResult: (String) -> Unit
    ) {
        if (query.isBlank()) {
            onResult(query)
            return
        }

        onPreparing()
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                translator.translate(query)
                    .addOnSuccessListener { onResult(it) }
                    .addOnFailureListener { onResult(query) }
            }
            .addOnFailureListener { onResult(query) }
    }

    fun close() = translator.close()
}
