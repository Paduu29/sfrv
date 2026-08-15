package com.streamflixrevanced.streamflix.extractors

import androidx.media3.common.MimeTypes
import com.streamflixrevanced.streamflix.models.Video
import com.streamflixrevanced.streamflix.utils.NetworkClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.decodeBase64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

class VideasyExtractor : Extractor() {
    override val name = "Videasy"
    override val mainUrl = "https://api.speedracelight.com"
    override val aliasUrls = listOf(
        "https://api.videasy.net",
        "https://api.videasy.to",
    )

    data class ServerConfig(
        val name: String,
        val endpoint: String,
        val enabled: Boolean = true,
    )

    private val englishServers = listOf(
        ServerConfig("Yoru", "cdn", enabled = false),
        ServerConfig("Breach", "m4uhd"),
        ServerConfig("Neon", "neon2", enabled = false),
        ServerConfig("Vyse", "hdmovie"),
    )

    fun servers(videoType: Video.Type, language: String): List<Video.Server> {
        val configs = when (language) {
            "en" -> englishServers
            "de" -> listOf(ServerConfig("Killjoy", "meine"))
            else -> return emptyList()
        }

        return configs.filter(ServerConfig::enabled).map { config ->
            val url = mainUrl.toHttpUrl().newBuilder()
                .addPathSegment(config.endpoint)
                .addPathSegment("sources-with-title")
                .apply {
                    when (videoType) {
                        is Video.Type.Movie -> {
                            addQueryParameter("title", videoType.title)
                            addQueryParameter("mediaType", "movie")
                            addQueryParameter("year", videoType.releaseDate.substringBefore("-"))
                            addQueryParameter("tmdbId", videoType.id)
                            addQueryParameter("imdbId", videoType.imdbId.orEmpty())
                        }

                        is Video.Type.Episode -> {
                            addQueryParameter("title", videoType.tvShow.title)
                            addQueryParameter("mediaType", "tv")
                            addQueryParameter(
                                "year",
                                videoType.tvShow.releaseDate?.substringBefore("-").orEmpty(),
                            )
                            addQueryParameter("tmdbId", videoType.tvShow.id)
                            addQueryParameter("imdbId", videoType.tvShow.imdbId.orEmpty())
                            addQueryParameter("episodeId", videoType.number.toString())
                            addQueryParameter("seasonId", videoType.season.number.toString())
                        }
                    }
                    if (language == "de") addQueryParameter("language", "german")
                }
                .build()

            Video.Server(
                id = "${config.name} (Videasy)",
                name = "${config.name} (Videasy)",
                src = url.toString(),
            )
        }
    }

    fun server(videoType: Video.Type, language: String): Video.Server? =
        servers(videoType, language).firstOrNull()

    override suspend fun extract(link: String): Video {
        val sourceUrl = normalizeLink(link)
        val mediaId = sourceUrl.queryParameter("tmdbId")
            ?.toIntOrNull()
            ?: throw Exception("Videasy link has no valid TMDB ID")

        var lastError: Exception? = null
        repeat(2) {
            try {
                val seed = fetchSeed(mediaId)
                val encrypted = fetchEncrypted(sourceUrl.newBuilder()
                    .setQueryParameter("enc", "2")
                    .setQueryParameter("seed", seed)
                    .build()
                    .toString())
                return parseVideo(
                    VideasyDecoder.decode(encrypted, seed, mediaId),
                    sourceUrl.toString(),
                    seed,
                    mediaId,
                )
            } catch (error: UnauthorizedSeedException) {
                lastError = error
            }
        }

        throw lastError ?: Exception("Failed to extract Videasy stream")
    }

    internal fun normalizeLink(link: String): okhttp3.HttpUrl {
        val url = link.toHttpUrl()
        if (url.host in PLAYER_HOSTS) return playerApiUrl(url)
        if (url.host !in LEGACY_API_HOSTS) return url

        val endpoint = url.pathSegments.firstOrNull().orEmpty()
        val currentEndpoint = LEGACY_ENDPOINTS[endpoint] ?: endpoint
        return url.newBuilder()
            .scheme("https")
            .host(mainUrl.toHttpUrl().host)
            .port(443)
            .apply {
                setPathSegment(0, currentEndpoint)
                removeAllQueryParameters("enc")
                removeAllQueryParameters("seed")
            }
            .build()
    }

