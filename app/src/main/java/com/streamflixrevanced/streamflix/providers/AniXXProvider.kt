package com.streamflixrevanced.streamflix.providers

import com.streamflixrevanced.streamflix.adapters.AppAdapter
import com.streamflixrevanced.streamflix.extractors.Extractor
import com.streamflixrevanced.streamflix.models.Category
import com.streamflixrevanced.streamflix.models.Episode
import com.streamflixrevanced.streamflix.models.Genre
import com.streamflixrevanced.streamflix.models.Movie
import com.streamflixrevanced.streamflix.models.People
import com.streamflixrevanced.streamflix.models.Season
import com.streamflixrevanced.streamflix.models.Show
import com.streamflixrevanced.streamflix.models.TvShow
import com.streamflixrevanced.streamflix.models.Video
import com.streamflixrevanced.streamflix.utils.DnsResolver
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

object AniXXProvider : Provider {

    private const val URL = "https://anixx.fun/"

    override val baseUrl = URL
    override val name = "AniXX"
    override val logo = "https://anixtv.in/images/logo.png"
    override val language = "en"

    private val service = Service.build()

    override suspend fun getHome(): List<Category> {
        val document = service.getHome()

        return document.select("div.section").mapNotNull { section ->
            val title = section.selectFirst("h2.section-title")?.text()?.trim()
                ?: return@mapNotNull null
            val shows = section.select("div.card-inner[data-id][data-type]")
                .mapNotNull(::parseCatalogCard)
                .distinctBy(::showId)

            if (shows.isEmpty()) null else Category(title, shows)
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank() || page > 1) return emptyList()

        return service.search(query).select("a.result-card").mapNotNull { card ->
            val href = card.attr("href")
            val id = queryValue(href, "id") ?: return@mapNotNull null
            val type = queryValue(href, "type")
                ?: card.selectFirst("span.media-tag")?.text()?.trim()
            val title = card.selectFirst("div.card-title")?.text()?.trim()
                .orEmpty().ifBlank { card.selectFirst("img")?.attr("alt").orEmpty() }
            if (title.isBlank()) return@mapNotNull null

            val released = card.select("div.card-meta span").firstOrNull {
                YEAR_REGEX.matches(it.text().trim())
            }?.text()?.trim()
            val poster = card.selectFirst("img.poster-img")?.imageUrl()

            when (type?.lowercase()) {
                "movie" -> Movie(id = id, title = title, released = released, poster = poster)
                "tv" -> TvShow(id = id, title = title, released = released, poster = poster)
                else -> null
            }
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        if (page > 1) return emptyList()

        return service.getCatalog("movies")
            .select("div.card-inner[data-id][data-type=movie]")
            .mapNotNull(::parseCatalogCard)
            .filterIsInstance<Movie>()
            .distinctBy { it.id }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        if (page > 1) return emptyList()

        return service.getCatalog("tv")
            .select("div.card-inner[data-id][data-type=tv]")
            .mapNotNull(::parseCatalogCard)
            .filterIsInstance<TvShow>()
            .distinctBy { it.id }
    }

    override suspend fun getMovieFromProvider(id: String): Movie {
        val details = service.getDetails(id, "movie")
        val watch = service.getWatch(id, "movie")
        val metadata = parseMetadata(details, watch)

        return Movie(
            id = id,
            title = metadata.title,
            overview = metadata.overview,
            released = metadata.released,
            runtime = metadata.runtime,
            quality = metadata.quality,
            rating = metadata.rating,
            poster = metadata.poster,
            banner = metadata.banner,
            genres = metadata.genres,
            cast = metadata.cast,
        )
    }

    override suspend fun getTvShowFromProvider(id: String): TvShow {
        val details = service.getDetails(id, "tv")
        val watch = service.getWatch(id, "tv")
        val metadata = parseMetadata(details, watch)
        val seasons = watch.select("#seasonSelect option").mapNotNull { option ->
            val number = option.attr("value").toIntOrNull() ?: return@mapNotNull null
            Season(
                id = "$id/$number",
                number = number,
                title = option.text().trim().ifBlank { "Season $number" },
            )
        }.ifEmpty {
            listOf(Season(id = "$id/1", number = 1, title = "Season 1"))
        }

        return TvShow(
            id = id,
            title = metadata.title,
            overview = metadata.overview,
            released = metadata.released,
            runtime = metadata.runtime,
            quality = metadata.quality,
            rating = metadata.rating,
            poster = metadata.poster,
            banner = metadata.banner,
            seasons = seasons,
            genres = metadata.genres,
            cast = metadata.cast,
        )
    }

    override suspend fun getEpisodesByProvider(seasonId: String): List<Episode> {
        val tvShowId = seasonId.substringBeforeLast('/')
        val seasonNumber = seasonId.substringAfterLast('/').toIntOrNull()
            ?: return emptyList()
        val document = service.getWatch(
            id = tvShowId,
            type = "tv",
            season = seasonNumber,
            episode = 1,
        )

        val playerEpisodes = document.select("#epList div.ep-item").mapNotNull { item ->
            val number = item.selectFirst("span.ep-num")?.text()
                ?.let { NUMBER_REGEX.find(it)?.value?.toIntOrNull() }
                ?: return@mapNotNull null
            Episode(
                id = "$tvShowId/$seasonNumber/$number",
                number = number,
                title = item.selectFirst("div.ep-title")?.text()?.trim(),
                poster = item.selectFirst("div.ep-thumb img")?.imageUrl(),
            )
        }
        if (playerEpisodes.isNotEmpty()) return playerEpisodes.distinctBy { it.number }

        return document.select("a.ep-card").mapNotNull { card ->
            val href = card.attr("href")
            val number = queryValue(href, "episode")?.toIntOrNull()
                ?: return@mapNotNull null
            Episode(
                id = "$tvShowId/$seasonNumber/$number",
                number = number,
                title = card.selectFirst("div.ep-title")?.text()?.trim(),
                poster = card.selectFirst("div.ep-thumb img")?.imageUrl(),
                overview = card.selectFirst("div.ep-desc")?.text()?.trim(),
            )
        }.distinctBy { it.number }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        throw UnsupportedOperationException("AniXX does not expose genre pages")
    }

    override suspend fun getPeople(id: String, page: Int): People {
        throw UnsupportedOperationException("AniXX does not expose people pages")
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val document = when (videoType) {
            is Video.Type.Movie -> service.getWatch(
                id = videoType.id,
                type = "movie",
            )

            is Video.Type.Episode -> service.getWatch(
                id = videoType.tvShow.id,
                type = "tv",
                season = videoType.season.number,
                episode = videoType.number,
            )
        }

        return document.select("button.server-btn").mapNotNull { button ->
            val serverKey = SERVER_KEY_REGEX.find(button.attr("onclick"))
                ?.groupValues?.get(1)
                ?: return@mapNotNull null
            if (serverKey !in EXTRACTABLE_SERVER_KEYS) return@mapNotNull null
            val playerUrl = playerUrl(serverKey, videoType) ?: return@mapNotNull null

            Video.Server(
                id = playerUrl,
                name = button.ownText().trim().ifBlank { serverKey },
            )
        }.distinctBy { it.id }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        return Extractor.extract(server.id)
    }

    private fun playerUrl(server: String, videoType: Video.Type): String? {
        val (type, id, season, episode) = when (videoType) {
            is Video.Type.Movie -> PlayerTarget("movie", videoType.id)
            is Video.Type.Episode -> PlayerTarget(
                type = "tv",
                id = videoType.tvShow.id,
                season = videoType.season.number,
                episode = videoType.number,
            )
        }

        val suffix = if (type == "movie") id else "$id/$season/$episode"
        return when (server) {
            "videasy" -> "https://player.videasy.net/$type/$suffix" +
                "?color=00A8E1&nextEpisode=true&autoplayNextEpisode=true"
            "vidplays" -> "https://vidplays.fun/embed/$type/$suffix"
            "vidcore" -> "https://vidcore.net/$type/$suffix?autoPlay=true&theme=00A8E1" +
                if (type == "movie") "&title=true&poster=true"
                else "&nextButton=true&autoNext=true&title=true&poster=true"
            "vidzen" -> "https://vidzen.fun/$type/$suffix"
            "multi" -> if (type == "movie") {
                "https://peachify.top/?type=movie&id=$id&autoplay=true&fullscreen=true&pip=true"
            } else {
                "https://peachify.top/?type=tv&id=$id&s=$season&e=$episode" +
                    "&autoplay=true&fullscreen=true&pip=true"
            }
            "embedmaster" -> "https://embedmaster.link/$type/$suffix"
            "vidnest" -> "https://vidnest.fun/$type/$suffix"
            "vidplus" -> "https://player.vidplus.to/embed/$type/$suffix"
            "vidfast" -> "https://vidfast.pro/$type/$suffix?autoPlay=true&theme=00A8E1"
            "mapple" -> if (type == "movie") {
                "https://mapple.uk/watch/movie/$id"
            } else {
                "https://mapple.uk/watch/tv/$id-$season-$episode"
            }
            "vidlink" -> "https://vidlink.pro/$type/$suffix"
            else -> null
        }
    }

    private data class PlayerTarget(
        val type: String,
        val id: String,
        val season: Int? = null,
        val episode: Int? = null,
    )

    private fun parseCatalogCard(card: Element): Show? {
        val id = card.attr("data-id").trim().ifBlank { return null }
        val title = card.selectFirst("span.static-title")?.text()?.trim()
            .orEmpty().ifBlank { card.selectFirst("img.poster-img")?.attr("alt").orEmpty() }
        if (title.isBlank()) return null

        val released = card.select("div.card-hover-meta span").firstOrNull {
            YEAR_REGEX.matches(it.text().trim())
        }?.text()?.trim()
        val quality = card.selectFirst("span.hover-quality")?.text()?.trim()
        val poster = card.selectFirst("img.poster-img")?.imageUrl()

        return when (card.attr("data-type").lowercase()) {
            "movie" -> Movie(
                id = id,
                title = title,
                released = released,
                quality = quality,
                poster = poster,
            )

            "tv" -> TvShow(
                id = id,
                title = title,
                released = released,
                quality = quality,
                poster = poster,
            )

            else -> null
        }
    }

    private fun showId(show: Show): String = when (show) {
        is Movie -> show.id
        is TvShow -> show.id
    }

    private fun parseMetadata(details: Document, watch: Document): Metadata {
        val title = details.selectFirst("img.media-logo")?.attr("alt")
            ?.removeSuffix(" Logo")?.trim()
            .orEmpty().ifBlank { watch.selectFirst("h1.media-title")?.text()?.trim().orEmpty() }
            .ifBlank { watch.selectFirst("span#playerTitle")?.text()?.trim().orEmpty() }
        val detailMeta = details.select("div.meta-row").firstOrNull()
        val watchMeta = watch.select("div.info-details div.meta-row").firstOrNull()
        val metaTexts = (detailMeta?.select("span") ?: emptyList())
            .map { it.text().trim() } +
            (watchMeta?.select("span") ?: emptyList()).map { it.text().trim() }

        val genreText = details.selectFirst("span.genre-text")?.text()?.trim()
            ?: metaTexts.lastOrNull { text -> text.isNotBlank() && text.none(Char::isDigit) }
        val genres = genreText.orEmpty().split(GENRE_SEPARATOR_REGEX)
            .map(String::trim)
            .filter(String::isNotBlank)
            .map { Genre(id = it.lowercase().replace(' ', '-'), name = it) }

        return Metadata(
            title = title,
            overview = details.selectFirst("div.desc-text")?.text()?.trim()
                ?: watch.selectFirst("p.overview-text")?.text()?.trim(),
            released = metaTexts.firstOrNull { YEAR_REGEX.matches(it) },
            runtime = metaTexts.firstNotNullOfOrNull { text ->
                RUNTIME_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull()
            },
            quality = metaTexts.firstOrNull { it.equals("HD", true) || it.equals("4K", true) },
            rating = details.selectFirst("div.score-badge")?.text()
                ?.substringBefore('/')?.trim()?.toDoubleOrNull()
                ?: watch.selectFirst("span.match-score")?.text()
                    ?.substringBefore('/')?.trim()?.toDoubleOrNull(),
            poster = details.selectFirst("img.hero-poster")?.imageUrl()
                ?: watch.selectFirst("div.info-poster img")?.imageUrl()
                ?: watch.selectFirst("img.sidebar-poster-img")?.imageUrl(),
            banner = details.selectFirst("div.hero-backdrop")?.attr("style")
                ?.let { BACKDROP_REGEX.find(it)?.groupValues?.get(1) },
            genres = genres,
            cast = details.select("div.person-card").mapNotNull { person ->
                val personName = person.selectFirst("div.person-name")?.text()?.trim()
                    ?: return@mapNotNull null
                People(
                    id = personName.lowercase().replace(' ', '-'),
                    name = personName,
                    image = person.selectFirst("img.person-img")?.imageUrl(),
                )
            },
        )
    }

    private fun Element.imageUrl(): String? {
        return attr("abs:src").ifBlank { attr("src") }.trim().ifBlank { null }
    }

    private fun queryValue(href: String, name: String): String? {
        return QUERY_REGEX.findAll(href).firstOrNull { it.groupValues[1] == name }
            ?.groupValues?.get(2)
    }

    private data class Metadata(
        val title: String,
        val overview: String?,
        val released: String?,
        val runtime: Int?,
        val quality: String?,
        val rating: Double?,
        val poster: String?,
        val banner: String?,
        val genres: List<Genre>,
        val cast: List<People>,
    )

    private val YEAR_REGEX = Regex("\\d{4}")
    private val NUMBER_REGEX = Regex("\\d+")
    private val RUNTIME_REGEX = Regex("(\\d+)\\s*(?:m|min)", RegexOption.IGNORE_CASE)
    private val GENRE_SEPARATOR_REGEX = Regex("\\s*(?:•|·|â€¢)\\s*")
    private val BACKDROP_REGEX = Regex("url\\(['\"]?([^'\")]+)")
    private val QUERY_REGEX = Regex("[?&]([^=&]+)=([^&]+)")
    private val SERVER_KEY_REGEX = Regex("""switchServer\(['\"]([^'\"]+)""")
    private val EXTRACTABLE_SERVER_KEYS = setOf(
        "videasy",
        "multi",
        "vidcore",
        "vidplays",
        "vidzen",
        "vidfast",
        "vidnest",
        "vidplus",
        "embedmaster",
        "mapple",
        "vidlink",
    )

    private interface Service {

        @GET(".")
        suspend fun getHome(): Document

        @GET(".")
        suspend fun getCatalog(@Query("filter") filter: String): Document

        @GET("search")
        suspend fun search(@Query("q") query: String): Document

        @GET("details")
        suspend fun getDetails(
            @Query("id") id: String,
            @Query("type") type: String,
        ): Document

        @GET("watch")
        suspend fun getWatch(
            @Query("id") id: String,
            @Query("type") type: String,
            @Query("season") season: Int? = null,
            @Query("episode") episode: Int? = null,
        ): Document

        companion object {
            fun build(): Service {
                val client = OkHttpClient.Builder()
                    .readTimeout(30, TimeUnit.SECONDS)
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .dns(DnsResolver.doh)
                    .addInterceptor { chain ->
                        chain.proceed(
                            chain.request().newBuilder()
                                .header("Accept-Language", "en-US,en;q=0.9")
                                .header(
                                    "User-Agent",
                                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                        "Chrome/124.0.0.0 Safari/537.36",
                                )
                                .build(),
                        )
                    }
                    .build()

                return Retrofit.Builder()
                    .baseUrl(URL)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .client(client)
                    .build()
                    .create(Service::class.java)
            }
        }
    }
}
