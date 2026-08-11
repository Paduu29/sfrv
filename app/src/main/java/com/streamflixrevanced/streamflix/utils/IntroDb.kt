package com.streamflixrevanced.streamflix.utils

import android.util.Log
import com.google.gson.annotations.SerializedName
import java.util.concurrent.ConcurrentHashMap
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query

object IntroDb {
    private const val TAG = "IntroDb"
    private const val BASE_URL = "https://api.introdb.app/"
    private const val SUCCESS_CACHE_TTL_MS = 24 * 60 * 60 * 1000L
    private const val NOT_FOUND_CACHE_TTL_MS = 6 * 60 * 60 * 1000L

    data class Segment(
        val startMs: Long,
        val endMs: Long,
        val confidence: Double?,
        val submissionCount: Int?,
    ) {
        fun contains(positionMs: Long): Boolean = positionMs in startMs until endMs
    }

    data class Segments(
        val intro: Segment?,
        val recap: Segment?,
        val outro: Segment?,
    )

    data class LookupResult(
        val imdbId: String,
        val segments: Segments?,
    )

    private data class CacheKey(
        val imdbId: String,
        val season: Int,
        val episode: Int,
    )

    private data class CacheEntry(
        val value: Segments?,
        val expiresAtMillis: Long,
    )

    private data class SegmentResponse(
        @SerializedName("start_ms") val startMs: Long?,
        @SerializedName("end_ms") val endMs: Long?,
        val confidence: Double?,
        @SerializedName("submission_count") val submissionCount: Int?,
    ) {
        fun toSegment(): Segment? {
            val start = startMs ?: return null
            val end = endMs ?: return null
            if (start < 0L || end <= start) return null
            return Segment(
                startMs = start,
                endMs = end,
                confidence = confidence,
                submissionCount = submissionCount,
            )
        }
    }

    private data class SegmentsResponse(
        val intro: SegmentResponse?,
        val recap: SegmentResponse?,
        val outro: SegmentResponse?,
    ) {
        fun toSegments() = Segments(
            intro = intro?.toSegment(),
            recap = recap?.toSegment(),
            outro = outro?.toSegment(),
        )
    }

    private interface Service {
        @Headers("Accept: application/json")
        @GET("segments")
        suspend fun getSegments(
            @Query("imdb_id") imdbId: String,
            @Query("season") season: Int,
            @Query("episode") episode: Int,
        ): Response<SegmentsResponse>
    }

    private val service: Service by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(NetworkClient.default)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(Service::class.java)
    }

    private val cache = ConcurrentHashMap<CacheKey, CacheEntry>()

    suspend fun getSegments(
        imdbId: String?,
        season: Int,
        episode: Int,
        title: String? = null,
        year: Int? = null,
        language: String? = null,
    ): LookupResult? {
        if (season < 1 || episode < 1) {
            return null
        }

        val normalizedImdbId = imdbId
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.matches(Regex("^tt\\d{7,8}$")) }
            ?: title
                ?.takeIf { it.isNotBlank() }
                ?.let { TmdbUtils.getTvShow(it, year = year, language = language)?.imdbId }
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.matches(Regex("^tt\\d{7,8}$")) }

        if (normalizedImdbId == null) {
            Log.d(TAG, "No IMDb ID available for '$title' ($year)")
            return null
        }

        if (imdbId.isNullOrBlank()) {
            Log.d(TAG, "Resolved '$title' to $normalizedImdbId")
        }

        val key = CacheKey(normalizedImdbId, season, episode)
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { it.expiresAtMillis > now }?.let {
            return LookupResult(normalizedImdbId, it.value)
        }
        cache.remove(key)

        val segments = runCatching {
            val response = service.getSegments(normalizedImdbId, season, episode)
            when {
                response.isSuccessful -> {
                    val segments = response.body()?.toSegments()
                    cache[key] = CacheEntry(segments, now + SUCCESS_CACHE_TTL_MS)
                    Log.d(TAG, "Loaded $key: intro=${segments?.intro}, outro=${segments?.outro}")
                    segments
                }

                response.code() == 404 -> {
                    cache[key] = CacheEntry(null, now + NOT_FOUND_CACHE_TTL_MS)
                    null
                }

                else -> {
                    Log.w(TAG, "Lookup failed with HTTP ${response.code()} for $key")
                    null
                }
            }
        }.onFailure {
            Log.w(TAG, "Lookup failed for $key", it)
        }.getOrNull()
        return LookupResult(normalizedImdbId, segments)
    }
}
