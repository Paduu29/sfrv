package com.streamflixrevanced.streamflix.providers

import android.util.Log
import com.streamflixrevanced.streamflix.StreamFlixApp
import com.streamflixrevanced.streamflix.adapters.AppAdapter
import com.streamflixrevanced.streamflix.extractors.Extractor
import com.streamflixrevanced.streamflix.models.Category
import com.streamflixrevanced.streamflix.models.Episode
import com.streamflixrevanced.streamflix.models.Genre
import com.streamflixrevanced.streamflix.models.Movie
import com.streamflixrevanced.streamflix.models.People
import com.streamflixrevanced.streamflix.models.Season
import com.streamflixrevanced.streamflix.models.TvShow
import com.streamflixrevanced.streamflix.models.Video
import com.streamflixrevanced.streamflix.utils.ArtworkRequestHeaders
import com.streamflixrevanced.streamflix.utils.DnsResolver
import com.streamflixrevanced.streamflix.utils.NetworkClient
import com.streamflixrevanced.streamflix.utils.WebViewResolver
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.HttpException
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Url
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.jvm.java
import kotlin.text.format

object MkissaProvider : Provider {

    private const val TAG = "MkissaProvider"
    private const val API_URL = "https://api.allanime.day/api"
    private const val CLOCK_URL = "https://allanime.day"
    private const val SEARCH_HASH = "a24c500a1b765c68ae1d8dd85174931f661c71369c89b92b88b75a725afc471c"
    private const val POPULAR_DAILY_HASH = "a0aca6827cc9a3ad7bc711da4d200a04adea8f1a7545dc418d5e92e74c3aad15"
    private const val POPULAR_HASH = "ac2c75884a11fca5707ce4ad10f2e3e2aae31e42af5e4d9c511a4a5e708e4c6d"
    private val DETAIL_HASH: String by lazy { sha256Hex(DETAIL_QUERY) }
    private const val GENRE_HASH = "ff61a63ff776f334f80c1e6ad1aa49ef71eab831e235e5d6ec679eae5b83450f"
    private const val IMAGE_URL = "https://aln.youtube-anime.com"
    private const val DEFAULT_BUILD_ID = "110"
    private const val HOME_ROW_LIMIT = 20
    private const val HOME_TAG_LIMIT = 20
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val translationTypes = listOf("sub", "dub", "raw")
    private val browseTranslationTypes = listOf("sub", "dub")
    private val SHOW_FIELDS = """
        _id
        type
        englishName
        name
        nativeName
        nameOnlyString
        altNames
        slugTime
        description
        availableEpisodes
        episodeCount
        lastEpisodeInfo
        episodeDuration
        airedStart
        score
        thumbnail
        banner
        genres
        isAdult
    """.trimIndent()

    private val SEARCH_QUERY = """
        query(
          ${'$'}search: SearchInput
          ${'$'}limit: Int
          ${'$'}page: Int
          ${'$'}translationType: VaildTranslationTypeEnumType
          ${'$'}countryOrigin: VaildCountryOriginEnumType
          ${'$'}allowAdult: Boolean
        ) {
          shows(
            search: ${'$'}search
            limit: ${'$'}limit
            page: ${'$'}page
            translationType: ${'$'}translationType
            countryOrigin: ${'$'}countryOrigin
            allowAdult: ${'$'}allowAdult
          ) {
            pageInfo { total }
            edges { $SHOW_FIELDS }
          }
        }
    """.trimIndent()

    private val POPULAR_DAILY_QUERY = """
        query(
          ${'$'}type: VaildPopularTypeEnumType!
          ${'$'}size: Int!
          ${'$'}dateRange: Int
          ${'$'}page: Int
          ${'$'}allowAdult: Boolean
          ${'$'}allowUnknown: Boolean
        ) {
          queryPopular(
            type: ${'$'}type
            size: ${'$'}size
            dateRange: ${'$'}dateRange
            page: ${'$'}page
            allowAdult: ${'$'}allowAdult
            allowUnknown: ${'$'}allowUnknown
          ) {
            total
            recommendations {
              anyCard {
                $SHOW_FIELDS
                lastEpisodeDate
                lastChapterDate
                availableChapters
              }
            }
          }
        }
    """.trimIndent()

    private val TAG_QUERY = """
        query(${ '$' }search: ListForTagInput!) {
          queryListForTag(search: ${ '$' }search) {
            pageInfo { total }
            edges { $SHOW_FIELDS }
          }
        }
    """.trimIndent()

    private val TAGS_QUERY = """
        query(
          ${ '$' }page: Int
          ${ '$' }offset: Int
          ${ '$' }limit: Int
          ${ '$' }search: TagSearchInput
        ) {
          queryTags(
            page: ${ '$' }page
            offset: ${ '$' }offset
            limit: ${ '$' }limit
            search: ${ '$' }search
          ) {
            pageInfo { total }
            edges {
              _id
              name
              slug
              tagType
            }
          }
        }
    """.trimIndent()

