package com.streamflixrevanced.streamflix.providers

import android.util.Log
import com.streamflixrevanced.streamflix.adapters.AppAdapter
import com.streamflixrevanced.streamflix.extractors.Extractor
import com.streamflixrevanced.streamflix.models.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import com.streamflixrevanced.streamflix.utils.LiveChannelGrouping
import com.streamflixrevanced.streamflix.utils.TvLogoRepository
import java.util.concurrent.TimeUnit

class VavooProvider(override val language: String) : IptvProvider {

    companion object {
        private const val TAG = "VavooProvider"
        private const val CACHE_DURATION = 30 * 60 * 1000L
        private const val POSTER = "https://www.clipartmax.com/png/full/46-463028_television-images-clip-art.png"

        // Only the languages supported by the app
        private val LANG_CONFIG = mapOf(
            "de" to Triple("de", "DE", listOf("Germany", "GERMANY")),
            "it" to Triple("it", "IT", listOf("Italy")),
            "fr" to Triple("fr", "FR", listOf("France", "France Sport")),
            "es" to Triple("es", "ES", listOf("Spain")),
            "pl" to Triple("pl", "PL", listOf("Poland")),
            "ro" to Triple("ro", "RO", listOf("Romania"))
        )

        private val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        private val channelNames = java.util.concurrent.ConcurrentHashMap<String, String>()

        fun cachedChannelName(id: String): String? = channelNames[id]
    }

    override val baseUrl: String = "https://vavoo.to"

    private val CATALOG_URL = "$baseUrl/mediahubmx-catalog.json"
    private val RESOLVE_URL = "$baseUrl/mediahubmx-resolve.json"

    // Cache for home categories per language to avoid instant re-fetching
    private val homeCache = mutableMapOf<String, List<VavooChannel>>()
    private val cacheTimestamps = mutableMapOf<String, Long>()

    // Cache to temporarily map channel IDs to their names when found via search/genres
    private val searchCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val playbackFeeds = java.util.concurrent.ConcurrentHashMap<String, List<VavooChannel>>()

    data class VavooChannel(
        val id: String,
        val name: String,
        val url: String,
        val logo: String? = null,
        val programs: List<VavooProgram> = emptyList()
    ) {
        fun currentProgram(nowMillis: Long = System.currentTimeMillis()): VavooProgram? =
            programs.firstOrNull { program ->
                nowMillis >= program.start * 1000 && nowMillis < program.stop * 1000
            }

        fun nextProgram(nowMillis: Long = System.currentTimeMillis()): VavooProgram? =
            programs.firstOrNull { it.start * 1000 > nowMillis }
    }

    data class VavooProgram(
        val start: Long,
        val stop: Long,
        val title: String
    )

    // Config for this instance
    private val config = LANG_CONFIG[language] ?: LANG_CONFIG["de"]!!

    override val name: String = "Vavoo ${config.third.first()} Live TV"
    override val logo: String = "$baseUrl/assets/favicon-Djqjt9PL.ico"

    private val primaryGroups: List<String> = config.third

    private fun fetchChannels(search: String, group: String, cursor: Int? = null): Pair<List<VavooChannel>, Int?> {
        val filterObj = JSONObject().apply {
            put("group", group)
        }
        val body = JSONObject().apply {
            put("language", "de")
            put("region", "DE")
            put("catalogId", "iptv")
            put("id", "")
            put("adult", false)
            put("search", search)
            put("sort", "name")
            put("filter", filterObj)
            if (cursor != null) put("cursor", cursor) else put("cursor", JSONObject.NULL)
        }.toString()

        return try {
            val request = Request.Builder()
                .url(CATALOG_URL)
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Origin", baseUrl)
                .header("Referer", "$baseUrl/")
                .build()

            val response = client.newCall(request).execute()
            val json = JSONObject(response.body?.string() ?: return Pair(emptyList(), null))
            val items = json.optJSONArray("items") ?: return Pair(emptyList(), null)
            val nextCursor = if (json.isNull("nextCursor")) null else json.optInt("nextCursor")

            val channels = (0 until items.length()).mapNotNull { i ->
                val item = items.getJSONObject(i)
                val url = item.optString("url").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val name = item.optString("name").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val programs = item.optJSONArray("epg")?.let { epg ->
                    (0 until epg.length()).mapNotNull { index ->
                        epg.optJSONObject(index)?.let { program ->
                            val title = program.optString("name").takeIf { it.isNotBlank() }
                                ?: return@let null
                            VavooProgram(
                                start = program.optLong("start"),
                                stop = program.optLong("stop"),
                                title = title
                            )
                        }
                    }
                } ?: emptyList()
                val id = item.optJSONObject("ids")?.optString("id") ?: url
                channelNames[id] = name
                VavooChannel(
                    id = id,
                    name = name,
                    url = url,
                    logo = channelLogo(item, name),
                    programs = programs
                )
            }
            Pair(channels, nextCursor)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching channels (search='$search', group='$group'): ${e.message}")
            Pair(emptyList(), null)
        }
    }

