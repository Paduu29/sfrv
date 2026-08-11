package com.streamflixrevanced.streamflix.fragments.home

import android.util.Log
import com.streamflixrevanced.streamflix.StreamFlixApp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.streamflixrevanced.streamflix.adapters.AppAdapter
import com.streamflixrevanced.streamflix.database.AppDatabase
import com.streamflixrevanced.streamflix.sync.CloudSyncHooks
import com.streamflixrevanced.streamflix.models.Category
import com.streamflixrevanced.streamflix.models.Episode
import com.streamflixrevanced.streamflix.models.Movie
import com.streamflixrevanced.streamflix.models.LiveChannel
import com.streamflixrevanced.streamflix.models.LiveProgram
import com.streamflixrevanced.streamflix.models.Season
import com.streamflixrevanced.streamflix.models.TvShow
import com.streamflixrevanced.streamflix.providers.AnimeOnlineNinjaProvider
import com.streamflixrevanced.streamflix.providers.Provider
import com.streamflixrevanced.streamflix.ui.UserDataNotifier
import com.streamflixrevanced.streamflix.utils.HomeCacheStore
import com.streamflixrevanced.streamflix.utils.ParentalControlUtils
import com.streamflixrevanced.streamflix.utils.ParentalControlNotifier
import com.streamflixrevanced.streamflix.utils.ProfileManager
import com.streamflixrevanced.streamflix.utils.ProviderChangeNotifier
import com.streamflixrevanced.streamflix.utils.UserDataCache
import com.streamflixrevanced.streamflix.utils.UserDataCache.toCached
import com.streamflixrevanced.streamflix.utils.UserDataCache.toEpisode
import com.streamflixrevanced.streamflix.utils.UserDataCache.toMovie
import com.streamflixrevanced.streamflix.utils.UserPreferences
import com.streamflixrevanced.streamflix.utils.format
import com.streamflixrevanced.streamflix.utils.combine
import com.streamflixrevanced.streamflix.providers.HdFullProvider
import com.streamflixrevanced.streamflix.providers.MkissaProvider
import com.streamflixrevanced.streamflix.utils.LiveChannelMetadata
import com.streamflixrevanced.streamflix.utils.TmdbUtils
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

class HomeViewModel : ViewModel() {

    private val appContext = StreamFlixApp.instance.applicationContext

    private fun <T> preserveCacheOrder(
        cached: List<T>,
        incoming: List<T>,
        idOf: (T) -> String,
    ): List<T> {
        val incomingById = incoming.associateBy(idOf)
        val orderedExisting = cached.mapNotNull { cachedItem -> incomingById[idOf(cachedItem)] }
        val cachedIds = cached.mapTo(HashSet(cached.size), idOf)
        val appendedNew = incoming.filter { incomingItem ->
            idOf(incomingItem) !in cachedIds
        }
        return orderedExisting + appendedNew
    }

    /**
     * Keep only the most-recently-watched episode per TV show so the
     * continue-watching row doesn't show stale entries.
     */
    private fun deduplicateEpisodesByTvShow(
        episodes: List<UserDataCache.CachedEpisode>,
    ): List<UserDataCache.CachedEpisode> =
        episodes.filter { it.tvShowId == null } +
            episodes
                .filter { it.tvShowId != null }
                .groupBy { it.tvShowId }
                .map { (_, group) -> group.maxByOrNull { it.lastEngagementTimeUtcMillis ?: 0L }!! }

    private fun TvShow.toLiveChannel(programTitle: String? = currentProgram): LiveChannel {
        val canonicalName = LiveChannelMetadata.canonicalName(id, title)
        return LiveChannel(
            id = id,
            name = canonicalName,
            logo = LiveChannelMetadata.canonicalLogo(canonicalName, poster ?: banner),
            currentProgram = programTitle?.takeUnless {
                it.isBlank() || it.equals("Watch Now", ignoreCase = true) || it.equals("Live TV", ignoreCase = true)
            }?.let {
                LiveProgram(start = 0L, stop = 0L, title = it)
            },
            providerName = UserPreferences.currentProvider?.name,
            favoritedAtMillis = favoritedAtMillis,
            isFavorite = isFavorite,
        )
    }

