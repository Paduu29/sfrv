package com.streamflixrevanced.streamflix.utils

object SubtitleLanguageFilter {
    const val ALL = "all"

    private data class Language(
        val openSubtitlesId: String,
        val aliases: Set<String>,
    )

    private val languages = mapOf(
        "ar" to language("ara", "ar", "arabic"),
        "bg" to language("bul", "bg", "bulgarian"),
        "zh" to language("chi", "zh", "zho", "chinese", "mandarin"),
        "hr" to language("hrv", "hr", "croatian"),
        "cs" to language("cze", "cs", "ces", "czech"),
        "da" to language("dan", "da", "danish"),
        "nl" to language("dut", "nl", "nld", "dutch"),
        "en" to language("eng", "en", "english"),
        "fi" to language("fin", "fi", "finnish"),
        "fr" to language("fre", "fr", "fra", "french"),
        "de" to language("ger", "de", "deu", "german"),
        "el" to language("ell", "el", "gre", "greek"),
        "he" to language("heb", "he", "iw", "hebrew"),
        "hu" to language("hun", "hu", "hungarian"),
        "id" to language("ind", "id", "indonesian"),
        "it" to language("ita", "it", "italian"),
        "ja" to language("jpn", "ja", "japanese"),
        "ko" to language("kor", "ko", "korean"),
        "no" to language("nor", "no", "norwegian"),
        "fa" to language("per", "fa", "fas", "persian", "farsi"),
        "pl" to language("pol", "pl", "polish"),
        "pt" to language("por", "pt", "portuguese", "brazilian portuguese"),
        "ro" to language("rum", "ro", "ron", "romanian"),
        "ru" to language("rus", "ru", "russian"),
        "sr" to language("scc", "sr", "srp", "serbian"),
        "sk" to language("slo", "sk", "slk", "slovak"),
        "sl" to language("slv", "sl", "slovenian"),
        "es" to language("spa", "es", "spanish", "castilian"),
        "sv" to language("swe", "sv", "swedish"),
        "th" to language("tha", "th", "thai"),
        "tr" to language("tur", "tr", "turkish"),
        "uk" to language("ukr", "uk", "ukrainian"),
        "vi" to language("vie", "vi", "vietnamese"),
    )

    fun shouldSearch(selected: Set<String> = UserPreferences.subtitleLanguages): Boolean =
        selected.isNotEmpty()

    fun openSubtitlesLanguageIds(
        selected: Set<String> = UserPreferences.subtitleLanguages,
    ): List<String>? =
        selected
            .takeUnless { ALL in it }
            ?.mapNotNull { languages[it]?.openSubtitlesId }
            ?.distinct()
            ?.sorted()

    fun subDlQuery(selected: Set<String> = UserPreferences.subtitleLanguages): String? =
        selected
            .takeUnless { ALL in it }
            ?.filter { it in languages }
            ?.map { it.uppercase() }
            ?.distinct()
            ?.sorted()
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(",")

    fun allowsOpenSubtitle(
        subtitle: OpenSubtitles.Subtitle,
        selected: Set<String> = UserPreferences.subtitleLanguages,
    ): Boolean = allows(
        selected,
        subtitle.subLanguageID,
        subtitle.iso639,
        subtitle.languageName,
    )

    fun allowsSubDLSubtitle(
        subtitle: SubDL.Subtitle,
        selected: Set<String> = UserPreferences.subtitleLanguages,
    ): Boolean = allows(selected, subtitle.lang, subtitle.language)

    private fun allows(selected: Set<String>, vararg values: String?): Boolean {
        if (ALL in selected) return true
        if (selected.isEmpty()) return false

        val candidates = values
            .filterNotNull()
            .map(::normalize)
            .filter { it.isNotEmpty() }

        return selected.any { code ->
            languages[code]?.aliases?.any { alias ->
                candidates.any { candidate ->
                    candidate == alias ||
                        candidate.substringBefore('-') == alias ||
                        candidate.startsWith("$alias ")
                }
            } == true
        }
    }

    private fun language(openSubtitlesId: String, vararg aliases: String) =
        Language(openSubtitlesId, aliases.map(::normalize).toSet() + openSubtitlesId)

    private fun normalize(value: String): String =
        value.trim().lowercase().replace('_', '-')
}
