package com.streamflixrevanced.streamflix.providers

import android.util.Log
import com.streamflixrevanced.streamflix.adapters.AppAdapter
import com.streamflixrevanced.streamflix.models.*
import com.streamflixrevanced.streamflix.utils.LiveChannelGrouping
import com.streamflixrevanced.streamflix.utils.TvLogoRepository
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Live IPTV provider backed by huhu.to's mediaurl API. */
class HuhuProvider(override val language: String) : IptvProvider {

    companion object {
        private const val TAG = "HuhuProvider"
        private const val CACHE_DURATION = 30 * 60 * 1000L
        private const val POSTER = "https://www.clipartmax.com/png/full/46-463028_television-images-clip-art.png"
        private val GROUPS = mapOf(
            "de" to listOf("Germany"), "it" to listOf("Italy"),
            "fr" to listOf("France", "France Sport"), "es" to listOf("Spain"),
            "pl" to listOf("Poland"), "ro" to listOf("Romania"),
        )
        private val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
        private val channelNames = ConcurrentHashMap<String, String>()

        fun cachedChannelName(id: String): String? = channelNames[id]
    }

    override val baseUrl = "https://huhu.to"
    private val catalogUrl = "$baseUrl/mediaurl-catalog.json"
    private val resolveUrl = "$baseUrl/mediaurl-resolve.json"
    private val groups = GROUPS[language] ?: GROUPS.getValue("de")
    override val name = "Huhu ${groups.first()} Live TV"
    override val logo = "$baseUrl/assets/favicon-D1X6JocT.ico"

    private data class Channel(val id: String, val name: String, val url: String,
                               val logo: String?, val epg: List<Program>)
    private data class Program(val start: Long, val stop: Long, val title: String)
    private val homeCache = mutableMapOf<String, List<Channel>>()
    private val cacheTimes = mutableMapOf<String, Long>()
    private val searchCache = ConcurrentHashMap<String, String>()
    private val playbackFeeds = ConcurrentHashMap<String, List<Channel>>()

    private fun fetch(group: String, search: String = "", cursor: Int? = null): Pair<List<Channel>, Int?> {
        val filter = JSONObject().put("group", group)
        val body = JSONObject().apply {
            put("language", "de"); put("region", "DE"); put("catalogId", "iptv")
            put("id", ""); put("adult", false); put("search", search)
            put("sort", "trending-region"); put("filter", filter)
            put("cursor", cursor ?: JSONObject.NULL)
        }.toString()
        return try {
            val request = Request.Builder().url(catalogUrl)
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("User-Agent", "Mozilla/5.0")
                .header("Origin", baseUrl).header("Referer", "$baseUrl/").build()
            client.newCall(request).execute().use { response ->
                val json = JSONObject(response.body?.string() ?: return Pair(emptyList(), null))
                val items = json.optJSONArray("items") ?: return Pair(emptyList(), null)
                val channels = (0 until items.length()).mapNotNull { index ->
                    val item = items.optJSONObject(index) ?: return@mapNotNull null
                    val id = item.optJSONObject("ids")?.optString("id").orEmpty()
                    val url = item.optString("url")
                    val title = item.optString("name")
                    if (id.isBlank() || url.isBlank() || title.isBlank()) return@mapNotNull null
                    channelNames[id] = title
                    val epg = item.optJSONArray("epg")?.let { programs ->
                        (0 until programs.length()).mapNotNull { p ->
                            programs.optJSONObject(p)?.let { program ->
                                val name = program.optString("name").takeIf { it.isNotBlank() }
                                    ?: return@let null
                                Program(program.optLong("start"), program.optLong("stop"), name)
                            }
                        }
                    } ?: emptyList()
                    Channel(id, title, url, item.optString("logo").takeIf { it.isNotBlank() }, epg)
                }
                channels to if (json.isNull("nextCursor")) null else json.optInt("nextCursor")
            }
        } catch (error: Exception) {
            Log.e(TAG, "Could not fetch Huhu channels for $group", error)
            emptyList<Channel>() to null
        }
    }

    private fun home(group: String): List<Channel> {
        val now = System.currentTimeMillis()
        if (now - (cacheTimes[group] ?: 0) < CACHE_DURATION) return homeCache[group].orEmpty()
        val channels = fetch(group).first
        if (channels.isNotEmpty()) { homeCache[group] = channels; cacheTimes[group] = now }
        return channels
    }

    private fun fetchAll(group: String): List<Channel> {
        val result = mutableListOf<Channel>()
        val visitedCursors = mutableSetOf<Int?>()
        var cursor: Int? = null

        while (visitedCursors.add(cursor)) {
            val (channels, nextCursor) = fetch(group, cursor = cursor)
            result += channels
            if (channels.isEmpty() || nextCursor == null || nextCursor == cursor) break
            cursor = nextCursor
        }

        return result
    }

    private fun Channel.current() = epg.firstOrNull { it.start * 1000 <= System.currentTimeMillis() && it.stop * 1000 > System.currentTimeMillis() }
    private fun Channel.next() = epg.firstOrNull { it.start * 1000 > System.currentTimeMillis() }
    private fun Channel.toLive() = LiveChannel(
        id = id, name = name, logo = logo, streamUrl = url,
        currentProgram = current()?.let { LiveProgram(it.start, it.stop, it.title) },
        nextProgram = next()?.let { LiveProgram(it.start, it.stop, it.title) },
        progressPercent = current()?.let { ((System.currentTimeMillis() / 1000 - it.start) * 100 / (it.stop - it.start).coerceAtLeast(1)).toInt().coerceIn(0, 100) },
        providerName = this@HuhuProvider.name,
    )

