package com.streamflixrevanced.streamflix.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.streamflixrevanced.streamflix.BuildConfig
import com.streamflixrevanced.streamflix.database.AppDatabase
import com.streamflixrevanced.streamflix.database.ProfileDatabase
import com.streamflixrevanced.streamflix.database.dao.ProfileDao
import com.streamflixrevanced.streamflix.models.Profile
import com.streamflixrevanced.streamflix.providers.Provider
import com.streamflixrevanced.streamflix.sync.CloudSyncManager
import kotlinx.coroutines.flow.Flow

object ProfileManager {

    private const val TAG = "ProfileManager"
    private const val GLOBAL_PREFS_NAME = "${BuildConfig.APPLICATION_ID}.profile_global"
    private const val KEY_ACTIVE_PROFILE_ID = "ACTIVE_PROFILE_ID"
    private const val DEFAULT_PROFILE_ID = "default"
    private lateinit var appContext: Context
    private var profileDao: ProfileDao? = null
    private var _activeProfile: Profile? = null

    val activeProfile: Profile? get() = _activeProfile
    val activeProfileId: String? get() = _activeProfile?.id

    private val globalPrefs: SharedPreferences?
        get() = if (::appContext.isInitialized)
            appContext.getSharedPreferences(GLOBAL_PREFS_NAME, Context.MODE_PRIVATE) else null

    init {
        UserPreferences._profileManagerReady = true
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext

        val db = ProfileDatabase.getInstance(appContext)
        profileDao = db.profileDao()

        val storedId = globalPrefs?.getString(KEY_ACTIVE_PROFILE_ID, null)

        if (storedId == null) {
            createDefaultProfile()
        } else {
            val profile = runCatching {
                kotlinx.coroutines.runBlocking {
                    profileDao?.getProfileById(storedId)
                }
            }.getOrNull()

            if (profile == null) {
                Log.w(TAG, "Stored profile $storedId not found, creating default")
                createDefaultProfile()
            } else {
                _activeProfile = profile
            }
        }

        // Cleanup any stale cloud_account_state entries that reference profiles
        // which do not exist locally. This prevents orphaned mappings from
        // blocking sign-in after a profile was deleted externally.
        runCatching {
            kotlinx.coroutines.runBlocking {
                val validIds = profileDao?.getAllProfilesList()?.map { it.id }?.toSet() ?: setOf(DEFAULT_PROFILE_ID)
                com.streamflixrevanced.streamflix.sync.CloudAccountStore.removeProfilesNotIn(appContext, validIds)
            }
        }.onFailure { error -> Log.w(TAG, "Failed to cleanup stale cloud account entries", error) }

        applyActiveProfilePrefs()
        Log.i(TAG, "Initialized. Active profile: ${_activeProfile?.name} (${_activeProfile?.id})")
    }

    private fun createDefaultProfile() {
        val defaultProfile = Profile(
            id = DEFAULT_PROFILE_ID,
            name = appContext.getString(com.streamflixrevanced.streamflix.R.string.profile_default_name),
            position = 0,
        )
        kotlinx.coroutines.runBlocking {
            profileDao?.insert(defaultProfile)
        }

        _activeProfile = defaultProfile
        globalPrefs?.edit()?.putString(KEY_ACTIVE_PROFILE_ID, defaultProfile.id)?.apply()

        migrateLegacyPrefs()
        migrateLegacyDatabasesToDefaultProfile()
        UserDataCache.migrateLegacyCacheToDefaultProfile(appContext, Provider.providers.keys)
        Log.i(TAG, "Created default profile: ${defaultProfile.name}")
    }

