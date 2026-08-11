package com.streamflixrevanced.streamflix.sync

import com.streamflixrevanced.streamflix.utils.ProfileManager
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.SettingsSessionManager
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object SupabaseProvider {
    private const val SESSION_KEY_PREFIX = "streamflix_cloud_session_"
    private val clientsMutex = Mutex()
    private val clients = mutableMapOf<String, ClientEntry>()

    private data class ClientEntry(
        val configIdentity: String,
        val client: SupabaseClient,
    )

    val isConfigured: Boolean
        get() = SupabaseSettings.isConfigured

    val client: SupabaseClient
        get() {
            val profileId = ProfileManager.activeProfileId
                ?: error("No local profile is active")
            return clientOrNull(profileId)
                ?: error("Supabase has not been initialized for profile $profileId")
        }

    fun activeClientOrNull(): SupabaseClient? =
        ProfileManager.activeProfileId?.let(::clientOrNull)

    fun clientOrNull(profileId: String): SupabaseClient? = synchronized(clients) {
        val configIdentity = SupabaseSettings.config?.identity ?: return@synchronized null
        clients[profileId]
            ?.takeIf { it.configIdentity == configIdentity }
            ?.client
    }

    suspend fun clientFor(profileId: String): SupabaseClient {
        return clientsMutex.withLock {
            val config = requireNotNull(SupabaseSettings.config) { "Supabase is not configured" }
            clientOrNull(profileId) ?: createClient(profileId, config).also { client ->
                synchronized(clients) {
                    clients[profileId] = ClientEntry(config.identity, client)
                }
            }
        }
    }

    suspend fun removeProfile(profileId: String) {
        val removed = clientsMutex.withLock {
            synchronized(clients) {
                clients.remove(profileId)
            }
        }
        removed?.client?.close()
        SettingsSessionManager(key = sessionKey(profileId)).deleteSession()
    }

    suspend fun replaceConfiguration(
        config: SupabaseConfig,
        profileIds: Collection<String>,
    ): Boolean = clientsMutex.withLock {
        val oldConfig = SupabaseSettings.config
        if (!requiresSupabaseSessionReset(oldConfig, config)) return@withLock false

        val oldClients = synchronized(clients) {
            clients.values.map(ClientEntry::client).also { clients.clear() }
        }
        oldClients.forEach { client -> runCatching { client.close() } }
        profileIds.forEach { profileId ->
            SettingsSessionManager(key = sessionKey(profileId)).deleteSession()
        }
        // Persist only after every old session has been removed. If deletion fails,
        // keeping the previous configuration prevents its token reaching a new host.
        SupabaseSettings.save(config)
        true
    }

    private fun createClient(profileId: String, config: SupabaseConfig): SupabaseClient =
        createSupabaseClient(
            supabaseUrl = config.url,
            supabaseKey = config.publishableKey,
        ) {
            install(Auth) {
                // Keep the historical per-profile key so sessions survive the migration
                // release. replaceConfiguration deletes it before switching instances.
                sessionManager = SettingsSessionManager(key = sessionKey(profileId))
            }
            install(Postgrest)
            install(Realtime)
            install(Storage)
        }

    private fun sessionKey(profileId: String): String = "$SESSION_KEY_PREFIX$profileId"
}
