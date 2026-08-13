package com.streamflixrevanced.streamflix.fragments.movies

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixrevanced.streamflix.database.AppDatabase
import com.streamflixrevanced.streamflix.models.Movie
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

class MoviesViewModel(private val database: AppDatabase) : ViewModel() {

    private val _state = MutableStateFlow<State>(State.Loading)
    
    init {
        // Listen for provider changes and reload data
        viewModelScope.launch {
            ProviderChangeNotifier.providerChangeFlow.collect {
                getMovies()
            }
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<State> = combine(
        _state,
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessLoading -> {
                    if (state.movies.isEmpty()) {
                        emit(emptyList())
                    } else {
                        emitAll(database.movieDao().getByIds(state.movies.map { it.id }))
                    }
                }
                else -> emit(emptyList<Movie>())
            }
        },
    ) { state, moviesDb ->
        when (state) {
            is State.SuccessLoading -> {
                val moviesById = moviesDb.associateBy { it.id }
                State.SuccessLoading(
                    movies = state.movies.map { movie ->
                        moviesById[movie.id]
                            ?.takeIf { !movie.isSame(it) }
                            ?.let { movie.copy().merge(it) }
                            ?: movie
                    },
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
        data class SuccessLoading(val movies: List<Movie>, val hasMore: Boolean) : State()
        data class FailedLoading(val error: Exception) : State()
    }

    init {
        getMovies()
    }


    fun getMovies() = viewModelScope.launch(Dispatchers.IO) {
        ratingRefreshJob?.cancel()
        _state.emit(State.Loading)

        try {
            val provider = UserPreferences.currentProvider!!
            val movies = ParentalControlUtils.filterItems(provider.getMovies())
                .filterIsInstance<Movie>()

            page = 1

            _state.emit(State.SuccessLoading(movies, movies.isNotEmpty()))
            ratingRefreshJob = refreshRatings(provider, movies)
        } catch (e: Exception) {
            Log.e("MoviesViewModel", "getMovies: ", e)
            _state.emit(State.FailedLoading(e))
        }
    }

    fun loadMoreMovies() = viewModelScope.launch(Dispatchers.IO) {
        val currentState = _state.value
        if (currentState is State.SuccessLoading) {
            _state.emit(State.LoadingMore)

            try {
                val provider = UserPreferences.currentProvider!!
                val movies = ParentalControlUtils.filterItems(provider.getMovies(page + 1))
                    .filterIsInstance<Movie>()

                page += 1

                _state.emit(
                    State.SuccessLoading(
                        movies = currentState.movies + movies,
                        hasMore = movies.isNotEmpty(),
                    )
                )
                ratingRefreshJob?.cancel()
                ratingRefreshJob = refreshRatings(provider, movies)
            } catch (e: Exception) {
                Log.e("MoviesViewModel", "loadMoreMovies: ", e)
                _state.emit(State.FailedLoading(e))
            }
        }
    }

    private fun refreshRatings(provider: com.streamflixrevanced.streamflix.providers.Provider, movies: List<Movie>) =
        viewModelScope.launch(Dispatchers.IO) {
            if (!UserPreferences.enableTmdb) return@launch
            coroutineScope {
                movies.groupBy { "${it.title}|${it.released?.get(java.util.Calendar.YEAR)}" }
                    .values
                    .forEach { matchingMovies ->
                        val movie = matchingMovies.first()
                        launch {
                            val result = runCatching {
                                provider.getTmdbMovieRating(movie)
                            }.getOrNull() ?: return@launch
                            if (!result.found) return@launch
                            matchingMovies.forEach { matchingMovie ->
                                database.movieDao().updateRatingAndReleased(
                                    matchingMovie.id,
                                    result.rating,
                                    result.released,
                                )
                            }
                            _state.update { state ->
                                when (state) {
                                    is State.SuccessLoading -> state.copy(
                                        movies = state.movies.map { item ->
                                            if (matchingMovies.any { it.id == item.id }) {
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