    private fun db(): AppDatabase = AppDatabase.getInstance(appContext)

    private val _state = MutableStateFlow<State>(State.Loading)
    private val _userDataCache = MutableStateFlow<UserDataCache.UserData?>(null)
    private var currentProvider: Provider? = null
    private var ratingRefreshJob: Job? = null
    private val isLoadingHome = AtomicBoolean(false)
    private val reportedMissingHdFullCredentials = AtomicBoolean(false)
    private val syncedSeasonRepairs = ConcurrentHashMap.newKeySet<String>()
    private val seasonRepairQueue = Channel<SeasonRepair>(Channel.UNLIMITED)

    private data class SeasonRepair(
        val episode: Episode,
        val season: Season,
    )

    private fun scheduleSeasonRepair(episode: Episode, season: Season) {
        if (season.number == 0 || episode.id.isBlank()) return
        if (!syncedSeasonRepairs.add(episode.id)) return
        if (seasonRepairQueue.trySend(SeasonRepair(episode, season)).isFailure) {
            syncedSeasonRepairs.remove(episode.id)
        }
    }

    private fun repairSeasonAndSync(repair: SeasonRepair) {
        val (episode, season) = repair
        val database = db()
        database.seasonDao().insert(season)

        val persistedEpisode = database.episodeDao().getById(episode.id) ?: return
        val repairedEpisode = persistedEpisode.copy(season = season).apply {
            merge(persistedEpisode)
        }
        database.episodeDao().update(repairedEpisode)

        val provider = UserPreferences.currentProvider ?: return
        CloudSyncHooks.episode(appContext, provider, repairedEpisode)
    }

    private fun publishCatalog(categories: List<Category>): Boolean {
        var changed = false
        _state.update { current ->
            if (
                current is State.SuccessLoading &&
                HomeContentEquivalence.categoriesEqual(current.categories, categories)
            ) {
                current
            } else {
                changed = true
                State.SuccessLoading(
                    categories = categories,
                    refreshToken = (current as? State.SuccessLoading)?.refreshToken ?: 0L,
                )
            }
        }
        return changed
    }

    private fun publishUserDataCache(data: UserDataCache.UserData?) {
        if (_userDataCache.value != data) {
            _userDataCache.value = data
        }
    }

    private fun <T : AppAdapter.Item> Flow<List<T>>.distinctHomeItems(): Flow<List<T>> =
        distinctUntilChanged { before, after ->
            HomeContentEquivalence.itemsEqual(before, after)
        }

    private inline fun Category.mapItemsPreservingInstance(
        transform: (AppAdapter.Item) -> AppAdapter.Item,
    ): Category {
        var updated: MutableList<AppAdapter.Item>? = null
        list.forEachIndexed { index, item ->
            val transformed = transform(item)
            if (transformed !== item) {
                val mutableItems = updated ?: list.toMutableList().also { updated = it }
                mutableItems[index] = transformed
            }
        }
        return updated?.let { copy(list = it) } ?: this
    }

