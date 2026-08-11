package com.streamflixrevanced.streamflix.utils

import android.content.Context
import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.GranularRoundedCorners
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.streamflixrevanced.streamflix.R
import com.streamflixrevanced.streamflix.database.AppDatabase
import com.streamflixrevanced.streamflix.models.Movie
import com.streamflixrevanced.streamflix.models.TvShow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Rounds the decoded poster itself instead of relying only on View outline
 * clipping, which is inconsistent on some Android TV hardware renderers.
 */
fun RequestBuilder<Drawable>.topRoundedPoster(
    context: Context,
    radiusDp: Int = 12,
    centerCrop: Boolean = true,
): RequestBuilder<Drawable> {
    val radius = radiusDp.dp(context).toFloat()
    val roundedCorners = GranularRoundedCorners(radius, radius, 0f, 0f)
    return if (centerCrop) {
        transform(CenterCrop(), roundedCorners)
    } else {
        transform(roundedCorners)
    }
}

private object ArtworkRepairCoordinator {
    private const val REPAIR_COOLDOWN_MS = 5 * 60 * 1000L
    private val repairScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightRepairs = ConcurrentHashMap.newKeySet<String>()
    private val lastRepairAttempts = ConcurrentHashMap<String, Long>()

    fun shouldRepair(url: String?, error: GlideException?): Boolean {
        return ArtworkRepair.shouldRepair(url, error)
    }

    fun repairMovieArtwork(
        imageView: ImageView,
        movie: Movie,
        artworkSlot: String,
        staleUrl: String,
        onUpdated: (Movie) -> Unit,
    ) {
        val provider = UserPreferences.currentProvider ?: return
        val repairKey = "${provider.name}|movie|${movie.id}|$artworkSlot|$staleUrl"
        if (!reserveRepair(repairKey)) return

        repairScope.launch {
            try {
                val database = AppDatabase.getInstance(imageView.context)
                val refreshedMovie = ArtworkRepair.repairMovie(
                    context = imageView.context,
                    provider = provider,
                    database = database,
                    movie = movie,
                ) ?: return@launch

                imageView.post {
                    movie.poster = refreshedMovie.poster
                    movie.banner = refreshedMovie.banner
                    onUpdated(refreshedMovie)
                }
            } finally {
                inFlightRepairs.remove(repairKey)
            }
        }
    }

    fun repairTvShowArtwork(
        imageView: ImageView,
        tvShow: TvShow,
        artworkSlot: String,
        staleUrl: String,
        onUpdated: (TvShow) -> Unit,
    ) {
        val provider = UserPreferences.currentProvider ?: return
        val repairKey = "${provider.name}|tv|${tvShow.id}|$artworkSlot|$staleUrl"
        if (!reserveRepair(repairKey)) return

        repairScope.launch {
            try {
                val database = AppDatabase.getInstance(imageView.context)
                val refreshedTvShow = ArtworkRepair.repairTvShow(
                    context = imageView.context,
                    provider = provider,
                    database = database,
                    tvShow = tvShow,
                ) ?: return@launch

                imageView.post {
                    tvShow.poster = refreshedTvShow.poster
                    tvShow.banner = refreshedTvShow.banner
                    onUpdated(refreshedTvShow)
                }
            } finally {
                inFlightRepairs.remove(repairKey)
            }
        }
    }

    @Synchronized
    private fun reserveRepair(repairKey: String): Boolean {
        if (repairKey in inFlightRepairs) return false
        val now = System.currentTimeMillis()
        val previousAttempt = lastRepairAttempts[repairKey]
        if (previousAttempt != null && now - previousAttempt < REPAIR_COOLDOWN_MS) return false
        lastRepairAttempts[repairKey] = now
        return inFlightRepairs.add(repairKey)
    }
}

