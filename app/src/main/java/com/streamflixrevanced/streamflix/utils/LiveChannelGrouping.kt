package com.streamflixrevanced.streamflix.utils

import com.streamflixrevanced.streamflix.models.LiveChannel

object LiveChannelGrouping {

    data class Rule(
        val id: String,
        val name: String,
        val prefixes: List<String>,
    )

    data class Group(
        val id: String,
        val name: String,
        val channels: List<LiveChannel>,
    )

    private data class Labels(
        val all: String,
        val other: String,
        val sports: String,
        val kids: String,
        val music: String,
    )

    private val labelsByLanguage = mapOf(
        "de" to Labels("Alle", "Andere", "Sports", "Kids", "Music"),
        "it" to Labels("Tutti", "Altri", "Sport", "Bambini", "Musica"),
        "fr" to Labels("Toutes", "Autres", "Sport", "Jeunesse", "Musique"),
        "es" to Labels("Todos", "Otros", "Deportes", "Infantil", "Música"),
        "pl" to Labels("Wszystkie", "Inne", "Sport", "Dzieci", "Muzyka"),
        "ro" to Labels("Toate", "Altele", "Sport", "Copii", "Muzică"),
    )

    private val commonRules = listOf(
        Rule("sky", "Sky", listOf("Sky")),
        Rule("dazn", "DAZN", listOf("DAZN")),
        Rule("discovery", "Discovery", listOf("Discovery", "DMAX")),
        Rule("nat geo", "Nat Geo", listOf("Nat Geo", "National Geo")),
        Rule("eurosport", "Eurosport", listOf("Eurosport")),
        Rule("warner", "Warner", listOf("Warner", "TNT")),
    )

    private val rulesByLanguage = mapOf(
        "de" to listOf(
            Rule("magenta", "Magenta", listOf("Magenta", "Telekom")),
            Rule("bluetv", "BlueTV", listOf("BlueTV")),
            Rule("db liga", "DB LIGA", listOf("DB Liga")),
            Rule("dyn", "DYN", listOf("DYN")),
            Rule("rtl", "RTL Gruppe", listOf("RTL", "VOX", "Nitro", "Super RTL", "N-TV", "NTV")),
            Rule(
                "prosieben-sat1",
                "ProSieben - Sat.1",
                listOf("Sat 1", "Sat.1", "ProSieben", "Pro 7", "Pro7", "Kabel 1", "Kabel Eins", "Sixx"),
            ),
            Rule(
                "public-tv",
                "Öffentliches Fernsehen",
                listOf(
                    "ARD", "Das Erste", "ZDF", "3sat", "Arte", "Phoenix", "Tagesschau", "ONE",
                    "WDR", "NDR", "SWR", "MDR", "RBB", "BR ", "BR.", "HR ", "HR.",
                ),
            ),
            Rule("cinedome", "Cinedome", listOf("Cinedome")),
        ),
        "it" to listOf(
            Rule("rai", "RAI", listOf("RAI")),
            Rule(
                "mediaset-it",
                "Mediaset",
                listOf(
                    "Mediaset", "Canale 5", "Italia 1", "Italia 2", "Rete 4", "Iris", "La 5",
                    "Cine 34", "20 Mediaset", "27 Twenty", "Focus", "Top Crime", "Boing", "Cartoonito",
                ),
            ),
            Rule("la7", "La7", listOf("La 7", "La7")),
            Rule("rsi", "RSI", listOf("RSI")),
        ),
        "fr" to listOf(
            Rule(
                "france-tv",
                "France Télévisions",
                listOf("France 2", "France 3", "France 4", "France 5", "Franceinfo"),
            ),
            Rule("tf1", "Groupe TF1", listOf("TF1", "TMC", "TFX", "LCI", "Série Club", "Serie Club")),
            Rule(
                "m6",
                "Groupe M6",
                listOf("M6", "6ter", "W9", "Gulli", "Téva", "Teva", "Paris Première", "Paris Premiere"),
            ),
            Rule("canal-plus", "Canal+", listOf("Canal +", "Canal+")),
            Rule("bein-sports", "beIN Sports", listOf("beIN Sports", "beIN Sport")),
            Rule("bfm-rmc", "BFM / RMC", listOf("BFM", "RMC")),
        ),
        "es" to listOf(
            Rule(
                "rtve",
                "RTVE",
                listOf("RTVE", "TVE", "La 1", "La 2", "24 Horas", "Teledeporte", "Clan TVE", "Clan"),
            ),
            Rule(
                "atresmedia",
                "Atresmedia",
                listOf("Antena 3", "A3 Series", "Atreseries", "La Sexta", "Neox", "Nova", "Mega"),
            ),
            Rule(
                "mediaset-es",
                "Mediaset España",
                listOf(
                    "Telecinco", "Cuatro", "FDF", "Factoría de Ficción", "Factoria de Ficcion",
                    "Boing", "Divinity", "Energy", "Be Mad",
                ),
            ),
            Rule("movistar", "Movistar+", listOf("Movistar", "M+", "M.")),
            Rule("laliga", "LaLiga", listOf("LaLiga", "La Liga")),
        ),
        "pl" to listOf(
            Rule("tvp", "TVP", listOf("TVP")),
            Rule("polsat", "Polsat", listOf("Polsat", "CI Polsat", "JimJam Polsat")),
            Rule("tvn", "TVN", listOf("TVN")),
            Rule(
                "canal-plus",
                "Canal+",
                listOf(
                    "Canal +", "Canal+", "Canal Sport", "Canal Film", "Canal Family", "Canal Dokument",
                    "Canal Seriale", "Canal Now",
                ),
            ),
            Rule("eleven-sports", "Eleven Sports", listOf("Eleven Sport")),
        ),
        "ro" to listOf(
            Rule("tvr", "TVR", listOf("TVR", "TVRI")),
            Rule(
                "pro-tv",
                "Pro TV",
                listOf("Pro TV", "Pro 2", "Pro Arena", "Pro Cinema", "Pro Gold", "Pro X", "Pro Fit"),
            ),
            Rule("antena", "Antena", listOf("Antena")),
            Rule("digi", "Digi", listOf("Digi")),
            Rule("prima", "Prima", listOf("Prima")),
        ),
    )

