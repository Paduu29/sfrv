package com.streamflixrevanced.streamflix.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleLanguageFilterTest {

    private val selected = setOf("es", "en", "de")

    @Test
    fun `builds individual OpenSubtitles language ids from selected languages`() {
        assertEquals(
            listOf("eng", "ger", "spa"),
            SubtitleLanguageFilter.openSubtitlesLanguageIds(selected),
        )
    }

    @Test
    fun `all languages uses one unfiltered OpenSubtitles request`() {
        assertEquals(
            null,
            SubtitleLanguageFilter.openSubtitlesLanguageIds(setOf("all")),
        )
    }

    @Test
    fun `builds comma separated SubDL query from selected languages`() {
        assertEquals(
            "DE,EN,ES",
            SubtitleLanguageFilter.subDlQuery(selected),
        )
        assertEquals(null, SubtitleLanguageFilter.subDlQuery(setOf("all")))
    }

    @Test
    fun `matches OpenSubtitles language identifiers and names`() {
        assertTrue(
            SubtitleLanguageFilter.allowsOpenSubtitle(
                OpenSubtitles.Subtitle(subLanguageID = "spa"),
                selected,
            )
        )
        assertTrue(
            SubtitleLanguageFilter.allowsOpenSubtitle(
                OpenSubtitles.Subtitle(languageName = "German"),
                selected,
            )
        )
        assertFalse(
            SubtitleLanguageFilter.allowsOpenSubtitle(
                OpenSubtitles.Subtitle(subLanguageID = "fre"),
                selected,
            )
        )
    }

    @Test
    fun `matches SubDL codes and language names`() {
        assertTrue(
            SubtitleLanguageFilter.allowsSubDLSubtitle(
                SubDL.Subtitle(lang = "EN"),
                selected,
            )
        )
        assertTrue(
            SubtitleLanguageFilter.allowsSubDLSubtitle(
                SubDL.Subtitle(language = "Spanish (Latin America)"),
                selected,
            )
        )
        assertFalse(
            SubtitleLanguageFilter.allowsSubDLSubtitle(
                SubDL.Subtitle(lang = "Italian"),
                selected,
            )
        )
    }

    @Test
    fun `ranks provider results using configured language priority`() {
        val priority = listOf("es", "en", "de")

        assertEquals(
            0,
            SubtitleLanguageFilter.priorityIndex(
                selected = selected,
                label = "Spanish (Latin America)",
                priority = priority,
            ),
        )
        assertEquals(
            1,
            SubtitleLanguageFilter.priorityIndex(
                selected = selected,
                label = "English",
                priority = priority,
            ),
        )
        assertEquals(
            2,
            SubtitleLanguageFilter.priorityIndex(
                selected = selected,
                label = "German",
                priority = priority,
            ),
        )
    }

    @Test
    fun `all preserves existing behavior and empty disables external results`() {
        val french = SubDL.Subtitle(lang = "French")
        assertTrue(SubtitleLanguageFilter.allowsSubDLSubtitle(french, setOf("all")))
        assertFalse(SubtitleLanguageFilter.allowsSubDLSubtitle(french, emptySet()))
        assertFalse(SubtitleLanguageFilter.shouldSearch(emptySet()))
    }
}