    private val DETAIL_QUERY = """
        query(${ '$' }_id: String!) {
          show(_id: ${ '$' }_id) {
            $SHOW_FIELDS
            status
            averageScore
            rating
            airedEnd
            studios
            countryOfOrigin
            availableEpisodesDetail
            isAdult
            tags
          }
        }
    """.trimIndent()

    private val RANDOM_QUERY = """
        query(
          ${ '$' }format: String!
          ${ '$' }allowAdult: Boolean
        ) {
          queryRandomRecommendation(
            format: ${ '$' }format
            allowAdult: ${ '$' }allowAdult
          ) {
            $SHOW_FIELDS
          }
        }
    """.trimIndent()

    private val SOURCE_QUERY = """
        query(
          ${ '$' }showId: String!
          ${ '$' }translationType: VaildTranslationTypeEnumType!
          ${ '$' }episodeString: String!
        ) {
          episode(
            showId: ${ '$' }showId
            translationType: ${ '$' }translationType
            episodeString: ${ '$' }episodeString
          ) {
            episodeString
            uploadDate
            sourceUrls
            thumbnail
            notes
            show { $SHOW_FIELDS }
            versionFix
          }
          pageStatus {
            _id
            notes
            pageId
            showId
          }
          episodeInfo {
            notes
            thumbnails
            vidInforssub
            uploadDates
            vidInforsdub
            vidInforsraw
            description
          }
        }
    """.trimIndent()

    override val name = "MKissa"
    override val baseUrl = "https://mkissa.to/anime"
    override val language = "en"
    override val logo = "https://mkissa.to/favicon-32x32.png"

    private val service = Retrofit.Builder()
        .baseUrl("https://mkissa.to/")
        .addConverterFactory(ScalarsConverterFactory.create())
        .client(
            OkHttpClient.Builder()
                .cache(Cache(File("cacheDir", "mkissa_okhttpcache"), 10 * 1024 * 1024))
                .readTimeout(30, TimeUnit.SECONDS)
                .connectTimeout(30, TimeUnit.SECONDS)
                .dns(DnsResolver.doh)
                .build()
        )
        .build()
        .create(MkissaService::class.java)

    private val sourceResolverClient = OkHttpClient.Builder()
        .dns(DnsResolver.doh)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val webViewResolver by lazy {
        WebViewResolver(StreamFlixApp.instance)
    }

