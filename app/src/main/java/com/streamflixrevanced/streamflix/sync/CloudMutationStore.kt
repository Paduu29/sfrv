package com.streamflixrevanced.streamflix.sync

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

object CloudMutationStore {
    private const val PREFS = "cloud_sync_queue"
    private const val QUEUE = "pending_media_states"
    private const val DEFAULT_PROFILE_ID = "default"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(RemoteMediaState.serializer())

    @Synchronized
    fun enqueue(context: Context, profileId: String, state: RemoteMediaState) {
        val current = read(context, profileId).associateByTo(linkedMapOf()) { it.queueKey }
        current[state.queueKey] = state
        write(context, profileId, current.values.toList())
    }

    @Synchronized
    fun pendingForUser(
        context: Context,
        profileId: String,
        userId: String,
    ): List<RemoteMediaState> =
        read(context, profileId).filter { it.userId == userId }

    @Synchronized
    fun acknowledge(
        context: Context,
        profileId: String,
        uploaded: List<RemoteMediaState>,
    ) {
        if (uploaded.isEmpty()) return
        val uploadedVersions = uploaded.associate { it.queueKey to it.clientUpdatedAtMillis }
        val remaining = read(context, profileId).filter { state ->
            val uploadedVersion = uploadedVersions[state.queueKey]
            uploadedVersion == null || state.clientUpdatedAtMillis > uploadedVersion
        }
        write(context, profileId, remaining)
    }

    @Synchronized
    fun clearProfile(context: Context, profileId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(queueKey(profileId))
            .apply()
    }

    private fun read(context: Context, profileId: String): List<RemoteMediaState> {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        migrateLegacyQueue(preferences, profileId)
        val raw = preferences.getString(queueKey(profileId), null) ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    private fun write(context: Context, profileId: String, states: List<RemoteMediaState>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(queueKey(profileId), json.encodeToString(serializer, states))
            .apply()
    }

    private fun migrateLegacyQueue(
        preferences: android.content.SharedPreferences,
        profileId: String,
    ) {
        if (profileId != DEFAULT_PROFILE_ID || !preferences.contains(QUEUE)) return
        val scopedKey = queueKey(profileId)
        preferences.edit().apply {
            if (!preferences.contains(scopedKey)) {
                preferences.getString(QUEUE, null)?.let { putString(scopedKey, it) }
            }
            remove(QUEUE)
        }.apply()
    }

    private fun queueKey(profileId: String): String = "${QUEUE}_$profileId"
}
