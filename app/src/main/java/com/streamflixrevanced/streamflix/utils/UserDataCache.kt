package com.streamflixrevanced.streamflix.utils

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.streamflixrevanced.streamflix.adapters.AppAdapter
import com.streamflixrevanced.streamflix.database.AppDatabase
import com.streamflixrevanced.streamflix.models.Episode
import com.streamflixrevanced.streamflix.models.Movie
import com.streamflixrevanced.streamflix.models.Season
import com.streamflixrevanced.streamflix.models.TvShow
import com.streamflixrevanced.streamflix.models.WatchItem
import com.streamflixrevanced.streamflix.providers.Provider
import com.streamflixrevanced.streamflix.sync.CloudSyncHooks
import com.streamflixrevanced.streamflix.ui.UserDataNotifier
import com.streamflixrevanced.streamflix.utils.ProfileManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object UserDataCache {

    private val gson = Gson()
    private val memoryCache = ConcurrentHashMap<String, UserData>()

    // Prevent duplicate concurrent reads for the same cache key
    private val readLocks = ConcurrentHashMap<String, Any>()

    // Incremented whenever an active profile changes so in-flight operations can abort
    private val profileEpoch = java.util.concurrent.atomic.AtomicInteger(0)

    data class UserData(
        val favoritesMovies: List<CachedMovie> = emptyList(),
        val favoritesTvShows: List<CachedTvShow> = emptyList(),
        val continueWatchingMovies: List<CachedMovie> = emptyList(),
        val continueWatchingEpisodes: List<CachedEpisode> = emptyList(),
    )

    private fun UserData.normalized(): UserData = copy(
        favoritesMovies = favoritesMovies.sortedByDescending { it.favoritedAtMillis ?: 0L },
        favoritesTvShows = favoritesTvShows.sortedByDescending { it.favoritedAtMillis ?: 0L },
        continueWatchingMovies = continueWatchingMovies
            .filterNot { it.isWatched }
            .sortedByDescending {
                it.lastEngagementTimeUtcMillis ?: it.lastPlayedAtMillis ?: 0L
            },
        continueWatchingEpisodes = continueWatchingEpisodes
            .filterNot { it.isWatched }
            .sortedByDescending { it.lastEngagementTimeUtcMillis ?: 0L }
            .deduplicateByTvShow(),
    )

    // -------------------------
    // CACHE FILE
    // -------------------------

    private fun cacheKey(
        provider: Provider,
        profileId: String = ProfileManager.activeProfileId ?: "default",
    ): String {
        val baseUrlKey = provider.baseUrl.trim().trimEnd('/')
        return listOf(profileId, provider.name, baseUrlKey)
            .filter { it.isNotEmpty() }
            .joinToString("__")
    }

    /**
     * Moves cache files written before profiles existed into the first default
     * profile. The old cache key was provider + base URL; the new key adds the
     * profile ID. Keeping this migration here also makes it safe for callers
     * that read the cache before the next database refresh.
     */
    fun migrateLegacyCacheToDefaultProfile(context: Context, providers: Iterable<Provider>) {
        if (ProfileManager.activeProfileId != "default") return

        providers.forEach { provider ->
            val baseUrlKey = provider.baseUrl.trim().trimEnd('/')
            val legacyKey = listOf(provider.name, baseUrlKey)
                .filter { it.isNotEmpty() }
                .joinToString("__")
            val legacyFile = cacheFile(context, legacyKey)
            val profileFile = cacheFile(context, cacheKey(provider))

            if (legacyFile.exists() && !profileFile.exists()) {
                runCatching {
                    profileFile.parentFile?.mkdirs()
                    legacyFile.copyTo(profileFile, overwrite = false)
                    Log.i("UserDataCache", "Migrated legacy cache for ${provider.name} to default profile")
                }.onFailure { error ->
                    Log.w("UserDataCache", "Could not migrate legacy cache for ${provider.name}", error)
                }
            }
        }
    }

    private fun cacheFile(context: Context, cacheKey: String): File {
        val safeName = cacheKey.replace(Regex("[^a-zA-Z0-9._-]+"), "_")
        return File(context.filesDir, "user-data-cache/$safeName.json")
    }

    // -------------------------
    // READ / WRITE
    // -------------------------

    fun read(
        context: Context,
        provider: Provider,
        profileId: String = ProfileManager.activeProfileId ?: "default",
    ): UserData? {
        val key = cacheKey(provider, profileId)

        // Fast-path from memory
        memoryCache[key]?.let { return it }

        val file = cacheFile(context, key)
        if (!file.exists()) return null

        // Use a per-key lock to dedupe concurrent reads and avoid multiple
        // expensive parses for the same file. Also capture the current
        // profileEpoch so this read can abort if a profile switch happens.
        val currentEpoch = profileEpoch.get()
        val lock = readLocks.computeIfAbsent(key) { Any() }

        synchronized(lock) {
            // Another thread may have populated memory while waiting for the lock
            memoryCache[key]?.let { return it }

            // If profile changed while we waited, abort
            if (profileEpoch.get() != currentEpoch) return null

            return runCatching {
                // Use Gson JsonReader streaming to avoid building a large
                // intermediate String in memory (better for large cache files).
                java.io.FileReader(file).use { fr ->
                    com.google.gson.stream.JsonReader(fr).use { jr ->
                        gson.fromJson<UserData>(jr, UserData::class.java)
                    }
                }?.normalized()?.also {
                    // If profile changed while parsing, discard result
                    if (profileEpoch.get() == currentEpoch) {
                        memoryCache[key] = it
                    }
                }
            }.getOrNull()
        }
    }

    fun write(
        context: Context,
        provider: Provider,
        newData: UserData,
        profileId: String = ProfileManager.activeProfileId ?: "default",
    ) {
        val key = cacheKey(provider, profileId)
        val normalizedData = newData.normalized()
        val oldData = memoryCache[key]

        // ✅ prevent spam
        if (oldData == normalizedData) return

        memoryCache[key] = normalizedData

        runCatching {
            cacheFile(context, key).apply {
                parentFile?.mkdirs()
                writeText(gson.toJson(normalizedData))
            }
        }

        UserDataNotifier.notifyChanged()
    }

    fun clear(context: Context, provider: Provider) {
        val key = cacheKey(provider)
        memoryCache.remove(key)
        cacheFile(context, key).delete()
    }

    fun clearAll(context: Context) {
        memoryCache.clear()
        val cacheDir = File(context.filesDir, "user-data-cache")
        if (cacheDir.exists()) {
            cacheDir.deleteRecursively()
        }
    }

    fun clearMemory() {
        memoryCache.clear()
    }

    @Volatile
    private var lastActiveProfileId: String? = null

    /**
     * Called when the active profile is switched. This increments an epoch which
     * causes in-flight reads to abort, clears locks and cached data associated
     * with the previous profile only (other profiles' warm caches are preserved).
     */
    fun onProfileSwitched(newProfileId: String) {
        // New epoch prevents in-flight operations from writing back results
        profileEpoch.incrementAndGet()

        // Only remove entries belonging to the previous profile
        val oldProfileId = lastActiveProfileId
        if (oldProfileId != null) {
            val prefix = "${oldProfileId}__"
            memoryCache.keys.removeAll { key -> key.startsWith(prefix) }
            readLocks.keys.removeAll { key -> key.startsWith(prefix) }
        }

        lastActiveProfileId = newProfileId
    }

    fun clearProfile(context: Context, profileId: String) {
        val safePrefix = profileId.replace(Regex("[^a-zA-Z0-9._-]+"), "_") + "__"
        memoryCache.keys.removeAll { key -> key.startsWith("${profileId}__") }
        val cacheDir = File(context.filesDir, "user-data-cache")
        cacheDir.listFiles()
            ?.filter { file -> file.name.startsWith(safePrefix) }
            ?.forEach(File::delete)
    }

    // -------------------------
    // CONTINUE WATCHING DEDUP
    // -------------------------

    /**
     * For each TV show, keep only the episode with the most recent
     * [CachedEpisode.lastEngagementTimeUtcMillis].  Episodes without a
     * [tvShowId] are kept as-is.  This prevents stale entries from piling
     * up when a user watches multiple episodes of the same series.
     */
    private fun List<CachedEpisode>.deduplicateByTvShow(): List<CachedEpisode> =
        filter { it.tvShowId == null } +
            filter { it.tvShowId != null }
                .groupBy { it.tvShowId }
                .map { (_, episodes) ->
                    episodes.maxByOrNull { it.lastEngagementTimeUtcMillis ?: 0L }!!
                }

    // -------------------------
    // WRITE HELPERS (FIXED)
    // -------------------------

    fun writeMovies(
        context: Context,
        provider: Provider,
        movies: List<Movie>,
        profileId: String = ProfileManager.activeProfileId ?: "default",
    ) {
        val current = read(context, provider, profileId) ?: UserData()
        val moviesById = movies.associateBy { it.id }

        val newData = current.copy(
            favoritesMovies = current.favoritesMovies.mapNotNull { cached ->
                moviesById[cached.id]?.takeIf { it.isFavorite }?.toCached()
            } + movies.filter { it.isFavorite && current.favoritesMovies.none { cached -> cached.id == it.id } }
                .map { it.toCached() },
            continueWatchingMovies = current.continueWatchingMovies.mapNotNull { cached ->
                moviesById[cached.id]?.takeIf { !it.isWatched && it.watchHistory != null }?.toCached()
            } + movies.filter { !it.isWatched && it.watchHistory != null && current.continueWatchingMovies.none { cached -> cached.id == it.id } }
                .map { it.toCached() }
        )

        write(context, provider, newData, profileId)
    }

    fun writeTvShows(
        context: Context,
        provider: Provider,
        tvShows: List<TvShow>,
        profileId: String = ProfileManager.activeProfileId ?: "default",
    ) {
        val current = read(context, provider, profileId) ?: UserData()
        val tvShowsById = tvShows.associateBy { it.id }

        val newData = current.copy(
            favoritesTvShows = current.favoritesTvShows.mapNotNull { cached ->
                tvShowsById[cached.id]?.takeIf { it.isFavorite }?.toCached()
            } + tvShows.filter { it.isFavorite && current.favoritesTvShows.none { cached -> cached.id == it.id } }
                .map { it.toCached() }
        )

        write(context, provider, newData, profileId)
    }

    fun writeEpisodes(
        context: Context,
        provider: Provider,
        episodes: List<Episode>,
        profileId: String = ProfileManager.activeProfileId ?: "default",
    ) {
        val current = read(context, provider, profileId) ?: UserData()
        val episodesById = episodes.associateBy { it.id }

        val newData = current.copy(
            continueWatchingEpisodes = (current.continueWatchingEpisodes.mapNotNull { cached ->
                episodesById[cached.id]?.takeIf { !it.isWatched && it.watchHistory != null }?.toCached()
            } + episodes.filter { !it.isWatched && it.watchHistory != null && current.continueWatchingEpisodes.none { cached -> cached.id == it.id } }
                .map { it.toCached() }).deduplicateByTvShow()
        )

        write(context, provider, newData, profileId)
    }

    // -------------------------
    // MOVIES
    // -------------------------

    fun removeMovieFromContinueWatching(context: Context, provider: Provider, id: String) {
        val current = read(context, provider) ?: UserData()

        runCatching {
            val db = AppDatabase.getInstance(context)
            db.movieDao().getById(id)?.let { movie ->
                movie.watchHistory = null
                movie.isWatched = false
                movie.watchedDate = null
                db.movieDao().update(movie)
            }
        }

        write(context, provider, current.copy(
            continueWatchingMovies = current.continueWatchingMovies.filter { it.id != id }
        ))
        CloudSyncHooks.movie(context, provider, id)
        UserDataNotifier.notifyChanged()
    }

    fun addMovieToContinueWatching(context: Context, provider: Provider, movie: Movie) {
        val current = read(context, provider) ?: UserData()
        val updated = current.continueWatchingMovies.filter { it.id != movie.id }

        write(context, provider, current.copy(
            continueWatchingMovies = if (movie.isWatched) {
                updated
            } else {
                (updated + movie.toCached()).distinctBy { it.id }
            }
        ))
        CloudSyncHooks.movie(context, provider, movie)
        UserDataNotifier.notifyChanged()
    }

    fun removeMovieFromFavorites(context: Context, provider: Provider, id: String) {
        val current = read(context, provider) ?: UserData()

        write(context, provider, current.copy(
            favoritesMovies = current.favoritesMovies.filter { it.id != id }
        ))
        CloudSyncHooks.movie(context, provider, id)
        UserDataNotifier.notifyChanged()
    }

    fun addMovieToFavorites(context: Context, provider: Provider, movie: Movie) {
        val current = read(context, provider) ?: UserData()
        val favoritedMovie = movie.copy().apply {
            isFavorite = true
            favoritedAtMillis = favoritedAtMillis ?: System.currentTimeMillis()
        }

        write(context, provider, current.copy(
            favoritesMovies = (current.favoritesMovies + favoritedMovie.toCached())
                .distinctBy { it.id }
        ))
        CloudSyncHooks.movie(context, provider, favoritedMovie)
        UserDataNotifier.notifyChanged()
    }

    // -------------------------
    // EPISODES
    // -------------------------

    fun removeEpisodeFromContinueWatching(context: Context, provider: Provider, id: String) {
        val current = read(context, provider) ?: UserData()

        write(context, provider, current.copy(
            continueWatchingEpisodes = current.continueWatchingEpisodes.filter { it.id != id }
        ))
        CloudSyncHooks.episode(context, provider, id)
        UserDataNotifier.notifyChanged()
    }

    fun addEpisodeToContinueWatching(context: Context, provider: Provider, episode: Episode) {
        val current = read(context, provider) ?: UserData()
        val updated = current.continueWatchingEpisodes.filter { it.id != episode.id }

        write(context, provider, current.copy(
            continueWatchingEpisodes = if (episode.isWatched) {
                updated
            } else {
                (updated + episode.toCached()).deduplicateByTvShow()
            }
        ))
        CloudSyncHooks.episode(context, provider, episode)
        UserDataNotifier.notifyChanged()
    }

    // -------------------------
    // TV SHOWS
    // -------------------------

    fun removeTvShowFromFavorites(context: Context, provider: Provider, id: String) {
        val current = read(context, provider) ?: UserData()

        write(context, provider, current.copy(
            favoritesTvShows = current.favoritesTvShows.filter { it.id != id }
        ))
        CloudSyncHooks.tvShow(context, provider, id)
        UserDataNotifier.notifyChanged()
    }

    fun addTvShowToFavorites(context: Context, provider: Provider, tvShow: TvShow) {
        val current = read(context, provider) ?: UserData()
        val favoritedTvShow = tvShow.copy().apply {
            isFavorite = true
            favoritedAtMillis = favoritedAtMillis ?: System.currentTimeMillis()
        }

        write(context, provider, current.copy(
            favoritesTvShows = (current.favoritesTvShows + favoritedTvShow.toCached())
                .distinctBy { it.id }
        ))
        CloudSyncHooks.tvShow(context, provider, favoritedTvShow)
        UserDataNotifier.notifyChanged()
    }

    // -------------------------
    // CACHE SYNC (Keep cache & DB in sync)
    // -------------------------

    fun syncMovieToCache(context: Context, provider: Provider, movie: Movie) {
        val current = read(context, provider) ?: UserData()
        
        val updatedContinueWatching = if (
            !movie.isWatched &&
            (movie.watchHistory != null || movie.lastPlayedAtMillis != null)
        ) {
            (current.continueWatchingMovies.filter { it.id != movie.id } + movie.toCached())
                .distinctBy { it.id }
        } else {
            current.continueWatchingMovies.filter { it.id != movie.id }
        }
        
        val updatedFavorites = if (movie.isFavorite) {
            (current.favoritesMovies.filter { it.id != movie.id } + movie.toCached().copy(
                favoritedAtMillis = movie.favoritedAtMillis
                    ?: current.favoritesMovies.firstOrNull { it.id == movie.id }?.favoritedAtMillis
                    ?: System.currentTimeMillis()
            ))
                .distinctBy { it.id }
        } else {
            current.favoritesMovies.filter { it.id != movie.id }
        }
        
        write(context, provider, current.copy(
            continueWatchingMovies = updatedContinueWatching,
            favoritesMovies = updatedFavorites
        ))
        CloudSyncHooks.movie(context, provider, movie)
        UserDataNotifier.notifyChanged()
    }

    fun syncEpisodeToCache(context: Context, provider: Provider, episode: Episode) {
        val current = read(context, provider) ?: UserData()
        
        val updatedContinueWatching = if (!episode.isWatched && episode.watchHistory != null) {
            (current.continueWatchingEpisodes.filter { it.id != episode.id } + episode.toCached())
                .deduplicateByTvShow()
        } else {
            current.continueWatchingEpisodes.filter { it.id != episode.id }
        }
        
        write(context, provider, current.copy(
            continueWatchingEpisodes = updatedContinueWatching
        ))
        CloudSyncHooks.episode(context, provider, episode)
        UserDataNotifier.notifyChanged()
    }

    fun syncTvShowToCache(context: Context, provider: Provider, tvShow: TvShow) {
        val current = read(context, provider) ?: UserData()

        val updatedFavorites = if (tvShow.isFavorite) {
            (current.favoritesTvShows.filter { it.id != tvShow.id } + tvShow.toCached().copy(
                favoritedAtMillis = tvShow.favoritedAtMillis
                    ?: current.favoritesTvShows.firstOrNull { it.id == tvShow.id }?.favoritedAtMillis
                    ?: System.currentTimeMillis()
            ))
                .distinctBy { it.id }
        } else {
            current.favoritesTvShows.filter { it.id != tvShow.id }
        }

        write(context, provider, current.copy(
            favoritesTvShows = updatedFavorites
        ))
        CloudSyncHooks.tvShow(context, provider, tvShow)
        UserDataNotifier.notifyChanged()
    }





    data class CachedMovie(
        val id: String,
        val title: String,
        val overview: String? = null,
        val released: String? = null,
        val runtime: Int? = null,
        val trailer: String? = null,
        val quality: String? = null,
        val rating: Double? = null,
        val poster: String? = null,
        val banner: String? = null,
        val isFavorite: Boolean = false,
        val isWatched: Boolean = false,
        val lastPlayedAtMillis: Long? = null,
        val favoritedAtMillis: Long? = null,
        val lastEngagementTimeUtcMillis: Long? = null,
        val lastPlaybackPositionMillis: Long? = null,
        val durationMillis: Long? = null,
    )

    data class CachedTvShow(
        val id: String,
        val title: String,
        val overview: String? = null,
        val released: String? = null,
        val runtime: Int? = null,
        val trailer: String? = null,
        val quality: String? = null,
        val rating: Double? = null,
        val poster: String? = null,
        val banner: String? = null,
        val isFavorite: Boolean = false,
        val favoritedAtMillis: Long? = null,
    )

    data class CachedEpisode(
        val id: String,
        val number: Int,
        val title: String? = null,
        val released: String? = null,
        val poster: String? = null,
        val overview: String? = null,
        val isWatched: Boolean = false,
        val lastEngagementTimeUtcMillis: Long? = null,
        val lastPlaybackPositionMillis: Long? = null,
        val durationMillis: Long? = null,
        val tvShowId: String? = null,
        val tvShowTitle: String? = null,
        val tvShowPoster: String? = null,
        val tvShowBanner: String? = null,
        val seasonId: String? = null,
        val seasonNumber: Int? = null,
        val seasonTitle: String? = null,
        val seasonPoster: String? = null,
    )


    fun CachedMovie.toMovie() = Movie(
        id = id,
        title = title,
        overview = overview,
        released = released,
        runtime = runtime,
        trailer = trailer,
        quality = quality,
        rating = rating,
        poster = poster,
        banner = banner,
    ).apply {
        isFavorite = this@toMovie.isFavorite
        favoritedAtMillis = this@toMovie.favoritedAtMillis
        isWatched = this@toMovie.isWatched
        lastPlayedAtMillis = this@toMovie.lastPlayedAtMillis
        if (this@toMovie.lastEngagementTimeUtcMillis != null) {
            watchHistory = WatchItem.WatchHistory(
                lastEngagementTimeUtcMillis = this@toMovie.lastEngagementTimeUtcMillis,
                lastPlaybackPositionMillis = this@toMovie.lastPlaybackPositionMillis ?: 0,
                durationMillis = this@toMovie.durationMillis ?: 0
            )
        }
    }

    fun CachedTvShow.toTvShow() = TvShow(
        id = id,
        title = title,
        overview = overview,
        released = released,
        runtime = runtime,
        trailer = trailer,
        quality = quality,
        rating = rating,
        poster = poster,
        banner = banner,
    ).apply {
        isFavorite = this@toTvShow.isFavorite
        favoritedAtMillis = this@toTvShow.favoritedAtMillis
    }

    fun CachedEpisode.toEpisode() = Episode(
        id = id,
        number = number,
        title = title,
        released = released,
        poster = poster,
        overview = overview,
    ).apply {
        isWatched = this@toEpisode.isWatched
        if (this@toEpisode.lastEngagementTimeUtcMillis != null) {
            watchHistory = WatchItem.WatchHistory(
                lastEngagementTimeUtcMillis = this@toEpisode.lastEngagementTimeUtcMillis,
                lastPlaybackPositionMillis = this@toEpisode.lastPlaybackPositionMillis ?: 0,
                durationMillis = this@toEpisode.durationMillis ?: 0
            )
        }
        tvShow = this@toEpisode.tvShowId?.let {
            TvShow(
                id = it,
                title = this@toEpisode.tvShowTitle.orEmpty(),
                poster = this@toEpisode.tvShowPoster,
                banner = this@toEpisode.tvShowBanner,
            )
        }
        season = this@toEpisode.seasonId?.let {
            Season(
                id = it,
                number = this@toEpisode.seasonNumber ?: 0,
                title = this@toEpisode.seasonTitle.orEmpty(),
                poster = this@toEpisode.seasonPoster,
            )
        }
    }
    fun Movie.toCached() = UserDataCache.CachedMovie(
        id = id,
        title = title,
        overview = overview,
        released = released?.format("yyyy-MM-dd"),
        runtime = runtime,
        trailer = trailer,
        quality = quality,
        rating = rating,
        poster = poster,
        banner = banner,
        isFavorite = isFavorite,
        isWatched = isWatched,
        lastPlayedAtMillis = lastPlayedAtMillis,
        favoritedAtMillis = favoritedAtMillis,
        lastEngagementTimeUtcMillis = watchHistory?.lastEngagementTimeUtcMillis,
        lastPlaybackPositionMillis = watchHistory?.lastPlaybackPositionMillis,
        durationMillis = watchHistory?.durationMillis
    )
    fun TvShow.toCached() = UserDataCache.CachedTvShow(
        id = id,
        title = title,
        overview = overview,
        released = released?.format("yyyy-MM-dd"),
        runtime = runtime,
        trailer = trailer,
        quality = quality,
        rating = rating,
        poster = poster,
        banner = banner,
        isFavorite = isFavorite,
        favoritedAtMillis = favoritedAtMillis,
    )
    fun Episode.toCached() = UserDataCache.CachedEpisode(
        id = id,
        number = number,
        title = title,
        released = released?.format("yyyy-MM-dd"),
        poster = poster,
        overview = overview,
        isWatched = isWatched,
        lastEngagementTimeUtcMillis = watchHistory?.lastEngagementTimeUtcMillis,
        lastPlaybackPositionMillis = watchHistory?.lastPlaybackPositionMillis,
        durationMillis = watchHistory?.durationMillis,

        tvShowId = tvShow?.id,
        tvShowTitle = tvShow?.title,
        tvShowPoster = tvShow?.poster,
        tvShowBanner = tvShow?.banner,

        seasonId = season?.id,
        seasonNumber = season?.number,
        seasonTitle = season?.title,
        seasonPoster = season?.poster,
    )
}