    private interface MkissaService {
        @Headers(
            "Accept: application/json",
            "Origin: https://mkissa.to",
            "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )
        @GET
        suspend fun api(
            @Url apiUrl: String,
            @Query("variables") variables: String,
            @Query("extensions") extensions: String,
            @Header("x-build-id") buildId: String,
            @Header("Referer") referer: String
        ): String

        @Headers(
            "Accept: application/json",
            "Content-Type: application/json",
            "Origin: https://mkissa.to",
            "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )
        @POST
        suspend fun apiPost(
            @Url apiUrl: String,
            @Body body: okhttp3.RequestBody,
            @Header("x-build-id") buildId: String,
            @Header("Referer") referer: String
        ): String
    }

    override suspend fun getHome(): List<Category> = coroutineScope {
        fun category(name: String, block: suspend () -> List<AppAdapter.Item>) = async {
            Category(
                name = name,
                list = try {
                    block()
                } catch (_: Exception) {
                    emptyList()
                }
            )
        }

        val dynamicTags = try {
            homeTags()
        } catch (_: Exception) {
            fallbackHomeTags
        }

        val newSeries = category("New Series") {
            val now = java.util.Calendar.getInstance()
            searchShows(
                search = mapOf(
                    "season" to currentAnimeSeason(now.get(java.util.Calendar.MONTH) + 1),
                    "year" to now.get(java.util.Calendar.YEAR)
                ),
                limit = HOME_ROW_LIMIT,
                page = 1,
                countryOrigin = "JP"
            )
        }

        val categories = buildList {
            add(category("Latest Updates (Sub/Dub)") {
                searchShows(mapOf("sortBy" to "Recent"), limit = HOME_ROW_LIMIT, page = 1)
            })
            add(newSeries)
            add(category("Random") { randomShows(limit = HOME_ROW_LIMIT) })
            addAll(
                dynamicTags.map { tag ->
                    category(tag.name) {
                        tagShows(
                            slug = tag.slug,
                            name = tag.name,
                            tagType = tag.tagType,
                            limit = HOME_ROW_LIMIT,
                            page = 1
                        )
                    }
                }
            )
            add(category("Trending Activity") { popularByDateRange(dateRange = 1, page = 1, size = HOME_ROW_LIMIT) })
        }

        categories
            .map { it.await() }
            .filter { it.list.isNotEmpty() }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return genres
        return searchItems(mapOf("query" to query), limit = 26, page = page)
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        return searchMovies(page = page)
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return searchShows(mapOf("sortBy" to "Popular", "types" to listOf("TV")), limit = 26, page = page)
    }

    override suspend fun getMovieFromProvider(id: String): Movie {
        return showDetails(id.removePrefix("movie:")).toMovie()
    }

    override suspend fun getTvShowFromProvider(id: String): TvShow {
        return showDetails(id.removePrefix("movie:"))
    }

    override suspend fun getEpisodesByProvider(seasonId: String): List<Episode> {
        val parts = seasonId.split("|")
        val showId = parts.firstOrNull().orEmpty()
        val translation = parts.getOrNull(1) ?: "sub"
        val show = showDetails(showId)
        val count = show.seasons.firstOrNull { it.id == seasonId }?.episodes?.size ?: 0
        return buildEpisodes(showId, count, translation)
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val name = id.replace('_', ' ')
            .split(" ")
            .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
        val shows = tagShows(slug = id, name = name, limit = 26, page = page)
        return Genre(id = id, name = name, shows = shows)
    }

    override suspend fun getPeople(id: String, page: Int): People {
        throw Exception("People pages are not available in MKissa")
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val parts = id.split("|")
        val showId = when (videoType) {
            is Video.Type.Movie -> videoType.id.removePrefix("movie:")
            is Video.Type.Episode -> videoType.tvShow.id.removePrefix("movie:")
        }
        val episode = when (videoType) {
            is Video.Type.Movie -> "1"
            is Video.Type.Episode -> videoType.number.toString()
        }
        val requestedTranslation = parts.getOrNull(2)

        return translationTypes
            .filter { translation ->
                requestedTranslation == null || requestedTranslation == translation
            }
            .flatMap { translation ->
                val watchUrl = "$baseUrl/$showId/p-$episode-$translation"
                resolveVisibleWatchPage(watchUrl).map { sourceUrl ->
                    Video.Server(
                        id = sourceUrl,
                        name = "MKissa ${translation.uppercase()}".trim(),
                        src = sourceUrl
                    )
                }
            }
    }

    private suspend fun resolveVisibleWatchPage(pageUrl: String): List<String> {
        Log.d(TAG, "Opening visible Mkissa WebView: $pageUrl")
        val result = webViewResolver.getResult(
            url = pageUrl,
            headers = mapOf(
                "User-Agent" to NetworkClient.USER_AGENT,
                "Referer" to "$baseUrl/",
            ),
            showImmediately = true,
            completion = { currentUrl, html, _ ->
                !isCloudflareChallenge(html) &&
                        extractWatchLinks(html, currentUrl).isNotEmpty()
            },
        )

        val finalUrl = result.finalUrl ?: pageUrl
        val links = extractWatchLinks(result.html, finalUrl)
        Log.d(TAG, "Visible Mkissa WebView finished: url=$finalUrl links=${links.size}")
        return links
    }

    private fun extractWatchLinks(html: String, baseUri: String): List<String> {
        if (html.isBlank()) return emptyList()
        val document = Jsoup.parse(html, baseUri)
        return document.select(
            "iframe[src], video[src], video[currentSrc], source[src], " +
                    "a[href], [data-src], [data-url], [data-file]"
        ).flatMap { element ->
            listOf("src", "href", "currentSrc", "data-src", "data-url", "data-file")
                .mapNotNull { attribute ->
                    val value = if (attribute == "src" || attribute == "href") {
                        element.absUrl(attribute)
                    } else {
                        element.attr(attribute).trim()
                    }
                    value.takeIf(::isWatchLink)
                }
        }.distinct()
    }

    private fun isCloudflareChallenge(html: String): Boolean {
        return listOf(
            "Just a moment...",
            "cf-chl-",
            "turnstile",
            "challenge-platform",
            "Checking your browser",
        ).any { html.contains(it, ignoreCase = true) }
    }

    private fun isWatchLink(value: String): Boolean {
        val lower = value.lowercase()
        if (!lower.startsWith("http")) return false
        if (lower.contains("mkissa.to/cdn-cgi/") || lower.contains("challenges.cloudflare.com")) return false
        if (lower.contains(".js") || lower.contains(".css") || lower.contains(".png") ||
            lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".svg") ||
            lower.contains("favicon")) return false
        return lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".mpd") ||
                lower.contains("/embed") || lower.contains("/stream") || lower.contains("/source") ||
                lower.contains("/file") || lower.contains("player")
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val source = resolveSourceUrl(server.src.ifBlank { server.id })
            ?: throw Exception("Selected MKissa source could not be resolved")

        if (source.contains(".m3u8", ignoreCase = true) || source.contains(".mp4", ignoreCase = true)) {
            return Video(
                source = source,
                headers = directPlaybackHeaders()
            )
        }

        return Extractor.extract(source, server)
    }

    private suspend fun popularShows(page: Int, size: Int): List<TvShow> {
        val variables = JSONObject()
            .put(
                "search",
                JSONObject()
                    .put("page", page)
                    .put("size", size)
                    .put("sortBy", "Popular")
                    .put("allowAdult", false)
                    .put("allowUnknown", false)
            )
        return parseShows(api(variables, POPULAR_HASH, SEARCH_QUERY).data)
    }

