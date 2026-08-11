package com.streamflixrevanced.streamflix.models

import com.streamflixrevanced.streamflix.adapters.AppAdapter

data class LiveProgram(
    val start: Long,
    val stop: Long,
    val title: String,
    val overview: String? = null,
)

data class LiveChannel(
    val id: String,
    val name: String,
    val logo: String? = null,
    val streamUrl: String? = null,
    val currentProgram: LiveProgram? = null,
    val nextProgram: LiveProgram? = null,
    val progressPercent: Int? = null,
    var providerName: String? = null,
    var favoritedAtMillis: Long? = null,
    var isFavorite: Boolean = false,
) : AppAdapter.Item {
    val poster: String? get() = logo
    val banner: String? get() = logo
    val overview: String
        get() = buildString {
            currentProgram?.let { append("Now: ${it.title}") }
            nextProgram?.let {
                if (isNotEmpty()) append("\n")
                append("Next: ${it.title}")
            }
        }.ifBlank { "Live TV" }

    override lateinit var itemType: AppAdapter.Type
}
