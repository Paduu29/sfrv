package com.streamflixrevanced.streamflix.utils

import com.streamflixrevanced.streamflix.models.LiveChannel

/**
 * Keeps the live-channel row/grid that launched the player so channel zapping
 * follows the order the user was browsing without putting a large list in nav args.
 */
object LiveChannelPlaybackQueue {
    private var providerName: String? = null
    private var channels: List<LiveChannel> = emptyList()

    fun set(providerName: String?, channels: List<LiveChannel>) {
        this.providerName = providerName
        this.channels = channels.distinctBy { channel -> channel.id }.toList()
    }

    fun get(providerName: String?, currentChannelId: String): List<LiveChannel> {
        if (this.providerName != providerName) return emptyList()
        return channels.takeIf { queue ->
            queue.any { channel -> channel.id == currentChannelId }
        }.orEmpty()
    }
}
