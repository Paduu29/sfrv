package com.streamflixrevanced.streamflix.fragments.home

import com.streamflixrevanced.streamflix.adapters.AppAdapter
import com.streamflixrevanced.streamflix.models.Category
import com.streamflixrevanced.streamflix.models.Episode
import com.streamflixrevanced.streamflix.models.LiveChannel
import com.streamflixrevanced.streamflix.models.Movie
import com.streamflixrevanced.streamflix.models.Season
import com.streamflixrevanced.streamflix.models.TvShow

/**
 * Compares the content rendered by Home without consulting mutable adapter-only
 * state such as itemType, selectedIndex, or spacing.
 */
internal object HomeContentEquivalence {

    fun categoriesEqual(first: List<Category>, second: List<Category>): Boolean =
        first.size == second.size && first.indices.all { index ->
            val before = first[index]
            val after = second[index]
            before.stableKey == after.stableKey && itemsEqual(before.list, after.list)
        }

    fun itemsEqual(first: List<AppAdapter.Item>, second: List<AppAdapter.Item>): Boolean =
        first.size == second.size && first.indices.all { index ->
            itemEqual(first[index], second[index])
        }

    private fun itemEqual(first: AppAdapter.Item, second: AppAdapter.Item): Boolean = when {
        first is Movie && second is Movie -> movieEqual(first, second)
        first is TvShow && second is TvShow -> tvShowEqual(first, second)
        first is Episode && second is Episode -> episodeEqual(first, second)
        first is LiveChannel && second is LiveChannel -> first == second
        else -> first == second
    }

    private fun movieEqual(first: Movie, second: Movie): Boolean =
        first.id == second.id &&
            first.title == second.title &&
            first.overview == second.overview &&
            first.released?.timeInMillis == second.released?.timeInMillis &&
            first.runtime == second.runtime &&
            first.trailer == second.trailer &&
            first.quality == second.quality &&
            first.rating == second.rating &&
            first.poster == second.poster &&
            first.banner == second.banner &&
            first.imdbId == second.imdbId &&
            first.providerName == second.providerName &&
            first.isFavorite == second.isFavorite &&
            first.favoritedAtMillis == second.favoritedAtMillis &&
            first.isWatched == second.isWatched &&
            first.watchedDate?.timeInMillis == second.watchedDate?.timeInMillis &&
            first.watchHistory == second.watchHistory &&
            first.lastPlayedAtMillis == second.lastPlayedAtMillis

    private fun tvShowEqual(first: TvShow, second: TvShow): Boolean =
        first.id == second.id &&
            first.title == second.title &&
            first.overview == second.overview &&
            first.released?.timeInMillis == second.released?.timeInMillis &&
            first.runtime == second.runtime &&
            first.trailer == second.trailer &&
            first.quality == second.quality &&
            first.rating == second.rating &&
            first.poster == second.poster &&
            first.banner == second.banner &&
            first.imdbId == second.imdbId &&
            first.providerName == second.providerName &&
            first.isFavorite == second.isFavorite &&
            first.favoritedAtMillis == second.favoritedAtMillis &&
            first.isWatching == second.isWatching &&
            first.lastPlayedAtMillis == second.lastPlayedAtMillis &&
            first.lastPlayedEpisodeId == second.lastPlayedEpisodeId &&
            first.currentProgram == second.currentProgram &&
            episodeSummaryEqual(first.lastPlayedEpisode, second.lastPlayedEpisode) &&
            seasonsEqual(first.seasons, second.seasons)

    private fun episodeEqual(first: Episode, second: Episode): Boolean =
        first.id == second.id &&
            first.number == second.number &&
            first.title == second.title &&
            first.overview == second.overview &&
            first.released?.timeInMillis == second.released?.timeInMillis &&
            first.poster == second.poster &&
            first.isWatched == second.isWatched &&
            first.watchedDate?.timeInMillis == second.watchedDate?.timeInMillis &&
            first.watchHistory == second.watchHistory &&
            tvShowSummaryEqual(first.tvShow, second.tvShow) &&
            seasonSummaryEqual(first.season, second.season)

    private fun tvShowSummaryEqual(first: TvShow?, second: TvShow?): Boolean = when {
        first == null || second == null -> first == null && second == null
        else -> first.id == second.id &&
            first.title == second.title &&
            first.poster == second.poster &&
            first.banner == second.banner &&
            first.currentProgram == second.currentProgram
    }

    private fun seasonSummaryEqual(first: Season?, second: Season?): Boolean = when {
        first == null || second == null -> first == null && second == null
        else -> first.id == second.id &&
            first.number == second.number &&
            first.title == second.title &&
            first.poster == second.poster
    }

    private fun episodeSummaryEqual(first: Episode?, second: Episode?): Boolean = when {
        first == null || second == null -> first == null && second == null
        else -> first.id == second.id &&
            first.number == second.number &&
            first.title == second.title &&
            first.watchHistory == second.watchHistory &&
            seasonSummaryEqual(first.season, second.season)
    }

    private fun seasonsEqual(first: List<Season>, second: List<Season>): Boolean =
        first.size == second.size && first.indices.all { index ->
            val before = first[index]
            val after = second[index]
            seasonSummaryEqual(before, after) &&
                before.episodes.size == after.episodes.size &&
                before.episodes.indices.all { episodeIndex ->
                    episodeSummaryEqual(
                        before.episodes[episodeIndex],
                        after.episodes[episodeIndex],
                    )
                }
        }
}