    private fun playerApiUrl(playerUrl: okhttp3.HttpUrl): okhttp3.HttpUrl {
        val mediaType = playerUrl.pathSegments.getOrNull(0)?.lowercase()
        val mediaId = playerUrl.pathSegments.getOrNull(1)
            ?.takeIf { it.toIntOrNull() != null }
            ?: throw Exception("Videasy player link has no valid TMDB ID")
        if (mediaType != "movie" && mediaType != "tv") {
            throw Exception("Unsupported Videasy media type: $mediaType")
        }

        return mainUrl.toHttpUrl().newBuilder()
            .addPathSegment(DEFAULT_PLAYER_ENDPOINT)
            .addPathSegment("sources-with-title")
            .addQueryParameter("mediaType", mediaType)
            .addQueryParameter("tmdbId", mediaId)
            .apply {
                if (mediaType == "tv") {
                    val season = playerUrl.pathSegments.getOrNull(2)
                        ?: throw Exception("Videasy TV link has no season")
                    val episode = playerUrl.pathSegments.getOrNull(3)
                        ?: throw Exception("Videasy TV link has no episode")
                    addQueryParameter("seasonId", season)
                    addQueryParameter("episodeId", episode)
                }
            }
            .build()
    }

    private fun fetchSeed(mediaId: Int): String {
        val url = "$mainUrl/seed".toHttpUrl().newBuilder()
            .addQueryParameter("mediaId", mediaId.toString())
            .build()
        val body = execute(url.toString())
        return JSONObject(body).optString("seed")
            .takeIf(String::isNotBlank)
            ?: throw Exception("Videasy returned an empty seed")
    }

    private fun fetchEncrypted(url: String): String = execute(url, retryUnauthorized = true)