    private fun loadHomeGroupChannels(group: String): List<VavooChannel> {
        val now = System.currentTimeMillis()
        val cached = homeCache[group]
        if (cached != null && (now - (cacheTimestamps[group] ?: 0)) < CACHE_DURATION) {
            return cached
        }
        val (channels, _) = fetchChannels("", group)
        if (channels.isNotEmpty()) {
            homeCache[group] = channels
            cacheTimestamps[group] = now
        }
        return channels
    }

    private fun fetchAll(group: String): List<VavooChannel> {
        val result = mutableListOf<VavooChannel>()
        val visitedCursors = mutableSetOf<Int?>()
        var cursor: Int? = null

        while (visitedCursors.add(cursor)) {
            val (channels, nextCursor) = fetchChannels("", group, cursor)
            result += channels
            if (channels.isEmpty() || nextCursor == null || nextCursor == cursor) break
            cursor = nextCursor
        }

        return result
    }

    private fun channelLogo(item: JSONObject, channelName: String): String? {
        val images = item.optJSONObject("images")
        val raw = sequenceOf(
            item.optString("logo"),
            item.optString("icon"),
            item.optString("image"),
            item.optString("poster"),
            item.optString("thumbnail"),
            images?.optString("logo"),
            images?.optString("icon"),
        ).firstOrNull { !it.isNullOrBlank() }

        if (raw == null) return TvLogoRepository.url(channelName, language)

        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> baseUrl + raw
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            else -> "$baseUrl/$raw"
        }
    }

    private fun VavooChannel.toTvShow(): TvShow {
        val current = currentProgram()
        val next = nextProgram()
        val displayTitle = current?.title?.let { "$name  •  Now: $it" } ?: name

        val epgOverview = buildString {
            current?.let { append("Now: ${it.title}") }
            next?.let {
                if (isNotEmpty()) append("\n")
                append("Next: ${it.title}")
            }
        }

        return TvShow(
            id = id,
            title = displayTitle,
            poster = logo ?: POSTER,
            banner = logo ?: POSTER,
            overview = epgOverview.ifBlank { "Vavoo Live IPTV Stream" },
            currentProgram = current?.title
        )
    }

    private fun VavooChannel.toLiveChannel(): LiveChannel {
        val current = currentProgram()
        val next = nextProgram()
        val progress = current?.let {
            val duration = (it.stop - it.start).coerceAtLeast(1)
            val elapsed = (System.currentTimeMillis() / 1000L - it.start).coerceAtLeast(0)
            (elapsed * 100 / duration).toInt().coerceIn(0, 100)
        }

        fun VavooProgram.toLiveProgram() = LiveProgram(
            start = start,
            stop = stop,
            title = title,
        )

        return LiveChannel(
            id = id,
            name = name,
            logo = logo,
            streamUrl = url,
            currentProgram = current?.toLiveProgram(),
            nextProgram = next?.toLiveProgram(),
            progressPercent = progress,
            providerName = this@VavooProvider.name,
        )
    }

    private fun groupPlaybackFeeds(channels: List<VavooChannel>): List<LiveChannel> {
        return channels
            .distinctBy { it.id }
            .groupBy { TvLogoRepository.playbackIdentity(it.name) }
            .values
            .map { matchingFeeds ->
                val orderedFeeds = matchingFeeds.sortedWith(
                    compareBy<VavooChannel> { TvLogoRepository.isBackup(it.name) }
                        .thenBy { TvLogoRepository.isHdPlus(it.name) }
                        .thenBy { TvLogoRepository.sourceVariant(it.name) != null }
                        .thenBy { TvLogoRepository.sourceVariant(it.name).orEmpty() }
                        .thenBy { it.id }
                )
                orderedFeeds.forEach { feed -> playbackFeeds[feed.id] = orderedFeeds }
                orderedFeeds.first().toLiveChannel().copy(
                    name = TvLogoRepository.playbackGroupName(orderedFeeds.first().name),
                )
            }
    }

    private fun serverName(feed: VavooChannel, position: Int): String {
        val variant = TvLogoRepository.sourceVariant(feed.name)
        val baseName = when {
            variant != null -> "Vavoo $variant"
            position == 0 -> "Vavoo"
            else -> "Vavoo ${position + 1}"
        }
        val labels = buildList {
            if (TvLogoRepository.isHdPlus(feed.name)) add("HD+")
            if (TvLogoRepository.isBackup(feed.name)) add("Backup")
        }
        return if (labels.isEmpty()) baseName else "$baseName (${labels.joinToString()})"
    }

    private fun feedsForPlayback(id: String): List<VavooChannel> {
        playbackFeeds[id]?.let { return it }

        val cachedChannels = homeCache.values.flatten()
        groupPlaybackFeeds(cachedChannels)
        playbackFeeds[id]?.let { return it }

        // Favorites can be opened directly after an app restart, before any
        // channel screen has populated the feed map.
        groupPlaybackFeeds(primaryGroups.flatMap(::fetchAll))
        return playbackFeeds[id].orEmpty()
    }

    data class ResolvedChannel(val name: String, val url: String)

    private fun resolveChannel(vavooUrl: String): ResolvedChannel? {
        val body = JSONObject().apply {
            put("language", "de")
            put("region", "DE")
            put("url", vavooUrl)
        }.toString()
        return try {
            val request = Request.Builder()
                .url(RESOLVE_URL)
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Origin", baseUrl)
                .header("Referer", "$baseUrl/")
                .build()
            val response = client.newCall(request).execute()
            val jsonArray = org.json.JSONArray(response.body?.string() ?: return null)
            if (jsonArray.length() > 0) {
                val obj = jsonArray.getJSONObject(0)
                val url = obj.optString("url").takeIf { it.isNotEmpty() } ?: return null
                val name = obj.optString("name")
                ResolvedChannel(name = name, url = url)
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Resolve error for $vavooUrl: ${e.message}")
            null
        }
    }

    override suspend fun getHome(): List<Category> {
        val channelsByProviderGroup = primaryGroups.associateWith { providerGroup ->
            groupPlaybackFeeds(loadHomeGroupChannels(providerGroup).take(300))
        }
        val channels = channelsByProviderGroup.values
            .flatten()
            .distinctBy { it.id }
        val configuredGroups = LiveChannelGrouping.group(channels, language)

        if (configuredGroups.isNotEmpty()) {
            return configuredGroups.map { group ->
                Category(
                    name = group.name,
                    list = group.channels.take(300),
                    stableKey = group.id,
                )
            }
        }

        return channelsByProviderGroup.map { (providerGroup, providerChannels) ->
            Category(
                name = "Vavoo $providerGroup Live TV",
                list = providerChannels,
                stableKey = "vavoo-provider-group:$providerGroup",
            )
        }
    }

    override suspend fun getLiveChannels(page: Int): List<LiveChannel> {
        return primaryGroups
            .flatMap(::fetchAll)
            .let(::groupPlaybackFeeds)
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        val cursor = if (page > 1) (page - 1) * 300 else null
        val channels = primaryGroups.flatMap { group -> fetchChannels(query, group, cursor).first }
        return groupPlaybackFeeds(channels).onEach { channel ->
            searchCache[channel.id] = channel.name
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()

    override suspend fun getTvShows(page: Int): List<TvShow> = emptyList()

    override suspend fun getMovieFromProvider(id: String): Movie = Movie(id = id, title = "Live", poster = "")

    override suspend fun getTvShowFromProvider(id: String): TvShow {
        // 1. Try in-memory caches first (fast path)
        var cachedChannel: VavooChannel? = null
        var cachedName: String? = null
        for (group in primaryGroups) {
            val found = homeCache[group]?.find { it.id == id }
            if (found != null) {
                cachedChannel = found
                cachedName = found.name
                break
            }
        }
        if (cachedName == null) {
            cachedName = searchCache[id]
        }

        // Reload the catalog when the app was restarted and the in-memory
        // channel cache does not contain this channel yet. The catalog carries
        // the EPG data; the resolver only returns playback information.
        if (cachedChannel == null) {
            for (group in primaryGroups) {
                cachedChannel = loadHomeGroupChannels(group).find { it.id == id }
                if (cachedChannel != null) break
            }
            cachedChannel?.let {
                cachedName = it.name
                searchCache[id] = it.name
            }
        }

        // 2. If not found (e.g. after app restart), call the resolve API:
        //    the response contains the real channel name on the "name" field
        val title = cachedName ?: run {
            val vavooUrl = "$baseUrl/vavoo-iptv/play/$id"
            val resolved = resolveChannel(vavooUrl)
            if (resolved != null && resolved.name.isNotEmpty()) {
                searchCache[id] = resolved.name
                resolved.name
            } else {
                id
            }
        }

        return TvShow(
            id = id,
            title = TvLogoRepository.playbackGroupName(cachedChannel?.name ?: title),
            poster = cachedChannel?.logo ?: POSTER,
            banner = cachedChannel?.logo ?: POSTER,
            overview = cachedChannel?.toTvShow()?.overview ?: "Vavoo Live IPTV Stream",
            seasons = listOf(Season(id = id, number = 1, title = "Watch")),
            currentProgram = cachedChannel?.currentProgram()?.title
        )
    }

    override suspend fun getEpisodesByProvider(seasonId: String): List<Episode> {
        val channel = homeCache.values
            .asSequence()
            .flatten()
            .firstOrNull { it.id == seasonId }
        val currentTitle = channel?.currentProgram()?.title

        return listOf(
            Episode(
                id = seasonId,
                number = 1,
                title = currentTitle?.let { "Now: $it" } ?: "Watch Now",
                overview = channel?.nextProgram()?.let { "Next: ${it.title}" },
                season = null
            )
        )
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        return Genre(id = id, name = id)
    }

    override suspend fun getPeople(id: String, page: Int): People {
        return People(id = id, name = "Vavoo", image = logo, biography = "", birthday = "", deathday = "", placeOfBirth = "")
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val feeds = feedsForPlayback(id)
        if (feeds.isEmpty()) return listOf(Video.Server(id = id, name = "Vavoo"))
        val names = feeds.mapIndexed { index, feed -> serverName(feed, index) }
        val totals = names.groupingBy { it }.eachCount()
        val occurrences = mutableMapOf<String, Int>()
        return feeds.mapIndexed { index, feed ->
            val baseName = names[index]
            val occurrence = (occurrences[baseName] ?: 0) + 1
            occurrences[baseName] = occurrence
            val displayName = if ((totals[baseName] ?: 0) > 1) "$baseName #$occurrence" else baseName
            Video.Server(id = feed.id, name = displayName)
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val vavooUrl = if (server.id.startsWith("http")) server.id else "$baseUrl/vavoo-iptv/play/${server.id}"
        Log.d(TAG, "[$language] Resolving: $vavooUrl")
        val resolved = resolveChannel(vavooUrl)
            ?: throw Exception("Vavoo: could not resolve stream URL for $vavooUrl")
        Log.d(TAG, "[$language] Playing: ${resolved.url}")
        return Video(source = resolved.url, subtitles = emptyList())
    }
}

