package com.streamflixrevanced.streamflix.fragments.favorites

import androidx.lifecycle.ViewModel
import com.streamflixrevanced.streamflix.adapters.AppAdapter
import com.streamflixrevanced.streamflix.database.AppDatabase
import com.streamflixrevanced.streamflix.models.Movie
import com.streamflixrevanced.streamflix.models.TvShow
import com.streamflixrevanced.streamflix.models.LiveChannel
import com.streamflixrevanced.streamflix.providers.Provider
import com.streamflixrevanced.streamflix.utils.ProviderChangeNotifier
import com.streamflixrevanced.streamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

class FavoritesViewModel(
    database: AppDatabase,
    private val providerName: String,
) : ViewModel() {

    enum class Section(val key: String) {
        MOVIES("movies"),
        TV_SHOWS("tv_shows");

        companion object {
            fun fromKey(key: String): Section? = entries.firstOrNull { it.key == key }
        }
    }

    enum class SortMode(val key: String) {
        MANUAL("manual"),
        RECENTLY_ADDED("recently_added"),
        TITLE_ASCENDING("title_ascending"),
        TITLE_DESCENDING("title_descending");

        companion object {
            fun fromKey(key: String): SortMode = entries.firstOrNull { it.key == key } ?: MANUAL
        }
    }

    data class FavoriteSection(
        val section: Section,
        val items: List<AppAdapter.Item>,
    )

    private val order = MutableStateFlow(readOrder())
    private val sortMode = MutableStateFlow(SortMode.fromKey(UserPreferences.getFavoriteSortMode(providerName)))
    private val orderRevision = MutableStateFlow(0)
    @Volatile
    private var currentSections: List<FavoriteSection> = emptyList()

    // The ViewModel can survive a profile switch, while AppDatabase is rebound
    // to a different profile. Recreate the DAO flows after every profile/provider
    // change instead of observing the database captured at construction time.
    private val profileAwareFavorites: Flow<Pair<List<Movie>, List<TvShow>>> =
        ProviderChangeNotifier.providerChangeFlow
            .onStart { emit(Unit) }
            .map { AppDatabase.getInstance(com.streamflixrevanced.streamflix.StreamFlixApp.instance.applicationContext) }
            .flatMapLatest { currentDatabase ->
                combine(
                    currentDatabase.movieDao().getFavorites(),
                    currentDatabase.tvShowDao().getFavorites(),
                ) { movies, tvShows -> movies to tvShows }
            }

    val sections: Flow<List<FavoriteSection>> = combine(
        profileAwareFavorites,
        order,
        combine(sortMode, orderRevision) { mode, _ -> mode },
    ) { favorites, sectionOrder, mode ->
        val (movies, tvShows) = favorites
        val liveProvider = Provider.findByName(providerName)?.takeIf { Provider.supportsLiveTv(it) }
        val displayedTvShows: List<AppAdapter.Item> = if (liveProvider != null) {
            tvShows.map { show ->
                LiveChannel(
                    id = show.id,
                    name = show.title,
                    logo = show.poster,
                    providerName = providerName,
                    favoritedAtMillis = show.favoritedAtMillis,
                    isFavorite = true,
                )
            }
        } else {
            tvShows
        }
        sectionOrder.map { section ->
            when (section) {
                Section.MOVIES -> FavoriteSection(section, sortItems(section, movies, mode))
                Section.TV_SHOWS -> FavoriteSection(section, sortItems(section, displayedTvShows, mode))
            }
        }.also { currentSections = it }
    }.flowOn(Dispatchers.IO)

    fun reverseCategoryOrder() {
        setCategoryOrder(order.value.reversed())
    }

    fun setCategoryOrder(newOrder: List<Section>) {
        val normalized = (newOrder + Section.entries).distinct()
        order.value = normalized
        UserPreferences.setFavoriteCategoryOrder(providerName, normalized.map { it.key })
    }

    fun setSortMode(mode: SortMode) {
        sortMode.value = mode
        UserPreferences.setFavoriteSortMode(providerName, mode.key)
    }

    fun moveItem(section: Section, itemId: String, delta: Int) {
        val ids = currentSections.firstOrNull { it.section == section }
            ?.items
            ?.mapNotNull(::itemId)
            ?.toMutableList()
            ?: return
        val from = ids.indexOf(itemId)
        if (from < 0) return
        val to = (from + delta).coerceIn(0, ids.lastIndex)
        if (from == to) return
        val moved = ids.removeAt(from)
        ids.add(to, moved)
        UserPreferences.setFavoriteItemOrder(providerName, section.key, ids)
        UserPreferences.setFavoriteSortMode(providerName, SortMode.MANUAL.key)
        sortMode.value = SortMode.MANUAL
        orderRevision.value += 1
    }

    fun setManualItemOrder(section: Section, itemIds: List<String>) {
        UserPreferences.setFavoriteItemOrder(providerName, section.key, itemIds)
        UserPreferences.setFavoriteSortMode(providerName, SortMode.MANUAL.key)
        sortMode.value = SortMode.MANUAL
        orderRevision.value += 1
    }

    fun currentSortMode(): SortMode = sortMode.value

    private fun sortItems(
        section: Section,
        items: List<AppAdapter.Item>,
        mode: SortMode,
    ): List<AppAdapter.Item> = when (mode) {
        SortMode.MANUAL -> {
            val savedOrder = UserPreferences.getFavoriteItemOrder(providerName, section.key)
            val currentIds = items.mapNotNull(::itemId)
            val currentIdSet = currentIds.toSet()
            val normalizedOrder = (
                savedOrder.filter { it in currentIdSet } +
                    currentIds.filterNot { it in savedOrder }
                ).distinct()

            if (normalizedOrder != savedOrder) {
                UserPreferences.setFavoriteItemOrder(providerName, section.key, normalizedOrder)
            }

            val positions = normalizedOrder.withIndex().associate { it.value to it.index }
            items.sortedBy { positions[itemId(it)] ?: Int.MAX_VALUE }
        }
        SortMode.RECENTLY_ADDED -> items.sortedByDescending(::favoriteTime)
        SortMode.TITLE_ASCENDING -> items.sortedBy(::titleLowercase)
        SortMode.TITLE_DESCENDING -> items.sortedByDescending(::titleLowercase)
    }

    private fun itemId(item: AppAdapter.Item): String? = when (item) {
        is Movie -> item.id
        is TvShow -> item.id
        is LiveChannel -> item.id
        else -> null
    }

    private fun favoriteTime(item: AppAdapter.Item): Long = when (item) {
        is Movie -> item.favoritedAtMillis ?: 0L
        is TvShow -> item.favoritedAtMillis ?: 0L
        is LiveChannel -> item.favoritedAtMillis ?: 0L
        else -> 0L
    }

    private fun titleLowercase(item: AppAdapter.Item): String = when (item) {
        is Movie -> item.title.lowercase()
        is TvShow -> item.title.lowercase()
        is LiveChannel -> item.name.lowercase()
        else -> ""
    }

    private fun readOrder(): List<Section> = UserPreferences
        .getFavoriteCategoryOrder(providerName)
        .mapNotNull(Section::fromKey)
        .let { (it + Section.entries).distinct() }
}
