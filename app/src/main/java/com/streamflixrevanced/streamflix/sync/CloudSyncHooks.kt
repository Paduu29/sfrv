package com.streamflixrevanced.streamflix.sync

import android.content.Context
import com.streamflixrevanced.streamflix.database.AppDatabase
import com.streamflixrevanced.streamflix.models.Episode
import com.streamflixrevanced.streamflix.models.Movie
import com.streamflixrevanced.streamflix.models.TvShow
import com.streamflixrevanced.streamflix.providers.Provider
import com.streamflixrevanced.streamflix.utils.ProfileManager

object CloudSyncHooks {
    fun movie(context: Context, provider: Provider, movie: Movie) {
        enqueue(context) { userId, now ->
            RemoteMediaState.fromMovie(userId, provider.name, movie, now)
        }
    }

    fun movie(context: Context, provider: Provider, id: String) {
        if (CloudSyncManager.isApplyingRemote) return
        val movie = runCatching { AppDatabase.getInstance(context).movieDao().getById(id) }.getOrNull()
            ?: Movie(id = id)
        movie(context, provider, movie)
    }

    fun tvShow(context: Context, provider: Provider, show: TvShow) {
        enqueue(context) { userId, now ->
            RemoteMediaState.fromTvShow(userId, provider.name, show, now)
        }
    }

    fun tvShow(context: Context, provider: Provider, id: String) {
        if (CloudSyncManager.isApplyingRemote) return
        val show = runCatching { AppDatabase.getInstance(context).tvShowDao().getById(id) }.getOrNull()
            ?: TvShow(id = id)
        tvShow(context, provider, show)
    }

    fun episode(context: Context, provider: Provider, episode: Episode) {
        enqueue(context) { userId, now ->
            RemoteMediaState.fromEpisode(userId, provider.name, episode, now)
        }
    }

    fun episode(context: Context, provider: Provider, id: String) {
        if (CloudSyncManager.isApplyingRemote) return
        val episode = runCatching { AppDatabase.getInstance(context).episodeDao().getById(id) }.getOrNull()
            ?: Episode(id = id)
        episode(context, provider, episode)
    }

    fun providerFavorite(
        context: Context,
        providerName: String,
        isFavorite: Boolean,
    ) {
        enqueue(context) { userId, now ->
            RemoteMediaState.fromProviderFavorite(
                userId = userId,
                providerName = providerName,
                isFavorite = isFavorite,
                now = now,
            )
        }
    }

    private inline fun enqueue(
        context: Context,
        state: (userId: String, now: Long) -> RemoteMediaState,
    ) {
        if (CloudSyncManager.isApplyingRemote) return
        val profileId = ProfileManager.activeProfileId ?: return
        val appContext = context.applicationContext
        val userId = CloudSyncManager.currentUserId()
            ?: CloudAccountStore.activeUserId(appContext, profileId)
            ?: return
        CloudMutationStore.enqueue(
            appContext,
            profileId,
            state(userId, System.currentTimeMillis()),
        )
        CloudSyncScheduler.enqueue(context, profileId, userId)
    }
}
