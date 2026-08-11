package com.streamflixrevanced.streamflix.fragments.tv_shows

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixrevanced.streamflix.database.AppDatabase
import com.streamflixrevanced.streamflix.models.TvShow
import com.streamflixrevanced.streamflix.models.LiveChannel
import com.streamflixrevanced.streamflix.adapters.AppAdapter
import com.streamflixrevanced.streamflix.providers.Provider
import com.streamflixrevanced.streamflix.utils.ParentalControlUtils
import com.streamflixrevanced.streamflix.utils.UserPreferences
import com.streamflixrevanced.streamflix.utils.ProviderChangeNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.update
import com.streamflixrevanced.streamflix.utils.TmdbUtils
import com.streamflixrevanced.streamflix.utils.format

class TvShowsViewModel(private val database: AppDatabase) : ViewModel() {

    private val _state = MutableStateFlow<State>(State.Loading)
    
    init {
        // Listen for provider changes and reload data
        viewModelScope.launch {
            ProviderChangeNotifier.providerChangeFlow.collect {
                getTvShows()
            }
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<State> = combine(
        _state,
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessLoading -> {
                    if (state.tvShows.isEmpty()) {
                        emit(emptyList())
                    } else {
                        emitAll(database.tvShowDao().getByIds(state.tvShows.map { it.id }))
                    }
                }
                else -> emit(emptyList<TvShow>())
            }
        },
    ) { state, tvShowsDb ->
        when (state) {
            is State.SuccessLoading -> {
                val tvShowsById = tvShowsDb.associateBy { it.id }
                State.SuccessLoading(
                    tvShows = state.tvShows.map { tvShow ->
                        tvShowsById[tvShow.id]
                            ?.takeIf { !tvShow.isSame(it) }
                            ?.let { tvShow.copy().merge(it) }
                            ?: tvShow
                    },
                    liveChannels = state.liveChannels,
                    hasMore = state.hasMore
                )

            }
            else -> state
        }
    }.flowOn(Dispatchers.IO)

    private var page = 1
    private var ratingRefreshJob: Job? = null

    sealed class State {
        data object Loading : State()
        data object LoadingMore : State()
        data class SuccessLoading(
            val tvShows: List<TvShow>,
            val hasMore: Boolean,
            val liveChannels: List<LiveChannel> = emptyList(),
        ) : State()
        data class FailedLoading(val error: Exception) : State()
    }

    init {
        getTvShows()
    }


    fun getTvShows() = viewModelScope.launch(Dispatchers.IO) {
        ratingRefreshJob?.cancel()
        _state.emit(State.Loading)

        try {
            val provider = UserPreferences.currentProvider!!
            if (Provider.supportsLiveTv(provider)) {
                val liveChannels = provider.getLiveChannels()
                    .onEach { it.itemType = AppAdapter.Type.LIVE_CHANNEL_GRID_MOBILE_ITEM }
                page = 1
                _state.emit(State.SuccessLoading(emptyList(), false, liveChannels))
                return@launch
            }
            val tvShows = ParentalControlUtils.filterItems(provider.getTvShows())
                .filterIsInstance<TvShow>()
                .map { tvShow ->
                    if (UserPreferences.enableTmdb) tvShow.copy(rating = null) else tvShow
                }

            page = 1

            _state.emit(State.SuccessLoading(tvShows, tvShows.isNotEmpty()))
            ratingRefreshJob = refreshRatings(provider, tvShows)
        } catch (e: Exception) {
            Log.e("TvShowsViewModel", "getTvShows: ", e)
            _state.emit(State.FailedLoading(e))
        }
    }

    fun loadMoreTvShows() = viewModelScope.launch(Dispatchers.IO) {
        val currentState = _state.value
        if (currentState is State.SuccessLoading) {
            _state.emit(State.LoadingMore)

            try {
                val provider = UserPreferences.currentProvider!!
                if (Provider.supportsLiveTv(provider)) return@launch
                val tvShows = ParentalControlUtils.filterItems(provider.getTvShows(page + 1))
                    .filterIsInstance<TvShow>()
                    .map { tvShow ->
                        if (UserPreferences.enableTmdb) tvShow.copy(rating = null) else tvShow
                    }

                page += 1

                _state.emit(
                    State.SuccessLoading(
                        tvShows = currentState.tvShows + tvShows,
                        hasMore = tvShows.isNotEmpty(),
                    )
                )
                ratingRefreshJob?.cancel()
                ratingRefreshJob = refreshRatings(provider, tvShows)
            } catch (e: Exception) {
                Log.e("TvShowsViewModel", "loadMoreTvShows: ", e)
                _state.emit(State.FailedLoading(e))
            }
        }
    }

    private fun refreshRatings(provider: com.streamflixrevanced.streamflix.providers.Provider, tvShows: List<TvShow>) =
        viewModelScope.launch(Dispatchers.IO) {
            if (!UserPreferences.enableTmdb) return@launch
            coroutineScope {
                tvShows.groupBy { "${it.title}|${it.released?.get(java.util.Calendar.YEAR)}" }
                    .values
                    .forEach { matchingTvShows ->
                        val tvShow = matchingTvShows.first()
                        launch {
                            val result = runCatching {
                                provider.getTmdbTvShowRating(tvShow)
                            }.getOrNull() ?: return@launch
                            if (!result.found) return@launch
                            matchingTvShows.forEach { matchingTvShow ->
                                database.tvShowDao().updateRatingAndReleased(
                                    matchingTvShow.id,
                                    result.rating,
                                    result.released,
                                )
                            }
                            _state.update { state ->
                                when (state) {
                                    is State.SuccessLoading -> state.copy(
                                        tvShows = state.tvShows.map { item ->
                                            if (matchingTvShows.any { it.id == item.id }) {
                                                item.copy(
                                                    rating = result.rating,
                                                    released = result.released ?: item.released?.format("yyyy-MM-dd"),
                                                )
                                            } else item
                                        }
                                    )
                                    else -> state
                                }
                            }
                        }
                    }
            }
        }
}
