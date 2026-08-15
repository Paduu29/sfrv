package com.streamflixrevanced.streamflix.extractors

import android.util.Base64
import androidx.core.net.toUri
import androidx.media3.common.MimeTypes
import com.streamflixrevanced.streamflix.models.Video
import com.streamflixrevanced.streamflix.utils.DnsResolver
import com.streamflixrevanced.streamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class VidsrcMeExtractor : Extractor() {

    override val name = "Vidsrc.Me"
    override val mainUrl = "https://vidsrc.me"
    override val aliasUrls = listOf(
        "https://player.unlimitedfiles.xyz",
        "https://cloudorchestranova.com",
    )

    private companion object {
        const val API_BASE = "https://data.vidsrcme.ru"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        val client = OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .dns(DnsResolver.doh)
            .build()

        // Token cache per origin to avoid repeated generate.php calls (rate-limited)
        private val tokenCache = mutableMapOf<String, String>()

        val CHACHA_CONSTANTS = intArrayOf(
            0x61707865, 0x3320646e, 0x79622d32, 0x6b206574
        )

        // LEB encoding of the first ChaCha constant with its i32.const opcode and local.set
        val CHACHA_CORE_MARKER = byteArrayOf(
            0x41, 0xe5.toByte(), 0xf0.toByte(), 0xc1.toByte(), 0x8b.toByte(), 0x06, 0x21
        )
    }

    fun server(videoType: Video.Type): Video.Server {
        return Video.Server(
            id = name,
            name = name,
            src = when (videoType) {
                is Video.Type.Movie -> {
                    val id = videoType.imdbId ?: videoType.id
                    val idType = if (videoType.imdbId != null) "imdb" else "tmdb"
                    "$mainUrl/embed/movie?$idType=$id"
                }

                is Video.Type.Episode -> {
                    val id = videoType.tvShow.imdbId ?: videoType.tvShow.id
                    val idType = if (videoType.tvShow.imdbId != null) "imdb" else "tmdb"
                    "$mainUrl/embed/tv?$idType=$id&season=${videoType.season.number}&episode=${videoType.number}"
                }
            },
        )
    }

    override suspend fun extract(link: String): Video {
        val uri = link.toUri()
        val path = uri.path.orEmpty()
        val type = if (path.contains("movie")) "movie" else "tv"

        var imdb = uri.getQueryParameter("imdb")
        var tmdb = uri.getQueryParameter("tmdb")
        var season = uri.getQueryParameter("season")
        var episode = uri.getQueryParameter("episode")

        if (imdb == null && tmdb == null) {
            // path-style ids: /embed/{movie|tv}/{id}[/{season}/{episode}]
            val match = Regex("/embed/(?:movie|tv)/([^/]+)(?:/(\\d+)/(\\d+))?")
                .find(path)
            if (match != null) {
                val pathId = match.groupValues[1]
                if (pathId.startsWith("tt", ignoreCase = true)) {
                    imdb = pathId
                } else {
                    tmdb = pathId
                }
                season = match.groupValues[2].ifBlank { null }
                episode = match.groupValues[3].ifBlank { null }
            }
        }

        val id = imdb ?: tmdb ?: throw Exception("Can't retrieve Vidsrc.Me id")
        val idType = if (imdb != null) "imdb" else "tmdb"

        val apiUrl = buildString {
            append("$API_BASE/api.php?type=$type&$idType=$id")
            if (type == "tv") {
                if (season != null) append("&season=$season")
                if (episode != null) append("&episode=$episode")
            }
            append("&stream_urls")
        }

        val apiJson = withContext(Dispatchers.IO) {
            JSONObject(fetchString(apiUrl))
        }
        if (apiJson.optInt("status_code") != 200)
            throw Exception("Vidsrc.Me returned status ${apiJson.optInt("status_code")}")

        val data = apiJson.getJSONObject("data")
        val vs = apiJson.getJSONObject("vs")
        val wasmUrl = vs.getString("wasm_url")

        val wasm = withContext(Dispatchers.IO) { fetchBytes(wasmUrl) }
        val key = extractKey(wasm)

        val encrypted = Base64.decode(data.getString("stream_urls"), Base64.DEFAULT)
        if (encrypted.size <= 12) throw Exception("Vidsrc.Me encrypted payload too small")

        val nonce = encrypted.copyOfRange(0, 12)
        val ciphertext = encrypted.copyOfRange(12, encrypted.size)
        val plaintext = chacha20(key, nonce, ciphertext)

        val streamUrl = String(plaintext, Charsets.UTF_8)
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("http") }
            ?: throw Exception("No stream found after Vidsrc.Me decryption")

        // The CDN requires a short-lived, IP-bound token from its own /generate.php,
        // stamped on the master URL (the manifest's variant/segment URLs come back
        // pre-tokenized by the CDN).
        val streamUri = streamUrl.toUri()
        val origin = "${streamUri.scheme}://${streamUri.authority}"
        val token = withContext(Dispatchers.IO) {
            // Use cached token if available, otherwise fetch with one retry on 429
            tokenCache[origin]?.let { return@withContext it }
            var attempt = 0
            while (attempt < 2) {
                attempt++
                try {
                    val fetched = parseToken(fetchString("$origin/generate.php"))
                    if (fetched.isNotBlank()) {
                        tokenCache[origin] = fetched
                        return@withContext fetched
                    }
                } catch (e: Exception) {
                    if (attempt == 2 || e.message?.contains("429") != true) throw e
                    delay(500)
                }
            }
            ""
        }
        val finalUrl = if (token.isBlank()) {
            streamUrl
        } else if (streamUrl.contains("__TOKEN__")) {
            streamUrl.replace("__TOKEN__", token)
        } else {
            streamUrl + (if (streamUrl.contains("?")) "&" else "?") + "token=$token"
        }

        return Video(
            source = finalUrl,
            subtitles = emptyList(),
            headers = mapOf(
                "User-Agent" to NetworkClient.USER_AGENT,
            ),
            type = MimeTypes.APPLICATION_M3U8,
        )
    }

    private fun extractKey(wasm: ByteArray): ByteArray {
        val segments = parseDataSegments(wasm)
        val (keyAddrA, keyAddrB) = findKeyLoads(wasm)
        val partA = readMemory(segments, keyAddrA, 32)
        val partB = readMemory(segments, keyAddrB, 32)
        return ByteArray(32) { (partA[it].toInt() xor partB[it].toInt()).toByte() }
    }

    private fun parseDataSegments(wasm: ByteArray): List<Pair<Int, ByteArray>> {
        val segments = mutableListOf<Pair<Int, ByteArray>>()
        var off = 8
        while (off < wasm.size) {
            val sectionId = wasm[off].toInt() and 0xff
            off++
            val (size, next) = lebU(wasm, off)
            off = next
            if (sectionId == 11) {
                var p = off
                val (count, p1) = lebU(wasm, p)
                p = p1
                repeat(count) {
                    val (flags, p2) = lebU(wasm, p)
                    p = p2
                    when (flags) {
                        0 -> {
                            val (offset, p3) = readConstExpr(wasm, p)
                            val (segLen, p4) = lebU(wasm, p3)
                            segments.add(offset to wasm.copyOfRange(p4, p4 + segLen))
                            p = p4 + segLen
                        }
                        2 -> {
                            val (_, pMem) = lebU(wasm, p)
                            val (offset, p3) = readConstExpr(wasm, pMem)
                            val (segLen, p4) = lebU(wasm, p3)
                            segments.add(offset to wasm.copyOfRange(p4, p4 + segLen))
                            p = p4 + segLen
                        }
                        else -> throw Exception("Unsupported Vidsrc.Me wasm data flag: $flags")
                    }
                }
                return segments
            }
            off += size
        }
        throw Exception("No data section in Vidsrc.Me wasm")
    }

    private fun findKeyLoads(wasm: ByteArray): Pair<Int, Int> {
        var off = 8
        while (off < wasm.size) {
            val sectionId = wasm[off].toInt() and 0xff
            off++
            val (size, next) = lebU(wasm, off)
            off = next
            if (sectionId == 10) {
                val (count, p1) = lebU(wasm, off)
                var p = p1
                repeat(count) {
                    val (funcSize, q1) = lebU(wasm, p)
                    val bodyEnd = q1 + funcSize
                    var lc = q1
                    val (localGroups, q2) = lebU(wasm, lc)
                    lc = q2
                    repeat(localGroups) {
                        val (_, q3) = lebU(wasm, lc)
                        lc = q3
                        lc++ // valtype
                    }
                    val body = wasm.copyOfRange(lc, bodyEnd)
                    if (containsSubArray(body, CHACHA_CORE_MARKER)) {
                        val matches = scanKeyLoads(body)
                        if (matches.size >= 8) return Pair(matches[0].first, matches[0].second)
                    }
                    p = bodyEnd
                }
                throw Exception("Could not locate ChaCha core in Vidsrc.Me wasm")
            }
            off += size
        }
        throw Exception("No code section in Vidsrc.Me wasm")
    }

    private fun scanKeyLoads(body: ByteArray): List<Pair<Int, Int>> {
        val matches = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i < body.size - 20) {
            if (body[i].toInt() != 0x41) {
                i++
                continue
            }
            val a = runCatching { lebS(body, i + 1) }.getOrNull() ?: break
            val k = a.second
            if (k + 4 < body.size &&
                body[k] == 0x28.toByte() && body[k + 1] == 0x02.toByte() && body[k + 2] == 0x00.toByte() &&
                body[k + 3] == 0x41.toByte()
            ) {
                val b = runCatching { lebS(body, k + 4) }.getOrNull() ?: break
                val k2 = b.second
                if (k2 + 5 <= body.size &&
                    body[k2] == 0x28.toByte() && body[k2 + 1] == 0x02.toByte() && body[k2 + 2] == 0x00.toByte() &&
                    body[k2 + 3] == 0x73.toByte() && body[k2 + 4] == 0x21.toByte()
                ) {
                    matches.add(Pair(a.first, b.first))
                    i = k2 + 5
                    continue
                }
            }
            i++
        }
        return if (matches.size >= 8 &&
            matches.indices.all { j ->
                matches[j].first == matches[0].first + 4 * j &&
                    matches[j].second == matches[0].second + 4 * j
            }
        ) matches else emptyList()
    }

    private fun containsSubArray(body: ByteArray, needle: ByteArray): Boolean {
        if (needle.size > body.size) return false
        for (i in 0..body.size - needle.size) {
            var j = 0
            while (j < needle.size && body[i + j] == needle[j]) j++
            if (j == needle.size) return true
        }
        return false
    }

    private fun readMemory(segments: List<Pair<Int, ByteArray>>, addr: Int, length: Int): ByteArray {
        for ((offset, data) in segments) {
            if (addr >= offset && addr + length <= offset + data.size) {
                return data.copyOfRange(addr - offset, addr - offset + length)
            }
        }
        throw Exception("Vidsrc.Me wasm memory not in segments: 0x${addr.toString(16)}")
    }

    // ----- WASM helpers -----

    private fun readConstExpr(wasm: ByteArray, start: Int): Pair<Int, Int> {
        val op = wasm[start].toInt() and 0xff
        if (op != 0x41) throw Exception("Unexpected const expr opcode: $op")
        val (value, next) = lebS(wasm, start + 1)
        return Pair(value, next + 1) // skip 0x0b end
    }

    private fun lebU(wasm: ByteArray, start: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var p = start
        while (true) {
            val b = wasm[p].toInt() and 0xff
            p++
            result = result or ((b and 0x7f) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return Pair(result, p)
    }

    private fun lebS(wasm: ByteArray, start: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var p = start
        var b = 0
        while (true) {
            b = wasm[p].toInt() and 0xff
            p++
            result = result or ((b and 0x7f) shl shift)
            shift += 7
            if (b and 0x80 == 0) break
        }
        if (b and 0x40 != 0) result = result or (-1 shl shift)
        return Pair(result, p)
    }

    // ----- ChaCha20 -----

    private fun chacha20(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        val out = ByteArray(ciphertext.size)
        var offset = 0
        var block = 0
        while (offset < ciphertext.size) {
            val keystream = chachaBlock(key, block, nonce)
            val n = minOf(64, ciphertext.size - offset)
            for (i in 0 until n) {
                out[offset + i] = (ciphertext[offset + i].toInt() xor keystream[i].toInt()).toByte()
            }
            offset += n
            block++
        }
        return out
    }

    private fun chachaBlock(key: ByteArray, counter: Int, nonce: ByteArray): ByteArray {
        val state = IntArray(16)
        System.arraycopy(CHACHA_CONSTANTS, 0, state, 0, 4)
        for (i in 0 until 8) state[4 + i] = leInt(key, i * 4)
        state[12] = counter
        state[13] = leInt(nonce, 0)
        state[14] = leInt(nonce, 4)
        state[15] = leInt(nonce, 8)

        val w = state.copyOf()
        for (round in 0 until 10) {
            quarterRound(w, 0, 4, 8, 12)
            quarterRound(w, 1, 5, 9, 13)
            quarterRound(w, 2, 6, 10, 14)
            quarterRound(w, 3, 7, 11, 15)
            quarterRound(w, 0, 5, 10, 15)
            quarterRound(w, 1, 6, 11, 12)
            quarterRound(w, 2, 7, 8, 13)
            quarterRound(w, 3, 4, 9, 14)
        }

        val out = ByteArray(64)
        for (i in 0 until 16) writeIntLE(out, i * 4, w[i] + state[i])
        return out
    }

    private fun quarterRound(w: IntArray, a: Int, b: Int, c: Int, d: Int) {
        w[a] = w[a] + w[b]
        w[d] = rotl(w[d] xor w[a], 16)
        w[c] = w[c] + w[d]
        w[b] = rotl(w[b] xor w[c], 12)
        w[a] = w[a] + w[b]
        w[d] = rotl(w[d] xor w[a], 8)
        w[c] = w[c] + w[d]
        w[b] = rotl(w[b] xor w[c], 7)
    }

    private fun rotl(x: Int, n: Int) = (x shl n) or (x ushr (32 - n))

    private fun leInt(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xff) or
            ((data[offset + 1].toInt() and 0xff) shl 8) or
            ((data[offset + 2].toInt() and 0xff) shl 16) or
            ((data[offset + 3].toInt() and 0xff) shl 24)
    }

    private fun writeIntLE(data: ByteArray, offset: Int, value: Int) {
        data[offset] = value.toByte()
        data[offset + 1] = (value ushr 8).toByte()
        data[offset + 2] = (value ushr 16).toByte()
        data[offset + 3] = (value ushr 24).toByte()
    }

    // ----- Network -----

    private fun parseToken(text: String): String {
        val trimmed = text.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            runCatching {
                val j = JSONObject(trimmed)
                return j.optString("token")
                    .ifBlank { j.optString("data") }
                    .ifBlank { j.optString("string") }
                    .ifBlank { j.optString("result") }
            }
        }
        return trimmed
    }

    private fun fetchString(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code} for $url")
            return response.body?.string().orEmpty()
        }
    }

    private fun fetchBytes(url: String): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code} for $url")
            return response.body?.bytes() ?: throw Exception("Empty body for $url")
        }
    }
}
