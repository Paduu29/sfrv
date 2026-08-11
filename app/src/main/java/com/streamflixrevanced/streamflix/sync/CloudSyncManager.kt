package com.streamflixrevanced.streamflix.sync

import android.content.Context
import android.util.Log
import com.streamflixrevanced.streamflix.database.AppDatabase
import com.streamflixrevanced.streamflix.models.Episode
import com.streamflixrevanced.streamflix.models.Movie
import com.streamflixrevanced.streamflix.models.Season
import com.streamflixrevanced.streamflix.models.TvShow
import com.streamflixrevanced.streamflix.models.WatchItem
import com.streamflixrevanced.streamflix.providers.Provider
import com.streamflixrevanced.streamflix.providers.TmdbProvider
import com.streamflixrevanced.streamflix.ui.UserDataNotifier
import com.streamflixrevanced.streamflix.utils.ProfileManager
import com.streamflixrevanced.streamflix.utils.ProviderChangeNotifier
import com.streamflixrevanced.streamflix.utils.UserDataCache
import com.streamflixrevanced.streamflix.utils.UserPreferences
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object CloudSyncManager {
    private const val TAG = "CloudSync"
    private const val TABLE = "user_media_state"
    private const val FETCH_PAGE_SIZE = 500L
    private val accountSyncMutex = Mutex()
    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var profileChangeJob: Job? = null

    @Volatile
    var isApplyingRemote: Boolean = false
        private set

    fun currentUserId(): String? {
        if (!SupabaseProvider.isConfigured) return null
        val profileId = ProfileManager.activeProfileId ?: return null
        return SupabaseProvider.clientOrNull(profileId)
            ?.auth
            ?.currentSessionOrNull()
            ?.user
            ?.id
    }

    fun currentUserEmail(): String? {
        if (!SupabaseProvider.isConfigured) return null
        val profileId = ProfileManager.activeProfileId ?: return null
        return SupabaseProvider.clientOrNull(profileId)
            ?.auth
            ?.currentSessionOrNull()
            ?.user
            ?.email
    }

    suspend fun initialize(context: Context) {
        val profileId = ProfileManager.activeProfileId ?: return
        initializeProfile(context.applicationContext, profileId)
    }

    suspend fun updateSupabaseConfiguration(
        context: Context,
        url: String,
        publishableKey: String,
        avatarBucket: String? = null,
    ): SupabaseConfigValidation {
        val validation = SupabaseSettings.validate(url, publishableKey, avatarBucket)
        if (validation !is SupabaseConfigValidation.Valid) return validation
        if (SupabaseSettings.config == validation.config) return validation

        val appContext = context.applicationContext
        val profileIds = ProfileManager.getAllProfiles().map { it.id }
            .plus(listOfNotNull(ProfileManager.activeProfileId))
            .plus("default")
            .distinct()

        CloudRealtimeSync.stop()
        val changed = SupabaseProvider.replaceConfiguration(validation.config, profileIds)
        if (changed) {
            profileIds.forEach { profileId ->
                CloudSyncScheduler.cancelProfile(appContext, profileId)
                CloudMutationStore.clearProfile(appContext, profileId)
                CloudAccountStore.clearProfile(appContext, profileId)
            }
        }
        return validation
    }

    fun onProfileChanged(context: Context, profileId: String) {
        profileChangeJob?.cancel()
        profileChangeJob = lifecycleScope.launch {
            CloudRealtimeSync.stop()
            if (ProfileManager.activeProfileId != profileId) return@launch
            runCatching {
                initializeProfile(context.applicationContext, profileId)
            }.onFailure { error ->
                Log.w(TAG, "Could not initialize cloud account for profile $profileId", error)
            }
        }
    }

    suspend fun onProfileDeleted(context: Context, profileId: String) {
        CloudSyncScheduler.cancelProfile(context, profileId)
        CloudMutationStore.clearProfile(context, profileId)
        CloudAccountStore.clearProfile(context, profileId)
        SupabaseProvider.removeProfile(profileId)
    }

    private suspend fun initializeProfile(context: Context, profileId: String) {
        val appContext = context.applicationContext
        if (!SupabaseProvider.isConfigured) return

        if (profileId != "default") {
            SupabaseProvider.clientFor("default").auth.awaitInitialization()
            CloudAccountStore.activeUserId(appContext, "default")
        }
        val client = SupabaseProvider.clientFor(profileId)
        client.auth.awaitInitialization()
        val userId = client.auth.currentSessionOrNull()?.user?.id
        if (userId == null) {
            if (ProfileManager.activeProfileId == profileId) {
                CloudRealtimeSync.stop()
            }
            // A missing/expired refresh session is not an instruction to destroy the
            // profile's local library. Keep both its data and ownership marker so the
            // same account can safely reconnect and merge again.
            Log.i(TAG, "No authenticated cloud session for profile $profileId")
            return
        }
        activateAccount(appContext, profileId, userId, client = client)
        if (ProfileManager.activeProfileId == profileId) {
            CloudRealtimeSync.start(appContext, profileId, userId)
        }
    }

    suspend fun signIn(
        context: Context,
        email: String,
        password: String,
        onProgress: (CloudSyncProgress) -> Unit = {},
    ) {
        requireConfigured()
        val appContext = context.applicationContext
        val profileId = requireActiveProfileId()
        val client = SupabaseProvider.clientFor(profileId)
        client.auth.awaitInitialization()
        onProgress(CloudSyncProgress(CloudSyncProgress.Stage.AUTHENTICATING))
        client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
        checkActiveProfile(profileId)
        val userId = client.auth.currentSessionOrNull()?.user?.id
            ?: error("Sign in did not create a session")
        rejectAccountLinkedToAnotherProfile(appContext, profileId, userId, client)
        activateAccount(
            context = appContext,
            profileId = profileId,
            userId = userId,
            onProgress = onProgress,
            mergeLocalOnLogin = true,
            client = client,
        )
        CloudRealtimeSync.start(appContext, profileId, userId)
    }

    suspend fun signUp(
        context: Context,
        email: String,
        password: String,
        onProgress: (CloudSyncProgress) -> Unit = {},
    ): Boolean {
        requireConfigured()
        val appContext = context.applicationContext
        val profileId = requireActiveProfileId()
        val client = SupabaseProvider.clientFor(profileId)
        client.auth.awaitInitialization()
        onProgress(CloudSyncProgress(CloudSyncProgress.Stage.AUTHENTICATING))
        client.auth.signUpWith(Email) {
            this.email = email
            this.password = password
        }
        checkActiveProfile(profileId)
        val userId = client.auth.currentSessionOrNull()?.user?.id ?: return false
        rejectAccountLinkedToAnotherProfile(appContext, profileId, userId, client)
        activateAccount(
            context = appContext,
            profileId = profileId,
            userId = userId,
            onProgress = onProgress,
            mergeLocalOnLogin = true,
            client = client,
        )
        CloudRealtimeSync.start(appContext, profileId, userId)
        return true
    }

    suspend fun signOut(context: Context) {
        val appContext = context.applicationContext
        val profileId = requireActiveProfileId()
        val client = SupabaseProvider.clientFor(profileId)
        client.auth.awaitInitialization()
        CloudRealtimeSync.stop()
        runCatching { flushPending(appContext, profileId, client) }
        if (SupabaseProvider.isConfigured) {
            client.auth.signOut()
        }
        isApplyingRemote = true
        try {
            withContext(Dispatchers.IO) {
                clearLocalUserState(appContext, profileId)
            }
            CloudAccountStore.setActiveAccount(
                appContext,
                profileId,
                userId = null,
                email = null,
            )
            CloudMutationStore.clearProfile(appContext, profileId)
        } finally {
            isApplyingRemote = false
        }
    }

    suspend fun syncNow(
        context: Context,
        onProgress: (CloudSyncProgress) -> Unit = {},
    ) {
        val profileId = requireActiveProfileId()
        val expectedUserId = currentUserId()
            ?: error("Sign in before synchronizing")
        val synchronized = syncProfile(
            context.applicationContext,
            profileId,
            expectedUserId,
            onProgress,
        )
        check(synchronized) { "The profile account changed before synchronization" }
    }

    suspend fun syncProfile(
        context: Context,
        profileId: String,
        expectedUserId: String,
        onProgress: (CloudSyncProgress) -> Unit = {},
    ): Boolean {
        val appContext = context.applicationContext
        val client = SupabaseProvider.clientFor(profileId)
        client.auth.awaitInitialization()
        val userId = client.auth.currentSessionOrNull()?.user?.id ?: return false
        if (userId != expectedUserId) return false
        accountSyncMutex.withLock {
            syncNowLocked(appContext, profileId, userId, client, onProgress)
        }
        return true
    }

    private suspend fun syncNowLocked(
        context: Context,
        profileId: String,
        userId: String,
        client: SupabaseClient,
        onProgress: (CloudSyncProgress) -> Unit,
    ) {
        flushPending(context, profileId, client, onProgress)
        onProgress(CloudSyncProgress(CloudSyncProgress.Stage.CHECKING_CLOUD))
        var remote = fetchRemote(client)
        val remoteProviderFavorites = remote.asSequence()
            .filter { state -> state.mediaType == "provider_favorite" }
            .map { state -> state.mediaId }
            .toSet()
        val now = System.currentTimeMillis()
        val missingProviderFavorites = UserPreferences.getFavoriteProviders(profileId)
            .filterNot(remoteProviderFavorites::contains)
            .map { providerName ->
                RemoteMediaState.fromProviderFavorite(
                    userId = userId,
                    providerName = providerName,
                    isFavorite = true,
                    now = now,
                )
            }
        if (missingProviderFavorites.isNotEmpty()) {
            upsert(client, missingProviderFavorites, onProgress)
            remote = fetchRemote(client)
        }
        onProgress(
            CloudSyncProgress(
                CloudSyncProgress.Stage.APPLYING_CLOUD,
                current = remote.size,
                total = remote.size,
            ),
        )
        withContext(Dispatchers.IO) { applyRemote(context, profileId, remote) }
        onProgress(CloudSyncProgress(CloudSyncProgress.Stage.FINALIZING))
        CloudAccountStore.setActiveAccount(
            context,
            profileId,
            userId,
            client.auth.currentSessionOrNull()?.user?.email,
        )
    }

    private suspend fun flushPending(
        context: Context,
        profileId: String,
        client: SupabaseClient,
        onProgress: (CloudSyncProgress) -> Unit = {},
    ) {
        client.auth.awaitInitialization()
        val userId = client.auth.currentSessionOrNull()?.user?.id ?: return
        while (true) {
            val pending = CloudMutationStore.pendingForUser(context, profileId, userId)
            if (pending.isEmpty()) return
            // A mutation may have been queued on another device before the
            // device went offline. Fetch first so an old local position cannot
            // overwrite newer playback state already written by another device.
            val remoteByKey = fetchRemote(client).associateBy { it.queueKey }
            val uploadable = pending.filter { mutation ->
                val remote = remoteByKey[mutation.queueKey]
                remote == null || pendingStateWins(mutation, remote)
            }
            if (uploadable.isNotEmpty()) {
                upsert(client, uploadable, onProgress)
            }
            // Acknowledge both uploaded and stale mutations. Newer mutations
            // queued during the upload are retained by acknowledge().
            CloudMutationStore.acknowledge(context, profileId, pending)
        }
    }

    private suspend fun activateAccount(
        context: Context,
        profileId: String,
        userId: String,
        onProgress: (CloudSyncProgress) -> Unit = {},
        mergeLocalOnLogin: Boolean = false,
        client: SupabaseClient,
    ) = accountSyncMutex.withLock {
        val previousUserId = CloudAccountStore.activeUserId(context, profileId)
        if (previousUserId == userId && !mergeLocalOnLogin) {
            syncNowLocked(context, profileId, userId, client, onProgress)
            return@withLock
        }

        onProgress(CloudSyncProgress(CloudSyncProgress.Stage.CHECKING_CLOUD))
        val remote = fetchRemote(client)
        val legacyOwnerId = CloudAccountStore.legacyOwnerId(context, profileId)
        val canMergeLocal = shouldMergeLocal(
            previousUserId = previousUserId,
            legacyOwnerId = legacyOwnerId,
            userId = userId,
            mergeLocalOnLogin = mergeLocalOnLogin,
        )

        isApplyingRemote = true
        try {
            if (canMergeLocal) {
                onProgress(CloudSyncProgress(CloudSyncProgress.Stage.PREPARING_LOCAL))
                val local = withContext(Dispatchers.IO) {
                    collectLocalState(context, profileId, userId)
                }
                onProgress(CloudSyncProgress(CloudSyncProgress.Stage.MERGING))
                val merged = mergeForFirstLogin(
                    remote = remote,
                    local = local,
                    mergedAtMillis = System.currentTimeMillis(),
                )
                if (local.isNotEmpty()) {
                    val localKeys = local.mapTo(hashSetOf()) { it.queueKey }
                    upsert(
                        client,
                        merged.filter { it.queueKey in localKeys },
                        onProgress,
                    )
                }
                val finalRemote = if (local.isEmpty()) remote else {
                    onProgress(CloudSyncProgress(CloudSyncProgress.Stage.CHECKING_CLOUD))
                    fetchRemote(client)
                }
                onProgress(
                    CloudSyncProgress(
                        CloudSyncProgress.Stage.APPLYING_CLOUD,
                        current = finalRemote.size,
                        total = finalRemote.size,
                    ),
                )
                withContext(Dispatchers.IO) {
                    applyRemoteInternal(context, profileId, finalRemote)
                }
                CloudAccountStore.claimLegacyData(context, profileId, userId)
            } else {
                val local = withContext(Dispatchers.IO) {
                    collectLocalState(context, profileId, userId)
                }
                if (local.isNotEmpty()) {
                    // Never silently destroy local profile state when a profile
                    // is reconnected to a different cloud account. The user
                    // must resolve the ownership conflict explicitly.
                    runCatching { client.auth.signOut() }
                    if (ProfileManager.activeProfileId == profileId) {
                        CloudRealtimeSync.stop()
                    }
                    throw CloudAccountDataConflictException()
                }

                onProgress(
                    CloudSyncProgress(
                        CloudSyncProgress.Stage.APPLYING_CLOUD,
                        current = remote.size,
                        total = remote.size,
                    ),
                )
                withContext(Dispatchers.IO) {
                    applyRemoteInternal(context, profileId, remote)
                }
            }
            onProgress(CloudSyncProgress(CloudSyncProgress.Stage.FINALIZING))
            CloudAccountStore.setActiveAccount(
                context,
                profileId,
                userId,
                client.auth.currentSessionOrNull()?.user?.email,
            )
        } finally {
            isApplyingRemote = false
        }
    }

    internal suspend fun applyRealtimeState(
        context: Context,
        profileId: String,
        state: RemoteMediaState,
    ) = accountSyncMutex.withLock {
        if (ProfileManager.activeProfileId != profileId) return@withLock
        val userId = SupabaseProvider.clientOrNull(profileId)
            ?.auth
            ?.currentSessionOrNull()
            ?.user
            ?.id
        val pending = userId?.let {
            CloudMutationStore.pendingForUser(context, profileId, it)
        }.orEmpty()
        if (
            !shouldApplyRealtimeState(
                currentProfileId = ProfileManager.activeProfileId,
                eventProfileId = profileId,
                currentUserId = userId,
                state = state,
                pending = pending,
            )
        ) {
            return@withLock
        }

        withContext(Dispatchers.IO) {
            applyRemote(context.applicationContext, profileId, listOf(state))
        }
    }

    internal fun shouldApplyRealtimeState(
        currentProfileId: String?,
        eventProfileId: String,
        currentUserId: String?,
        state: RemoteMediaState,
        pending: List<RemoteMediaState>,
    ): Boolean {
        if (currentProfileId == null || currentProfileId != eventProfileId) return false
        if (currentUserId == null || state.userId != currentUserId) return false
        return pending.none { mutation ->
            mutation.queueKey == state.queueKey && pendingStateWins(mutation, state)
        }
    }

    /**
     * client_updated_at is the enqueue time, not the time playback happened.
     * Compare the actual user-state timestamps first so an old offline queue
     * cannot beat a newer progress update from another device.
     */
    private fun pendingStateWins(
        pending: RemoteMediaState,
        remote: RemoteMediaState,
    ): Boolean {
        val pendingStateTime = pending.userStateTimestamp()
        val remoteStateTime = remote.userStateTimestamp()
        return if (pendingStateTime != remoteStateTime) {
            pendingStateTime > remoteStateTime
        } else {
            pending.clientUpdatedAtMillis >= remote.clientUpdatedAtMillis
        }
    }

    private fun RemoteMediaState.userStateTimestamp(): Long = listOfNotNull(
        watchedAtMillis,
        lastEngagementAtMillis,
        favoritedAtMillis,
        lastPlayedAtMillis,
    ).maxOrNull() ?: clientUpdatedAtMillis

    internal fun shouldMergeLocal(
        previousUserId: String?,
        legacyOwnerId: String?,
        userId: String,
        mergeLocalOnLogin: Boolean,
    ): Boolean {
        val localDataBelongsToUser =
            legacyOwnerId == null || legacyOwnerId == userId
        val accountCanOwnCurrentLocalData =
            previousUserId == null || (mergeLocalOnLogin && previousUserId == userId)
        return localDataBelongsToUser && accountCanOwnCurrentLocalData
    }

    internal fun mergeForFirstLogin(
        remote: List<RemoteMediaState>,
        local: List<RemoteMediaState>,
        mergedAtMillis: Long,
    ): List<RemoteMediaState> {
        val merged = remote.associateByTo(linkedMapOf()) { it.queueKey }
        local.forEach { localState ->
            val remoteState = merged[localState.queueKey]
            merged[localState.queueKey] = if (remoteState == null) {
                localState.copy(
                    clientUpdatedAtMillis = maxOf(
                        localState.clientUpdatedAtMillis,
                        mergedAtMillis,
                    ),
                )
            } else {
                mergeState(remoteState, localState, mergedAtMillis)
            }
        }
        return merged.values.toList()
    }

    private fun mergeState(
        remote: RemoteMediaState,
        local: RemoteMediaState,
        mergedAtMillis: Long,
    ): RemoteMediaState {
        val newest = if (local.clientUpdatedAtMillis >= remote.clientUpdatedAtMillis) {
            local
        } else {
            remote
        }
        val oldest = if (newest === local) remote else local
        val latestHistory = when {
            newest.isWatched && newest.lastEngagementAtMillis == null -> null
            local.lastEngagementAtMillis == null -> remote.takeIf {
                it.lastEngagementAtMillis != null
            }
            remote.lastEngagementAtMillis == null -> local
            local.lastEngagementAtMillis >= remote.lastEngagementAtMillis -> local
            else -> remote
        }
        val latestPlayed = when {
            local.lastPlayedAtMillis == null -> remote.takeIf { it.lastPlayedAtMillis != null }
            remote.lastPlayedAtMillis == null -> local
            local.lastPlayedAtMillis >= remote.lastPlayedAtMillis -> local
            else -> remote
        }
        val watchingSource = latestPlayed ?: newest
        val otherWatchingSource = if (watchingSource === local) remote else local

        return newest.copy(
            parentShowId = newest.parentShowId ?: oldest.parentShowId,
            parentShowTitle = newest.parentShowTitle ?: oldest.parentShowTitle,
            parentShowPoster = newest.parentShowPoster ?: oldest.parentShowPoster,
            parentShowBanner = newest.parentShowBanner ?: oldest.parentShowBanner,
            seasonId = newest.seasonId ?: oldest.seasonId,
            seasonNumber = newest.seasonNumber ?: oldest.seasonNumber,
            seasonTitle = newest.seasonTitle ?: oldest.seasonTitle,
            seasonPoster = newest.seasonPoster ?: oldest.seasonPoster,
            episodeNumber = newest.episodeNumber ?: oldest.episodeNumber,
            title = newest.title.ifBlank { oldest.title },
            poster = newest.poster ?: oldest.poster,
            banner = newest.banner ?: oldest.banner,
            isFavorite = remote.isFavorite || local.isFavorite,
            favoritedAtMillis = maxNullable(
                remote.favoritedAtMillis,
                local.favoritedAtMillis,
            ),
            // Watched state is a replaceable user state. Using OR here made a
            // stale remote completion impossible to undo locally.
            isWatched = newest.isWatched,
            watchedAtMillis = newest.watchedAtMillis,
            lastEngagementAtMillis = latestHistory?.lastEngagementAtMillis,
            playbackPositionMillis = latestHistory?.playbackPositionMillis,
            durationMillis = latestHistory?.durationMillis,
            isWatching = watchingSource.isWatching ?: otherWatchingSource.isWatching,
            lastPlayedAtMillis = latestPlayed?.lastPlayedAtMillis,
            lastPlayedEpisodeId = latestPlayed?.let { source ->
                source.lastPlayedEpisodeId
                    ?: if (source === local) remote.lastPlayedEpisodeId
                    else local.lastPlayedEpisodeId
            },
            clientUpdatedAtMillis = maxOf(
                remote.clientUpdatedAtMillis,
                local.clientUpdatedAtMillis,
                mergedAtMillis,
            ),
        )
    }

    private fun maxNullable(first: Long?, second: Long?): Long? = when {
        first == null -> second
        second == null -> first
        else -> maxOf(first, second)
    }

    private suspend fun fetchRemote(client: SupabaseClient): List<RemoteMediaState> =
        collectPages(FETCH_PAGE_SIZE) { from, to ->
            client.from(TABLE).select {
                order("provider", Order.ASCENDING)
                order("media_type", Order.ASCENDING)
                order("media_id", Order.ASCENDING)
                range(from, to)
            }.decodeList()
        }

    internal suspend fun <T> collectPages(
        pageSize: Long,
        fetchPage: suspend (from: Long, to: Long) -> List<T>,
    ): List<T> {
        require(pageSize > 0)
        val items = mutableListOf<T>()
        var from = 0L
        do {
            val page = fetchPage(from, from + pageSize - 1)
            items += page
            from += page.size
        } while (page.size == pageSize.toInt())
        return items
    }

    private suspend fun upsert(
        client: SupabaseClient,
        states: List<RemoteMediaState>,
        onProgress: (CloudSyncProgress) -> Unit = {},
    ) {
        var uploaded = 0
        onProgress(
            CloudSyncProgress(
                CloudSyncProgress.Stage.UPLOADING,
                current = uploaded,
                total = states.size,
            ),
        )
        states.chunked(250).forEach { chunk ->
            client.from(TABLE).upsert(chunk) {
                onConflict = "user_id,provider,media_type,media_id"
            }
            uploaded += chunk.size
            onProgress(
                CloudSyncProgress(
                    CloudSyncProgress.Stage.UPLOADING,
                    current = uploaded,
                    total = states.size,
                ),
            )
        }
    }

    private fun collectLocalState(
        context: Context,
        profileId: String,
        userId: String,
    ): List<RemoteMediaState> {
        val states = mutableListOf<RemoteMediaState>()
        val collectedAtMillis = System.currentTimeMillis()
        UserPreferences.getFavoriteProviders(profileId).forEach { providerName ->
            states += RemoteMediaState.fromProviderFavorite(
                userId = userId,
                providerName = providerName,
                isFavorite = true,
                now = collectedAtMillis,
            )
        }
        existingProviders(context, profileId).forEach { provider ->
            val db = AppDatabase.getInstanceForProvider(provider.name, context, profileId)
            try {
                db.movieDao().getAll()
                    .filter { movie ->
                        movie.isFavorite || movie.isWatched || movie.watchedDate != null ||
                            movie.watchHistory != null || movie.lastPlayedAtMillis != null
                    }
                    .forEach { movie ->
                        states += RemoteMediaState.fromMovie(
                            userId,
                            provider.name,
                            movie,
                            movie.stateTimestamp(),
                        )
                    }
                db.tvShowDao().getAllForBackup()
                    .filter { show ->
                        show.isFavorite || show.lastPlayedAtMillis != null ||
                            show.lastPlayedEpisodeId != null
                    }
                    .forEach { show ->
                        states += RemoteMediaState.fromTvShow(
                            userId,
                            provider.name,
                            show,
                            show.stateTimestamp(),
                        )
                    }
                db.episodeDao().getAllForBackup()
                    .filter { episode ->
                        episode.isWatched || episode.watchedDate != null || episode.watchHistory != null
                    }
                    .forEach { episode ->
                        states += RemoteMediaState.fromEpisode(
                            userId,
                            provider.name,
                            episode,
                            episode.stateTimestamp(),
                        )
                    }
            } finally {
                db.close()
            }
        }
        return states
    }

    private fun applyRemote(
        context: Context,
        profileId: String,
        states: List<RemoteMediaState>,
    ) {
        isApplyingRemote = true
        try {
            applyRemoteInternal(context, profileId, states)
        } finally {
            isApplyingRemote = false
        }
    }

    private fun applyRemoteInternal(
        context: Context,
        profileId: String,
        states: List<RemoteMediaState>,
    ) {
        val providerFavoriteStates = states
            .filter { state -> state.mediaType == "provider_favorite" }
            .groupBy { state -> state.mediaId }
            .mapNotNull { (_, versions) ->
                versions.maxByOrNull { state -> state.clientUpdatedAtMillis }
            }
        if (providerFavoriteStates.isNotEmpty()) {
            val favoriteProviders = UserPreferences.getFavoriteProviders(profileId).toMutableSet()
            providerFavoriteStates.forEach { state ->
                if (state.isFavorite) {
                    favoriteProviders += state.mediaId
                } else {
                    favoriteProviders -= state.mediaId
                }
            }
            UserPreferences.setFavoriteProviders(profileId, favoriteProviders)
            if (ProfileManager.activeProfileId == profileId) {
                ProviderChangeNotifier.notifyProviderChanged()
            }
        }

        states.filterNot { state -> state.mediaType == "provider_favorite" }
            .groupBy { it.provider }.forEach { (providerName, providerStates) ->
            val provider = providerByName(providerName) ?: run {
                Log.w(TAG, "Skipping state for unavailable provider $providerName")
                return@forEach
            }
            val db = AppDatabase.getInstanceForProvider(provider.name, context, profileId)
            try {
                val statesToApply = providerStates.filter { state ->
                    shouldApplyRemoteState(db, state)
                }
                if (statesToApply.isEmpty()) return@forEach

                db.runInTransaction {
                    statesToApply.filter { it.mediaType == "movie" }.forEach { state ->
                        val movie = db.movieDao().getById(state.mediaId)
                            ?: Movie(
                                id = state.mediaId,
                                title = state.title,
                                poster = state.poster,
                                banner = state.banner,
                            )
                        movie.isFavorite = state.isFavorite
                        movie.favoritedAtMillis = state.favoritedAtMillis
                        movie.isWatched = state.isWatched
                        movie.watchedDate = state.watchedAtMillis.toCalendar()
                        movie.watchHistory = state.toWatchHistory()
                        movie.lastPlayedAtMillis = state.lastPlayedAtMillis
                        db.movieDao().insert(movie)
                    }

                    statesToApply.filter { it.mediaType == "tv_show" }.forEach { state ->
                        val show = db.tvShowDao().getById(state.mediaId)
                            ?: TvShow(
                                id = state.mediaId,
                                title = state.title,
                                poster = state.poster,
                                banner = state.banner,
                            )
                        show.isFavorite = state.isFavorite
                        show.favoritedAtMillis = state.favoritedAtMillis
                        show.isWatching = state.isWatching ?: true
                        show.lastPlayedAtMillis = state.lastPlayedAtMillis
                        show.lastPlayedEpisodeId = state.lastPlayedEpisodeId
                        db.tvShowDao().insert(show)
                    }

                    statesToApply.filter { it.mediaType == "episode" }.forEach { state ->
                        val show = state.parentShowId?.let { showId ->
                            db.tvShowDao().getById(showId) ?: TvShow(
                                id = showId,
                                title = state.parentShowTitle.orEmpty(),
                                poster = state.parentShowPoster,
                                banner = state.parentShowBanner,
                            ).also(db.tvShowDao()::insert)
                        }
                        val season = state.seasonId?.let { seasonId ->
                            db.seasonDao().getById(seasonId) ?: Season(
                                id = seasonId,
                                number = state.seasonNumber ?: 0,
                                title = state.seasonTitle,
                                poster = state.seasonPoster,
                                tvShow = show,
                            ).also(db.seasonDao()::insert)
                        }
                        val episode = db.episodeDao().getById(state.mediaId)
                            ?: Episode(
                                id = state.mediaId,
                                number = state.episodeNumber ?: 0,
                                title = state.title,
                                poster = state.poster,
                                tvShow = show,
                                season = season,
                            )
                        episode.isWatched = state.isWatched
                        episode.watchedDate = state.watchedAtMillis.toCalendar()
                        episode.watchHistory = state.toWatchHistory()
                        db.episodeDao().insert(episode)
                    }
                }

                UserDataCache.writeMovies(
                    context,
                    provider,
                    db.movieDao().getAll(),
                    profileId,
                )
                UserDataCache.writeTvShows(
                    context,
                    provider,
                    db.tvShowDao().getAllForBackup(),
                    profileId,
                )
                UserDataCache.writeEpisodes(
                    context,
                    provider,
                    db.episodeDao().getAllForBackup(),
                    profileId,
                )
            } finally {
                db.close()
            }
        }
        UserDataNotifier.notifyChanged()
    }

    /**
     * Realtime can deliver the same row more than once. Replacing an identical
     * Room entity still invalidates every observing Flow, which can repeatedly
     * rebind a detail screen. Only apply newer, materially different state.
     */
    private fun shouldApplyRemoteState(
        database: AppDatabase,
        state: RemoteMediaState,
    ): Boolean {
        return when (state.mediaType) {
            "movie" -> database.movieDao().getById(state.mediaId)?.let { movie ->
                if (movie.cloudStateTimestamp() > state.clientUpdatedAtMillis) return false
                movie.matchesRemoteState(state).not()
            } ?: true

            "tv_show" -> database.tvShowDao().getById(state.mediaId)?.let { show ->
                if (show.cloudStateTimestamp() > state.clientUpdatedAtMillis) return false
                show.matchesRemoteState(state).not()
            } ?: true

            "episode" -> database.episodeDao().getById(state.mediaId)?.let { episode ->
                if (episode.cloudStateTimestamp() > state.clientUpdatedAtMillis) return false
                episode.matchesRemoteState(state).not()
            } ?: true

            else -> false
        }
    }

    private fun Movie.matchesRemoteState(state: RemoteMediaState): Boolean =
        isFavorite == state.isFavorite &&
            favoritedAtMillis == state.favoritedAtMillis &&
            isWatched == state.isWatched &&
            watchedDate?.timeInMillis == state.watchedAtMillis &&
            watchHistory?.lastEngagementTimeUtcMillis == state.lastEngagementAtMillis &&
            watchHistory?.lastPlaybackPositionMillis == state.playbackPositionMillis &&
            watchHistory?.durationMillis == state.durationMillis &&
            lastPlayedAtMillis == state.lastPlayedAtMillis

    private fun TvShow.matchesRemoteState(state: RemoteMediaState): Boolean =
        isFavorite == state.isFavorite &&
            favoritedAtMillis == state.favoritedAtMillis &&
            isWatching == (state.isWatching ?: true) &&
            lastPlayedAtMillis == state.lastPlayedAtMillis &&
            lastPlayedEpisodeId == state.lastPlayedEpisodeId

    private fun Episode.matchesRemoteState(state: RemoteMediaState): Boolean =
        isWatched == state.isWatched &&
            watchedDate?.timeInMillis == state.watchedAtMillis &&
            watchHistory?.lastEngagementTimeUtcMillis == state.lastEngagementAtMillis &&
            watchHistory?.lastPlaybackPositionMillis == state.playbackPositionMillis &&
            watchHistory?.durationMillis == state.durationMillis

    private fun Movie.cloudStateTimestamp(): Long = listOfNotNull(
        favoritedAtMillis,
        watchedDate?.timeInMillis,
        watchHistory?.lastEngagementTimeUtcMillis,
        lastPlayedAtMillis,
    ).maxOrNull() ?: Long.MIN_VALUE

    private fun TvShow.cloudStateTimestamp(): Long = listOfNotNull(
        favoritedAtMillis,
        lastPlayedAtMillis,
    ).maxOrNull() ?: Long.MIN_VALUE

    private fun Episode.cloudStateTimestamp(): Long = listOfNotNull(
        watchedDate?.timeInMillis,
        watchHistory?.lastEngagementTimeUtcMillis,
    ).maxOrNull() ?: Long.MIN_VALUE

    private fun clearLocalUserState(context: Context, profileId: String) {
        UserPreferences.setFavoriteProviders(profileId, emptySet())
        existingProviders(context, profileId).forEach { provider ->
            val db = AppDatabase.getInstanceForProvider(provider.name, context, profileId)
            try {
                db.runInTransaction {
                    db.movieDao().clearUserState()
                    db.tvShowDao().clearUserState()
                    db.episodeDao().clearUserState()
                }
            } finally {
                db.close()
            }
        }
        UserDataCache.clearProfile(context, profileId)
        if (ProfileManager.activeProfileId == profileId) {
            ProviderChangeNotifier.notifyProviderChanged()
            UserDataNotifier.notifyChanged()
        }
    }

    private fun existingProviders(
        context: Context,
        profileId: String,
    ): List<Provider> = allProviders()
        .distinctBy { it.name }
        .filter { provider ->
            context.getDatabasePath(
                AppDatabase.databaseNameFor(provider.name, profileId),
            ).exists()
        }

    private fun allProviders(): List<Provider> = (Provider.providers.keys +
        listOf("it", "en", "es", "de", "fr").map(::TmdbProvider)).toList()

    private fun providerByName(name: String): Provider? =
        allProviders().firstOrNull { it.name == name }

    private fun requireActiveProfileId(): String =
        requireNotNull(ProfileManager.activeProfileId) { "No local profile is active" }

    private fun checkActiveProfile(expectedProfileId: String) {
        check(ProfileManager.activeProfileId == expectedProfileId) {
            "The active profile changed during authentication"
        }
    }

    private suspend fun rejectAccountLinkedToAnotherProfile(
        context: Context,
        profileId: String,
        userId: String,
        client: SupabaseClient,
    ) {
        val linkedProfileId = CloudAccountStore.profileIdForUser(context, userId)
        if (CloudAccountStore.canLinkAccount(linkedProfileId, profileId)) return
        checkNotNull(linkedProfileId)
        val linkedProfileName = ProfileManager.getProfileById(linkedProfileId)?.name
            ?: linkedProfileId
        client.auth.signOut()
        throw CloudAccountAlreadyLinkedException(linkedProfileName)
    }

    private fun requireConfigured() {
        check(SupabaseProvider.isConfigured) { "Supabase is not configured in Account settings" }
    }

    private fun Movie.stateTimestamp(): Long = listOfNotNull(
        favoritedAtMillis,
        watchedDate?.timeInMillis,
        watchHistory?.lastEngagementTimeUtcMillis,
        lastPlayedAtMillis,
    ).maxOrNull() ?: System.currentTimeMillis()

    private fun TvShow.stateTimestamp(): Long = listOfNotNull(
        favoritedAtMillis,
        lastPlayedAtMillis,
    ).maxOrNull() ?: System.currentTimeMillis()

    private fun Episode.stateTimestamp(): Long = listOfNotNull(
        watchedDate?.timeInMillis,
        watchHistory?.lastEngagementTimeUtcMillis,
    ).maxOrNull() ?: System.currentTimeMillis()

    private fun RemoteMediaState.toWatchHistory(): WatchItem.WatchHistory? =
        lastEngagementAtMillis?.let { engagedAt ->
            WatchItem.WatchHistory(
                lastEngagementTimeUtcMillis = engagedAt,
                lastPlaybackPositionMillis = playbackPositionMillis ?: 0L,
                durationMillis = durationMillis ?: 0L,
            )
        }

    private fun Long?.toCalendar(): Calendar? = this?.let { millis ->
        Calendar.getInstance().apply { timeInMillis = millis }
    }
}
