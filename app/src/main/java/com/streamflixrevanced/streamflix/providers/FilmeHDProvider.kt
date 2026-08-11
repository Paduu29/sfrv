package com.streamflixrevanced.streamflix.providers

import com.streamflixrevanced.streamflix.adapters.AppAdapter
import com.streamflixrevanced.streamflix.extractors.Extractor
import com.streamflixrevanced.streamflix.models.Category
import com.streamflixrevanced.streamflix.models.Episode
import com.streamflixrevanced.streamflix.models.Genre
import com.streamflixrevanced.streamflix.models.Movie
import com.streamflixrevanced.streamflix.models.People
import com.streamflixrevanced.streamflix.models.Show
import com.streamflixrevanced.streamflix.models.TvShow
import com.streamflixrevanced.streamflix.models.Video
import com.streamflixrevanced.streamflix.utils.DnsResolver
import com.streamflixrevanced.streamflix.utils.NetworkClient
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Url
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object FilmeHDProvider : Provider {

    override val name = "FilmeHD"
    override val baseUrl = "https://filmehd.one"
    override val logo = "${baseUrl}/logo.svg"
    override val language = "ro"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private interface Service {
        @Headers("User-Agent: $USER_AGENT")
        @GET
        suspend fun getPage(@Url url: String): Document

        companion object {
            fun build(baseUrl: String): Service {
                val client = OkHttpClient.Builder()
                    .dns(DnsResolver.doh)
                    .cookieJar(NetworkClient.cookieJar)
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .addInterceptor { chain ->
                        chain.proceed(
                            chain.request().newBuilder()
                                .header("User-Agent", USER_AGENT)
                                .header("Referer", baseUrl)
                                .header("Accept-Language", "ro-RO,ro;q=0.9,en-US;q=0.8,en;q=0.7")
                                .build()
                        )
                    }
                    .build()

                return Retrofit.Builder()
                    .baseUrl("$baseUrl/")
                    .addConverterFactory(JsoupConverterFactory.create())
                    .client(client)
                    .build()
                    .create(Service::class.java)
            }
        }
    }

    private val service by lazy { Service.build(baseUrl) }

    override suspend fun getHome(): List<Category> = coroutineScope {
        val latest = async { runCatching { getMovies(1) }.getOrDefault(emptyList()) }

        listOfNotNull(
            latest.await().takeIf { it.isNotEmpty() }?.let { Category("Filme adăugate recent", it) }
        )
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            return listOf(
                Genre(id = "actiune-filme", name = "Acțiune"),
                Genre(id = "comedie", name = "Comedie"),
                Genre(id = "drama", name = "Dramă"),
                Genre(id = "horror", name = "Horror"),
                Genre(id = "thriller", name = "Thriller"),
                Genre(id = "sf", name = "Sci-Fi"),
                Genre(id = "romantic", name = "Romantic"),
                Genre(id = "animatie", name = "Animație"),
                Genre(id = "documentar", name = "Documentar"),
                Genre(id = "crima", name = "Crimă"),
                Genre(id = "aventura", name = "Aventură"),
                Genre(id = "muzical", name = "Muzical"),
                Genre(id = "razboi", name = "Război"),
                Genre(id = "filme-vechi", name = "Filme Vechi"),
            )
        }

        val url = "${baseUrl}/search?q=${URLEncoder.encode(query, "UTF-8")}"
        val document = service.getPage(url)
        return parseCards(document)
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        val url = if (page <= 1) "${baseUrl}/filme" else "${baseUrl}/filme?page=$page"
        val document = service.getPage(url)
        return parseCards(document).filterIsInstance<Movie>()
    }

    override suspend fun getTvShows(page: Int): List<TvShow> = emptyList()

    override suspend fun getMovieFromProvider(id: String): Movie {
        val url = absoluteUrl(id)
        val document = service.getPage(url)

        val movieJson = parseJsonLd(document)

        val title = movieJson?.optString("name")
            ?.substringBeforeLast("(")?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore("•")?.trim()
            ?: document.title().substringBefore("•").trim()

        val poster = movieJson?.optString("image")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val overview = movieJson?.optString("description")
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")

        val year = movieJson?.optString("dateCreated")

        val genres = movieJson?.optJSONArray("genre")?.let { arr ->
            (0 until arr.length()).map { i ->
                val genreName = arr.getString(i)
                Genre(
                    id = genreName.lowercase().replace(" ", "-"),
                    name = genreName
                )
            }
        } ?: parseGenresFromHtml(document)

        val rating = movieJson?.optJSONObject("aggregateRating")?.optDouble("ratingValue")

        val runtime = movieJson?.optString("duration")?.let { parseIsoDuration(it) }
            ?: parseDurationFromHtml(document)

        val director = movieJson?.optJSONObject("director")?.optString("name")

        return Movie(
            id = url,
            title = title,
            overview = overview,
            poster = poster,
            banner = poster,
            genres = genres,
            released = year,
            runtime = runtime,
            rating = rating,
            directors = director?.let { listOf(People(id = it, name = it)) } ?: emptyList()
        )
    }

    override suspend fun getTvShowFromProvider(id: String): TvShow {
        throw UnsupportedOperationException("FilmeHD does not support TV shows")
    }

    override suspend fun getEpisodesByProvider(seasonId: String): List<Episode> = emptyList()

    override suspend fun getGenre(id: String, page: Int): Genre {
        val url = if (page <= 1) absoluteUrl(id) else "${absoluteUrl(id)}?page=$page"
        val document = service.getPage(url)
        val name = document.selectFirst("h1")?.text()?.trim()
            ?: id.substringAfterLast('/').replace('-', ' ').replaceFirstChar { it.uppercaseChar() }
        val shows = parseCards(document).map { it as Show }
        return Genre(id = id, name = name, shows = shows)
    }

    override suspend fun getPeople(id: String, page: Int): People {
        val url = if (page <= 1) absoluteUrl(id) else "${absoluteUrl(id)}?page=$page"
        val document = service.getPage(url)
        val name = document.selectFirst("h1")?.text()?.trim()
            ?: id.substringAfterLast('/').replace('-', ' ').replaceFirstChar { it.uppercaseChar() }
        val filmography = parseCards(document).map { it as Show }
        return People(id = id, name = name, filmography = filmography)
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val url = absoluteUrl(id)
        val document = service.getPage(url)

        val nuxtData = document.selectFirst("#__NUXT_DATA__")?.data()
            ?: return emptyList()

        val jsonArray = runCatching { JSONArray(nuxtData) }.getOrNull()
            ?: return emptyList()

        val srcRegex = Regex("""src=["']([^"']+)""")

        return (0 until jsonArray.length())
            .asSequence()
            .mapNotNull { jsonArray.opt(it) as? String }
            .filter { it.contains("<iframe") }
            .flatMap { html -> srcRegex.findAll(html) }
            .mapNotNull { match ->
                val src = match.groupValues[1]
                if (src.isBlank()) return@mapNotNull null

                val finalUrl = absoluteUrl(src)

                val host = runCatching {
                    finalUrl.substringAfter("://")
                        .substringBefore("/")
                        .removePrefix("www.")
                        .substringBefore(".")
                        .replaceFirstChar {
                            if (it.isLowerCase()) it.titlecase() else it.toString()
                        }
                }.getOrDefault("Server")

                Video.Server(
                    id = finalUrl,
                    name = host,
                    src = finalUrl
                )
            }
            .distinctBy { it.src }
            .toList()
    }

    override suspend fun getVideo(server: Video.Server): Video {
        return Extractor.extract(server.src.ifBlank { server.id }, server)
    }

    private fun parseCards(document: Document): List<AppAdapter.Item> {
        return document.select("a[href$=.html]").mapNotNull { link ->
            parseMovieFromLink(link)
        }.distinctBy { it.id }
    }

    private fun parseMovieFromLink(link: Element): Movie? {
        val href = link.attr("href").takeIf { it.isNotBlank() } ?: return null
        val url = absoluteUrl(href)

        val title = link.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: link.text().replace(Regex("\\s+"), " ").trim().takeIf { it.isNotBlank() }
            ?: href.substringAfterLast('/').substringBefore('.').replace('-', ' ').trim()
                .replaceFirstChar { it.uppercaseChar() }
                .takeIf { it.isNotBlank() }
            ?: return null

        val poster = link.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
            ?.let { absoluteUrl(it) }

        return Movie(
            id = url,
            title = title,
            poster = poster,
            banner = poster
        )
    }

    private fun parseJsonLd(document: Document): JSONObject? {
        return document.select("script[type=application/ld+json]")
            .mapNotNull { script ->
                runCatching {
                    val json = JSONObject(script.html())
                    when {
                        json.has("@graph") -> {
                            val graph = json.getJSONArray("@graph")
                            (0 until graph.length()).firstNotNullOfOrNull { i ->
                                val item = graph.getJSONObject(i)
                                item.takeIf { it.optString("@type") == "Movie" }
                            }
                        }
                        json.optString("@type") == "Movie" -> json
                        else -> null
                    }
                }.getOrNull()
            }
            .firstOrNull()
    }

    private fun parseIsoDuration(duration: String): Int? {
        val hours = Regex("""PT(\d+)H""").find(duration)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minutes = Regex("""PT(?:\d+H)?(\d+)M""").find(duration)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val total = hours * 60 + minutes
        return total.takeIf { it > 0 }
    }

    private fun parseDurationFromHtml(document: Document): Int? {
        val durationText = document.select("li").firstOrNull { li ->
            li.text().contains("Durată", ignoreCase = true)
        }?.text()?.substringAfter(":")?.trim() ?: return null

        val hours = Regex("""(\d+)\s*h""", RegexOption.IGNORE_CASE).find(durationText)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minutes = Regex("""(\d+)\s*m""", RegexOption.IGNORE_CASE).find(durationText)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val total = hours * 60 + minutes
        return total.takeIf { it > 0 }
    }

    private fun parseGenresFromHtml(document: Document): List<Genre> {
        return document.select("a[href]").filter { link ->
            link.parent()?.text()?.contains("Gen film", ignoreCase = true) == true
        }.map { link ->
            val genreName = link.text().trim()
            Genre(
                id = link.attr("href").removePrefix("/"),
                name = genreName
            )
        }
    }

    private fun absoluteUrl(url: String): String {
        if (url.isBlank()) return baseUrl
        return when {
            url.startsWith("http://", true) || url.startsWith("https://", true) -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> baseUrl + url
            else -> "$baseUrl/$url"
        }.substringBefore("#")
    }
}
