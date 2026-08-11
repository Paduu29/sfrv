package com.streamflixrevanced.streamflix.sync

import com.streamflixrevanced.streamflix.database.AppDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMediaStateTest {
    @Test
    fun realtimeChangeIsRejectedForAnotherUser() {
        val remote = state(mediaId = "show", clientUpdatedAtMillis = 200L)

        assertFalse(
            CloudSyncManager.shouldApplyRealtimeState(
                currentProfileId = "profile-a",
                eventProfileId = "profile-a",
                currentUserId = "different-user",
                state = remote,
                pending = emptyList(),
            ),
        )
    }

    @Test
    fun realtimeChangeDoesNotOverwriteNewerPendingLocalMutation() {
        val remote = state(mediaId = "show", clientUpdatedAtMillis = 200L)
        val newerLocal = state(mediaId = "show", clientUpdatedAtMillis = 300L)

        assertFalse(
            CloudSyncManager.shouldApplyRealtimeState(
                currentProfileId = "profile-a",
                eventProfileId = "profile-a",
                currentUserId = remote.userId,
                state = remote,
                pending = listOf(newerLocal),
            ),
        )
    }

    @Test
    fun realtimeChangeAppliesWhenItIsCurrent() {
        val remote = state(mediaId = "show", clientUpdatedAtMillis = 300L)
        val olderLocal = state(mediaId = "show", clientUpdatedAtMillis = 200L)

        assertTrue(
            CloudSyncManager.shouldApplyRealtimeState(
                currentProfileId = "profile-a",
                eventProfileId = "profile-a",
                currentUserId = remote.userId,
                state = remote,
                pending = listOf(olderLocal),
            ),
        )
    }

    @Test
    fun realtimeChangeIsRejectedForAnotherLocalProfile() {
        val remote = state(mediaId = "show", clientUpdatedAtMillis = 300L)

        assertFalse(
            CloudSyncManager.shouldApplyRealtimeState(
                currentProfileId = "default",
                eventProfileId = "alex",
                currentUserId = remote.userId,
                state = remote,
                pending = emptyList(),
            ),
        )
    }

    @Test
    fun cloudAccountCanOnlyBeLinkedToItsOwningLocalProfile() {
        assertTrue(
            CloudAccountStore.canLinkAccount(
                linkedProfileId = null,
                requestedProfileId = "alex",
            ),
        )
        assertTrue(
            CloudAccountStore.canLinkAccount(
                linkedProfileId = "alex",
                requestedProfileId = "alex",
            ),
        )
        assertFalse(
            CloudAccountStore.canLinkAccount(
                linkedProfileId = "default",
                requestedProfileId = "alex",
            ),
        )
    }

    @Test
    fun localMediaDatabasesAreSeparatedByProfile() {
        assertNotEquals(
            AppDatabase.databaseNameFor("SeriesFlix", "default"),
            AppDatabase.databaseNameFor("SeriesFlix", "alex"),
        )
    }

    @Test
    fun cloudFetchCollectsEveryPagePastApiRowLimit() = runBlocking {
        val requestedRanges = mutableListOf<LongRange>()
        val remoteRows = (0 until 1_110).toList()

        val fetched = CloudSyncManager.collectPages(pageSize = 500L) { from, to ->
            requestedRanges += from..to
            remoteRows.drop(from.toInt()).take((to - from + 1).toInt())
        }

        assertEquals(remoteRows, fetched)
        assertEquals(
            listOf(0L..499L, 500L..999L, 1_000L..1_499L),
            requestedRanges,
        )
    }

    @Test
    fun explicitLoginMergesLocalDataAlreadyOwnedBySameUser() {
        assertTrue(
            CloudSyncManager.shouldMergeLocal(
                previousUserId = "user-a",
                legacyOwnerId = "user-a",
                userId = "user-a",
                mergeLocalOnLogin = true,
            ),
        )
    }

    @Test
    fun startupSyncDoesNotReimportLocalDataForAlreadyActiveUser() {
        assertFalse(
            CloudSyncManager.shouldMergeLocal(
                previousUserId = "user-a",
                legacyOwnerId = "user-a",
                userId = "user-a",
                mergeLocalOnLogin = false,
            ),
        )
    }

    @Test
    fun loginNeverMergesDataClaimedByAnotherUser() {
        assertFalse(
            CloudSyncManager.shouldMergeLocal(
                previousUserId = null,
                legacyOwnerId = "user-a",
                userId = "user-b",
                mergeLocalOnLogin = true,
            ),
        )
    }

    @Test
    fun requiredDatabaseDefaultsAreAlwaysEncoded() {
        val state = RemoteMediaState(
            userId = "00000000-0000-0000-0000-000000000000",
            provider = "test",
            mediaType = "tv_show",
            mediaId = "42",
            clientUpdatedAtMillis = 1L,
        )

        val json = Json.parseToJsonElement(Json.encodeToString(state)).jsonObject

        assertTrue(json.containsKey("title"))
        assertTrue(json.containsKey("is_favorite"))
        assertTrue(json.containsKey("is_watched"))
        assertEquals("", json.getValue("title").jsonPrimitive.content)
        assertEquals(false, json.getValue("is_favorite").jsonPrimitive.content.toBoolean())
        assertEquals(false, json.getValue("is_watched").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun clearedProgressIsEncodedAsExplicitNullsForPostgrestUpsert() {
        val state = state(
            mediaType = "episode",
            mediaId = "episode",
            lastEngagementAtMillis = null,
            playbackPositionMillis = null,
            durationMillis = null,
            clientUpdatedAtMillis = 2L,
        )

        val json = Json.parseToJsonElement(Json.encodeToString(state)).jsonObject

        assertEquals(JsonNull, json.getValue("last_engagement_at_millis"))
        assertEquals(JsonNull, json.getValue("playback_position_millis"))
        assertEquals(JsonNull, json.getValue("duration_millis"))
    }

    @Test
    fun firstLoginMergeUnionsDevicesAndPreservesPositiveState() {
        val remoteMovie = state(
            mediaId = "shared",
            isFavorite = true,
            lastEngagementAtMillis = 100L,
            playbackPositionMillis = 10L,
            clientUpdatedAtMillis = 100L,
        )
        val localMovie = state(
            mediaId = "shared",
            isWatched = true,
            watchedAtMillis = 200L,
            lastEngagementAtMillis = 300L,
            playbackPositionMillis = 30L,
            clientUpdatedAtMillis = 300L,
        )
        val remoteOnly = state(mediaId = "remote-only", clientUpdatedAtMillis = 50L)
        val localOnly = state(
            mediaId = "local-only",
            isWatched = true,
            clientUpdatedAtMillis = 75L,
        )

        val merged = CloudSyncManager.mergeForFirstLogin(
            remote = listOf(remoteMovie, remoteOnly),
            local = listOf(localMovie, localOnly),
            mergedAtMillis = 1_000L,
        ).associateBy { it.mediaId }

        assertEquals(setOf("shared", "remote-only", "local-only"), merged.keys)
        assertTrue(merged.getValue("shared").isFavorite)
        assertTrue(merged.getValue("shared").isWatched)
        assertEquals(30L, merged.getValue("shared").playbackPositionMillis)
        assertEquals(1_000L, merged.getValue("shared").clientUpdatedAtMillis)
        assertEquals(50L, merged.getValue("remote-only").clientUpdatedAtMillis)
        assertTrue(merged.getValue("local-only").isWatched)
        assertEquals(1_000L, merged.getValue("local-only").clientUpdatedAtMillis)
    }

    @Test
    fun firstLoginMergeUsesNewestProgressAndLastPlayedEpisode() {
        val remote = state(
            mediaType = "tv_show",
            mediaId = "show",
            isWatching = false,
            lastPlayedAtMillis = 500L,
            lastPlayedEpisodeId = "remote-episode",
            lastEngagementAtMillis = 600L,
            playbackPositionMillis = 60L,
            durationMillis = 100L,
            clientUpdatedAtMillis = 600L,
        )
        val local = state(
            mediaType = "tv_show",
            mediaId = "show",
            isWatching = true,
            lastPlayedAtMillis = 700L,
            lastPlayedEpisodeId = "local-episode",
            lastEngagementAtMillis = 400L,
            playbackPositionMillis = 40L,
            durationMillis = 90L,
            clientUpdatedAtMillis = 700L,
        )

        val merged = CloudSyncManager.mergeForFirstLogin(
            remote = listOf(remote),
            local = listOf(local),
            mergedAtMillis = 800L,
        ).single()

        assertTrue(merged.isWatching == true)
        assertEquals(700L, merged.lastPlayedAtMillis)
        assertEquals("local-episode", merged.lastPlayedEpisodeId)
        assertEquals(600L, merged.lastEngagementAtMillis)
        assertEquals(60L, merged.playbackPositionMillis)
        assertEquals(100L, merged.durationMillis)
        assertFalse(merged.isWatched)
        assertNull(merged.watchedAtMillis)
    }

    @Test
    fun firstLoginMergeAllowsNewerUnwatchedStateToClearRemoteCompletion() {
        val remote = state(
            mediaId = "episode",
            isWatched = true,
            watchedAtMillis = 100L,
            lastEngagementAtMillis = 100L,
            playbackPositionMillis = 2_700L,
            durationMillis = 3_000L,
            clientUpdatedAtMillis = 100L,
        )
        val local = state(
            mediaId = "episode",
            isWatched = false,
            lastEngagementAtMillis = 200L,
            playbackPositionMillis = 1_500L,
            durationMillis = 3_000L,
            clientUpdatedAtMillis = 200L,
        )

        val merged = CloudSyncManager.mergeForFirstLogin(
            remote = listOf(remote),
            local = listOf(local),
            mergedAtMillis = 300L,
        ).single()

        assertFalse(merged.isWatched)
        assertNull(merged.watchedAtMillis)
        assertEquals(1_500L, merged.playbackPositionMillis)
    }

    private fun state(
        mediaType: String = "movie",
        mediaId: String,
        isFavorite: Boolean = false,
        isWatched: Boolean = false,
        watchedAtMillis: Long? = null,
        lastEngagementAtMillis: Long? = null,
        playbackPositionMillis: Long? = null,
        durationMillis: Long? = null,
        isWatching: Boolean? = null,
        lastPlayedAtMillis: Long? = null,
        lastPlayedEpisodeId: String? = null,
        clientUpdatedAtMillis: Long,
    ) = RemoteMediaState(
        userId = "00000000-0000-0000-0000-000000000000",
        provider = "test",
        mediaType = mediaType,
        mediaId = mediaId,
        isFavorite = isFavorite,
        isWatched = isWatched,
        watchedAtMillis = watchedAtMillis,
        lastEngagementAtMillis = lastEngagementAtMillis,
        playbackPositionMillis = playbackPositionMillis,
        durationMillis = durationMillis,
        isWatching = isWatching,
        lastPlayedAtMillis = lastPlayedAtMillis,
        lastPlayedEpisodeId = lastPlayedEpisodeId,
        clientUpdatedAtMillis = clientUpdatedAtMillis,
    )
}
