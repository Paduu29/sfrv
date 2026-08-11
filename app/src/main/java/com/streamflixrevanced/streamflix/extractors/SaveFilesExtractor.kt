package com.streamflixrevanced.streamflix.extractors

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixrevanced.streamflix.models.Video
import com.streamflixrevanced.streamflix.utils.JsUnpacker
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.Header
import retrofit2.http.POST
import java.net.URL

class SaveFilesExtractor: Extractor() {

    override val name = "Savefiles"
    override val mainUrl = "https://savefiles.com/"
    override val aliasUrls = listOf("https://streamhls.to", "https://savefiles.top")

    override suspend fun extract(link: String): Video {
        val parsedUrl = URL(link)
        val pathParts = parsedUrl.path.split("/").filter { it.isNotEmpty() }
        if (pathParts.isEmpty()) {
            throw Exception("File code not found in URL")
        }
        
        val fileCode = pathParts.last().split("?")[0].trim()
        if (fileCode.isEmpty()) {
            throw Exception("File code not found in URL")
        }

        val baseUrl = parsedUrl.protocol + "://" + parsedUrl.host
        val service = SaveFilesExtractorService.build(baseUrl)
        val source = service.getDl(
            op = "embed",
            fileCode = fileCode,
            auto = "0",
            referer = link,
        )

        val m3u8 = source.select("script")
            .asSequence()
            .flatMap { script ->
                val packed = script.data().ifBlank { script.html() }
                sequenceOf(packed, JsUnpacker(packed).unpack().orEmpty())
            }
            .mapNotNull { script ->
                Regex(
                    """(?:file|hls\d*)\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""",
                    RegexOption.IGNORE_CASE,
                ).find(script)?.groupValues?.getOrNull(1)
            }
            .firstOrNull()
            ?: throw Exception("Stream URL not found in SaveFiles player")

        return Video(
            source = m3u8,
            subtitles = listOf(),
            headers = mapOf("Referer" to "$baseUrl/"),
        )
    }

    private interface SaveFilesExtractorService {
        companion object {
            fun build(baseUrl: String): SaveFilesExtractorService {
                val retrofitRedirected = Retrofit.Builder()
                    .baseUrl("$baseUrl/")
                    .addConverterFactory(JsoupConverterFactory.create())
                    .build()
                return retrofitRedirected.create(SaveFilesExtractorService::class.java)
            }
        }
        @FormUrlEncoded
        @POST("dl")
        suspend fun getDl(
            @Field("op") op: String,
            @Field("file_code") fileCode: String,
            @Field("auto") auto: String,
            @Field("referer") referer: String,
            @Header("User-Agent") userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36",
        ): Document
    }
}