    private fun execute(url: String, retryUnauthorized: Boolean = false): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "*/*")
            .header("Origin", PLAYER_ORIGIN)
            .header("Referer", "$PLAYER_ORIGIN/")
            .header("User-Agent", NetworkClient.USER_AGENT)
            .build()

        return NetworkClient.default.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (retryUnauthorized && response.code == 401) throw UnauthorizedSeedException()
            if (!response.isSuccessful) {
                throw Exception("Videasy request failed with HTTP ${response.code}")
            }
            body.takeIf(String::isNotBlank) ?: throw Exception("Videasy returned an empty response")
        }
    }

    private fun parseVideo(json: String, link: String, seed: String, mediaId: Int): Video {
        val result = JSONObject(json)
        val sources = result.optJSONArray("sources") ?: JSONArray()
        val candidates = (0 until sources.length()).mapNotNull { index ->
            sources.optJSONObject(index)?.takeIf { sourceAvailable(it) }
        }

        val endpoint = link.toHttpUrl().pathSegments.firstOrNull()
        val config = englishServers.find { endpoint == it.endpoint }
        val filtered = when (config?.name) {
            "Neon" -> candidates.filter { source ->
                source.optString("type").equals("dash", true) ||
                    sourceUrl(source).substringBefore('?').endsWith(".mpd", true)
            }.ifEmpty { candidates }
            "Vyse" -> candidates.filter { it.optString("quality").equals("English", true) }
                .ifEmpty { candidates }
            else -> candidates
        }
        val source = candidates.firstOrNull { it.optBoolean("selected") }
            ?: filtered.firstOrNull { it.optString("data").isNotBlank() }
            ?: filtered.firstOrNull { sourceUrl(it).isNotBlank() }
            ?: filtered.firstOrNull()
            ?: throw Exception("No Videasy video source found")
        val videoUrl = resolveSourceUrl(source, seed, mediaId)

        val subtitles = result.optJSONArray("subtitles")?.let { tracks ->
            (0 until tracks.length()).mapNotNull { index ->
                val track = tracks.optJSONObject(index) ?: return@mapNotNull null
                val url = track.optString("url").ifBlank { track.optString("file") }
                if (url.isBlank()) return@mapNotNull null
                Video.Subtitle(
                    label = track.optString("language")
                        .ifBlank { track.optString("lang") }
                        .ifBlank { "Unknown" },
                    file = url,
                    // Breach currently names these files .vtt, but serves SubRip content.
                    mimeType = if (endpoint == DEFAULT_PLAYER_ENDPOINT) {
                        MimeTypes.APPLICATION_SUBRIP
                    } else {
                        null
                    },
                )
            }
        }.orEmpty()

        return Video(
            source = videoUrl,
            type = mimeType(videoUrl, source.optString("type")),
            subtitles = subtitles,
            headers = mapOf(
                "Origin" to PLAYER_ORIGIN,
                "Referer" to "$PLAYER_ORIGIN/",
                "User-Agent" to NetworkClient.USER_AGENT,
                "X-Requested-With" to "XMLHttpRequest",
            ),
        )
    }

    private fun sourceUrl(source: JSONObject): String =
        source.optString("url").ifBlank { source.optString("file") }

    private fun sourceAvailable(source: JSONObject): Boolean =
        source.optString("data").isNotBlank() || sourceUrl(source).isNotBlank()

    private fun resolveSourceUrl(source: JSONObject, seed: String, mediaId: Int): String {
        val data = source.optString("data")
        if (data.isNotBlank()) {
            val endpoint = "$PLAYER_ORIGIN/$STREAM_PATH/$data"
            val body = executeStream(endpoint)
            return runCatching { parseStreamBody(body, seed, mediaId) }
                .getOrElse { endpoint }
        }
        return sourceUrl(source)
    }

    private fun executeStream(url: String): String {
        val request = Request.Builder()
            .url(url)
            .post(ByteArray(0).toRequestBody())
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "*/*")
            .header("Origin", PLAYER_ORIGIN)
            .header("Referer", "$PLAYER_ORIGIN/")
            .header("User-Agent", NetworkClient.USER_AGENT)
            .build()

        return NetworkClient.default.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw Exception("Videasy stream request failed with HTTP ${response.code}")
            }
            body.takeIf(String::isNotBlank) ?: throw Exception("Videasy returned an empty stream response")
        }
    }

    private fun parseStreamBody(body: String, seed: String, mediaId: Int): String {
        val trimmed = body.trim().trim('"')
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed
        }
        runCatching { JSONObject(trimmed) }.getOrNull()?.let { json ->
            urlFromJson(json)?.let { return it }
        }
        runCatching { VideasyDecoder.decode(body, seed, mediaId) }
            .getOrNull()
            ?.let { decoded -> parseDecodedStream(decoded) }
            ?.let { return it }
        throw Exception("Videasy stream response could not be resolved to a playable URL")
    }

    private fun parseDecodedStream(decoded: String): String? {
        val trimmed = decoded.trim().trim('"')
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        return runCatching { JSONObject(trimmed) }.getOrNull()?.let { urlFromJson(it) }
    }

    private fun urlFromJson(json: JSONObject): String? {
        var fallback: String? = null
        fun scan(obj: JSONObject): String? {
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                when (val value = obj.opt(key)) {
                    is String -> {
                        if (value.startsWith("http://") || value.startsWith("https://")) {
                            if (key in STREAM_URL_KEYS) return value
                            if (fallback == null) fallback = value
                        }
                    }

                    is JSONObject -> scan(value)?.let { return it }
                    is JSONArray -> for (index in 0 until value.length()) {
                        (value.opt(index) as? JSONObject)?.let { scan(it)?.let { url -> return url } }
                    }

                    else -> Unit
                }
            }
            return null
        }
        return scan(json) ?: fallback
    }

    private fun mimeType(url: String, declaredType: String): String = when {
        declaredType.equals("dash", true) || url.substringBefore('?').endsWith(".mpd", true) ->
            MimeTypes.APPLICATION_MPD
        declaredType.equals("mp4", true) || url.substringBefore('?').endsWith(".mp4", true) ->
            MimeTypes.VIDEO_MP4
        else -> MimeTypes.APPLICATION_M3U8
    }

    private class UnauthorizedSeedException : Exception("Videasy seed expired")

    private companion object {
        const val PLAYER_ORIGIN = "https://player.videasy.to"
        val LEGACY_API_HOSTS = setOf("api.videasy.net", "api.videasy.to")
        val PLAYER_HOSTS = setOf("player.videasy.net", "player.videasy.to")
        const val DEFAULT_PLAYER_ENDPOINT = "m4uhd"
        const val STREAM_PATH = "ffff1f6738309ae837ebfa1cc8cdde9390c88177/ilaif/lwUXbKKAnnM"
        val STREAM_URL_KEYS = setOf(
            "url",
            "file",
            "source",
            "src",
            "location",
            "link",
            "stream",
            "playback",
            "data",
        )
        val LEGACY_ENDPOINTS = mapOf(
            "mb-flix" to "neon2",
            "downloader2" to "neon2",
            "1movies" to "m4uhd",
        )
    }
}

