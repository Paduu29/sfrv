package com.streamflixrevanced.streamflix.utils

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bumptech.glide.load.engine.GlideException
import com.streamflixrevanced.streamflix.database.AppDatabase
import com.streamflixrevanced.streamflix.models.Movie
import com.streamflixrevanced.streamflix.models.TvShow
import com.streamflixrevanced.streamflix.providers.AniWorldProvider
import com.streamflixrevanced.streamflix.providers.HdFullProvider
import com.streamflixrevanced.streamflix.providers.Provider
import com.streamflixrevanced.streamflix.providers.IptvProvider
import com.streamflixrevanced.streamflix.providers.SerienStreamProvider
import java.io.FileNotFoundException
import retrofit2.HttpException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

object ArtworkRepair {

    private const val TAG = "ArtworkRepair"
    const val KEY_PROVIDER_NAME = "provider_name"

    fun shouldRepair(url: String?, error: GlideException?): Boolean {
        if (url.isNullOrBlank()) return false
        return !isRemoteArtworkUrl(url) ||
                containsFileNotFound(error) ||
                isAnimeOnlineNinjaArtwork(url) && containsAuthFailure(error)
    }

    fun isRemoteArtworkUrl(url: String?): Boolean {
        return !url.isNullOrBlank() && (url.startsWith("https://") || url.startsWith("http://"))
    }

    suspend fun resolveMovieForFavorite(context: Context, movie: Movie, favorite: Boolean): Movie {
        if (!favorite || hasUsableArtwork(movie.poster, movie.banner)) return movie
        val provider = UserPreferences.currentProvider ?: return movie
        val database = AppDatabase.getInstance(context)
        return repairMovie(context, provider, database, movie) ?: movie
    }

    suspend fun resolveTvShowForFavorite(context: Context, tvShow: TvShow, favorite: Boolean): TvShow {
        if (!favorite || hasUsableArtwork(tvShow.poster, tvShow.banner)) return tvShow
        val provider = UserPreferences.currentProvider ?: return tvShow
        val database = AppDatabase.getInstance(context)
        return repairTvShow(context, provider, database, tvShow) ?: tvShow
    }

    suspend fun repairMovie(
        context: Context,
        provider: Provider,
        database: AppDatabase,
        movie: Movie,
    ): Movie? {
        return runCatching {
            prepareProvider(context, provider)
            val refreshedMovie = provider.getMovie(movie.id).also { fetchedMovie ->
                if (provider !is IptvProvider) {
                    applyTmdbFallbackToMovie(
                        currentMovie = fetchedMovie,
                        fallbackTitle = movie.title,
                        providerLanguage = provider.language,
                    )
                }
            }
            database.movieDao().getById(movie.id)?.let { refreshedMovie.merge(it) }
            database.movieDao().insert(refreshedMovie)
            refreshedMovie
        }.onFailure { error ->
            Log.w(TAG, "Unable to refresh movie artwork for ${movie.id} on ${provider.name}", error)
        }.getOrNull()
    }

    suspend fun repairTvShow(
        context: Context,
        provider: Provider,
        database: AppDatabase,
        tvShow: TvShow,
    ): TvShow? {
        return runCatching {
            prepareProvider(context, provider)
            val refreshedTvShow = provider.getTvShow(tvShow.id).also { fetchedTvShow ->
                if (provider !is IptvProvider) {
                    applyTmdbFallbackToTvShow(
                        currentTvShow = fetchedTvShow,
                        fallbackTitle = tvShow.title,
                        providerLanguage = provider.language,
                    )
                }
            }
            database.tvShowDao().getById(tvShow.id)?.let { refreshedTvShow.merge(it) }
            database.tvShowDao().insert(refreshedTvShow)
            refreshedTvShow
        }.onFailure { error ->
            Log.w(TAG, "Unable to refresh tv show artwork for ${tvShow.id} on ${provider.name}", error)
        }.getOrNull()
    }

