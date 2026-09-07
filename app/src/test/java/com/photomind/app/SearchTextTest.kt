package com.photomind.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTextTest {
    @Test
    fun removesPolishAndEnglishStopWords() {
        assertEquals(listOf("psa", "plazy"), SearchText.tokens("zdjęcie psa na plaży"))
        assertEquals(listOf("dog", "beach"), SearchText.tokens("a photo of a dog on the beach"))
    }

    @Test
    fun customTagsHaveHighWeight() {
        val tagged = photo(userTags = "Maciek działka", labels = "Person Outdoor")
        val onlyLabel = photo(mediaId = 2, labels = "Person Outdoor Maciek")

        val tokens = SearchText.tokens("Maciek")
        assertTrue(SearchText.score(tagged, tokens) > SearchText.score(onlyLabel, tokens))
    }

    @Test
    fun matchingMoreQueryTermsRanksHigher() {
        val both = photo(labels = "Dog Beach")
        val one = photo(mediaId = 2, labels = "Dog")
        val tokens = SearchText.tokens("dog on the beach")

        assertTrue(SearchText.score(both, tokens) > SearchText.score(one, tokens))
        assertFalse(SearchText.score(one, tokens) == 0)
    }

    @Test
    fun foldsPolishDiacritics() {
        assertEquals("krakow lodz plazy", SearchText.fold("Kraków Łódź plaży"))
    }

    private fun photo(
        mediaId: Long = 1,
        labels: String = "",
        ocr: String = "",
        userTags: String = ""
    ) = PhotoItem(
        mediaId = mediaId,
        uri = "content://photo/$mediaId",
        displayName = "IMG_$mediaId.jpg",
        bucket = "Camera",
        dateTaken = 1,
        dateModified = 1,
        labels = labels,
        ocr = ocr,
        userTags = userTags
    )
}
