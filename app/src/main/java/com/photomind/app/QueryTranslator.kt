package com.photomind.app

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking

/**
 * Natural-language query parser. Gemini Nano runs locally through Android AICore.
 * The output is not used as free-form search text: it is reduced to the same structured
 * fields used by the index (people, canonical scene tags, place and year).
 */
class QueryTranslator {
    private val executor = Executors.newSingleThreadExecutor()
    private val model = Generation.getClient()

    fun translatePolishToEnglish(
        query: String,
        onPreparing: () -> Unit,
        onResult: (String?) -> Unit
    ) {
        onPreparing()
        executor.execute {
            val intent = runCatching { interpretWithNano(query) }
                .getOrElse { fallback(query) }
            onResult(intent.encode())
        }
    }

    private fun interpretWithNano(query: String): SearchIntent {
        if (!ensureReady()) return fallback(query)
        val prompt = """
            You are a parser for a private photo search app. Parse the Polish or English user query.
            Return EXACTLY four lines and nothing else:
            PEOPLE=name1,name2
            SCENE=tag1,tag2
            PLACE=place name
            YEAR=yyyy

            Rules:
            - PEOPLE: only personal names explicitly present in the query. Never output generic words like child, woman, man, family.
            - SCENE: only tags from this exact canonical list, in English: ${SceneTagCatalog.promptList()}
            - PLACE: geographic place explicitly requested by the user, normalized to its common base name. Do not infer a place if none was written.
            - YEAR: four digit year explicitly requested; otherwise leave empty.
            - Never move a person's name into SCENE. Never invent people, places or dates.
            - Empty fields must still be printed after '='.

            Query: $query
        """.trimIndent()

        val response = runBlocking { model.generateContent(prompt) }
            .candidates
            .firstOrNull()
            ?.text
            .orEmpty()
        return parse(response, query)
    }

    private fun ensureReady(): Boolean = runBlocking {
        when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> true
            FeatureStatus.DOWNLOADABLE -> {
                var completed = false
                model.download().collect { status ->
                    if (status.javaClass.simpleName.contains("Completed", ignoreCase = true)) completed = true
                }
                completed || model.checkStatus() == FeatureStatus.AVAILABLE
            }
            else -> false
        }
    }

    private fun parse(response: String, original: String): SearchIntent {
        fun field(name: String): String = response.lineSequence()
            .firstOrNull { it.trim().startsWith("$name=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            .orEmpty()

        val people = field("PEOPLE")
            .split(',', ';', '|')
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.equals("none", true) && !it.equals("brak", true) }
            .distinctBy { SearchText.fold(it) }

        val scenes = SceneTagCatalog.sanitizeModelOutput(field("SCENE"))
        val place = field("PLACE")
            .takeIf { it.isNotBlank() && !it.equals("none", true) && !it.equals("brak", true) }
        val year = Regex("(?:19|20)\\d{2}").find(field("YEAR"))?.value?.toIntOrNull()

        val parsed = SearchIntent(people, scenes, place, year)
        return if (parsed.people.isEmpty() && parsed.sceneTags.isEmpty() && parsed.place == null && parsed.year == null) {
            fallback(original)
        } else parsed
    }

    private fun fallback(query: String): SearchIntent {
        val year = Regex("(?:19|20)\\d{2}").find(query)?.value?.toIntOrNull()
        val scenes = SceneTagCatalog.fromQuery(query)
        val people = Regex("\\b\\p{Lu}[\\p{L}-]{2,}\\b")
            .findAll(query)
            .map { it.value }
            .filterNot { Regex("^(Rok|Zdjecia|Zdjecie|Pokaż|Pokaz)$", RegexOption.IGNORE_CASE).matches(it) }
            .distinctBy { SearchText.fold(it) }
            .toList()
        val place = Regex("\\b(?:w|we)\\s+([\\p{L}-]{3,})", RegexOption.IGNORE_CASE)
            .find(query)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { SceneTagCatalog.fromQuery(it).isEmpty() }
        return SearchIntent(people, scenes, place, year)
    }

    fun close() {
        executor.shutdownNow()
        model.close()
    }
}
