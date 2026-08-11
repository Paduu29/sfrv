package com.streamflixrevanced.streamflix.utils

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.streamflixrevanced.streamflix.adapters.AppAdapter
import com.streamflixrevanced.streamflix.models.Category
import com.streamflixrevanced.streamflix.models.Episode
import com.streamflixrevanced.streamflix.models.Movie
import com.streamflixrevanced.streamflix.models.LiveChannel
import com.streamflixrevanced.streamflix.models.LiveProgram
import com.streamflixrevanced.streamflix.models.Season
import com.streamflixrevanced.streamflix.models.TvShow
import com.streamflixrevanced.streamflix.models.WatchItem
import com.streamflixrevanced.streamflix.providers.Provider
import com.streamflixrevanced.streamflix.providers.ObejrzyjProvider
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object HomeCacheStore {
    private val gson = Gson()
    private val memoryCache = ConcurrentHashMap<String, List<CachedCategory>>()
    private val cacheUpdates = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val updates: SharedFlow<String> = cacheUpdates.asSharedFlow()

    fun isUpdateFor(updateKey: String, provider: Provider): Boolean =
        updateKey == cacheKey(provider)

    fun read(context: Context, provider: Provider): List<Category>? {
        val cacheKey = cacheKey(provider)
        memoryCache[cacheKey]?.let { payload ->
            return payload.toCategories()
        }

        val file = cacheFile(context, cacheKey)
        if (file.exists()) {
            return readFromDisk(cacheKey, file)
        }

        // Migration: pick up old profile-scoped cache files that used
        // "<provider>__<baseUrl>__<profileId>" keys.  The "default"
        // profile is the most common source of a warm cache.
        val migrated = migrateProfileScopedCache(context, provider, cacheKey)
        if (migrated != null) return migrated

        return null
    }

    private fun readFromDisk(cacheKey: String, file: java.io.File): List<Category>? {
        return runCatching {
            val type = object : TypeToken<List<CachedCategory>>() {}.type
            val payload: List<CachedCategory> = gson.fromJson(file.readText(), type)
            memoryCache[cacheKey] = payload
            payload.toCategories()
        }.recoverCatching {
            if (it is JsonSyntaxException) {
                memoryCache.remove(cacheKey)
                file.delete()
            }
            null
        }.getOrNull()
    }

    /**
     * Before the home cache was made profile-agnostic, each profile had
     * its own cache file keyed by "<provider>__<baseUrl>__<profileId>".
     * This method finds the "default" profile's file (the most likely
     * source of warm data) and migrates it to the new profile-agnostic
     * key so every profile benefits immediately.
     */
    private fun migrateProfileScopedCache(
        context: Context,
        provider: Provider,
        newCacheKey: String,
    ): List<Category>? {
        val baseUrlKey = provider.baseUrl.trim().trimEnd('/')
        val oldKey = buildList {
            add(provider.name)
            if (baseUrlKey.isNotEmpty()) add(baseUrlKey)
            add("default")
        }.joinToString("__")

        if (oldKey == newCacheKey) return null

        val oldFile = cacheFile(context, oldKey)
        if (!oldFile.exists()) return null

        val result = readFromDisk(newCacheKey, oldFile)
        if (result != null) {
            runCatching {
                val newFile = cacheFile(context, newCacheKey)
                newFile.parentFile?.mkdirs()
                oldFile.copyTo(newFile, overwrite = true)
                oldFile.delete()
            }
        }
        return result
    }

    fun readLegacy(context: Context, provider: Provider): List<Category>? {
        val legacyCacheKey = legacyCacheKey(provider)
        val file = cacheFile(context, legacyCacheKey)
        if (!file.exists()) return null

        return runCatching {
            val type = object : TypeToken<List<CachedCategory>>() {}.type
            val payload: List<CachedCategory> = gson.fromJson(file.readText(), type)
            payload.toCategories()
        }.getOrNull()
    }

    @Synchronized
    fun write(context: Context, provider: Provider, categories: List<Category>): Boolean {
        return runCatching {
            val cacheKey = cacheKey(provider)
            val incoming = categories.map { CachedCategory.from(it) }
            // A worker may refresh before Home has hydrated the in-memory
            // cache in this process. Load the persisted payload so an
            // equivalent refresh remains a true no-op across process starts.
            if (memoryCache[cacheKey] == null) {
                read(context, provider)
            }
            val cached = memoryCache[cacheKey].orEmpty()
            // Provider ordering is often non-deterministic (for example when
            // a scraper returns items in completion order). Keep the order of
            // content already on screen and append only genuinely new items.
            // This makes a refresh a content update instead of a focus-moving
            // reorder for TV users.
            val payload = preserveDisplayOrder(cached, incoming)
            if (cached == payload && cacheFile(context, cacheKey).exists()) {
                return@runCatching false
            }
            cacheFile(context, cacheKey).apply {
                parentFile?.mkdirs()
                writeText(gson.toJson(payload))
            }
            memoryCache[cacheKey] = payload
            cacheUpdates.tryEmit(cacheKey)
            true
        }.getOrDefault(false)
    }

    private fun preserveDisplayOrder(
        cached: List<CachedCategory>,
        incoming: List<CachedCategory>,
    ): List<CachedCategory> {
        val cachedByName = cached.associateBy { it.name }
        val incomingByName = incoming.associateBy { it.name }
        val orderedCategories = cached.mapNotNull { cachedCategory ->
            incomingByName[cachedCategory.name]?.let { freshCategory ->
                freshCategory.copy(list = preserveItemOrder(cachedCategory.list, freshCategory.list))
            }
        }
        val newCategories = incoming.filter { incomingCategory ->
            cachedByName[incomingCategory.name] == null
        }
        return orderedCategories + newCategories
    }

    private fun preserveItemOrder(
        cached: List<CachedItem>,
        incoming: List<CachedItem>,
    ): List<CachedItem> {
        val incomingByIdentity = incoming.associateBy { it.type to it.id }
        val orderedExisting = cached.mapNotNull { cachedItem ->
            incomingByIdentity[cachedItem.type to cachedItem.id]
        }
        val existingIdentities = cached.mapTo(HashSet(cached.size)) { it.type to it.id }
        return orderedExisting + incoming.filter {
            (it.type to it.id) !in existingIdentities
        }
    }

    fun clear(context: Context, provider: Provider) {
        val cacheKey = cacheKey(provider)
        memoryCache.remove(cacheKey)
        cacheFile(context, cacheKey).delete()
    }

    private fun cacheFile(context: Context, cacheKey: String): File {
        val safeName = cacheKey.replace(Regex("[^a-zA-Z0-9._-]+"), "_")
        return File(context.filesDir, "home-cache/$safeName.json")
    }

    /**
     * The home screen catalog (categories, items) is identical across all
     * profiles — only user-specific data (favorites, watch history) differs,
     * and that is merged separately from the Room database in
     * HomeViewModel.state. Scoping this cache per profile forced every new
     * profile to wait for a full provider.getHome() network request before
     * displaying anything.
     */
    private fun cacheKey(provider: Provider): String {
        val baseUrlKey = provider.baseUrl.trim().trimEnd('/')
        return buildList {
            add(provider.name)
            if (baseUrlKey.isNotEmpty()) {
                add(baseUrlKey)
            }
        }.joinToString("__")
            .let { key ->
                if (provider === ObejrzyjProvider) "${key}__featured-v2" else key
            }
    }

    private fun legacyCacheKey(provider: Provider): String {
        val baseUrlKey = provider.baseUrl.trim().trimEnd('/')
        return buildList {
            add(provider.name)
            if (baseUrlKey.isNotEmpty()) {
                add(baseUrlKey)
            }
        }.joinToString("__")
    }

    private fun List<CachedCategory>.toCategories(): List<Category> {
        return mapNotNull { it.toCategoryOrNull() }
    }

    private data class CachedCategory(
        val name: String,
        val list: List<CachedItem>,
    ) {
        fun toCategoryOrNull(): Category? {
            val items = list.mapNotNull { it.toItemOrNull() }
            return Category(name = name, list = items)
        }

        companion object {
            fun from(category: Category): CachedCategory {
                return CachedCategory(
                    name = category.name,
                    list = category.list.mapNotNull(CachedItem::from)
                )
            }
        }
    }

    private data class CachedItem(
        val type: String,
        val id: String,
        val title: String? = null,
        val overview: String? = null,
        val released: String? = null,
        val runtime: Int? = null,
        val trailer: String? = null,
        val quality: String? = null,
        val rating: Double? = null,
        val poster: String? = null,
        val banner: String? = null,
        val episodeNumber: Int? = null,
        val tvShowId: String? = null,
        val tvShowTitle: String? = null,
        val tvShowPoster: String? = null,
        val tvShowBanner: String? = null,
        val seasonId: String? = null,
        val seasonNumber: Int? = null,
        val seasonTitle: String? = null,
        val seasonPoster: String? = null,
        val lastPlaybackPositionMillis: Long? = null,
        val durationMillis: Long? = null,
        val lastEngagementTimeUtcMillis: Long? = null,
        val currentProgramStart: Long? = null,
        val currentProgramStop: Long? = null,
        val currentProgramTitle: String? = null,
        val nextProgramStart: Long? = null,
        val nextProgramStop: Long? = null,
        val nextProgramTitle: String? = null,
        val liveProgressPercent: Int? = null,
    ) {
        fun toItemOrNull(): AppAdapter.Item? {
            val watchHistory =
                if (
                    lastPlaybackPositionMillis != null &&
                    durationMillis != null &&
                    durationMillis > 0 &&
                    lastEngagementTimeUtcMillis != null
                ) {
                    WatchItem.WatchHistory(
                        lastEngagementTimeUtcMillis = lastEngagementTimeUtcMillis,
                        lastPlaybackPositionMillis = lastPlaybackPositionMillis,
                        durationMillis = durationMillis,
                    )
                } else null
            return when (type) {
                "movie" -> Movie(
                    id = id,
                    title = title.orEmpty(),
                    overview = overview,
                    released = released,
                    runtime = runtime,
                    trailer = trailer,
                    quality = quality,
                    rating = rating,
                    poster = poster,
                    banner = banner,
                ).apply {
                    this.watchHistory = watchHistory
                }

                "tv" -> TvShow(
                    id = id,
                    title = title.orEmpty(),
                    overview = overview,
                    released = released,
                    runtime = runtime,
                    trailer = trailer,
                    quality = quality,
                    rating = rating,
                    poster = poster,
                    banner = banner,
                )

                "episode" -> Episode(
                    id = id,
                    number = episodeNumber ?: 0,
                    title = title,
                    released = released,
                    poster = poster,
                    overview = overview,
                    tvShow = tvShowId?.let {
                        TvShow(
                            id = it,
                            title = tvShowTitle.orEmpty(),
                            poster = tvShowPoster,
                            banner = tvShowBanner,
                        )
                    },
                    season = seasonId?.let {
                        Season(
                            id = it,
                            number = seasonNumber ?: 0,
                            title = seasonTitle.orEmpty(),
                            poster = seasonPoster,
                        )
                    }
                ).apply {
                    this.watchHistory = watchHistory
                }

                "live" -> LiveChannel(
                    id = id,
                    name = title.orEmpty(),
                    logo = poster,
                    currentProgram = currentProgramTitle?.let { programTitle ->
                        LiveProgram(
                            start = currentProgramStart ?: 0L,
                            stop = currentProgramStop ?: 0L,
                            title = programTitle,
                        )
                    },
                    nextProgram = nextProgramTitle?.let { programTitle ->
                        LiveProgram(
                            start = nextProgramStart ?: 0L,
                            stop = nextProgramStop ?: 0L,
                            title = programTitle,
                        )
                    },
                    progressPercent = liveProgressPercent,
                )

                else -> null
            }
        }

        companion object {
            fun from(item: AppAdapter.Item): CachedItem? {
                return when (item) {
                    is Movie -> CachedItem(
                        type = "movie",
                        id = item.id,
                        title = item.title,
                        overview = item.overview,
                        released = item.released?.format("yyyy-MM-dd"),
                        runtime = item.runtime,
                        trailer = item.trailer,
                        quality = item.quality,
                        rating = item.rating,
                        poster = item.poster,
                        banner = item.banner,
                        lastPlaybackPositionMillis = item.watchHistory?.lastPlaybackPositionMillis,
                        durationMillis = item.watchHistory?.durationMillis,
                        lastEngagementTimeUtcMillis = item.watchHistory?.lastEngagementTimeUtcMillis,
                    )

                    is TvShow -> CachedItem(
                        type = "tv",
                        id = item.id,
                        title = item.title,
                        overview = item.overview,
                        released = item.released?.format("yyyy-MM-dd"),
                        runtime = item.runtime,
                        trailer = item.trailer,
                        quality = item.quality,
                        rating = item.rating,
                        poster = item.poster,
                        banner = item.banner,
                    )

                    is Episode -> CachedItem(
                        type = "episode",
                        id = item.id,
                        title = item.title,
                        overview = item.overview,
                        released = item.released?.format("yyyy-MM-dd"),
                        poster = item.poster,
                        episodeNumber = item.number,
                        tvShowId = item.tvShow?.id,
                        tvShowTitle = item.tvShow?.title,
                        tvShowPoster = item.tvShow?.poster,
                        tvShowBanner = item.tvShow?.banner,
                        seasonId = item.season?.id,
                        seasonNumber = item.season?.number,
                        seasonTitle = item.season?.title,
                        seasonPoster = item.season?.poster,
                        lastPlaybackPositionMillis = item.watchHistory?.lastPlaybackPositionMillis,
                        durationMillis = item.watchHistory?.durationMillis,
                        lastEngagementTimeUtcMillis = item.watchHistory?.lastEngagementTimeUtcMillis,
                    )

                    is LiveChannel -> CachedItem(
                        type = "live",
                        id = item.id,
                        title = item.name,
                        poster = item.logo,
                        banner = item.logo,
                        currentProgramStart = item.currentProgram?.start,
                        currentProgramStop = item.currentProgram?.stop,
                        currentProgramTitle = item.currentProgram?.title,
                        nextProgramStart = item.nextProgram?.start,
                        nextProgramStop = item.nextProgram?.stop,
                        nextProgramTitle = item.nextProgram?.title,
                        liveProgressPercent = item.progressPercent,
                    )

                    else -> null
                }
            }
        }
    }
}
