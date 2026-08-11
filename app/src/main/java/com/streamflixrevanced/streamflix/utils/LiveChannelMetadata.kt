package com.streamflixrevanced.streamflix.utils

import com.streamflixrevanced.streamflix.providers.HuhuProvider
import com.streamflixrevanced.streamflix.providers.Provider
import com.streamflixrevanced.streamflix.providers.VavooProvider

/** Resolves live metadata from the active provider catalog instead of stale Room rows. */
object LiveChannelMetadata {

    fun canonicalName(
        id: String,
        fallback: String,
        provider: Provider? = UserPreferences.currentProvider,
    ): String {
        val catalogName = when (provider) {
            is HuhuProvider -> HuhuProvider.cachedChannelName(id)
            is VavooProvider -> VavooProvider.cachedChannelName(id)
            else -> null
        }
        return TvLogoRepository.playbackGroupName(catalogName ?: fallback)
    }

    fun canonicalLogo(
        channelName: String,
        fallback: String?,
        provider: Provider? = UserPreferences.currentProvider,
    ): String? = TvLogoRepository.url(
        channelName,
        TvLogoRepository.countryCode(provider?.name),
    ) ?: fallback
}
