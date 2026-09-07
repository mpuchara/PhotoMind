package com.photomind.app

import java.text.Normalizer
import java.util.Locale

object SearchText {
    private val stopWords = setOf(
        // Polish
        "a", "aby", "albo", "ale", "bez", "bo", "by", "byl", "byla", "bylo", "byly", "co", "czy",
        "dla", "do", "gdzie", "i", "ich", "jak", "jest", "ktora", "ktore", "ktory", "ma", "mi", "na",
        "nad", "nie", "o", "od", "oraz", "po", "pod", "przed", "przy", "sie", "to", "u", "w", "we",
        "z", "za", "ze", "zdjecie", "zdjecia", "zdjec", "fotka", "fotki", "obraz", "obrazy",
        // English
        "a", "an", "and", "are", "at", "by", "for", "from", "in", "is", "of", "on", "or", "the",
        "to", "with", "photo", "photos", "picture", "pictures", "image", "images"
    )

    fun tokens(query: String): List<String> {
        return splitTokens(fold(query))
            .filterNot { it in stopWords }
            .distinct()
    }

    fun rawTokens(query: String): List<String> {
        return splitTokens(query.lowercase(Locale.ROOT))
            .filterNot { fold(it) in stopWords }
            .distinct()
    }

    fun score(photo: PhotoItem, tokens: List<String>): Int {
        if (tokens.isEmpty()) return 0

        val tags = fold(photo.userTags)
        val labels = fold(photo.labels)
        val ocr = fold(photo.ocr)
        val name = fold(photo.displayName)
        val bucket = fold(photo.bucket)

        var score = 0
        var matchedTokens = 0

        tokens.forEach { token ->
            var matched = false

            if (containsTokenish(tags, token)) {
                score += 12
                matched = true
            }
            if (containsTokenish(labels, token)) {
                score += 7
                matched = true
            }
            if (ocr.contains(token)) {
                score += 3
                matched = true
            }
            if (name.contains(token)) {
                score += 2
                matched = true
            }
            if (bucket.contains(token)) {
                score += 2
                matched = true
            }

            if (matched) matchedTokens++
        }

        if (matchedTokens == tokens.size) {
            score += 15 + tokens.size * 3
        } else if (matchedTokens > 1) {
            score += matchedTokens * 3
        }

        return score
    }

    fun fold(value: String): String {
        val lower = value.lowercase(Locale.ROOT)
            .replace('ł', 'l')
            .replace('đ', 'd')
            .replace('ø', 'o')
        return Normalizer.normalize(lower, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
    }

    private fun splitTokens(value: String): List<String> = value
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .map { it.trim() }
        .filter { it.length >= 2 }

    private fun containsTokenish(haystack: String, token: String): Boolean {
        if (haystack.isBlank()) return false
        return haystack.contains(token)
    }
}
