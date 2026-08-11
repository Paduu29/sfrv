package com.streamflixrevanced.streamflix.sync

import android.content.Context
import com.streamflixrevanced.streamflix.utils.ProfileManager

object CloudAccountStore {
    private const val PREFS = "cloud_account_state"
    private const val ACTIVE_USER = "active_user_id"
    private const val ACTIVE_EMAIL = "active_user_email"
    private const val LEGACY_OWNER = "legacy_owner_id"
    private const val DEFAULT_PROFILE_ID = "default"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun activeUserId(
        context: Context,
        profileId: String = requireActiveProfileId(),
    ): String? {
        migrateLegacyKey(context, ACTIVE_USER, profileId)
        return prefs(context).getString(profileKey(ACTIVE_USER, profileId), null)
    }

    fun activeUserEmail(
        context: Context,
        profileId: String = requireActiveProfileId(),
    ): String? {
        migrateLegacyKey(context, ACTIVE_EMAIL, profileId)
        return prefs(context).getString(profileKey(ACTIVE_EMAIL, profileId), null)
    }

    fun setActiveAccount(
        context: Context,
        profileId: String,
        userId: String?,
        email: String?,
    ) {
        prefs(context).edit().apply {
            val userKey = profileKey(ACTIVE_USER, profileId)
            val emailKey = profileKey(ACTIVE_EMAIL, profileId)
            if (userId == null) remove(userKey) else putString(userKey, userId)
            if (email == null) remove(emailKey) else putString(emailKey, email)
        }.apply()
    }

    fun legacyOwnerId(
        context: Context,
        profileId: String = requireActiveProfileId(),
    ): String? {
        migrateLegacyKey(context, LEGACY_OWNER, profileId)
        return prefs(context).getString(profileKey(LEGACY_OWNER, profileId), null)
    }

    fun claimLegacyData(context: Context, profileId: String, userId: String) {
        prefs(context).edit()
            .putString(profileKey(LEGACY_OWNER, profileId), userId)
            .apply()
    }

    fun profileIdForUser(context: Context, userId: String): String? =
        prefs(context).all.entries.firstNotNullOfOrNull { (key, value) ->
            if (key.startsWith("${ACTIVE_USER}_") && value == userId) {
                key.removePrefix("${ACTIVE_USER}_")
            } else {
                null
            }
        }

    internal fun canLinkAccount(
        linkedProfileId: String?,
        requestedProfileId: String,
    ): Boolean = linkedProfileId == null || linkedProfileId == requestedProfileId

    fun clearProfile(context: Context, profileId: String) {
        prefs(context).edit()
            .remove(profileKey(ACTIVE_USER, profileId))
            .remove(profileKey(ACTIVE_EMAIL, profileId))
            .remove(profileKey(LEGACY_OWNER, profileId))
            .apply()
    }

    fun hasLegacyGlobalAccount(context: Context): Boolean =
        prefs(context).contains(ACTIVE_USER)

    private fun migrateLegacyKey(context: Context, key: String, profileId: String) {
        if (profileId != DEFAULT_PROFILE_ID) return
        val preferences = prefs(context)
        val scopedKey = profileKey(key, profileId)
        if (preferences.contains(scopedKey) || !preferences.contains(key)) return
        preferences.edit().apply {
            preferences.getString(key, null)?.let { putString(scopedKey, it) }
            remove(key)
        }.apply()
    }

    private fun profileKey(key: String, profileId: String): String = "${key}_$profileId"

    private fun requireActiveProfileId(): String =
        requireNotNull(ProfileManager.activeProfileId) { "No local profile is active" }

    /**
     * Remove any stored per-profile cloud-account entries that do not belong to
     * an existing local profile. This helps avoid stale mappings pointing at
     * deleted profiles (see issue where deleted profile left cloud_account_state
     * entries and prevented reuse of the Supabase account).
     */
    fun removeProfilesNotIn(context: Context, validProfileIds: Set<String>) {
        val preferences = prefs(context)
        val editor = preferences.edit()
        preferences.all.entries.forEach { (key, _) ->
            // keys have the form "<KEY>_<profileId>" for per-profile entries
            val underscore = key.lastIndexOf('_')
            if (underscore <= 0) return@forEach
            val profileId = key.substring(underscore + 1)
            if (profileId !in validProfileIds) {
                // remove the three known per-profile keys for this profile id
                editor.remove(profileKey(ACTIVE_USER, profileId))
                editor.remove(profileKey(ACTIVE_EMAIL, profileId))
                editor.remove(profileKey(LEGACY_OWNER, profileId))
            }
        }
        editor.apply()
    }
}