    private fun migrateLegacyPrefs() {
        val legacyPrefs = appContext.getSharedPreferences(
            "${BuildConfig.APPLICATION_ID}.preferences",
            Context.MODE_PRIVATE,
        )

        val profilePrefs = getProfilePrefs(DEFAULT_PROFILE_ID)
        profilePrefs.edit().apply {
            legacyPrefs.all.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Set<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        putStringSet(key, value as Set<String>)
                    }
                    else -> {}
                }
            }
            commit()
        }

        Log.i(TAG, "Migrated ${legacyPrefs.all.size} legacy preferences to profile: $DEFAULT_PROFILE_ID")
    }

    private fun migrateLegacyDatabasesToDefaultProfile() {
        val currentProviderName = UserPreferences.currentProvider?.name
        val expectedLegacyDbNames = (Provider.providers.keys.map { it.name } + listOfNotNull(currentProviderName))
            .map { AppDatabase.legacyDatabaseNameFor(it) }
            .toSet()

        val legacyDbNames = appContext.databaseList()
            .filter { name ->
                name in expectedLegacyDbNames || (name.startsWith("tmdb_") && name.endsWith(".db"))
            }

        var migratedCount = 0
        legacyDbNames.forEach { legacyDbName ->
            val providerPart = legacyDbName.removeSuffix(".db")
            val defaultDbName = "${AppDatabase.sanitizeDatabasePart(DEFAULT_PROFILE_ID)}_$providerPart.db"
            val legacyDb = appContext.getDatabasePath(legacyDbName)
            val defaultDb = appContext.getDatabasePath(defaultDbName)

            if (!legacyDb.exists()) return@forEach
            if (defaultDb.exists()) return@forEach

            runCatching {
                listOf("", "-wal", "-shm").forEach { suffix ->
                    val source = appContext.getDatabasePath("$legacyDbName$suffix")
                    if (!source.exists()) return@forEach

                    val destination = appContext.getDatabasePath("$defaultDbName$suffix")
                    if (destination.exists()) return@forEach
                    destination.parentFile?.mkdirs()
                    source.copyTo(destination, overwrite = false)
                }
            }.onSuccess {
                migratedCount++
                Log.i(TAG, "Migrated legacy database $legacyDbName to default profile database $defaultDbName")
            }.onFailure { error ->
                Log.e(TAG, "Failed to migrate legacy database $legacyDbName", error)
            }
        }

        Log.i(TAG, "Migrated $migratedCount legacy databases to profile: $DEFAULT_PROFILE_ID")
    }

    suspend fun switchToProfile(profileId: String) {
        val profile = profileDao?.getProfileById(profileId)
        if (profile == null) {
            Log.e(TAG, "Cannot switch to non-existent profile: $profileId")
            return
        }

        AppDatabase.resetInstance()
        // Notify UserDataCache that the profile changed so in-flight reads/writes stop
        UserDataCache.onProfileSwitched(profileId)

        _activeProfile = profile
        globalPrefs?.edit {
            putString(KEY_ACTIVE_PROFILE_ID, profileId)
        }

        applyActiveProfilePrefs()

        // A profile switch changes the database and cached user data even
        // when the selected provider remains the same. Refresh all active
        // provider screens, especially Home, in that case as well.
        ProviderChangeNotifier.notifyProviderChanged()
        CloudSyncManager.onProfileChanged(appContext, profileId)
        Log.i(TAG, "Switched to profile: ${profile.name} (${profile.id})")
    }

    private fun applyActiveProfilePrefs() {
        val profileId = _activeProfile?.id ?: return
        val profilePrefs = getProfilePrefs(profileId)
        UserPreferences.profilePrefs = profilePrefs
        UserPreferences.profileId = profileId
        UserPreferences.reloadProviderCache()
    }

    fun getProfilePrefs(profileId: String): SharedPreferences {
        return appContext.getSharedPreferences(
            "${BuildConfig.APPLICATION_ID}.preferences_${profileId}",
            Context.MODE_PRIVATE,
        )
    }

    fun getAllProfilesFlow(): Flow<List<Profile>>? = profileDao?.getAllProfiles()

    suspend fun getAllProfiles(): List<Profile> = profileDao?.getAllProfilesList() ?: emptyList()

    suspend fun getProfileById(id: String): Profile? = profileDao?.getProfileById(id)

    suspend fun createProfile(name: String): Profile? {
        val pos = profileDao?.getNextPosition() ?: return null
        val profile = Profile(
            name = name.trim().take(30),
            position = pos,
        )
        profileDao?.insert(profile)
        Log.i(TAG, "Created profile: ${profile.name} (${profile.id})")
        return profile
    }

    suspend fun renameProfile(id: String, newName: String): Boolean {
        val profile = profileDao?.getProfileById(id) ?: return false
        val updated = profile.copy(name = newName.trim().take(30))
        profileDao?.update(updated)
        if (id == _activeProfile?.id) {
            _activeProfile = updated
        }
        Log.i(TAG, "Renamed profile $id to: $newName")
        return true
    }

    suspend fun setProfileAvatar(id: String, avatarPath: String): Boolean {
        if (!ProfileAvatarRepository.isAllowedAvatarPath(avatarPath)) return false
        val profile = profileDao?.getProfileById(id) ?: return false
        val updated = profile.copy(avatarPath = avatarPath)
        profileDao?.update(updated)
        if (id == _activeProfile?.id) {
            _activeProfile = updated
        }
        Log.i(TAG, "Changed profile avatar for $id")
        return true
    }

    suspend fun deleteProfile(id: String): Boolean {
        val allProfiles = profileDao?.getAllProfilesList() ?: return false
        if (allProfiles.size <= 1) return false

        val profile = profileDao?.getProfileById(id) ?: return false
        profileDao?.delete(profile)

        val profilePrefs = getProfilePrefs(id)
        profilePrefs.edit().clear().commit()

        if (id == _activeProfile?.id) {
            val next = allProfiles.firstOrNull { it.id != id }
            if (next != null) switchToProfile(next.id)
        }

        CloudSyncManager.onProfileDeleted(appContext, id)
        // Ensure cloud-account state for the deleted profile is removed locally
        try {
            com.streamflixrevanced.streamflix.sync.CloudAccountStore.clearProfile(appContext, id)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to clear cloud account state for deleted profile $id", e)
        }
        Log.i(TAG, "Deleted profile: $id")
        return true
    }

    suspend fun getProfileCount(): Int = profileDao?.getProfileCount() ?: 1
}