private fun ImageView.loadRecoverableArtwork(
    initialUrl: String?,
    configure: RequestBuilder<Drawable>.() -> RequestBuilder<Drawable>,
    onRepair: (staleUrl: String, onUpdated: (String) -> Unit) -> Unit,
) {
    var hasRequestedRepairForBlankUrl = false

    fun submit(url: String?) {
        val requestedUrl = url
        if (requestedUrl.isNullOrBlank() && !hasRequestedRepairForBlankUrl) {
            hasRequestedRepairForBlankUrl = true
            onRepair("") { refreshedUrl ->
                if (!isAttachedToWindow || refreshedUrl.isBlank()) return@onRepair
                submit(refreshedUrl)
            }
        }
        if (requestedUrl.isNullOrBlank()) {
            Glide.with(this).clear(this)
            setImageResource(R.drawable.glide_fallback_cover)
            return
        }

        configure(Glide.with(this).load(requestedUrl))
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean,
                ): Boolean {
                    if (!ArtworkRepairCoordinator.shouldRepair(requestedUrl, e)) {
                        return false
                    }

                    onRepair(requestedUrl.orEmpty()) { refreshedUrl ->
                        if (!isAttachedToWindow) return@onRepair
                        submit(refreshedUrl)
                    }
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean,
                ) = false
            })
            .into(this)
    }

    submit(initialUrl)
}

fun ImageView.loadMoviePoster(
    movie: Movie,
    configure: RequestBuilder<Drawable>.() -> RequestBuilder<Drawable> = { this },
) {
    loadRecoverableArtwork(movie.poster, configure) { staleUrl, onUpdated ->
        ArtworkRepairCoordinator.repairMovieArtwork(this, movie, "poster", staleUrl) { refreshedMovie ->
            val refreshedUrl = refreshedMovie.poster
            if (!refreshedUrl.isNullOrBlank() && refreshedUrl != staleUrl) {
                onUpdated(refreshedUrl)
            }
        }
    }
}

fun ImageView.loadMovieBanner(
    movie: Movie,
    configure: RequestBuilder<Drawable>.() -> RequestBuilder<Drawable> = { this },
) {
    loadRecoverableArtwork(movie.banner, configure) { staleUrl, onUpdated ->
        ArtworkRepairCoordinator.repairMovieArtwork(this, movie, "banner", staleUrl) { refreshedMovie ->
            val refreshedUrl = refreshedMovie.banner
            if (!refreshedUrl.isNullOrBlank() && refreshedUrl != staleUrl) {
                onUpdated(refreshedUrl)
            }
        }
    }
}

fun ImageView.loadTvShowPoster(
    tvShow: TvShow,
    configure: RequestBuilder<Drawable>.() -> RequestBuilder<Drawable> = { this },
) {
    loadRecoverableArtwork(tvShow.poster, configure) { staleUrl, onUpdated ->
        ArtworkRepairCoordinator.repairTvShowArtwork(this, tvShow, "poster", staleUrl) { refreshedTvShow ->
            val refreshedUrl = refreshedTvShow.poster
            if (!refreshedUrl.isNullOrBlank() && refreshedUrl != staleUrl) {
                onUpdated(refreshedUrl)
            }
        }
    }
}

fun ImageView.loadTvShowBanner(
    tvShow: TvShow,
    configure: RequestBuilder<Drawable>.() -> RequestBuilder<Drawable> = { this },
) {
    loadRecoverableArtwork(tvShow.banner, configure) { staleUrl, onUpdated ->
        ArtworkRepairCoordinator.repairTvShowArtwork(this, tvShow, "banner", staleUrl) { refreshedTvShow ->
            val refreshedUrl = refreshedTvShow.banner
            if (!refreshedUrl.isNullOrBlank() && refreshedUrl != staleUrl) {
                onUpdated(refreshedUrl)
            }
        }
    }
}

fun ImageView.loadTvShowCardArtwork(
    tvShow: TvShow,
    configure: RequestBuilder<Drawable>.() -> RequestBuilder<Drawable> = { this },
) {
    loadRecoverableArtwork(tvShow.poster ?: tvShow.banner, configure) { staleUrl, onUpdated ->
        ArtworkRepairCoordinator.repairTvShowArtwork(this, tvShow, "card", staleUrl) { refreshedTvShow ->
            val refreshedUrl = refreshedTvShow.poster ?: refreshedTvShow.banner
            if (!refreshedUrl.isNullOrBlank() && refreshedUrl != staleUrl) {
                onUpdated(refreshedUrl)
            }
        }
    }
}
