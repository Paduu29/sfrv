package com.streamflixrevanced.streamflix.extractors

import android.util.Base64
import com.streamflixrevanced.streamflix.models.Video
import com.streamflixrevanced.streamflix.utils.JsUnpacker
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url
import java.net.URL

class VidGuardExtractor : Extractor() {
    override val name = "VidGuard"
    override val mainUrl = "https://vidguard.to"
    override val aliasUrls = listOf(
        "vembed.net", "bembed.cc", "vgfplay.com", "listeamed.net", "vidguard.to"
    )

    private val client = OkHttpClient()
    private val service = Retrofit.Builder()
        .baseUrl(mainUrl)
        .addConverterFactory(ScalarsConverterFactory.create())
        .client(client)
        .build()
        .create(VidGuardService::class.java)

    private interface VidGuardService {
        @GET
        suspend fun get(
            @Url url: String,
            @Header("Referer") referer: String?,
            @Header("User-Agent") userAgent: String,
        ): String
    }

    override suspend fun extract(link: String): Video {
        val (pageUrl, unpackedScript, followedRedirect) = loadPlayerPage(link)

        val urlEncoded = unpackedScript
            .substringAfter("window.svg={\"stream\":\"")
            .substringBefore("\",\"hash")

        val finalUrl = sigDecode(urlEncoded)

        return Video(
            source = finalUrl,
            headers = mapOf("Referer" to if (followedRedirect) pageUrl.origin() else mainUrl)
        )
    }

    private suspend fun loadPlayerPage(link: String): Triple<String, String, Boolean> {
        var currentUrl = link.withHttpsScheme()
        var referer: String? = null
        var pageHtml = service.get(currentUrl, referer, USER_AGENT)
        var followedRedirect = false

        repeat(MAX_REDIRECT_HOPS + 1) { attempt ->
            unpackPlayerScript(pageHtml)?.let { unpacked ->
                return Triple(currentUrl, unpacked, followedRedirect)
            }
            if (attempt == MAX_REDIRECT_HOPS) return@repeat

            val redirect = findJavascriptRedirect(pageHtml)
                ?: throw Exception("No se pudo desempacar el script de VidGuard")
            val nextUrl = URL(URL(currentUrl), redirect).toString()
            if (nextUrl == currentUrl) throw Exception("VidGuard devolvió una redirección circular")

            referer = currentUrl
            currentUrl = nextUrl
            followedRedirect = true
            pageHtml = service.get(currentUrl, referer, USER_AGENT)
        }
        throw Exception("No se pudo desempacar el script de VidGuard")
    }

    private fun unpackPlayerScript(pageHtml: String): String? {
        val markerIndex = pageHtml.indexOf(PACKER_MARKER)
        if (markerIndex < 0) return null
        val scriptData = pageHtml
            .substring(markerIndex + PACKER_MARKER.length)
            .substringBefore("</script>")
            .let { "$PACKER_MARKER$it" }
        return JsUnpacker(scriptData).unpack()
    }

    private fun findJavascriptRedirect(html: String): String? {
        val normalized = html
            .replace("\\/", "/")
            .replace("\\.", ".")
            .replace("\\&", "&")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
        return LOCATION_REPLACE_PATTERN.find(normalized)
            ?.groupValues
            ?.getOrNull(2)
            ?.replace("\\'", "'")
            ?.replace("\\\"", "\"")
            ?.takeIf { it.isNotBlank() }
    }

    private fun String.withHttpsScheme(): String = when {
        startsWith("//") -> "https:$this"
        startsWith("http://") || startsWith("https://") -> this
        else -> "https://$this"
    }

    private fun String.origin(): String {
        val url = URL(this)
        return "${url.protocol}://${url.authority}/"
    }

    private fun sigDecode(url: String): String {
        val sig = url.split("sig=")[1].split("&")[0]
        val decodedSig = sig.chunked(2)
            .joinToString("") { (Integer.parseInt(it, 16) xor 2).toChar().toString() }
            .let {
                val padding = when (it.length % 4) {
                    2 -> "=="
                    3 -> "="
                    else -> ""
                }
                String(Base64.decode((it + padding).toByteArray(), Base64.DEFAULT))
            }
            .dropLast(5)
            .reversed()
            .toCharArray()
            .apply {
                for (i in indices step 2) {
                    if (i + 1 < size) {
                        this[i] = this[i + 1].also { this[i + 1] = this[i] }
                    }
                }
            }
            .concatToString()
            .dropLast(5)
        return url.replace(sig, decodedSig)
    }

    companion object {
        private const val MAX_REDIRECT_HOPS = 3
        private const val PACKER_MARKER = "eval(function(p,a,c,k,e,d)"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private val LOCATION_REPLACE_PATTERN = Regex(
            """window\s*\.\s*location\s*\.\s*replace\s*\(\s*(['\"])(.*?)\1\s*\)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
    }
}