internal object VideasyDecoder {
    private val magic = byteArrayOf(109, 118, 109, 49) // "mvm1"

    fun decode(payload: String, seed: String, mediaId: Int): String {
        val normalized = payload.trim().replace('-', '+').replace('_', '/')
            .padEnd(((payload.trim().length + 3) / 4) * 4, '=')
        val encrypted = normalized.decodeBase64()?.toByteArray()
            ?: throw Exception("Videasy returned invalid base64")
        val keyStream = keyStream(seed, mediaId, encrypted.size)
        val decrypted = ByteArray(encrypted.size) { index ->
            (encrypted[index].toInt() xor keyStream[index].toInt()).toByte()
        }
        if (decrypted.size < magic.size || !decrypted.copyOfRange(0, magic.size).contentEquals(magic)) {
            throw Exception("Videasy decryption failed: bad seed or payload")
        }
        return String(decrypted, magic.size, decrypted.size - magic.size, StandardCharsets.UTF_8)
    }

    private fun keyStream(seed: String, mediaId: Int, size: Int): ByteArray {
        val slots = IntArray(61)
        val assigned = BooleanArray(61)
        var state = mix32(fnv1a(seed) xor mix32(mediaId xor GOLDEN_RATIO))

        repeat(8) { index ->
            val slot = Integer.remainderUnsigned(state, slots.size)
            state = Integer.rotateLeft(state + GOLDEN_RATIO, 7 + (index and 7))
            slots[slot] = state xor mix32(state)
            assigned[slot] = true
            state = mix32(state + slot)
        }

        var accumulator = mix32(0xa5a5a5a5.toInt() xor state)
        var counter = 0
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val slot = Integer.remainderUnsigned(accumulator, slots.size)
            val mixed = slots[slot] xor (GOLDEN_RATIO * (counter + 1))
            var value = if (assigned[slot]) accumulator or mixed else accumulator xor mixed
            value = Integer.rotateLeft(value + accumulator, slot and 31) xor
                Integer.rotateLeft(accumulator, (slot * 7) and 31)
            accumulator = mix32(value + GOLDEN_RATIO)
            slots[slot] = accumulator
            assigned[slot] = true

            result[offset++] = accumulator.toByte()
            if (offset < size) result[offset++] = (accumulator ushr 8).toByte()
            if (offset < size) result[offset++] = (accumulator ushr 16).toByte()
            if (offset < size) result[offset++] = (accumulator ushr 24).toByte()
            counter++
        }
        return result
    }

    private fun fnv1a(value: String): Int {
        var hash = 0x811c9dc5.toInt()
        value.forEach { hash = (hash xor it.code) * 16777619 }
        return mix32(hash)
    }

    private fun mix32(value: Int): Int {
        var mixed = value xor (value ushr 16)
        mixed *= -2048144789 // 0x85ebca6b
        mixed = mixed xor (mixed ushr 13)
        mixed *= -1028477387 // 0xc2b2ae35
        return mixed xor (mixed ushr 16)
    }

    private const val GOLDEN_RATIO = -1640531527 // 0x9e3779b9
}