    suspend fun repairStoredArtwork(
        context: Context,
        provider: Provider,
        database: AppDatabase,
    ) {
        // HDFull requires an account. Background artwork repair must not start its login/WebView
        // flow when the provider has not been configured by the user.
        if (provider === HdFullProvider && !HdFullProvider.hasConfiguredCredentials()) {
            Log.d(TAG, "Skipping HDFull artwork repair because credentials are not configured")
            return
        }
        prepareProvider(context, provider)

        // Batch TV show IDs and fetch existing shows in one DB call
        val tvShowIds = database.episodeDao().getArtworkRepairTvShowIds().distinct()
        if (tvShowIds.isNotEmpty()) {
            // getByIds returns a Flow<List<TvShow>>; take first emission
            val existingTvShows = database.tvShowDao().getByIds(tvShowIds).first()
            val existingMap = existingTvShows.associateBy { it.id }

            // Limit concurrency to avoid flooding network / GC spikes
            coroutineScope {
                val sem = Semaphore(4)
                tvShowIds.map { tvShowId ->
                    async {
                        sem.withPermit {
                            val existingTvShow = existingMap[tvShowId]
                            val missingArtwork = existingTvShow == null ||
                                !isRemoteArtworkUrl(existingTvShow.poster) ||
                                existingTvShow.banner.isNullOrBlank() ||
                                !isRemoteArtworkUrl(existingTvShow.banner)

                            if (missingArtwork) {
                                repairTvShow(
                                    context = context,
                                    provider = provider,
                                    database = database,
                                    tvShow = existingTvShow ?: TvShow(id = tvShowId, title = ""),
                                )
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        // Repair movie candidates in parallel with limited concurrency
        val movies = database.movieDao().getArtworkRepairCandidates().distinctBy { it.id }
        if (movies.isNotEmpty()) {
            coroutineScope {
                val sem = Semaphore(6)
                movies.map { movie ->
                    async {
                        sem.withPermit {
                            repairMovie(context, provider, database, movie)
                        }
                    }
                }.awaitAll()
            }
        }

        // Repair tvShow candidates (explicit) in parallel
        val tvShows = database.tvShowDao().getArtworkRepairCandidates().distinctBy { it.id }
        if (tvShows.isNotEmpty()) {
            coroutineScope {
                val sem = Semaphore(4)
                tvShows.map { tvShow ->
                    async {
                        sem.withPermit {
                            repairTvShow(context, provider, database, tvShow)
                        }
                    }
                }.awaitAll()
            }
        }
    }

    private suspend fun prepareProvider(context: Context, provider: Provider) {
        when (provider) {
            SerienStreamProvider -> SerienStreamProvider.initialize(context)
            AniWorldProvider -> AniWorldProvider.initialize(context)
        }
    }

    private fun hasUsableArtwork(poster: String?, banner: String?): Boolean {
        return isRemoteArtworkUrl(poster) && (banner.isNullOrBlank() || isRemoteArtworkUrl(banner))
    }

    private suspend fun applyTmdbFallbackToMovie(
        currentMovie: Movie,
        fallbackTitle: String?,
        providerLanguage: String?,
    ) {
        if (hasUsableArtwork(currentMovie.poster, currentMovie.banner)) return

        val lookupTitle = currentMovie.title.ifBlank { fallbackTitle.orEmpty() }
        if (lookupTitle.isBlank()) return

        val tmdbMovie = TmdbUtils.getMovie(lookupTitle, language = providerLanguage) ?: return
        if (!isRemoteArtworkUrl(currentMovie.poster) && isRemoteArtworkUrl(tmdbMovie.poster)) {
            currentMovie.poster = tmdbMovie.poster
        }
        if (!isRemoteArtworkUrl(currentMovie.banner) && isRemoteArtworkUrl(tmdbMovie.banner)) {
            currentMovie.banner = tmdbMovie.banner
        }
        if (currentMovie.imdbId.isNullOrBlank()) {
            currentMovie.imdbId = tmdbMovie.imdbId
        }
    }

    private suspend fun applyTmdbFallbackToTvShow(
        currentTvShow: TvShow,
        fallbackTitle: String?,
        providerLanguage: String?,
    ) {
        if (hasUsableArtwork(currentTvShow.poster, currentTvShow.banner)) return

        val lookupTitle = currentTvShow.title.ifBlank { fallbackTitle.orEmpty() }
        if (lookupTitle.isBlank()) return

        val tmdbTvShow = TmdbUtils.getTvShow(lookupTitle, language = providerLanguage) ?: return
        if (!isRemoteArtworkUrl(currentTvShow.poster) && isRemoteArtworkUrl(tmdbTvShow.poster)) {
            currentTvShow.poster = tmdbTvShow.poster
        }
        if (!isRemoteArtworkUrl(currentTvShow.banner) && isRemoteArtworkUrl(tmdbTvShow.banner)) {
            currentTvShow.banner = tmdbTvShow.banner
        }
        if (currentTvShow.imdbId.isNullOrBlank()) {
            currentTvShow.imdbId = tmdbTvShow.imdbId
        }
    }

    private fun containsFileNotFound(error: GlideException?): Boolean {
        if (error == null) return false
        if (generateSequence(error.cause) { it.cause }.any { it is FileNotFoundException }) return true
        return error.rootCauses.any { root ->
            root is FileNotFoundException || generateSequence(root.cause) { it.cause }.any { it is FileNotFoundException }
        }
    }

    private fun containsAuthFailure(error: GlideException?): Boolean {
        if (error == null) return false
        if (generateSequence(error.cause) { it.cause }.any { it.isHttpAuthFailure() }) return true
        return error.rootCauses.any { root ->
            root.isHttpAuthFailure() || generateSequence(root.cause) { it.cause }.any { it.isHttpAuthFailure() }
        }
    }

    private fun Throwable.isHttpAuthFailure(): Boolean {
        val httpException = this as? HttpException ?: return false
        return httpException.code() == 401 || httpException.code() == 403
    }

    private fun isAnimeOnlineNinjaArtwork(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("ww3.animeonline.ninja")
    }
}

object ArtworkRepairScheduler {

    fun schedule(context: Context, provider: Provider?) {
        provider ?: return
        schedule(context, provider.name)
    }

    fun schedule(context: Context, providerName: String?) {
        if (providerName.isNullOrBlank()) return

        val request = OneTimeWorkRequestBuilder<ArtworkRepairWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(
                androidx.work.workDataOf(ArtworkRepair.KEY_PROVIDER_NAME to providerName)
            )
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "repair_artwork_${sanitize(providerName)}",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private fun sanitize(name: String): String {
        return name.lowercase()
            .replace("[^a-z0-9]".toRegex(), "_")
            .replace("__+".toRegex(), "_")
            .trim('_')
    }
}

class ArtworkRepairWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val providerName = inputData.getString(ArtworkRepair.KEY_PROVIDER_NAME)
            ?: return Result.success()
        val provider = Provider.findByName(providerName) ?: return Result.success()
        val database = AppDatabase.getInstanceForProvider(provider.name, applicationContext)

        return try {
            ArtworkRepair.repairStoredArtwork(applicationContext, provider, database)
            Result.success()
        } catch (error: Exception) {
            Log.w("ArtworkRepairWorker", "Unable to repair artwork for ${provider.name}", error)
            Result.retry()
        } finally {
            database.close()
        }
    }
}
