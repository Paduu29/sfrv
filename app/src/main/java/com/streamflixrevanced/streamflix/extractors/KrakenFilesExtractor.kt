package com.streamflixrevanced.streamflix.extractors

import android.net.Uri
import androidx.media3.common.MimeTypes
import com.streamflixrevanced.streamflix.models.Video
import com.streamflixrevanced.streamflix.utils.NetworkClient
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url
import java.net.URL

class KrakenFilesExtractor : Extractor() {

    override val name = "KrakenFiles"
    override val mainUrl = "https://krakenfiles.com"

    override suspend fun extract(link: String): Video {
        val uri = Uri.parse(link)
        val origin = "${uri.scheme}://${uri.host}"
        val document = Service.build(origin).getPage(link, link)
        val sourceValue = document.selectFirst("video source[src], video[src]")
            ?.let { element -> element.attr("src").ifBlank { element.attr("data-src") } }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw Exception("KrakenFiles video source not found")
        val source = URL(URL(link), sourceValue).toString()

        return Video(
            source = source,
            type = MimeTypes.VIDEO_MP4,
            headers = mapOf(
                "Referer" to link,
                "Origin" to origin,
                "User-Agent" to NetworkClient.USER_AGENT,
            ),
        )
    }

    private interface Service {
        @GET
        suspend fun getPage(
            @Url url: String,
            @Header("Referer") referer: String,
            @Header("User-Agent") userAgent: String = NetworkClient.USER_AGENT,
        ): Document

        companion object {
            fun build(baseUrl: String): Service {
                return Retrofit.Builder()
                    .baseUrl("$baseUrl/")
                    .client(NetworkClient.default)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .build()
                    .create(Service::class.java)
            }
        }
    }
}
