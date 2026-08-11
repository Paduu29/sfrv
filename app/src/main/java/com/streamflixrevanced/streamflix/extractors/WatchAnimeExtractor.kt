package com.streamflixrevanced.streamflix.extractors

import android.net.Uri
import androidx.media3.common.MimeTypes
import com.streamflixrevanced.streamflix.models.Video
import com.streamflixrevanced.streamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Extracts the dynamic HLS manifest used by watchanime.uns.bio. */
class WatchAnimeExtractor : Extractor() {

    override val name = "WatchAnime"
    override val mainUrl = "https://watchanime.uns.bio"

    override suspend fun extract(link: String): Video = withContext(Dispatchers.IO) {
        val videoId = Uri.parse(link).fragment
            ?.substringBefore('&')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("WatchAnime video id is missing from the URL fragment")

        val requestUrl = "$mainUrl/api/v1/video".toHttpUrl().newBuilder()
            .addQueryParameter("id", videoId)
            .addQueryParameter("w", "1920")
            .addQueryParameter("h", "1080")
            .addQueryParameter("r", "")
            .build()

        val request = Request.Builder()
            .url(requestUrl)
            .header("User-Agent", NetworkClient.USER_AGENT)
            .header("Referer", "$mainUrl/#$videoId")
            .header("Origin", mainUrl)
            .build()

        val encryptedManifest = NetworkClient.default.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("WatchAnime manifest request failed: HTTP ${response.code}")
            }
            response.body?.string()?.trim().orEmpty()
        }

        if (encryptedManifest.startsWith("{")) {
            throw Exception(JSONObject(encryptedManifest).optString("message", "WatchAnime returned an invalid manifest"))
        }

        val manifest = decryptManifest(encryptedManifest)
        val source = sequenceOf(
            manifest.optString("source"),
            manifest.optString("cfNative"),
            manifest.optString("hlsVideoTiktok"),
            manifest.optString("cf"),
        ).firstOrNull { it.isNotBlank() }
            ?: throw Exception("WatchAnime did not return an HLS source")

        Video(
            source = source,
            subtitles = emptyList(),
            type = MimeTypes.APPLICATION_M3U8,
            headers = mapOf(
                "User-Agent" to NetworkClient.USER_AGENT,
                "Referer" to "$mainUrl/",
                "Origin" to mainUrl,
            ),
        )
    }

    private fun decryptManifest(payload: String): JSONObject {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec("kiemtienmua911ca".toByteArray(StandardCharsets.UTF_8), "AES"),
            IvParameterSpec("1234567890oiuytr".toByteArray(StandardCharsets.UTF_8)),
        )
        val plaintext = cipher.doFinal(payload.hexToByteArray())
            .toString(StandardCharsets.UTF_8)
        return JSONObject(plaintext)
    }

    private fun String.hexToByteArray(): ByteArray {
        if (length % 2 != 0 || any { it !in "0123456789abcdefABCDEF" }) {
            throw IllegalArgumentException("WatchAnime returned a malformed encrypted manifest")
        }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