    private fun categoryRules(labels: Labels) = listOf(
        Rule(
            "sports",
            labels.sports,
            listOf(
                "SportDeutschland", "Sport1", "Sport 1", "Sport Digital", "SportDigital", "MyTeamTV",
                "beIN Sports", "Canal Sport", "Eleven Sport", "ESPN", "Extreme Sport", "Fightbox",
                "Gol TV", "Info Sport", "Ligue ", "Orange Sport", "Prima Sport", "Telekom Sport",
            ),
        ),
        Rule(
            "kids",
            labels.kids,
            listOf(
                "Disney", "Nick", "Cartoon", "Boomerang", "KiKA", "Toggo", "Boing", "Cartoonito",
                "Canal J", "Gulli", "K2", "Rai Gulp", "Rai Yoyo", "Clan", "MiniMini", "JimJam",
                "Duck TV", "Megamax", "Da Vinci Kids", "CBeebies",
            ),
        ),
        Rule(
            "music",
            labels.music,
            listOf(
                "MTV", "Deluxe", "4Fun", "Eska", "Kiss TV", "Mooz", "Music Channel", "Nuta",
                "Polo TV", "Radio Italia", "RDS", "M2O", "Deejay TV",
            ),
        ),
    )

    /**
     * Groups channels using broadcaster families for [language]. A channel is assigned to the
     * first rule whose prefix matches the start of its name, ignoring case and leading whitespace.
    */
    fun group(channels: List<LiveChannel>, language: String = "de"): List<Group> {
        val languageCode = language.lowercase().substringBefore('-')
            .takeIf(labelsByLanguage::containsKey)
            ?: "de"
        val labels = labelsByLanguage.getValue(languageCode)
        val rules = commonRules + rulesByLanguage[languageCode].orEmpty() + categoryRules(labels)
        val assignments = channels.groupBy { channel -> matchingRule(channel, rules) }
        val namedGroups = rules.mapNotNull { rule ->
            assignments[rule]?.takeIf { it.isNotEmpty() }?.let { matchingChannels ->
                Group(
                    id = groupId(rule.id),
                    name = rule.name,
                    channels = matchingChannels,
                )
            }
        }

        // Keep the original flat channel list when none of the configured rules match.
        if (namedGroups.isEmpty()) return emptyList()

        val otherChannels = assignments[null].orEmpty()
        return buildList {
            add(Group(id = groupId("all"), name = labels.all, channels = channels))
            addAll(namedGroups)
            if (otherChannels.isNotEmpty()) {
                add(Group(id = groupId("andere"), name = labels.other, channels = otherChannels))
            }
        }
    }

    private fun matchingRule(channel: LiveChannel, rules: List<Rule>): Rule? {
        val channelName = channel.name.trimStart()
        return rules.firstOrNull { rule ->
            rule.prefixes.any { prefix -> channelName.startsWith(prefix, ignoreCase = true) }
        }
    }

    private fun groupId(id: String) = "live-channel-group:$id"
}
