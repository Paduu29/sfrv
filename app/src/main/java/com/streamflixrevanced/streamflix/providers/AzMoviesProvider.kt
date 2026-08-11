package com.streamflixrevanced.streamflix.providers

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixrevanced.streamflix.adapters.AppAdapter
import com.streamflixrevanced.streamflix.extractors.Extractor
import com.streamflixrevanced.streamflix.models.Category
import com.streamflixrevanced.streamflix.models.Episode
import com.streamflixrevanced.streamflix.models.Genre
import com.streamflixrevanced.streamflix.models.Movie
import com.streamflixrevanced.streamflix.models.People
import com.streamflixrevanced.streamflix.models.TvShow
import com.streamflixrevanced.streamflix.models.Video
import com.streamflixrevanced.streamflix.utils.DnsResolver
import okhttp3.OkHttpClient
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Url
import retrofit2.converter.gson.GsonConverterFactory
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object AzMoviesProvider : Provider {
    override val baseUrl = "https://azmovies.to"
    override val name = "AZMovies"
    override val logo = "https://azmovies.to/icons/ms-icon-310x310.png"
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
                if (!cookie.expiresAt.let { it < System.currentTimeMillis() }) cookies.add(cookie)
            }
        }
    }

    private interface Service {
        @GET
        suspend fun page(@Url url: String): Document

        @GET("search")
        suspend fun search(@Query("q") query: String): Document

        @Headers("Content-Type: application/json")
        @POST("verified")
        suspend fun verify(@Body request: VerificationRequest): VerificationResponse
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

    private val service = Retrofit.Builder()
        .baseUrl("$baseUrl/")
        .addConverterFactory(JsoupConverterFactory.create())
        .addConverterFactory(GsonConverterFactory.create())
        .client(client)
        .build()
        .create(Service::class.java)

    private suspend fun page(url: String): Document {
        var document = service.page(url)
        val token = Regex("verifyToken\\s*=\\s*['\"]([^'\"]+)")
            .find(document.select("script").html())?.groupValues?.get(1)
        if (token != null && service.verify(VerificationRequest(token)).ok) {
            document = service.page(url)
        }
        return document
    }

    override suspend fun getHome(): List<Category> {
        val document = page(baseUrl)
        return listOf(
            Category(Category.FEATURED, document.select(".home-hero .hero-slide").mapNotNull(::parseHeroMovie)),
            Category("Featured Movies", document.select(".home-section")
                .firstOrNull { it.selectFirst("h2")?.text()?.trim() == "Featured Movies" }
                ?.select("a.poster")
                ?.mapNotNull(::parsePosterMovie)
                .orEmpty()),
            Category("Recently Added", document.select("#movies-container a.poster").mapNotNull(::parsePosterMovie))
        ).filter { it.list.isNotEmpty() }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            if (page > 1) return emptyList()
            return page(baseUrl).select("a[href^=/genre/]")
                .mapNotNull { anchor ->
                    val href = anchor.absUrl("href")
                    val name = anchor.text().trim()
                    if (href.isBlank() || name.isBlank()) null else Genre(href, name)
                }
                .distinctBy { it.id }
        }
        val document = page("$baseUrl/search?q=${URLEncoder.encode(query, "UTF-8")}" + if (page > 1) "&page=$page" else "")
        return parseCards(document)
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        val document = if (page == 1) page("$baseUrl/all")
        else page("$baseUrl/search?page=$page&q=&year_from=0&year_to=0&rating_from=0&rating_to=10&sort=newest")
        return parseCards(document).filterIsInstance<Movie>()
    }

    override suspend fun getTvShows(page: Int): List<TvShow> = emptyList()

    override suspend fun getMovieFromProvider(id: String): Movie {
        val document = page(id)
        val title = document.selectFirst("h1.movie-title")?.text()?.trim().orEmpty()
        val meta = document.selectFirst(".movie-meta")?.text().orEmpty()
        val year = Regex("\\b(19|20)\\d{2}\\b").find(meta)?.value
        val runtime = Regex("(\\d+)\\s*h(?:\\s*(\\d+)\\s*m)?", RegexOption.IGNORE_CASE)
            .find(meta)?.let { (it.groupValues[1].toIntOrNull() ?: 0) * 60 + (it.groupValues[2].toIntOrNull() ?: 0) }
        return Movie(
            id = id,
            title = title,
            overview = document.selectFirst(".movie-overview p")?.text()?.trim(),
            released = year,
            runtime = runtime,
            rating = document.selectFirst(".rating-value")?.text()?.toDoubleOrNull(),
            poster = document.selectFirst(".movie-poster img")?.absUrl("src"),
            banner = Regex("url\\('([^']+)")
                .find(document.selectFirst(".movie-hero")?.attr("style").orEmpty())?.groupValues?.get(1),
            trailer = document.selectFirst("[data-trailer]")?.attr("data-trailer")?.let { "https://www.youtube.com/watch?v=$it" },
            genres = document.select(".movie-genres a[href]").map { Genre(it.attr("href"), it.text()) },
            cast = document.select(".cast-card__info strong").map { People(it.text(), it.text()) }
        )
    }

    override suspend fun getTvShowFromProvider(id: String): TvShow =
        throw UnsupportedOperationException("AZMovies is a movie-only provider")

    override suspend fun getGenre(id: String, page: Int): Genre {
        val genre = id.substringAfterLast('/').substringBefore('?')
        val document = page("$baseUrl/search?page=$page&q=&genre%5B%5D=${URLEncoder.encode(genre, "UTF-8")}&year_from=0&year_to=0&rating_from=0&rating_to=10&sort=newest")
        return Genre(id, genre.replace('-', ' '), parseCards(document).filterIsInstance<Movie>())
    }

    override suspend fun getPeople(id: String, page: Int) = People(id, id, filmography = emptyList())

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> =
        page(id).select("button.server-btn[data-url]").mapNotNull { button ->
            button.attr("data-url").takeIf { it.isNotBlank() }?.let { Video.Server(it, button.attr("data-name").ifBlank { "Server" }, it) }
        }

    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.src, server)

    private fun parseCards(document: Document): List<AppAdapter.Item> = document.select("a[href^=/movie/]")
        .mapNotNull { anchor ->
            val title = anchor.selectFirst(".poster__title, .result__title")?.text()?.trim()
                ?: anchor.selectFirst("img")?.attr("alt")?.trim() ?: return@mapNotNull null
            Movie(
                id = anchor.absUrl("href"),
                title = title,
                poster = anchor.selectFirst("img")?.let { it.absUrl("data-src").ifBlank { it.absUrl("src") } },
                released = Regex("\\b(19|20)\\d{2}\\b").find(anchor.text())?.value
            )
        }.distinctBy { it.id }

    private fun parsePosterMovie(anchor: Element): Movie? {
        val id = anchor.absUrl("href").takeIf { it.isNotBlank() } ?: return null
        val title = anchor.selectFirst(".poster__title")?.text()?.trim()
            ?: anchor.selectFirst("img")?.attr("alt")?.removeSuffix(" Poster")?.trim()
            ?: return null
        return Movie(
            id = id,
            title = title,
            poster = anchor.selectFirst("img")?.let { it.absUrl("data-src").ifBlank { it.absUrl("src") } },
            released = Regex("\\b(19|20)\\d{2}\\b").find(anchor.text())?.value
        )
    }

    private fun parseHeroMovie(slide: Element): Movie? {
        val watch = slide.selectFirst("a.home-hero__play[href^=/movie/]") ?: return null
        val style = slide.selectFirst(".home-hero__bg")?.attr("style").orEmpty()
        return Movie(
            id = watch.absUrl("href"),
            title = slide.attr("aria-label").trim().ifBlank {
                slide.selectFirst(".home-hero__logo")?.attr("alt").orEmpty()
            },
            overview = slide.selectFirst(".home-hero__overview")?.text()?.trim(),
            released = Regex("\\b(19|20)\\d{2}\\b").find(slide.selectFirst(".home-hero__meta")?.text().orEmpty())?.value,
            runtime = Regex("(\\d{1,2}):(\\d{2})").find(slide.selectFirst(".home-hero__meta")?.text().orEmpty())?.let {
                (it.groupValues[1].toIntOrNull() ?: 0) * 60 + (it.groupValues[2].toIntOrNull() ?: 0)
            },
            rating = slide.selectFirst(".rating-value")?.text()?.toDoubleOrNull(),
            poster = slide.selectFirst(".home-hero__logo")?.absUrl("src"),
            banner = Regex("url\\(['\"]?([^'\")]+)").find(style)?.groupValues?.get(1),
            trailer = slide.selectFirst("[data-trailer]")?.attr("data-trailer")?.let { "https://www.youtube.com/watch?v=$it" }
        )
    }
}
