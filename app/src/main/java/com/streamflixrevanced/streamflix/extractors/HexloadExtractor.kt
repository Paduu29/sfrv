package com.streamflixrevanced.streamflix.extractors

import android.net.Uri
import androidx.media3.common.MimeTypes
import com.streamflixrevanced.streamflix.models.Video
import com.streamflixrevanced.streamflix.utils.NetworkClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.Header
import retrofit2.http.POST

class HexloadExtractor : Extractor() {

    override val name = "Hexload"
    override val mainUrl = "https://hexload.com"

    override suspend fun extract(link: String): Video {
        val uri = Uri.parse(link)
        val origin = "${uri.scheme}://${uri.host}"
        val fileCode = Regex("""/embed-([A-Za-z0-9]+)""")
            .find(uri.path.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?: throw Exception("Hexload file code not found")

        val response = Service.build(origin).getDownload(
            referer = link,
            fileCode = fileCode,
        )
        val json = JSONObject(response)
        if (!json.optString("msg").equals("OK", ignoreCase = true)) {
            throw Exception("Hexload rejected the download request: ${json.optString("msg")}")
        }

        val source = json.optJSONObject("result")
            ?.optString("url")
            ?.takeIf { it.isNotBlank() }
            ?.let { it.toHttpUrlOrNull()?.toString() ?: it.replace(" ", "%20") }
            ?: throw Exception("Hexload video URL not found")

        return Video(
            source = source,
            type = MimeTypes.VIDEO_MP4,
            headers = mapOf(
                "Referer" to "$origin/",
                "Origin" to origin,
                "User-Agent" to NetworkClient.USER_AGENT,
            ),
        )
    }

    private interface Service {
        @FormUrlEncoded
        @POST("download")
        suspend fun getDownload(
            @Header("Referer") referer: String,
            @Header("X-Requested-With") requestedWith: String = "XMLHttpRequest",
            @Header("User-Agent") userAgent: String = NetworkClient.USER_AGENT,
            @Field("op") operation: String = "download3",
            @Field("id") fileCode: String,
            @Field("ajax") ajax: String = "1",
            @Field("method_free") freeMethod: String = "1",
            @Field("dataType") dataType: String = "json",
        ): String

        companion object {
            fun build(baseUrl: String): Service {
                return Retrofit.Builder()
                    .baseUrl("$baseUrl/")
                    .client(NetworkClient.default)
                    .addConverterFactory(ScalarsConverterFactory.create())
                    .build()
                    .create(Service::class.java)
            }
        }
    }
}
