package com.streamflixrevanced.streamflix.sync

import android.content.Context
import android.content.SharedPreferences
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString.Companion.decodeBase64
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class SupabaseConfig(
    val url: String,
    val publishableKey: String,
    val avatarBucket: String? = null,
) {
    /** A non-secret identifier used to invalidate clients when a configuration value changes. */
    val identity: String
        get() = MessageDigest.getInstance("SHA-256")
            .digest("$url\u0000$publishableKey\u0000${avatarBucket.orEmpty()}".toByteArray(StandardCharsets.UTF_8))
            .take(12)
            .joinToString("") { byte -> "%02x".format(byte) }
}

enum class SupabaseConfigError {
    INVALID_URL,
    MISSING_PUBLISHABLE_KEY,
    INVALID_PUBLISHABLE_KEY,
    SERVICE_ROLE_KEY,
}

sealed interface SupabaseConfigValidation {
    data class Valid(val config: SupabaseConfig) : SupabaseConfigValidation
    data class Invalid(val error: SupabaseConfigError) : SupabaseConfigValidation
}

internal fun requiresSupabaseSessionReset(
    current: SupabaseConfig?,
    replacement: SupabaseConfig,
): Boolean = current != replacement

internal interface SupabaseConfigStore {
    fun read(): SupabaseConfig?
    fun save(config: SupabaseConfig)
}

internal class SharedPreferencesSupabaseConfigStore(
    private val preferences: SharedPreferences,
) : SupabaseConfigStore {
    override fun read(): SupabaseConfig? {
        val url = preferences.getString(KEY_URL, null)?.takeIf(String::isNotBlank)
        val key = preferences.getString(KEY_PUBLISHABLE_KEY, null)?.takeIf(String::isNotBlank)
        val avatarBucket = preferences.getString(KEY_AVATAR_BUCKET, null)?.trim()?.takeIf(String::isNotEmpty)
        return if (url != null && key != null) SupabaseConfig(url, key, avatarBucket) else null
    }

    override fun save(config: SupabaseConfig) {
        check(
            preferences.edit()
                .putString(KEY_URL, config.url)
                .putString(KEY_PUBLISHABLE_KEY, config.publishableKey)
                .putString(KEY_AVATAR_BUCKET, config.avatarBucket)
                .commit()
        ) { "Could not persist Supabase configuration" }
    }

    private companion object {
        const val KEY_URL = "supabase_url"
        const val KEY_PUBLISHABLE_KEY = "supabase_publishable_key"
        const val KEY_AVATAR_BUCKET = "supabase_avatar_bucket"
    }
}

class SupabaseConfigRepository internal constructor(
    private val store: SupabaseConfigStore,
) {
    val activeConfig: SupabaseConfig?
        get() = store.read()?.takeIf {
            validate(it.url, it.publishableKey, it.avatarBucket) is SupabaseConfigValidation.Valid
        }

    val isConfigured: Boolean
        get() = activeConfig != null

    fun validate(url: String, publishableKey: String, avatarBucket: String? = null): SupabaseConfigValidation =
        Companion.validate(url, publishableKey, avatarBucket)

    fun save(url: String, publishableKey: String, avatarBucket: String? = null): SupabaseConfigValidation {
        val validation = validate(url, publishableKey, avatarBucket)
        if (validation is SupabaseConfigValidation.Valid) store.save(validation.config)
        return validation
    }

    internal fun save(config: SupabaseConfig) {
        val validation = validate(config.url, config.publishableKey, config.avatarBucket)
        require(validation is SupabaseConfigValidation.Valid) { "Invalid Supabase configuration" }
        store.save(validation.config)
    }

    companion object {
        private const val PREFERENCES_NAME = "supabase_configuration"

        fun create(context: Context): SupabaseConfigRepository = SupabaseConfigRepository(
            SharedPreferencesSupabaseConfigStore(
                context.applicationContext.getSharedPreferences(
                    PREFERENCES_NAME,
                    Context.MODE_PRIVATE,
                ),
            ),
        )

        fun validate(url: String, publishableKey: String, avatarBucket: String? = null): SupabaseConfigValidation {
            val normalizedUrl = url.trim().trimEnd('/')
            val parsedUrl = normalizedUrl.toHttpUrlOrNull()
            if (parsedUrl == null || parsedUrl.scheme != "https" || parsedUrl.host.isBlank()) {
                return SupabaseConfigValidation.Invalid(SupabaseConfigError.INVALID_URL)
            }

            val normalizedKey = publishableKey.trim()
            if (normalizedKey.isBlank()) {
                return SupabaseConfigValidation.Invalid(SupabaseConfigError.MISSING_PUBLISHABLE_KEY)
            }
            if (isServiceRoleKey(normalizedKey)) {
                return SupabaseConfigValidation.Invalid(SupabaseConfigError.SERVICE_ROLE_KEY)
            }
            if (!isClientSafeKey(normalizedKey)) {
                return SupabaseConfigValidation.Invalid(SupabaseConfigError.INVALID_PUBLISHABLE_KEY)
            }
            val normalizedAvatarBucket = avatarBucket?.trim()?.takeIf(String::isNotEmpty)
            return SupabaseConfigValidation.Valid(SupabaseConfig(normalizedUrl, normalizedKey, normalizedAvatarBucket))
        }

        private fun isServiceRoleKey(key: String): Boolean {
            if (key.startsWith("sb_secret_", ignoreCase = true)) return true
            return jwtPayload(key)?.let { payload ->
                Regex("\\\"role\\\"\\s*:\\s*\\\"service_role\\\"").containsMatchIn(payload)
            } ?: false
        }

        private fun isClientSafeKey(key: String): Boolean {
            if (key.startsWith("sb_publishable_") && key.length > "sb_publishable_".length) return true
            return jwtPayload(key)?.let { payload ->
                Regex("\\\"role\\\"\\s*:\\s*\\\"anon\\\"").containsMatchIn(payload)
            } ?: false
        }

        private fun jwtPayload(key: String): String? {
            val payload = key.split('.').takeIf { it.size == 3 }?.get(1) ?: return null
            val paddedPayload = payload.replace('-', '+').replace('_', '/').let { value ->
                value + "=".repeat((4 - value.length % 4) % 4)
            }
            return paddedPayload.decodeBase64()?.utf8()
        }
    }
}

object SupabaseSettings {
    private lateinit var repository: SupabaseConfigRepository

    val config: SupabaseConfig?
        get() = requireRepository().activeConfig

    val isConfigured: Boolean
        get() = requireRepository().isConfigured

    fun initialize(context: Context) {
        repository = SupabaseConfigRepository.create(context)
    }

    fun validate(url: String, publishableKey: String, avatarBucket: String? = null): SupabaseConfigValidation =
        requireRepository().validate(url, publishableKey, avatarBucket)

    internal fun save(config: SupabaseConfig) = requireRepository().save(config)

    private fun requireRepository(): SupabaseConfigRepository {
        check(::repository.isInitialized) { "Supabase settings have not been initialized" }
        return repository
    }
}
