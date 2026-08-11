package com.streamflixrevanced.streamflix.utils

import java.util.Locale

/** Resolves the filename convention used by tv-logo/tv-logos. */
object TvLogoRepository {
    private const val RAW_BASE = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries"
    private const val RAW_MISC_BASE = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/misc"

    private val countryDirectories = mapOf(
        "de" to "germany",
        "it" to "italy",
        "fr" to "france",
        "es" to "spain",
        "pl" to "poland",
        "ro" to "romania",
    )

    private val aliases = mapOf(
        "das erste" to "ard",
        "sat 1" to "sat1",
        "kabel eins" to "kabel-eins",
        "kabel 1" to "kabel-eins",
        "pro sieben" to "prosieben",
        "rtl zwei" to "rtl2",
        "rtl 2" to "rtl2",
        "super rtl" to "super-rtl",
    )

    private val mediaLogos = mapOf(
        "amazon prime video" to "amazon-prime-video",
        "amazon prime" to "amazon-prime",
        "prime video" to "prime-video",
        "apple tv plus" to "apple-tv-plus",
        "disney plus" to "disney-plus",
        "discovery plus" to "discovery-plus",
        "hbo max" to "hbo-max",
        "netflix" to "netflix",
        "paramount plus" to "paramount-plus",
        "pluto tv" to "pluto-tv",
        "youtube tv" to "youtube-tv",
    )

    private val backupSuffixRegex = Regex(
        "\\s*(?:\\(\\s*backup\\s*\\)|\\[\\s*backup\\s*\\]|\\bbackup)\\s*$",
        RegexOption.IGNORE_CASE,
    )

    fun displayName(channelName: String): String {
        return channelName
            // Vavoo/Huhu append a one-letter source variant such as ".b" or ".c".
            // Hide that implementation detail, but retain useful labels such as
            // HD, 4K, +1, RAW, and (BACKUP) so source metadata can still be inspected.
            .replace(Regex("\\s*(?:[-.]|\\|)\\s*[a-z]\\s*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+(?:c|b|s)\\s*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** Name shared by primary and backup feeds while retaining quality labels. */
    fun playbackName(channelName: String): String =
        displayName(channelName)
            .replace(backupSuffixRegex, "")
            .replace(Regex("\\bpro\\s+7\\b", RegexOption.IGNORE_CASE), "PRO7")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun isBackup(channelName: String): Boolean =
        backupSuffixRegex.containsMatchIn(displayName(channelName))

    fun isHdPlus(channelName: String): Boolean =
        Regex("\\bhd\\+(?=\\s|$)", RegexOption.IGNORE_CASE)
            .containsMatchIn(playbackName(channelName))

    /** Canonical card name shared by HD and HD+ feeds. */
    fun playbackGroupName(channelName: String): String =
        playbackName(channelName)
            .replace(Regex("\\bhd\\+(?=\\s|$)", RegexOption.IGNORE_CASE), "HD")

    /**
     * Stable identity used to collapse provider-specific source variants into
     * one playable channel. Quality and format labels are deliberately kept,
     * so (for example) "RTL Crime" and "RTL Crime HD" remain separate.
     */
    fun playbackIdentity(channelName: String): String =
        playbackGroupName(channelName).lowercase(Locale.ROOT)

    /** Returns the hidden source tag used by Vavoo/Huhu, such as ".c" or ".s". */
    fun sourceVariant(channelName: String): String? {
        val punctuated = Regex(
            "\\s*((?:[-.]|\\|)\\s*[a-z])\\s*$",
            RegexOption.IGNORE_CASE,
        ).find(channelName)?.groupValues?.getOrNull(1)
            ?.replace(Regex("\\s+"), "")
        if (punctuated != null) return punctuated.lowercase(Locale.ROOT)

        return Regex("\\s+(c|b|s)\\s*$", RegexOption.IGNORE_CASE)
            .find(channelName)
            ?.groupValues
            ?.getOrNull(1)
            ?.lowercase(Locale.ROOT)
    }

    private fun logoLookupName(channelName: String): String {
        var cleaned = channelName
        do {
            val withoutBracketedTag = cleaned.replace(Regex("\\[[^\\[\\]]*\\]"), " ")
            if (withoutBracketedTag == cleaned) break
            cleaned = withoutBracketedTag
        } while (true)

        return cleaned
            .replace(Regex("\\s*[-.]\\s*[a-z](?=\\s|$)", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s*\\|\\s*[a-z](?=\\s|$)", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+(?:c|b|s)$", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\b(uhd|fhd|4k|hd|sd)\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun urls(channelName: String, countryCode: String?): List<String> {
        val code = countryCode?.lowercase(Locale.ROOT)
        val directory = countryDirectories[code]
        val cleaned = logoLookupName(channelName).lowercase(Locale.ROOT)
        val slug = aliases[cleaned]
            ?: cleaned.replace("&", " and ").replace(Regex("[^a-z0-9]+"), "-").trim('-')
        if (slug.isBlank()) return emptyList()

        val result = mutableListOf<String>()
        mediaLogos[cleaned]?.let { mediaSlug ->
            result += "$RAW_MISC_BASE/media/$mediaSlug.png"
        }
        if (directory != null && code != null) {
            result += "$RAW_BASE/$directory/$slug-$code.png"
        }
        result += "$RAW_MISC_BASE/custom/$slug.png"
        return result.distinct()
    }

    fun url(channelName: String, countryCode: String?): String? =
        urls(channelName, countryCode).firstOrNull()

    fun countryCode(providerName: String?): String? = when {
        providerName?.contains("Germany", ignoreCase = true) == true -> "de"
        providerName?.contains("Italy", ignoreCase = true) == true -> "it"
        providerName?.contains("France", ignoreCase = true) == true -> "fr"
        providerName?.contains("Spain", ignoreCase = true) == true -> "es"
        providerName?.contains("Poland", ignoreCase = true) == true -> "pl"
        providerName?.contains("Romania", ignoreCase = true) == true -> "ro"
        else -> null
    }
}
