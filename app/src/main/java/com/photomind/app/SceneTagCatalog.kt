package com.photomind.app

/**
 * One canonical vocabulary shared by image indexing and natural-language search.
 * People, locations and dates deliberately do NOT belong here.
 */
object SceneTagCatalog {
    val allowed: Set<String> = linkedSetOf(
        "beach", "sea", "ocean", "lake", "river", "waterfall", "water", "pool",
        "mountains", "hills", "forest", "field", "meadow", "countryside", "park", "garden",
        "city", "street", "road", "village", "building", "house", "hotel", "restaurant", "cafe",
        "room", "kitchen", "bathroom", "indoor", "outdoor", "playground", "campsite",
        "snow", "ice", "rain", "fog", "cloudy", "sunny", "sunset", "sunrise", "night", "sky",
        "sand", "rocks", "bridge", "castle", "church", "monument",
        "car", "bicycle", "motorcycle", "train", "airplane", "boat", "sailboat", "kayak", "sup",
        "dog", "cat", "horse", "animal", "food", "drink", "flowers", "trees",
        "football", "swimming", "hiking", "cycling", "skiing", "sailing", "paddling",
        "running", "walking", "driving", "playing", "party", "concert", "wedding", "picnic",
        "camping", "sightseeing"
    )

    private val aliases: Map<String, String> = mapOf(
        // Polish scene words
        "plaza" to "beach", "plazy" to "beach", "morze" to "sea", "morzem" to "sea",
        "ocean" to "ocean", "jezioro" to "lake", "jeziorze" to "lake", "rzeka" to "river",
        "rzece" to "river", "wodospad" to "waterfall", "woda" to "water", "basen" to "pool",
        "gory" to "mountains", "gorach" to "mountains", "las" to "forest", "lesie" to "forest",
        "pole" to "field", "laka" to "meadow", "lace" to "meadow", "park" to "park",
        "ogrod" to "garden", "ogrodzie" to "garden", "miasto" to "city", "miescie" to "city",
        "ulica" to "street", "ulicy" to "street", "droga" to "road", "wies" to "village",
        "budynek" to "building", "dom" to "house", "hotelu" to "hotel", "hotel" to "hotel",
        "restauracja" to "restaurant", "restauracji" to "restaurant", "kawiarnia" to "cafe",
        "pokoj" to "room", "kuchnia" to "kitchen", "lazienka" to "bathroom",
        "plac zabaw" to "playground", "kemping" to "campsite", "namiot" to "campsite",
        "snieg" to "snow", "sniegu" to "snow", "lod" to "ice", "deszcz" to "rain",
        "mgla" to "fog", "pochmurno" to "cloudy", "slonecznie" to "sunny",
        "zachod slonca" to "sunset", "wschod slonca" to "sunrise", "noc" to "night",
        "piasek" to "sand", "skaly" to "rocks", "most" to "bridge", "zamek" to "castle",
        "kosciol" to "church", "pomnik" to "monument", "samochod" to "car", "auto" to "car",
        "rower" to "bicycle", "motocykl" to "motorcycle", "pociag" to "train", "samolot" to "airplane",
        "lodka" to "boat", "zaglowka" to "sailboat", "kajak" to "kayak", "sup" to "sup",
        "pies" to "dog", "psa" to "dog", "kot" to "cat", "konia" to "horse", "kon" to "horse",
        "jedzenie" to "food", "kwiaty" to "flowers", "drzewa" to "trees",
        "pilka nozna" to "football", "plywanie" to "swimming", "plywa" to "swimming",
        "wedrowka" to "hiking", "spacer po gorach" to "hiking", "rowerze" to "cycling",
        "narty" to "skiing", "zeglowanie" to "sailing", "zegluje" to "sailing",
        "wioslowanie" to "paddling", "bieganie" to "running", "biegnie" to "running",
        "spacer" to "walking", "spaceruje" to "walking", "jazda" to "driving",
        "zabawa" to "playing", "impreza" to "party", "koncert" to "concert",
        "slub" to "wedding", "wesele" to "wedding", "piknik" to "picnic",
        "biwak" to "camping", "zwiedzanie" to "sightseeing",
        // Common ML Kit / English variants
        "automobile" to "car", "vehicle" to "car", "bike" to "bicycle", "motorbike" to "motorcycle",
        "railway" to "train", "aircraft" to "airplane", "ship" to "boat", "yacht" to "sailboat",
        "woodland" to "forest", "woods" to "forest", "mountain" to "mountains", "hill" to "hills",
        "grassland" to "meadow", "flower" to "flowers", "tree" to "trees"
    )

    fun promptList(): String = allowed.joinToString(",")

    fun sanitizeModelOutput(value: String): List<String> {
        val foldedWhole = SearchText.fold(value)
        val result = linkedSetOf<String>()
        allowed.forEach { tag ->
            val folded = SearchText.fold(tag)
            if (Regex("(^|[^a-z0-9])${Regex.escape(folded)}([^a-z0-9]|$)").containsMatchIn(foldedWhole)) {
                result += tag
            }
        }
        aliases.forEach { (alias, tag) ->
            val foldedAlias = SearchText.fold(alias)
            if (Regex("(^|[^a-z0-9])${Regex.escape(foldedAlias)}([^a-z0-9]|$)").containsMatchIn(foldedWhole)) {
                result += tag
            }
        }
        return result.toList()
    }

    fun canonicalizeImageLabels(labels: List<String>): List<String> {
        val result = linkedSetOf<String>()
        labels.forEach { raw ->
            val folded = SearchText.fold(raw).trim()
            val direct = allowed.firstOrNull { SearchText.fold(it) == folded }
            when {
                direct != null -> result += direct
                aliases[folded] != null -> result += aliases.getValue(folded)
            }
        }
        return result.toList()
    }

    fun fromQuery(query: String): List<String> = sanitizeModelOutput(query)

    fun normalizeUserTags(value: String): String = value
        .split(Regex("[,;|\\n]+"))
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy { SearchText.fold(it) }
        .joinToString(", ")
}