    private fun groupPlaybackFeeds(channels: List<Channel>): List<LiveChannel> {
        return channels
            .distinctBy { it.id }
            .groupBy { TvLogoRepository.playbackIdentity(it.name) }
            .values
            .map { matchingFeeds ->
                val orderedFeeds = matchingFeeds.sortedWith(
                    compareBy<Channel> { TvLogoRepository.isBackup(it.name) }
                        .thenBy { TvLogoRepository.isHdPlus(it.name) }
                        .thenBy { TvLogoRepository.sourceVariant(it.name) != null }
                        .thenBy { TvLogoRepository.sourceVariant(it.name).orEmpty() }
                        .thenBy { it.id }
                )
                orderedFeeds.forEach { feed -> playbackFeeds[feed.id] = orderedFeeds }
                orderedFeeds.first().toLive().copy(
                    name = TvLogoRepository.playbackGroupName(orderedFeeds.first().name),
                )
            }
    }

    private fun serverName(feed: Channel, position: Int): String {
        val variant = TvLogoRepository.sourceVariant(feed.name)
        val baseName = when {
            variant != null -> "Huhu $variant"
            position == 0 -> "Huhu"
            else -> "Huhu ${position + 1}"
        }
        val labels = buildList {
            if (TvLogoRepository.isHdPlus(feed.name)) add("HD+")
            if (TvLogoRepository.isBackup(feed.name)) add("Backup")
        }
        return if (labels.isEmpty()) baseName else "$baseName (${labels.joinToString()})"
    }

    private fun feedsForPlayback(id: String): List<Channel> {
        playbackFeeds[id]?.let { return it }

        groupPlaybackFeeds(homeCache.values.flatten())
        playbackFeeds[id]?.let { return it }

        // Favorites can be opened directly after an app restart, before any
        // channel screen has populated the feed map.
        groupPlaybackFeeds(groups.flatMap(::fetchAll))
        return playbackFeeds[id].orEmpty()
    }

    private fun resolve(url: String): String? {
        return try {
            val body = JSONObject().put("language", "de").put("region", "DE").put("url", url).toString()
            val request = Request.Builder().url(resolveUrl)
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("User-Agent", "Mozilla/5.0").header("Origin", baseUrl)
                .header("Referer", "$baseUrl/").build()
            client.newCall(request).execute().use { response ->
                val value = response.body?.string() ?: return null
                val array = org.json.JSONArray(value)
                array.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Could not resolve Huhu stream", error); null
        }
    }

    override suspend fun getHome(): List<Category> {
        val channelsByProviderGroup = groups.associateWith { providerGroup ->
            groupPlaybackFeeds(home(providerGroup).take(300))
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
                name = "Huhu $providerGroup Live TV",
                list = providerChannels,
                stableKey = "huhu-provider-group:$providerGroup",
            )
        }
    }
    override suspend fun getLiveChannels(page: Int) = groups
        .flatMap(::fetchAll)
        .let(::groupPlaybackFeeds)
    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> = groupPlaybackFeeds(
        groups.flatMap { group ->
            fetch(group, query, if (page > 1) (page - 1) * 300 else null).first
        }
    ).onEach { channel -> searchCache[channel.id] = channel.name }
    override suspend fun getMovies(page: Int) = emptyList<Movie>()
    override suspend fun getTvShows(page: Int) = emptyList<TvShow>()
    override suspend fun getMovieFromProvider(id: String) = Movie(id, "Live", "")
    override suspend fun getTvShowFromProvider(id: String): TvShow {
        val channel = groups.asSequence()
            .map { group -> home(group).firstOrNull { it.id == id } }
            .filterNotNull()
            .firstOrNull()
        val title = TvLogoRepository.playbackGroupName(
            channel?.name ?: searchCache[id] ?: cachedChannelName(id) ?: id
        )
        channel?.let { searchCache[id] = it.name }
        return TvShow(
        id = id, title = title, poster = channel?.logo ?: POSTER, banner = channel?.logo ?: POSTER,
        overview = "Huhu Live IPTV Stream", seasons = listOf(Season(id, 1, "Watch")),
        )
    }
    override suspend fun getEpisodesByProvider(seasonId: String) = listOf(
        Episode(id = seasonId, number = 1, title = null)
    )
    override suspend fun getGenre(id: String, page: Int) = Genre(id, id)
    override suspend fun getPeople(id: String, page: Int) = People(id, "Huhu", logo, "", "", "", "")
    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val feeds = feedsForPlayback(id)
        if (feeds.isEmpty()) return listOf(Video.Server(id, "Huhu"))
        val names = feeds.mapIndexed { index, feed -> serverName(feed, index) }
        val totals = names.groupingBy { it }.eachCount()
        val occurrences = mutableMapOf<String, Int>()
        return feeds.mapIndexed { index, feed ->
            val baseName = names[index]
            val occurrence = (occurrences[baseName] ?: 0) + 1
            occurrences[baseName] = occurrence
            val displayName = if ((totals[baseName] ?: 0) > 1) "$baseName #$occurrence" else baseName
            Video.Server(feed.id, displayName)
        }
    }
    override suspend fun getVideo(server: Video.Server): Video {
        val url = if (server.id.startsWith("http")) server.id else "$baseUrl/huhu-iptv/play/${server.id}"
        return Video(source = resolve(url) ?: throw Exception("Huhu: could not resolve stream URL for $url"), subtitles = emptyList())
    }
}