    private suspend fun popularByDateRange(dateRange: Int, page: Int, size: Int): List<TvShow> {
        val variables = JSONObject()
            .put("type", "anime")
            .put("size", size)
            .put("dateRange", dateRange)
            .put("page", page)
            .put("allowAdult", false)
            .put("allowUnknown", false)
        return parsePopular(api(variables, POPULAR_DAILY_HASH, POPULAR_DAILY_QUERY).data)
    }

    private suspend fun tagShows(
        slug: String,
        name: String,
        tagType: String? = null,
        limit: Int = HOME_ROW_LIMIT,
        page: Int = 1
    ): List<TvShow> {
        val search = JSONObject()
            .put("slug", slug)
            .put("format", "anime")
            .put("page", page)
            .put("limit", limit)
            .put("name", name)
            .put("allowAdult", false)
            .put("allowUnknown", false)
        if (!tagType.isNullOrBlank()) search.put("tagType", tagType.normalizedTagType())
        val variables = JSONObject().put("search", search)
        return parseShows(api(variables, GENRE_HASH, TAG_QUERY).data)
    }

    private suspend fun homeTags(): List<HomeTag> {
        val variables = JSONObject()
            .put("page", 1)
            .put("limit", HOME_TAG_LIMIT)
            .put(
                "search",
                JSONObject()
                    .put("format", "anime")
                    .put("sortBy", "Recommendation")
                    .put("allowAdult", false)
                    .put("allowUnknown", false)
            )
        val response = postQuery(TAGS_QUERY, variables)
        val edges = response.optJSONObject("data")
            ?.optJSONObject("queryTags")
            ?.optJSONArray("edges")
            ?: JSONArray()
        return edges.asSequence()
            .mapNotNull { it as? JSONObject }
            .mapNotNull { tag ->
                val slug = tag.stringOrNull("slug") ?: tag.stringOrNull("name")?.slugify() ?: return@mapNotNull null
                val name = tag.stringOrNull("name") ?: return@mapNotNull null
                if (slug == "movie-anime") return@mapNotNull null
                HomeTag(
                    slug = slug,
                    name = name,
                    tagType = tag.stringOrNull("tagType")?.normalizedTagType()
                )
            }
            .distinctBy { it.slug }
            .toList()
    }

    private suspend fun randomShows(limit: Int): List<TvShow> {
        val response = postQuery(
            RANDOM_QUERY,
            JSONObject()
                .put("format", "anime")
                .put("allowAdult", false)
        )
        val items = response.optJSONObject("data")
            ?.optJSONArray("queryRandomRecommendation")
            ?: JSONArray()
        return items.asSequence()
            .mapNotNull { it as? JSONObject }
            .mapNotNull { it.toTvShow(detailed = false) }
            .take(limit)
            .toList()
    }

    private suspend fun searchShows(
        search: Map<String, Any?>,
        limit: Int,
        page: Int,
        countryOrigin: String? = null,
        hash: String = SEARCH_HASH
    ): List<TvShow> {
        val shows = buildList {
            for (translation in browseTranslationTypes) {
                val variables = JSONObject()
                    .put("search", JSONObject(search))
                    .put("limit", limit)
                    .put("page", page)
                    .put("translationType", translation)
                    .put("allowAdult", false)
                if (countryOrigin != null) variables.put("countryOrigin", countryOrigin)
                addAll(parseShows(api(variables, hash, SEARCH_QUERY).data))
            }
        }
        return shows
            .distinctBy { it.id }
            .take(limit)
    }

    private suspend fun searchItems(
        search: Map<String, Any?>,
        limit: Int,
        page: Int
    ): List<AppAdapter.Item> {
        val items = buildList {
            for (translation in browseTranslationTypes) {
                val variables = JSONObject()
                    .put("search", JSONObject(search))
                    .put("limit", limit)
                    .put("page", page)
                    .put("translationType", translation)
                    .put("allowAdult", false)
                addAll(parseSearchItems(api(variables, SEARCH_HASH, SEARCH_QUERY).data))
            }
        }
        return items
            .distinctBy { item ->
                when (item) {
                    is Movie -> item.id
                    is TvShow -> item.id
                    else -> item.itemType
                }
            }
            .take(limit)
    }

    private suspend fun searchMovies(page: Int, limit: Int = 26): List<Movie> {
        val movies = buildList {
            for (translation in browseTranslationTypes) {
                val variables = JSONObject()
                    .put("search", JSONObject(mapOf("sortBy" to "Popular", "types" to listOf("Movie"))))
                    .put("limit", limit)
                    .put("page", page)
                    .put("translationType", translation)
                    .put("allowAdult", false)
                addAll(
                    showEdges(api(variables, SEARCH_HASH, SEARCH_QUERY).data)
                        .asSequence()
                        .mapNotNull { it as? JSONObject }
                        .mapNotNull { it.toMovieOrNull(forceMovie = true) }
                        .toList()
                )
            }
        }
        return movies
            .distinctBy { it.id }
            .take(limit)
    }

