package com.photomind.app

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class SearchIntent(
    val people: List<String> = emptyList(),
    val sceneTags: List<String> = emptyList(),
    val place: String? = null,
    val year: Int? = null
) {
    fun encode(): String = buildString {
        append(PREFIX)
        append("people=").append(enc(people.joinToString(",")))
        append(";scene=").append(enc(sceneTags.joinToString(",")))
        append(";place=").append(enc(place.orEmpty()))
        append(";year=").append(year?.toString().orEmpty())
    }

    companion object {
        const val PREFIX = "PM3;"

        fun decode(value: String): SearchIntent? {
            if (!value.startsWith(PREFIX)) return null
            val fields = value.removePrefix(PREFIX)
                .split(';')
                .mapNotNull { part ->
                    val idx = part.indexOf('=')
                    if (idx <= 0) null else part.substring(0, idx) to dec(part.substring(idx + 1))
                }.toMap()
            return SearchIntent(
                people = split(fields["people"]),
                sceneTags = split(fields["scene"]).filter { it in SceneTagCatalog.allowed },
                place = fields["place"]?.trim()?.takeIf { it.isNotBlank() },
                year = fields["year"]?.trim()?.toIntOrNull()?.takeIf { it in 1900..2100 }
            )
        }

        private fun split(value: String?): List<String> = value.orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { SearchText.fold(it) }

        private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        private fun dec(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }
}
