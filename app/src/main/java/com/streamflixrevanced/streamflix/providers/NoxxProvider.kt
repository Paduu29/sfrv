package com.streamflixrevanced.streamflix.providers

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
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
import com.streamflixrevanced.streamflix.utils.DnsResolver
import okhttp3.OkHttpClient
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Multipart
import retrofit2.http.Part
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.QueryMap
import retrofit2.http.Url
import okhttp3.ResponseBody
import com.google.gson.JsonParser
import java.net.URLEncoder
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

object NoxxProvider : Provider {
    override val baseUrl = "https://noxx.to"
    override val name = "NOXX"
    override val logo = "$baseUrl/assets/android-icon-192x192.png"
    override val language = "en"
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"

    private data class VerificationRequest(val token: String)
    private data class VerificationResponse(val ok: Boolean = false)

    private class MemoryCookieJar : CookieJar {
        private val cookies = mutableListOf<Cookie>()

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.filter { it.matches(url) }

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, newCookies: List<Cookie>) {
            newCookies.forEach { cookie ->
                cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                if (cookie.expiresAt >= System.currentTimeMillis()) cookies.add(cookie)
            }
        }
    }

    private interface Service {
        @GET
        suspend fun page(@Url url: String): Document
        @GET("browse")
        suspend fun search(@Query("q") query: String): Document

        @GET("api/load-more-browse")
        suspend fun loadMoreBrowse(@QueryMap params: Map<String, String>): ResponseBody

        @Multipart
        @POST("verified")
        suspend fun verify(@Part("token") token: okhttp3.RequestBody): VerificationResponse
    }

    private val cookieJar = MemoryCookieJar()
    private val client = OkHttpClient.Builder()
        .dns(DnsResolver.doh)
        .cookieJar(cookieJar)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).header("Referer", baseUrl).build())
        }
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .build()

    private val service = Retrofit.Builder().baseUrl("$baseUrl/")
        .addConverterFactory(JsoupConverterFactory.create())
        .addConverterFactory(GsonConverterFactory.create())
        .client(client)
        .build().create(Service::class.java)

    private suspend fun page(url: String): Document {
        var document = service.page(url)
        val token = Regex("(?:verifyToken|token)\\s*=\\s*['\"]([^'\"]+)")
            .find(document.select("script").html())?.groupValues?.get(1)
        if (token != null && service.verify(token.toRequestBody("text/plain".toMediaType())).ok) {
            document = service.page(url)
        }
        return document
    }

    override suspend fun getHome(): List<Category> {
        val document = page(baseUrl)
        return listOf(
            Category(
                Category.FEATURED,
                document.select("section.hero article.hero-slide").mapNotNull(::parseHeroShow)
            ),
            Category("Aired This Week", parseHomeSection(document, "Aired This Week")),
            Category("Top Rated", parseHomeSection(document, "Top Rated")),
            Category("Recently Added", parseHomeSection(document, "Recently Added"))
        ).filter { it.list.isNotEmpty() }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            if (page > 1) return emptyList()
            return page(baseUrl).select("a[href*='/browse?g=']")
                .mapNotNull { anchor ->
                    val href = anchor.absUrl("href")
                    val name = anchor.text().trim()
                    if (href.isBlank() || name.isBlank()) null else Genre(href, name)
                }
                .distinctBy { it.id }
        }
        if (page == 1) return parseCards(page("$baseUrl/browse?q=${URLEncoder.encode(query, "UTF-8")}"))
        return parseApiSeries(service.loadMoreBrowse(mapOf("page" to page.toString(), "q" to query)).string())
    }

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()
    override suspend fun getTvShows(page: Int): List<TvShow> = if (page == 1) {
        parseCards(page("$baseUrl/browse")).filterIsInstance<TvShow>()
    } else {
        parseApiSeries(service.loadMoreBrowse(mapOf("page" to page.toString())).string())
    }

    override suspend fun getMovieFromProvider(id: String): Movie =
        throw UnsupportedOperationException("NOXX is a TV-show-only provider")

    override suspend fun getTvShowFromProvider(id: String): TvShow {
        val document = page(id)
        val showPath = Regex("$baseUrl/tv/([^/]+)").find(id)?.groupValues?.get(1).orEmpty()
        val poster = document.selectFirst(".watch-hero__poster")?.absUrl("src")
        val seasons = document.select(".season-tab[data-season]").mapNotNull { tab ->
            val number = tab.attr("data-season").toIntOrNull() ?: return@mapNotNull null
            Season("$baseUrl/tv/$showPath/$number/1", number, "Season $number", poster)
        }
        return TvShow(
            id = "$baseUrl/tv/$showPath/1/1",
            title = document.selectFirst(".watch-hero__title")?.text()?.trim().orEmpty(),
            overview = document.selectFirst(".watch-hero__overview")?.text()?.trim(),
            rating = document.selectFirst(".watch-hero__facts .rating")?.text()?.toDoubleOrNull(),
            poster = poster,
            banner = document.selectFirst(".watch-hero__backdrop")?.absUrl("src"),
            genres = document.select(".genre-list a[href]").map { Genre(it.attr("href"), it.text()) },
            seasons = seasons
        )
    }

    override suspend fun getEpisodesByProvider(seasonId: String): List<Episode> {
        val document = page(seasonId)
        val season = Regex("/tv/[^/]+/(\\d+)/1").find(seasonId)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        return document.select("#season-$season a.episode-card").mapNotNull { card ->
            val number = Regex("/(\\d+)$").find(card.attr("href"))?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            Episode(card.absUrl("href"), number, card.selectFirst(".episode-card__title")?.text(), card.selectFirst(".episode-card__date")?.text(), card.selectFirst("img")?.absUrl("src"), card.selectFirst(".episode-card__overview")?.text())
        }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val genre = URLDecoder.decode(id.substringAfter("g=", id).substringBefore('&'), "UTF-8")
            .replace('+', ' ')
        val shows = if (page == 1) {
            parseCards(page(if (id.startsWith("http")) id else "$baseUrl/browse?g=${URLEncoder.encode(genre, "UTF-8")}"))
        } else {
            parseApiSeries(service.loadMoreBrowse(mapOf("page" to page.toString(), "g" to genre)).string())
        }
        return Genre(id, genre, shows.filterIsInstance<TvShow>())
    }
    override suspend fun getPeople(id: String, page: Int) = People(id, id, filmography = emptyList())

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = page(id).select(".server-btn[value]").map {
        Video.Server(it.attr("value"), it.attr("data-name").ifBlank { "Server" }, it.attr("value"))
    }

    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.src, server)

    private fun parseHomeSection(document: Document, title: String): List<AppAdapter.Item> {
        val section = document.select("section.section").firstOrNull {
            it.selectFirst(".section__title")?.text()?.trim()?.equals(title, ignoreCase = true) == true
        }
        return section?.let(::parseCards).orEmpty()
    }

    private fun parseHeroShow(slide: Element): TvShow? {
        val watch = slide.selectFirst(".hero-slide__actions a[href*='/tv/']") ?: return null
        val href = watch.absUrl("href").takeIf { it.isNotBlank() } ?: return null
        val parts = Regex("/tv/([^/]+)(?:/(\\d+)/(\\d+))?$").find(href) ?: return null
        val seasonNumber = parts.groupValues.getOrNull(2)?.toIntOrNull()
        val episodeNumber = parts.groupValues.getOrNull(3)?.toIntOrNull()
        val style = slide.selectFirst(".hero-slide__bg")?.attr("style").orEmpty()
        val banner = Regex("url\\(['\"]?([^'\")]+)").find(style)?.groupValues?.get(1)
        val episodes = episodeNumber?.let { listOf(Episode(href, it)) }.orEmpty()
        val seasons = seasonNumber?.let {
            listOf(Season("$baseUrl/tv/${parts.groupValues[1]}/$it/1", it, episodes = episodes))
        }.orEmpty()

        return TvShow(
            id = href,
            title = slide.selectFirst(".hero-slide__title")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: slide.attr("aria-label").trim(),
            overview = slide.selectFirst(".hero-slide__overview")?.text()?.trim(),
            rating = slide.selectFirst(".hero-slide__meta .rating")?.text()
                ?.let { Regex("\\d+(?:\\.\\d+)?").find(it)?.value?.toDoubleOrNull() },
            poster = banner,
            banner = banner,
            genres = slide.select(".hero-slide__meta a[href*='g=']").map {
                Genre(it.absUrl("href"), it.text().trim())
            },
            seasons = seasons
        )
    }

    private fun parseCards(container: Element): List<AppAdapter.Item> = container.select("a.poster-card[href*='/tv/']").mapNotNull { card ->
        val href = card.absUrl("href")
        val parts = Regex("/tv/([^/]+)(?:/(\\d+)/(\\d+))?$").find(href) ?: return@mapNotNull null
        val season = parts.groupValues.getOrNull(2)?.toIntOrNull()
        TvShow(href, card.selectFirst(".poster-card__title")?.text()?.trim().orEmpty(), poster = card.selectFirst("img")?.absUrl("src"), seasons = season?.let { listOf(Season("$baseUrl/tv/${parts.groupValues[1]}/$it/1", it)) } ?: emptyList())
    }.distinctBy { it.id }

    private fun parseApiSeries(body: String): List<TvShow> = runCatching {
        JsonParser.parseString(body).asJsonObject.getAsJsonArray("series").mapNotNull { value ->
            val show = value.asJsonObject
            val slug = show.get("slug")?.asString?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            TvShow(
                id = "$baseUrl/tv/$slug",
                title = show.get("title")?.asString.orEmpty(),
                released = show.get("first_air_date")?.asString,
                rating = show.get("vote_average")?.asString?.toDoubleOrNull(),
                poster = show.get("poster_path")?.asString?.let { "https://image.tmdb.org/t/p/w342$it" }
            )
        }
    }.getOrDefault(emptyList())
}