    private suspend fun showDetails(id: String): TvShow {
        val show = showJson(id)
        return show.toTvShow(detailed = true) ?: throw Exception("MKissa show is missing required metadata")
    }

    private suspend fun showJson(id: String): JSONObject {
        val show = api(JSONObject().put("_id", id), DETAIL_HASH, DETAIL_QUERY).data
            .optJSONObject("data")
            ?.optJSONObject("show")
            ?: throw Exception("MKissa show not found")
        if (show.isAdultContent()) throw Exception("MKissa show not found")
        return show
    }

    private suspend fun api(variables: JSONObject, hash: String, fallbackQuery: String? = null, referer: String? = null): ApiResponse {
        val extensions = JSONObject()
            .put("persistedQuery", JSONObject().put("version", 1).put("sha256Hash", hash))
        val currentReferer = referer ?: "https://mkissa.to/"
        val responseStr = try {
            service.api(API_URL, variables.toString(), extensions.toString(), DEFAULT_BUILD_ID, currentReferer)
        } catch (error: HttpException) {
            Log.e(TAG, "api: GET failed with code ${error.code()}", error)
            if (fallbackQuery == null) throw error
            null
        }
        val response = responseStr?.let(::JSONObject)
        if (response != null && !response.shouldRetryWithQueryBody()) {
            if (response.hasNoGraphQlData()) throw response.toGraphQlException()
            return ApiResponse(response)
        }
        if (fallbackQuery == null) return ApiResponse(response ?: JSONObject())

        Log.d(TAG, "api: retrying with POST and query body")
        val body = JSONObject()
            .put("query", fallbackQuery)
            .put("variables", variables)
            .put("extensions", extensions)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val postResponse = JSONObject(service.apiPost(API_URL, body, DEFAULT_BUILD_ID, currentReferer))
        if (postResponse.hasNoGraphQlData()) throw postResponse.toGraphQlException()
        return ApiResponse(postResponse)
    }

    private suspend fun postQuery(query: String, variables: JSONObject): JSONObject {
        val body = JSONObject()
            .put("query", query)
            .put("variables", variables)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        return JSONObject(service.apiPost(API_URL, body, DEFAULT_BUILD_ID, "https://mkissa.to/"))
    }

    private fun parseShows(response: JSONObject): List<TvShow> {
        return showEdges(response)
            .mapNotNull { it as? JSONObject }
            .filterNot { it.isAdultContent() }
            .mapNotNull { it.toTvShow(detailed = false) }
            .toList()
    }

    private fun parseSearchItems(response: JSONObject): List<AppAdapter.Item> {
        val items = showEdges(response)
            .mapNotNull { it as? JSONObject }
            .filterNot { it.isAdultContent() }
            .mapNotNull { show ->
                val title = show.displayTitleOrNull() ?: "?"
                val type = show.stringOrNull("type") ?: "?"
                val genres = (0 until (show.optJSONArray("genres")?.length() ?: 0))
                    .map { show.optJSONArray("genres")!!.optString(it) }
                    .joinToString(",")
                val isAdult = show.opt("isAdult")
                Log.d(TAG, "RAW show: _id=${show.stringOrNull("_id")}, title=$title, type=$type, genres=[$genres], isAdult=$isAdult")
                if (show.stringOrNull("type").equals("Movie", ignoreCase = true)) {
                    show.toMovieOrNull(forceMovie = true)
                } else {
                    show.toTvShow(detailed = false)
                }
            }
            .toList()
        Log.d(TAG, "parseSearchItems: ${items.size} items parsed from search")
        items.forEach { item ->
            when (item) {
                is Movie -> Log.d(TAG, "  Movie: ${item.title} (genres: ${item.genres.map { it.name }})")
                is TvShow -> Log.d(TAG, "  TvShow: ${item.title} (genres: ${item.genres.map { it.name }})")
                else -> Log.d(TAG, "  Other: ${item.itemType}")
            }
        }
        return items
    }

    private fun showEdges(response: JSONObject): Sequence<Any?> {
        val edges = response.optJSONObject("data")
            ?.optJSONObject("shows")
            ?.optJSONArray("edges")
            ?: response.optJSONObject("data")
                ?.optJSONObject("queryListForTag")
                ?.optJSONArray("edges")
            ?: JSONArray()
        return edges.asSequence()
    }

    private fun JSONObject.shouldRetryWithQueryBody(): Boolean {
        val errors = optJSONArray("errors") ?: return false
        return errors.asSequence()
            .mapNotNull { it as? JSONObject }
            .any { error ->
                error.optString("message").contains("PersistedQueryNotFound", ignoreCase = true) ||
                        error.optString("message").contains("PersistedQueryNotSupported", ignoreCase = true) ||
                        error.optJSONObject("extensions")
                            ?.optString("code")
                            ?.contains("PERSISTED_QUERY", ignoreCase = true) == true
            }
    }