    private fun publishCatalogAndRefreshRatings(
        provider: Provider,
        categories: List<Category>,
    ) {
        val displayCategories = categoriesWithoutProviderRatings(categories)
        if (publishCatalog(displayCategories)) {
            ratingRefreshJob?.cancel()
            ratingRefreshJob = refreshHomeRatings(provider, displayCategories)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<State> = combine(
        _state,

        // CONTINUE WATCHING - Render cache first, then keep collecting Room so
        // playback changes update the row immediately.
        combine(
            _userDataCache.transformLatest { cache ->
                if (cache != null && cache.continueWatchingMovies.isNotEmpty()) {
                    emit(cache.continueWatchingMovies.map { it.toMovie() })
                }
                emitAll(db().movieDao().getWatchingMovies())
            }.distinctHomeItems().flowOn(Dispatchers.IO),
            _userDataCache.transformLatest { cache ->
                if (cache != null && cache.continueWatchingEpisodes.isNotEmpty()) {
                    emit(cache.continueWatchingEpisodes.map { it.toEpisode() })
                }
                emitAll(db().episodeDao().getLatestWatchingEpisodesPerTvShow())
            }.distinctHomeItems().flowOn(Dispatchers.IO),
            db().episodeDao().getNextEpisodesToWatch()
                .distinctHomeItems()
                .flowOn(Dispatchers.IO),
            db().tvShowDao().getAll()
                .distinctHomeItems()
                .flowOn(Dispatchers.IO),
        ) { watchingMovies, watchingEpisodes, watchNextEpisodes, tvShows ->

            val allEpisodes = (watchingEpisodes + watchNextEpisodes)
                .distinctBy { it.id }

            // The cache is intentionally used for fast home rendering, but it
            // may contain an older copy of an item without its playback state.
            // Enrich it from Room before binding cards.
            val movieDbById = if (watchingMovies.isEmpty()) {
                emptyMap()
            } else {
                db().movieDao().getAll().associateBy { it.id }
            }

            // Cache entries can contain the playback state without the Room
            // relationship metadata. Resolve the local episode rows as well,
            // otherwise continue-watching cards may fall back to "E#" and
            // omit the season number (most visible on the TV layout).
            val episodeDbById = if (allEpisodes.isEmpty()) {
                emptyMap()
            } else {
                db().episodeDao().getByIds(allEpisodes.map { it.id }).associateBy { it.id }
            }

            val seasonIds = (allEpisodes.mapNotNull { it.season?.id } +
                episodeDbById.values.mapNotNull { it.season?.id })
                .distinct()

            val tvShowsMap = tvShows.associateBy { it.id }

            val seasonsMap = if (seasonIds.isEmpty()) {
                emptyMap()
            } else {
                db().seasonDao()
                    .getByIds(seasonIds)
                    .associateBy { it.id }
            }

            // Home must be local-first. Provider lookups here made the whole
            // Home state wait for one request per show/season. The cached
            // episode already contains the progress and the database provides
            // the latest local show/season metadata. Provider refreshes belong
            // to the detail screen and must not block Home rendering.
            val localMovies = watchingMovies.map { movie ->
                val localMovie = movieDbById[movie.id]
                movie.copy().apply {
                    localMovie
                        ?.takeIf { it.watchHistory != null || it.isWatched }
                        ?.let { merge(it) }
                }
            }

            val localEpisodes = allEpisodes.map { episode ->
                val localEpisode = episodeDbById[episode.id]
                val resolvedSeason = episode.season?.id?.let { seasonsMap[it] }
                    ?: localEpisode?.season?.id?.let { seasonsMap[it] }
                    ?: episode.season
                val cachedSeason = resolvedSeason
                    ?.takeIf { it.number != 0 }
                if (cachedSeason != null && localEpisode != null) {
                    scheduleSeasonRepair(localEpisode, cachedSeason)
                }
                episode.copy(
                    tvShow = episode.tvShow?.id?.let { tvShowsMap[it] } ?: episode.tvShow,
                    season = resolvedSeason,
                ).apply {
                    localEpisode
                        ?.takeIf { it.watchHistory != null || it.isWatched }
                        ?.let { merge(it) }
                        ?: merge(episode)
                }
            }

            val orderedItems = (localMovies + localEpisodes)
                .filterNot { it.isWatched }
                .sortedByDescending { item ->
                    when (item) {
                        is Movie -> item.watchHistory?.lastEngagementTimeUtcMillis
                            ?: item.watchedDate?.timeInMillis
                            ?: 0L
                        is Episode -> item.watchHistory?.lastEngagementTimeUtcMillis
                            ?: item.watchedDate?.timeInMillis
                            ?: 0L
                        else -> 0L
                    }
                }

            if (UserPreferences.currentProvider?.let(Provider::supportsLiveTv) == true) {
                orderedItems.mapNotNull { item ->
                    when (item) {
                        is Episode -> item.tvShow?.toLiveChannel(item.title)
                        is Movie -> null
                        else -> null
                    }
                }.distinctBy { it.id }
            } else {
                orderedItems as List<AppAdapter.Item>
            }
        }.distinctUntilChanged { before, after ->
            HomeContentEquivalence.itemsEqual(before, after)
        }
            .flowOn(Dispatchers.IO),

        // RECENTLY WATCHED - Recorded immediately when playback starts.
        combine(
            db().movieDao().getRecentlyWatched().distinctHomeItems(),
            db().tvShowDao().getRecentlyWatched().distinctHomeItems(),
        ) { movies, tvShows ->
            val episodeIds = tvShows.mapNotNull { it.lastPlayedEpisodeId }.distinct()
            val episodesById = if (episodeIds.isEmpty()) {
                emptyMap()
            } else {
                db().episodeDao().getByIds(episodeIds).associateBy { it.id }
            }
            val seasonIds = episodesById.values.mapNotNull { it.season?.id }.distinct()
            val seasonsById = if (seasonIds.isEmpty()) {
                emptyMap()
            } else {
                db().seasonDao().getByIds(seasonIds).associateBy { it.id }
            }

            val recentlyWatchedTvShows = tvShows.map { tvShow ->
                tvShow.copy().apply {
                    merge(tvShow)
                    lastPlayedEpisode = lastPlayedEpisodeId?.let(episodesById::get)?.let { episode ->
                        val cachedEpisode = _userDataCache.value?.continueWatchingEpisodes
                            ?.firstOrNull { it.id == episode.id }
                        val repairedSeason = episode.season
                            ?.id
                            ?.let(seasonsById::get)
                            ?.takeIf { it.number != 0 }
                            ?: cachedEpisode?.seasonNumber
                                ?.takeIf { it != 0 }
                                ?.let { number ->
                                    Season(
                                        id = cachedEpisode.seasonId.orEmpty(),
                                        number = number,
                                        title = cachedEpisode.seasonTitle,
                                        poster = cachedEpisode.seasonPoster,
                                    )
                                }
                        if (repairedSeason != null) {
                            scheduleSeasonRepair(episode, repairedSeason)
                        }
                        episode.copy(
                            season = repairedSeason ?: episode.season,
                        )
                    }
                }
            }

            val orderedRecentlyWatched = (movies + recentlyWatchedTvShows)
                .sortedByDescending(::lastWatchedAt)
            val recentlyWatchedItems: List<AppAdapter.Item> = if (
                UserPreferences.currentProvider?.let(Provider::supportsLiveTv) == true
            ) {
                recentlyWatchedTvShows
                    .sortedByDescending(::lastWatchedAt)
                    .map { it.toLiveChannel(it.lastPlayedEpisode?.title ?: it.currentProgram) }
            } else {
                orderedRecentlyWatched
            }

            recentlyWatchedItems
        }.distinctUntilChanged { before, after ->
            HomeContentEquivalence.itemsEqual(before, after)
        }
            .flowOn(Dispatchers.IO),

        // MOVIES DB
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessLoading -> {
                    val movies = state.categories
                        .flatMap { it.list }
                        .filterIsInstance<Movie>()
                    if (movies.isEmpty()) {
                        emit(emptyList())
                    } else {
                        emitAll(
                            db().movieDao().getByIds(movies.map { it.id })
                                // Rating/release enrichment is background
                                // metadata. It must not make the Home state
                                // recompose for every individual lookup.
                                .debounce(200)
                                .distinctUntilChanged { old, new ->
                                    old.size == new.size &&
                                        old.zip(new).all { (before, after) ->
                                            before.isSame(after) &&
                                                before.rating == after.rating &&
                                                before.released == after.released
                                        }
                                }
                        )
                    }
                }
                else -> emit(emptyList<Movie>())
            }
        }.flowOn(Dispatchers.IO),

        // TV SHOWS DB
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessLoading -> {
                    val tvShows = state.categories
                        .flatMap { it.list }
                        .filterIsInstance<TvShow>()
                    if (tvShows.isEmpty()) {
                        emit(emptyList())
                    } else {
                        emitAll(
                            db().tvShowDao().getByIds(tvShows.map { it.id })
                                .debounce(200)
                                .distinctUntilChanged { old, new ->
                                    old.size == new.size &&
                                        old.zip(new).all { (before, after) ->
                                            before.isSame(after) &&
                                                before.rating == after.rating &&
                                                before.released == after.released
                                        }
                                }
                        )
                    }
                }
                else -> emit(emptyList<TvShow>())
            }
        }.flowOn(Dispatchers.IO),

        ) { state, continueWatching, recentlyWatched, moviesDb, tvShowsDb ->

        when (state) {
            is State.SuccessLoading -> {

                val moviesMap = moviesDb.associateBy { it.id }
                val tvShowsMap = tvShowsDb.associateBy { it.id }

                fun mergeItem(item: AppAdapter.Item): AppAdapter.Item {
                    return when (item) {
                        is Movie -> moviesMap[item.id]?.let { databaseMovie ->
                            if (item.isSame(databaseMovie) &&
                                item.rating == databaseMovie.rating &&
                                item.released == databaseMovie.released
                            ) {
                                item
                            } else {
                                item.copy(
                                    rating = databaseMovie.rating ?: item.rating,
                                    released = databaseMovie.released?.format("yyyy-MM-dd")
                                        ?: item.released?.format("yyyy-MM-dd"),
                                ).merge(databaseMovie)
                            }
                        } ?: item

                        is TvShow -> tvShowsMap[item.id]?.let { databaseTvShow ->
                            if (item.isSame(databaseTvShow) &&
                                item.rating == databaseTvShow.rating &&
                                item.released == databaseTvShow.released
                            ) {
                                item
                            } else {
                                item.copy(
                                    rating = databaseTvShow.rating ?: item.rating,
                                    released = databaseTvShow.released?.format("yyyy-MM-dd")
                                        ?: item.released?.format("yyyy-MM-dd"),
                                ).merge(databaseTvShow)
                            }
                        } ?: item

                        else -> item
                    }
                }

                val categories = ParentalControlUtils.filterCategories(listOfNotNull(

                    // FEATURED
                    state.categories
                        .find { it.name == Category.FEATURED }
                        ?.let { category ->
                            category.mapItemsPreservingInstance(::mergeItem)
                        },

                    // CONTINUE WATCHING
                    Category(
                        name = Category.CONTINUE_WATCHING,
                        list = continueWatching
                            .sortedByDescending {
                                when (it) {
                                    is Episode -> it.watchHistory?.lastEngagementTimeUtcMillis
                                        ?: it.watchedDate?.timeInMillis
                                        ?: 0L

                                    is Movie -> it.watchHistory?.lastEngagementTimeUtcMillis
                                        ?: it.watchedDate?.timeInMillis
                                        ?: 0L

                                    else -> 0L
                                }
                            }
                            .distinctBy {
                                when (it) {
                                    is Episode -> it.tvShow?.id
                                    is Movie -> it.id
                                    is LiveChannel -> it.id
                                    else -> null
                                }
                            },
                    ),

                    Category(
                        name = Category.RECENTLY_WATCHED,
                        list = recentlyWatched,
                    ),

                ) + state.categories
                    .filter { it.name != Category.FEATURED }
                    .map { category ->
                        category.mapItemsPreservingInstance(::mergeItem)
                    })

                State.SuccessLoading(categories, state.refreshToken)
            }

            else -> state
        }
    }.distinctUntilChanged { before, after ->
        when {
            before is State.SuccessLoading && after is State.SuccessLoading ->
                before.refreshToken == after.refreshToken &&
                    HomeContentEquivalence.categoriesEqual(before.categories, after.categories)
            else -> before == after
        }
    }.flowOn(Dispatchers.IO)

    private fun lastWatchedAt(item: AppAdapter.Item): Long = when (item) {
        is Movie -> item.lastPlayedAtMillis ?: 0L
        is TvShow -> item.lastPlayedAtMillis ?: 0L
        else -> 0L
    }

    sealed class State {
        data object Loading : State()
        data class SuccessLoading(
            val categories: List<Category>,
            val refreshToken: Long = 0L,
        ) : State()
        data class FailedLoading(val error: Exception) : State()
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            for (repair in seasonRepairQueue) {
                runCatching { repairSeasonAndSync(repair) }
                    .onFailure { error ->
                        syncedSeasonRepairs.remove(repair.episode.id)
                        Log.w("HomeViewModel", "Could not repair season metadata", error)
                    }
            }
        }
        val initialProvider = UserPreferences.currentProvider
        if (initialProvider != null) {
            currentProvider = initialProvider
            loadUserDataCache(initialProvider)
        }
        viewModelScope.launch {
            ProviderChangeNotifier.providerChangeFlow.collect {
                getHome()
            }
        }
        viewModelScope.launch {
            ParentalControlNotifier.changes.collect {
                refreshParentalFilter()
            }
        }

        viewModelScope.launch {
            UserDataNotifier.updates.collect {
                val provider = UserPreferences.currentProvider ?: return@collect
                loadUserDataCache(provider)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            HomeCacheStore.updates.collect { updateKey ->
                val provider = UserPreferences.currentProvider ?: return@collect
                if (!HomeCacheStore.isUpdateFor(updateKey, provider)) return@collect
                val categories = HomeCacheStore.read(appContext, provider) ?: return@collect
                publishCatalogAndRefreshRatings(provider, categories)
            }
        }
        getHome()
    }

    private fun refreshParentalFilter() {
        val current = _state.value
        if (current is State.SuccessLoading) {
            _state.value = current.copy(refreshToken = current.refreshToken + 1L)
        }
    }

    private fun categoriesWithoutProviderRatings(categories: List<Category>): List<Category> {
        if (!UserPreferences.enableTmdb) return categories
        return categories.map { category ->
            category.mapItemsPreservingInstance { item ->
                when (item) {
                    is Movie -> if (item.rating == null) item else item.copy(rating = null)
                    is TvShow -> if (item.rating == null) item else item.copy(rating = null)
                    else -> item
                }
            }
        }
    }

    private fun refreshHomeRatings(provider: Provider, categories: List<Category>): Job =
        viewModelScope.launch(Dispatchers.IO) {
            if (!UserPreferences.enableTmdb) return@launch
            val movieUpdates = ConcurrentHashMap<String, TmdbUtils.RatingLookup>()
            val tvShowUpdates = ConcurrentHashMap<String, TmdbUtils.RatingLookup>()
            coroutineScope {
                val movies = categories.flatMap { it.list }
                    .filterIsInstance<Movie>()
                    .groupBy { "${it.title}|${it.released?.get(java.util.Calendar.YEAR)}" }
                val tvShows = categories.flatMap { it.list }
                    .filterIsInstance<TvShow>()
                    .groupBy { "${it.title}|${it.released?.get(java.util.Calendar.YEAR)}" }

                movies.values.forEach { matchingMovies ->
                    launch {
                        val result = runCatching { provider.getTmdbMovieRating(matchingMovies.first()) }.getOrNull()
                            ?: return@launch
                        if (!result.found) return@launch
                        matchingMovies.forEach { movie -> movieUpdates[movie.id] = result }
                    }
                }

                tvShows.values.forEach { matchingTvShows ->
                    launch {
                        val result = runCatching { provider.getTmdbTvShowRating(matchingTvShows.first()) }.getOrNull()
                            ?: return@launch
                        if (!result.found) return@launch
                        matchingTvShows.forEach { tvShow -> tvShowUpdates[tvShow.id] = result }
                    }
                }
            }

            if (movieUpdates.isNotEmpty() || tvShowUpdates.isNotEmpty()) {
                val database = db()
                database.withTransaction {
                    movieUpdates.forEach { (id, result) ->
                        database.movieDao().updateRatingAndReleased(id, result.rating, result.released)
                    }
                    tvShowUpdates.forEach { (id, result) ->
                        database.tvShowDao().updateRatingAndReleased(id, result.rating, result.released)
                    }
                }
            }

            // Publish rating enrichment once after all lookups complete. The
            // catalog and its ordering are already usable; metadata freshness
            // should not compete with remote navigation for every result.
            if (movieUpdates.isNotEmpty() || tvShowUpdates.isNotEmpty()) {
                _state.update { state ->
                    when (state) {
                        is State.SuccessLoading -> state.copy(
                            categories = state.categories.map { category ->
                                category.mapItemsPreservingInstance { item ->
                                    when (item) {
                                        is Movie -> movieUpdates[item.id]?.let { result ->
                                            val released = result.released
                                                ?: item.released?.format("yyyy-MM-dd")
                                            if (
                                                item.rating == result.rating &&
                                                item.released?.format("yyyy-MM-dd") == released
                                            ) {
                                                item
                                            } else {
                                                item.copy(
                                                    rating = result.rating,
                                                    released = released,
                                                )
                                            }
                                        } ?: item
                                        is TvShow -> tvShowUpdates[item.id]?.let { result ->
                                            val released = result.released
                                                ?: item.released?.format("yyyy-MM-dd")
                                            if (
                                                item.rating == result.rating &&
                                                item.released?.format("yyyy-MM-dd") == released
                                            ) {
                                                item
                                            } else {
                                                item.copy(
                                                    rating = result.rating,
                                                    released = released,
                                                )
                                            }
                                        } ?: item
                                        else -> item
                                    }
                                }
                            }
                        )
                        else -> state
                    }
                }
            }
        }

    fun getHome() = viewModelScope.launch(Dispatchers.IO) {
        ratingRefreshJob?.cancel()
        if (!isLoadingHome.compareAndSet(false, true)) {
            return@launch
        }

        try {
            val provider = UserPreferences.currentProvider ?: run {
                _state.emit(State.FailedLoading(IllegalStateException("No provider selected")))
                return@launch
            }


        currentProvider = provider
        val appContext = StreamFlixApp.instance.applicationContext

        if (provider is HdFullProvider) {
            val hasCredentials =
                UserPreferences.getProviderCache(HdFullProvider, "username").isNotBlank() &&
                    UserPreferences.getProviderCache(HdFullProvider, "password").isNotBlank()

            if (!hasCredentials) {
                if (!reportedMissingHdFullCredentials.getAndSet(true)) {
                    _state.emit(
                        State.FailedLoading(
                            IllegalStateException(
                                "HdFull requires a saved username and password in provider settings."
                            )
                        )
                    )
                }
                isLoadingHome.set(false)
                return@launch
            }

            reportedMissingHdFullCredentials.set(false)
        }

        if (provider is HdFullProvider) {
            _state.emit(State.Loading)
            loadUserDataCache(provider)

            val result = runCatching { provider.getHome() }
            val categories = result.getOrNull()

            if (categories != null) {
                HomeCacheStore.write(appContext, provider, categories)
                publishCatalogAndRefreshRatings(
                    provider,
                    HomeCacheStore.read(appContext, provider) ?: categories,
                )
            } else {
                val error = result.exceptionOrNull()
                if (error is CancellationException) throw error
                Log.e("HomeViewModel", "getHome: ", error)
                val cachedCategories = HomeCacheStore.read(appContext, provider)
                    ?: HomeCacheStore.readLegacy(appContext, provider)
                if (!cachedCategories.isNullOrEmpty()) {
                    publishCatalogAndRefreshRatings(provider, cachedCategories)
                } else {
                    _state.emit(State.FailedLoading(error as? Exception ?: Exception(error?.message ?: "getHome failed")))
                }
            }
        } else {
            val cachedCategories = HomeCacheStore.read(appContext, provider)
                ?: HomeCacheStore.readLegacy(appContext, provider)
            val deferCachedHome =
                provider === MkissaProvider ||
                provider === AnimeOnlineNinjaProvider &&
                        !AnimeOnlineNinjaProvider.hasCurrentClearanceCookie() ||
                Provider.supportsLiveTv(provider)

            if (!cachedCategories.isNullOrEmpty() && !deferCachedHome) {
                publishCatalogAndRefreshRatings(provider, cachedCategories)
            } else {
                _state.emit(State.Loading)
            }

            loadUserDataCache(provider)

            try {
                val categories = provider.getHome()
                HomeCacheStore.write(appContext, provider, categories)
                publishCatalogAndRefreshRatings(
                    provider,
                    HomeCacheStore.read(appContext, provider) ?: categories,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("HomeViewModel", "getHome: ", e)
                if (cachedCategories.isNullOrEmpty()) {
                    _state.emit(State.FailedLoading(e))
                } else if (deferCachedHome) {
                    publishCatalogAndRefreshRatings(provider, cachedCategories)
                }
            }
        }
        } finally {
            // Also releases the gate on fresh-cache returns, errors, and
            // cancellation. Without this, one interrupted refresh could make
            // every later profile/provider refresh a no-op.
            isLoadingHome.set(false)
        }
    }

    private fun loadUserDataCache(provider: Provider) {
        val appContext = StreamFlixApp.instance.applicationContext
        val profileId = ProfileManager.activeProfileId ?: "default"

        viewModelScope.launch(Dispatchers.IO) {
            // File parsing belongs off the main thread, especially for profiles
            // with a long watch history.
            val cached = UserDataCache.read(appContext, provider, profileId)
            val isCurrent = {
                ProfileManager.activeProfileId == profileId &&
                    UserPreferences.currentProvider?.name == provider.name &&
                    UserPreferences.currentProvider?.baseUrl == provider.baseUrl
            }
            if (isCurrent()) {
                publishUserDataCache(cached)
            }

            val db = AppDatabase.getInstance(appContext)
            val moviesDeferred = async { db.movieDao().getFavorites().first() }
            val tvShowsDeferred = async { db.tvShowDao().getFavorites().first() }
            val watchingMoviesDeferred = async { db.movieDao().getWatchingMovies().first() }
            val watchingEpisodesDeferred = async { db.episodeDao().getLatestWatchingEpisodesPerTvShow().first() }

            val movies = moviesDeferred.await()
            val tvShows = tvShowsDeferred.await()
            val watchingMovies = watchingMoviesDeferred.await()
            val watchingEpisodesFromDb = watchingEpisodesDeferred.await()
            val watchingSeasonIds = watchingEpisodesFromDb.mapNotNull { it.season?.id }.distinct()
            val watchingSeasonsById = if (watchingSeasonIds.isEmpty()) {
                emptyMap()
            } else {
                db.seasonDao().getByIds(watchingSeasonIds).associateBy { it.id }
            }
            val watchingEpisodes = watchingEpisodesFromDb.map { episode ->
                episode.copy(
                    season = episode.season?.id?.let(watchingSeasonsById::get) ?: episode.season,
                )
            }

            val newData = UserDataCache.UserData(
                favoritesMovies = preserveCacheOrder(
                    cached = cached?.favoritesMovies ?: emptyList(),
                    incoming = movies.filter { it.isFavorite }.map { it.toCached() },
                    idOf = { it.id },
                ),
                favoritesTvShows = preserveCacheOrder(
                    cached = cached?.favoritesTvShows ?: emptyList(),
                    incoming = tvShows.filter { it.isFavorite }.map { it.toCached() },
                    idOf = { it.id },
                ),
                continueWatchingMovies = preserveCacheOrder(
                    cached = cached?.continueWatchingMovies ?: emptyList(),
                    incoming = watchingMovies
                        .filterNot { it.isWatched }
                        .map { it.toCached() },
                    idOf = { it.id },
                ),
                continueWatchingEpisodes = deduplicateEpisodesByTvShow(
                    preserveCacheOrder(
                        cached = cached?.continueWatchingEpisodes ?: emptyList(),
                        incoming = watchingEpisodes
                            .filterNot { it.isWatched }
                            .map { it.toCached() },
                        idOf = { it.id },
                    )
                ),
            )

            // A profile/provider switch may have happened while Room was
            // reading. Never publish or persist the stale result into the new
            // profile's cache.
            if (!isCurrent()) return@launch

            UserDataCache.write(appContext, provider, newData, profileId)

            publishUserDataCache(newData)
        }
    }
}
