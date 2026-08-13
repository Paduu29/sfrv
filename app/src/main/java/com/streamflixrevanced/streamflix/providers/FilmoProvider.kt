package com.streamflixrevanced.streamflix.providers

import com.streamflixrevanced.streamflix.adapters.AppAdapter
import com.streamflixrevanced.streamflix.extractors.Extractor
import com.streamflixrevanced.streamflix.models.Category
import com.streamflixrevanced.streamflix.models.Genre
import com.streamflixrevanced.streamflix.models.Movie
import com.streamflixrevanced.streamflix.models.People
import com.streamflixrevanced.streamflix.models.TvShow
import com.streamflixrevanced.streamflix.models.Video
import com.streamflixrevanced.streamflix.utils.DnsResolver
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object FilmoProvider : Provider {

    override val baseUrl = "https://filmo.to"
    override val name = "Filmo"
    override val logo = "$baseUrl/web-app-manifest-512x512.png"
    override val language = "de"

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
    private class MemoryCookieJar : CookieJar {

        private val cookies = mutableListOf<Cookie>()

        @Synchronized
        override fun saveFromResponse(
            url: HttpUrl,
            newCookies: List<Cookie>
        ) {
            newCookies.forEach { cookie ->
                cookies.removeAll {
                    it.name == cookie.name &&
                            it.domain == cookie.domain &&
                            it.path == cookie.path
                }

                if (cookie.expiresAt > System.currentTimeMillis()) {
                    cookies += cookie
                }
            }
        }

        @Synchronized
        override fun loadForRequest(
            url: HttpUrl
        ): List<Cookie> {
            val now = System.currentTimeMillis()

            cookies.removeAll {
                it.expiresAt <= now
            }

            return cookies.filter {
                it.matches(url)
            }
        }
    }

    private val cookieJar = MemoryCookieJar()

    private val client = OkHttpClient.Builder()
        .dns(DnsResolver.doh)
        .cookieJar(cookieJar)
        .addInterceptor { chain ->
            val request = chain.request()
                .newBuilder()
                .header("User-Agent", USER_AGENT)
                .header(
                    "Accept-Language",
                    "de,en-US;q=0.9,en;q=0.8"
                )
                .build()

            chain.proceed(request)
        }
        .followRedirects(true)
        .followSslRedirects(true)
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .build()
    private val noRedirectClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private interface Service {

        @GET
        suspend fun page(
            @Url url: String
        ): Document
    }

    private val service = Retrofit.Builder()
        .baseUrl("$baseUrl/")
        .addConverterFactory(JsoupConverterFactory.create())
        .client(client)
        .build()
        .create(Service::class.java)

override suspend fun getHome(): List<Category> {
    val document = service.page(baseUrl)

    return document
        .select(".video-row")
        .mapNotNull { row ->

            val heading = row
                .selectFirst(
                    "h2:has(a[href*=/collections/]), " +
                            "h3:has(a[href*=/collections/])"
                )
                ?: return@mapNotNull null

            val title = heading
                .ownText()
                .trim()
                .replace(Regex("\\s+"), " ")
                .takeIf { text -> text.isNotBlank() }
                ?: return@mapNotNull null

            val movies = row
                .select("a.video-card[href*=/movies/]")
                .mapNotNull(::parseVideoCard)
                .distinctBy { movie -> movie.id }

            if (movies.isEmpty()) {
                return@mapNotNull null
            }

            Category(
                title,
                movies
            )
        }
        .distinctBy { category -> category.name }
}

    override suspend fun search(
        query: String,
        page: Int
    ): List<AppAdapter.Item> {
        if (query.isBlank()) {
            if (page > 1) {
                return emptyList()
            }

            val document = service.page(baseUrl)

            return document
                .select("a[href*=/genres/]")
                .mapNotNull { anchor ->

                    val id = anchor.absUrl("href")
                    val title = anchor.text().trim()

                    if (
                        id.isBlank() ||
                        title.isBlank()
                    ) {
                        null
                    } else {
                        Genre(
                            id = id,
                            name = title
                        )
                    }
                }
                .distinctBy { it.id }
        }

        val encoded = URLEncoder.encode(
            query,
            "UTF-8"
        )

        val url = buildString {
            append("$baseUrl/search?q=$encoded")

            if (page > 1) {
                append("&page=$page")
            }
        }

        val document = service.page(url)

        return parseSearchResults(document)
    }

    override suspend fun getMovies(
        page: Int
    ): List<Movie> {

        val url =
            if (page <= 1) {
                "$baseUrl/movies"
            } else {
                "$baseUrl/movies?page=$page"
            }

        return service
            .page(url)
            .select(
                "a.movie-poster-grid-card[href*=/movies/]"
            )
            .mapNotNull(::parsePosterCard)
            .distinctBy { it.id }
    }


    override suspend fun getTvShows(
        page: Int
    ): List<TvShow> =
        emptyList()

    override suspend fun getTvShowFromProvider(
        id: String
    ): TvShow =
        throw UnsupportedOperationException(
            "Filmo is a movie-only provider"
        )

    override suspend fun getMovieFromProvider(
        id: String
    ): Movie {

        val document = service.page(id)

        val title = document
            .selectFirst("h1")
            ?.text()
            ?.trim()
            .orEmpty()

        val overview = document
            .selectFirst(".movie-detail-synopsis")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document
                .selectFirst("meta[name=description]")
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotBlank() }

        val released = metaValue(
            document,
            "Erscheinungsdatum"
        )

        val runtime = metaValue(
            document,
            "Laufzeit"
        )?.let(::parseRuntime)

        val rating = document
            .select("span.ft-meta-label, .ft-meta-definition-list dt")
            .firstOrNull {
                val t = it.text()
                t.contains("IMDb", ignoreCase = true) || 
                t.contains("Bewertung", ignoreCase = true) ||
                t.contains("Rating", ignoreCase = true)
            }
            ?.let { el ->
                // Check current text, next sibling, and parent for the value
                val text = el.text() + " " + (el.nextSibling()?.toString() ?: "") + " " + (el.parent()?.text() ?: "")
                Regex("""(?:IMDb|Bewertung|Rating)[:\s]*(\d+(?:[.,]\d+)?)""", RegexOption.IGNORE_CASE)
                    .find(text)
                    ?.groupValues
                    ?.get(1)
                    ?.replace(',', '.')
                    ?.toDoubleOrNull()
            } ?: Regex("""(?:IMDb|Bewertung)[:\s]*(\d+(?:[.,]\d+)?)""", RegexOption.IGNORE_CASE)
                .find(document.text())
                ?.groupValues
                ?.get(1)
                ?.replace(',', '.')
                ?.toDoubleOrNull()



        val poster = document
            .selectFirst(".movie-poster-modal__img")
            ?.let { img ->
                img.absUrl("data-src")
                    .ifBlank { img.attr("data-src") }
                    .ifBlank { img.absUrl("src") }
                    .ifBlank { img.attr("src") }
            }
            ?.takeIf { it.isNotBlank() }
            ?: document
                .selectFirst("""img[src*="/img/poster/"]""")
                ?.absUrl("src")
                ?.takeIf { it.isNotBlank() }

        val banner = document
            .selectFirst("meta[property=og:image]")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }

        val trailer = document
            .selectFirst(
                ".movie-trailer-modal__iframe[data-embed-src]"
            )
            ?.attr("data-embed-src")
            ?.takeIf { it.isNotBlank() }
            ?.let(::youtubeEmbedToWatchUrl)

        val genres = (metaLinks(document, "Genres") + metaLinks(document, "Genre")).distinctBy { it.text().trim().lowercase() }.map { anchor ->
            Genre(
                id = anchor
                    .absUrl("href")
                    .ifBlank {
                        anchor.attr("href")
                    },
                name = anchor.text().trim()
            )
        }

        val cast = (metaLinks(document, "Besetzung") + metaLinks(document, "Cast")).distinctBy { it.text().trim().lowercase() }.map { anchor ->
            People(
                id = anchor
                    .absUrl("href")
                    .ifBlank {
                        anchor.attr("href")
                    },
                name = anchor.text().trim()
            )
        }

        return Movie(
            id = id,
            title = title,
            overview = overview,
            released = released,
            runtime = runtime,
            rating = rating,
            poster = poster,
            banner = banner,
            trailer = trailer,
            genres = genres,
            cast = cast
        )
    }

    override suspend fun getGenre(
        id: String,
        page: Int
    ): Genre {

        val document = service.page(
            appendPage(id, page)
        )

        val title = document
            .selectFirst("h1")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: id
                .substringAfterLast('/')
                .substringBefore('?')
                .replace('-', ' ')

        val movies = document
            .select(
                "a.movie-poster-grid-card[href*=/movies/]"
            )
            .mapNotNull(::parsePosterCard)
            .distinctBy { it.id }

        return Genre(
            id = id,
            name = title,
            shows = movies
        )
    }

    override suspend fun getPeople(
        id: String,
        page: Int
    ): People {

        val document = service.page(
            appendPage(id, page)
        )

        val name = document
            .selectFirst("h1")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: id
                .substringAfterLast('/')
                .substringBefore('?')
                .replace('-', ' ')

        val movies = document
            .select(
                """
                a.movie-poster-grid-card[href*=/movies/],
                a.video-card[href*=/movies/]
                """.trimIndent()
            )
            .mapNotNull { anchor ->

                if (
                    anchor.hasClass(
                        "movie-poster-grid-card"
                    )
                ) {
                    parsePosterCard(anchor)
                } else {
                    parseVideoCard(anchor)
                }
            }
            .distinctBy { it.id }

        return People(
            id = id,
            name = name,
            filmography = movies
        )
    }

    override suspend fun getServers(
        id: String,
        videoType: Video.Type
    ): List<Video.Server> {
        val request = Request.Builder()
            .url(id)
            .get()
            .header("Referer", baseUrl)
            .build()

        val document = client
            .newCall(request)
            .execute()
            .use { response ->

                if (!response.isSuccessful) {
                    error(
                        "Filmo movie page failed: " +
                                "HTTP ${response.code}"
                    )
                }

                val html = response
                    .body
                    ?.string()
                    ?: error(
                        "Filmo returned an empty movie page"
                    )

                Jsoup.parse(
                    html,
                    id
                )
            }

        val csrfToken = document
            .selectFirst("meta[name=csrf-token]")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: error(
                "Filmo CSRF token not found"
            )

        return document
            .select(".provider-chip[data-p]")
            .mapNotNull { chip ->

                val payload = chip
                    .attr("data-p")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val provider = chip
                    .selectFirst(
                        ".provider-chip__name"
                    )
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: chip
                        .attr("aria-label")
                        .trim()
                        .takeIf { it.isNotBlank() }
                    ?: "Server"

                val quality = chip
                    .selectFirst(
                        ".provider-chip__metadata-tag"
                    )
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }

                val language = chip
                    .closest(".provider-row")
                    ?.selectFirst(
                        ".provider-row__lang"
                    )
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }

                val serverName = listOfNotNull(
                    provider,
                    quality,
                    language
                ).joinToString(" • ")

                val providerUrl = resolveFilmoServer(
                    payload = payload,
                    movieUrl = id,
                    csrfToken = csrfToken
                )

                Video.Server(
                    id = providerUrl,
                    name = serverName,
                    src = providerUrl
                )
            }
            .distinctBy { it.src }
    }
    private fun resolveFilmoServer(
        payload: String,
        movieUrl: String,
        csrfToken: String
    ): String {

        val json = JSONObject()
            .put("p", payload)
            .toString()

        val body = json.toRequestBody(
            "application/json; charset=UTF-8"
                .toMediaType()
        )

        val resolveRequest = Request.Builder()
            .url("$baseUrl/n")
            .post(body)
            .header("Referer", movieUrl)
            .header("Origin", baseUrl)
            .header(
                "X-Requested-With",
                "XMLHttpRequest"
            )
            .header(
                "X-CSRF-TOKEN",
                csrfToken
            )
            .header(
                "Accept",
                "application/json, text/plain, */*"
            )
            .build()

        val token = client
            .newCall(resolveRequest)
            .execute()
            .use { response ->

                val responseBody = response
                    .body
                    ?.string()
                    .orEmpty()

                if (!response.isSuccessful) {
                    error(
                        "Filmo /n failed: " +
                                "HTTP ${response.code}: " +
                                responseBody
                    )
                }

                JSONObject(responseBody)
                    .optString("x")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?: error(
                        "Filmo /n returned no x token: " +
                                responseBody
                    )
            }
        val redirectRequest = Request.Builder()
            .url("$baseUrl/n/$token")
            .get()
            .header("Referer", movieUrl)
            .build()

        return noRedirectClient
            .newCall(redirectRequest)
            .execute()
            .use { response ->

                val location = response
                    .header("Location")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }

                location
                    ?: error(
                        "Filmo /n/$token did not return " +
                                "a provider redirect " +
                                "(HTTP ${response.code})"
                    )
            }
    }
    override suspend fun getVideo(
        server: Video.Server
    ): Video =
        Extractor.extract(
            server.src,
            server
        )
    private fun parseSearchResults(
        document: Document
    ): List<AppAdapter.Item> {

        val spotlight = document
            .select(
                ".search-top-results a[href*=/movies/]"
            )
            .mapNotNull { anchor ->

                val id = anchor
                    .absUrl("href")
                    .takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val image = anchor
                    .selectFirst("img")

                val title = anchor
                    .selectFirst(
                        ".popular-spotlight-card__title"
                    )
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: image
                        ?.attr("alt")
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                Movie(
                    id = id,
                    title = title,
                    poster = image
                        ?.absUrl("src")
                        ?.takeIf { it.isNotBlank() }
                )
            }

        val cards = document
            .select(
                "a.movie-poster-grid-card[href*=/movies/]"
            )
            .mapNotNull(::parsePosterCard)

        return (spotlight + cards)
            .distinctBy { it.id }
    }

    private fun parsePosterCard(
        anchor: Element
    ): Movie? {

        val id = anchor
            .absUrl("href")
            .takeIf { it.isNotBlank() }
            ?: return null

        val image = anchor.selectFirst(
            ".movie-poster-grid-card__img"
        )

        val title = anchor
            .selectFirst(
                ".movie-poster-grid-card__title"
            )
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: image
                ?.attr("alt")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: return null

        return Movie(
            id = id,
            title = title,
            poster = image
                ?.absUrl("src")
                ?.takeIf { it.isNotBlank() }
        )
    }
    private fun parseVideoCard(anchor: Element): Movie? {
        val href = anchor
            .absUrl("href")
            .takeIf { url -> url.isNotBlank() }
            ?: return null

        val image = anchor.selectFirst(".card-image img")
            ?: anchor.selectFirst("img")

        val title = anchor
            .selectFirst(".swiper-card-title")
            ?.text()
            ?.trim()
            ?.takeIf { text -> text.isNotBlank() }
            ?: image
                ?.attr("alt")
                ?.trim()
                ?.takeIf { text -> text.isNotBlank() }
            ?: return null

        val poster = image
            ?.absUrl("src")
            ?.takeIf { url -> url.isNotBlank() }

        return Movie(
            id = href,
            title = title,
            poster = poster
        )
    }
    private fun metaValue(
        document: Document,
        label: String
    ): String? {

        return document
            .select(
                ".ft-meta-definition-list"
            )
            .firstOrNull { dl ->

                dl.selectFirst("dt")
                    ?.text()
                    ?.trim()
                    ?.equals(
                        label,
                        ignoreCase = true
                    ) == true
            }
            ?.selectFirst("dd")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun metaLinks(
        document: Document,
        label: String
    ): List<Element> {

        return document
            .select(
                ".ft-meta-definition-list"
            )
            .firstOrNull { dl ->

                dl.selectFirst("dt")
                    ?.text()
                    ?.trim()
                    ?.equals(
                        label,
                        ignoreCase = true
                    ) == true
            }
            ?.select("dd a[href]")
            .orEmpty()
    }

    private fun parseRuntime(
        text: String
    ): Int? {
        Regex(
            """(\d+)\s*h(?:\s*(\d+)\s*min)?""",
            RegexOption.IGNORE_CASE
        )
            .find(text)
            ?.let { match ->

                val hours = match
                    .groupValues[1]
                    .toIntOrNull()
                    ?: 0

                val minutes = match
                    .groupValues
                    .getOrNull(2)
                    ?.toIntOrNull()
                    ?: 0

                return hours * 60 + minutes
            }
        return Regex(
            """(\d+)\s*min""",
            RegexOption.IGNORE_CASE
        )
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    }

    private fun appendPage(
        url: String,
        page: Int
    ): String {

        if (page <= 1) {
            return url
        }

        return buildString {
            append(url)

            append(
                if ('?' in url) "&"
                else "?"
            )

            append("page=")
            append(page)
        }
    }

    private fun youtubeEmbedToWatchUrl(
        url: String
    ): String {

        val videoId = url
            .substringAfter(
                "/embed/",
                ""
            )
            .substringBefore('?')

        return if (videoId.isBlank()) {
            url
        } else {
            "https://www.youtube.com/watch?v=$videoId"
        }
    }

}