    private fun JSONObject.hasNoGraphQlData(): Boolean {
        return !has("data") || isNull("data")
    }

    private fun JSONObject.toGraphQlException(): Exception {
        val messages = optJSONArray("errors")
            ?.asSequence()
            ?.mapNotNull { it as? JSONObject }
            ?.mapNotNull { it.stringOrNull("message") }
            ?.distinct()
            ?.joinToString("; ")
            .orEmpty()
        return Exception(
            if (messages.isBlank()) "MKissa API returned no data" else "MKissa API error: $messages"
        )
    }

    private fun sha256Hex(value: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun JSONObject.firstStringOrNull(key: String): String? {
        val values = optJSONArray(key) ?: return null
        return values.asSequence()
            .mapNotNull { it as? String }
            .map { it.trim() }
            .firstOrNull {
                it.isNotBlank() &&
                        !it.equals("null", ignoreCase = true) &&
                        !it.equals("undefined", ignoreCase = true)
            }
    }

    private fun JSONArray.asSequence(): Sequence<Any?> = sequence {
        for (i in 0 until length()) yield(opt(i))
    }

    private fun currentAnimeSeason(month: Int): String {
        return when (month) {
            1, 2, 3 -> "Winter"
            4, 5, 6 -> "Spring"
            7, 8, 9 -> "Summer"
            else -> "Fall"
        }
    }

    private fun String.normalizedTagType(): String {
        return when (this) {
            "genre", "tag" -> "generic"
            "all" -> ""
            else -> this
        }
    }

    private fun String.slugify(): String {
        return lowercase()
            .replace("'", "")
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    }

    private fun String.humanizeSlug(): String {
        return replace('_', ' ')
            .replace('-', ' ')
            .split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase() else char.toString()
                }
            }
            .takeIf { it.isNotBlank() }
            ?: this
    }

    private data class ApiResponse(
        val data: JSONObject
    )

    private data class HomeTag(
        val slug: String,
        val name: String,
        val tagType: String? = null
    )

    private data class AvailableEpisodes(
        val translation: String,
        val count: Int
    )

    private val fallbackHomeTags = listOf(
        "Isekai",
        "Boys' Love",
        "Female Harem",
        "Yuri",
        "Reincarnation",
        "Male Protagonist",
        "Overpowered Protagonist",
        "Yandere",
        "Gyaru",
        "Cultivation",
        "Female Protagonist",
        "Full Color",
        "Magic",
        "Anti-Hero",
        "School",
        "POV",
        "Post-Apocalyptic",
        "Succubus",
        "Primarily Adult Cast",
        "Gender Bending"
    ).map { HomeTag(slug = it.slugify(), name = it) }

    private val genres = listOf(
        "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Isekai", "Magic", "Mystery",
        "Romance", "School", "Sci-Fi", "Seinen", "Shoujo", "Shounen", "Slice of Life",
        "Sports", "Super Power", "Supernatural", "Thriller"
    ).map { Genre(id = it.lowercase().replace(" ", "_"), name = it) }
    private fun parsePopular(response: JSONObject): List<TvShow> {
        val recommendations = response.optJSONObject("data")
            ?.optJSONObject("queryPopular")
            ?.optJSONArray("recommendations")
            ?: JSONArray()
        return recommendations.asSequence()
            .mapNotNull { (it as? JSONObject)?.optJSONObject("anyCard") }
            .mapNotNull { it.toTvShow(detailed = false) }
            .toList()
    }

    private fun JSONObject.toTvShow(detailed: Boolean): TvShow? {
        if (isAdultContent()) return null
        val rawId = stringOrNull("_id") ?: return null
        val isMovie = stringOrNull("type").equals("Movie", ignoreCase = true)
        val id = if (isMovie) "movie:$rawId" else rawId
        val title = displayTitleOrNull() ?: return null
        val overview = stringOrNull("description")?.let { Jsoup.parse(it).text() }
        // Browse results are compared by RecyclerView's DiffUtil. Keeping episode
        // graphs on those cards lets TvShow.episodeToWatch attach TvShow/Season
        // back-references, which makes model equality recurse indefinitely.
        // Episode metadata is only needed by the detail response.
        val availableEpisodes = if (detailed) {
            availableEpisodeTranslation(isMovie = isMovie)
        } else {
            null
        }
        val runtime = stringOrNull("episodeDuration")?.toLongOrNull()?.let { (it / 60000L).toInt() }

        return TvShow(
            id = id,
            title = title,
            overview = overview,
            released = dateString(optJSONObject("airedStart")),
            runtime = runtime,
            rating = optDoubleOrNull("score"),
            poster = imageUrl(stringOrNull("thumbnail")),
            banner = imageUrl(stringOrNull("banner")),
            genres = optJSONArray("genres")?.asSequence()
                ?.mapNotNull { it as? String }
                ?.map { Genre(id = it.lowercase().replace(" ", "_"), name = it) }
                ?.toList()
                ?: emptyList(),
            seasons = if (availableEpisodes != null) {
                listOf(
                    Season(
                        id = "$rawId|${availableEpisodes.translation}",
                        number = 1,
                        title = "Episodes",
                        episodes = buildEpisodes(rawId, availableEpisodes.count, availableEpisodes.translation)
                    )
                )
            } else {
                emptyList()
            }
        )
    }

    private fun JSONObject.availableEpisodeTranslation(isMovie: Boolean): AvailableEpisodes? {
        return translationTypes
            .firstNotNullOfOrNull { translation ->
                val count = availableEpisodeCount(translation = translation, isMovie = isMovie)
                if (count > 0) AvailableEpisodes(translation = translation, count = count) else null
            }
    }

    private fun JSONObject.availableEpisodeCount(translation: String, isMovie: Boolean): Int {
        val available = optJSONObject("availableEpisodes")
        if (available != null && available.has(translation) && !available.isNull(translation)) {
            return available.optInt(translation, 0).coerceAtLeast(0)
        }

        optJSONObject("availableEpisodesDetail")
            ?.optJSONArray(translation)
            ?.let { return it.length().coerceAtLeast(0) }

        return stringOrNull("episodeCount")?.toIntOrNull()?.coerceAtLeast(0)
            ?: optJSONObject("lastEpisodeInfo")
                ?.optJSONObject(translation)
                ?.optString("episodeString")
                ?.toIntOrNull()
                ?.coerceAtLeast(0)
            ?: if (isMovie) 1 else 0
    }

    private fun JSONObject.toMovieOrNull(forceMovie: Boolean = false): Movie? {
        if (isAdultContent()) return null
        val rawId = stringOrNull("_id") ?: return null
        val isMovie = stringOrNull("type").equals("Movie", ignoreCase = true)
        if (!forceMovie && !isMovie) return null
        val title = displayTitleOrNull() ?: return null
        val overview = stringOrNull("description")?.let { Jsoup.parse(it).text() }
        val runtime = stringOrNull("episodeDuration")?.toLongOrNull()?.let { (it / 60000L).toInt() }
        return Movie(
            id = "movie:$rawId",
            title = title,
            overview = overview,
            released = dateString(optJSONObject("airedStart")),
            runtime = runtime,
            rating = optDoubleOrNull("score"),
            poster = imageUrl(stringOrNull("thumbnail")),
            banner = imageUrl(stringOrNull("banner")),
            genres = optJSONArray("genres")?.asSequence()
                ?.mapNotNull { it as? String }
                ?.map { Genre(id = it.lowercase().replace(" ", "_"), name = it) }
                ?.toList()
                ?: emptyList()
        )
    }

    private fun JSONObject.displayTitleOrNull(): String? {
        return stringOrNull("englishName")
            ?: stringOrNull("name")
            ?: stringOrNull("nativeName")
            ?: firstStringOrNull("altNames")
            ?: stringOrNull("nameOnlyString")?.humanizeSlug()
            ?: stringOrNull("slugTime")?.humanizeSlug()
    }

    private fun TvShow.toMovie(): Movie {
        return Movie(
            id = id,
            title = title,
            overview = overview,
            released = released?.let { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(it.time) },
            runtime = runtime,
            rating = rating,
            poster = poster,
            banner = banner,
            genres = genres
        )
    }

    private fun buildEpisodes(showId: String, count: Int, translation: String): List<Episode> {
        if (count <= 0) return emptyList()
        return (1..count).map { number ->
            Episode(
                id = listOf(showId, number.toString(), translation).joinToString("|"),
                number = number,
                title = "Episode $number"
            )
        }
    }

    private fun imageUrl(value: String?): String? {
        val image = value?.takeIf { it.isNotBlank() } ?: return null
        val url = when {
            image.contains("/_tbs/") || image.contains("_tbs/") -> image
                .removePrefix("https://wp.youtube-anime.com/")
                .removePrefix("https://aln.youtube-anime.com/")
                .removePrefix("/")
                .substringBefore("?")
                .let { "$IMAGE_URL/$it?w=250" }
            image.startsWith("http") -> image
            image.startsWith("//") -> "https:$image"
            image.startsWith("images") -> "$IMAGE_URL/$image?w=250"
            else -> "$IMAGE_URL/images/$image?w=250"
        }
        return if (url.contains("youtube-anime.com")) {
            ArtworkRequestHeaders.withHeaders(
                url = url,
                referer = baseUrl,
                origin = "https://mkissa.to",
                userAgent = "Mozilla/5.0",
                accept = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
            )
        } else {
            url
        }
    }

    private fun dateString(date: JSONObject?): String? {
        val year = date?.optInt("year", 0)?.takeIf { it > 0 } ?: return null
        val month = date.optInt("month", 1).coerceIn(1, 12)
        val day = date.optInt("date", 1).coerceIn(1, 31)
        return "%04d-%02d-%02d".format(year, month, day)
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        return if (has(key) && !isNull(key)) optDouble(key) else null
    }

    private fun JSONObject.isAdultContent(): Boolean {
        if (!has("isAdult") || isNull("isAdult")) return false
        return when (val value = opt("isAdult")) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.equals("true", ignoreCase = true) || value == "1"
            else -> false
        }
    }

    private fun JSONObject.sourceUrl(): String {
        return stringOrNull("sourceUrl")
            ?: stringOrNull("url")
            ?: stringOrNull("source")
            ?: ""
    }

    private fun JSONObject.isKnownDeadEmbedSource(): Boolean {
        val source = sourceUrl().lowercase()
        return source.contains("streamsb.net") ||
                source.contains("streamlare.com")
    }

    private suspend fun resolveSourceUrl(value: String): String? {
        if (value.isBlank()) return null

        val decoded = decodePackedSourceUrl(value)
        val normalized = when {
            decoded.startsWith("//") -> "https:$decoded"
            decoded.startsWith("http", ignoreCase = true) -> decoded
            decoded.startsWith("/apivtwo/", ignoreCase = true) -> resolveAllanimeClockSource(decoded)
            else -> decoded.takeIf { it.isNotBlank() }
        }

        return normalized?.takeIf { it.isNotBlank() }
    }

    private fun decodePackedSourceUrl(value: String): String {
        if (!value.startsWith("--")) return value

        val bytes = runCatching {
            value.removePrefix("--")
                .chunked(2)
                .map { pair -> pair.toInt(16).xor(56).toByte() }
                .toByteArray()
        }.getOrNull() ?: return value

        return bytes.toString(Charsets.UTF_8).trim()
    }

    private suspend fun resolveAllanimeClockSource(path: String): String? {
        val normalizedPath = when {
            path.contains("/apivtwo/clock.json", ignoreCase = true) -> path
            path.contains("/apivtwo/clock?", ignoreCase = true) ->
                path.replace("/apivtwo/clock?", "/apivtwo/clock.json?", ignoreCase = true)
            else -> return null
        }
        val requestUrl = "$CLOCK_URL$normalizedPath" +
                if (normalizedPath.contains("referer=", ignoreCase = true)) "" else "&referer="
        val request = Request.Builder()
            .url(requestUrl)
            .header("Accept", "application/json, text/plain, */*")
            .header("Origin", "https://allanime.to")
            .header("Referer", "https://allanime.to/")
            .header("User-Agent", "Mozilla/5.0")
            .build()
        val body = sourceResolverClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string()
        } ?: return null
        return findClockLinks(body).firstOrNull()
    }

    private fun findClockLinks(body: String): Sequence<String> = sequence {
        val root: Any = runCatching { JSONObject(body) }.getOrNull()
            ?: runCatching { JSONArray(body) }.getOrNull()
            ?: return@sequence
        yieldAll(findClockLinks(root))
    }

    private fun findClockLinks(value: Any): Sequence<String> = sequence {
        when (value) {
            is JSONObject -> value.keys().forEach { key ->
                val child = value.opt(key)
                if (key.equals("link", true) || key.equals("url", true) ||
                    key.equals("sourceUrl", true) || key.equals("file", true) ||
                    key.equals("hls", true) || key.equals("mp4", true)
                ) {
                    val link = child as? String
                    if (!link.isNullOrBlank() && link.startsWith("http", true)) yield(link)
                }
                if (child is JSONObject || child is JSONArray) yieldAll(findClockLinks(child))
            }
            is JSONArray -> for (index in 0 until value.length()) {
                val child = value.opt(index)
                if (child is JSONObject || child is JSONArray) yieldAll(findClockLinks(child))
                else if (child is String && child.startsWith("http", true)) yield(child)
            }
        }
    }

    /*
        val normalizedPath = path.replace("/apivtwo/clock?", "/apivtwo/clock.json?")
        val request = Request.Builder()
            .url("$CLOCK_URL$normalizedPath")
            .header("Accept", "application/json")
            .header("Origin", CLOCK_URL)
            .header("Referer", "$CLOCK_URL/player.html")
            .header("User-Agent", "Mozilla/5.0")
            .build()

        val body = sourceResolverClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string()
        } ?: return null

        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val links = json.optJSONArray("links") ?: return null

        for (i in 0 until links.length()) {
            val linkObject = links.optJSONObject(i) ?: continue
            val link = linkObject.stringOrNull("link")
                ?: linkObject.stringOrNull("url")
                ?: linkObject.stringOrNull("sourceUrl")
                ?: linkObject.stringOrNull("file")
            if (!link.isNullOrBlank()) return link
        }

        return null*/

    private fun directPlaybackHeaders(): Map<String, String> {
        return mapOf(
            "Accept" to "*/*",
            "Origin" to CLOCK_URL,
            "Referer" to "$CLOCK_URL/",
            "User-Agent" to "Mozilla/5.0"
        )
    }

    private fun JSONObject.stringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key)
            .trim()
            .takeUnless {
                it.isBlank() ||
                        it.equals("null", ignoreCase = true) ||
                        it.equals("undefined", ignoreCase = true)
            }
    }

}
