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
import com.streamflixrevanced.streamflix.utils.NetworkClient
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url

/**
 * Polish movie and TV provider backed entirely by the HTML pages served by PREMIUMSMART.
 *
 * The site puts its page state in `window.bootstrapData`. Keeping all parsing here avoids the
 * private JSON API and also makes the provider work from the same pages a browser receives.
 */
object PremiumSmartProvider : Provider {

    override val name = "PREMIUMSMART"
    override val baseUrl = "https://premiumsmart.eu"
    override val logo = "$baseUrl/favicon/icon-192x192.png"
    override val language = "pl"

    private interface Service {
        @GET
        suspend fun getDocument(@Url url: String): Document
    }

    private val service = Retrofit.Builder()
        .baseUrl("$baseUrl/")
        .client(
            NetworkClient.default.newBuilder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder()
                            .header("Accept", "text/html,application/xhtml+xml")
                            .header("Referer", "$baseUrl/")
                            .build()
                    )
                }
                .dns(DnsResolver.doh)
                .build()
        )
        .addConverterFactory(JsoupConverterFactory.create())
        .build()
        .create(Service::class.java)

    override suspend fun getHome(): List<Category> {
        val root = getRoot(baseUrl)
        val channels = root.obj("loaders")
            ?.obj("channelPage")
            ?.obj("channel")
            ?.obj("content")
            ?.array("data")
            .orEmpty()

        return channels.mapNotNull { element ->
            val channel = element as? JsonObject ?: return@mapNotNull null
            val items = channel.obj("content")
                ?.array("data")
                .orEmpty()
                .toTitleItems()
                .take(20)
            if (items.isEmpty()) {
                null
            } else {
                Category(
                    name = if ((channel.string("name") ?: "").contains("slider", ignoreCase = true)) {
                        Category.FEATURED
                    } else {
                        channel.string("name") ?: name
                    },
                    list = items,
                    stableKey = "premiumsmart:${channel.int("id") ?: channel.string("slug")}",
                )
            }
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            return listOf(
                Genre(id = "/filmy", name = "Filmy"),
                Genre(id = "/seriale", name = "Seriale"),
            )
        }

        val path = "/search/${URLEncoder.encode(query, Charsets.UTF_8.name()).replace("+", "%20")}"
        return getRoot(pagedUrl(path, page))
            .obj("loaders")
            ?.obj("searchPage")
            ?.array("results")
            .orEmpty()
            .toTitleItems()
    }

    override suspend fun getMovies(page: Int): List<Movie> =
        getChannelItems(pagedUrl("/filmy", page)).filterIsInstance<Movie>()

    override suspend fun getTvShows(page: Int): List<TvShow> =
        getChannelItems(pagedUrl("/seriale", page)).filterIsInstance<TvShow>()

    override suspend fun getMovieFromProvider(id: String): Movie {
        val parsed = PremiumSmartHtmlParser.parseTitleId(id)
        val titlePage = getTitlePage(parsed)
        val title = titlePage.obj("title") ?: error("Missing PREMIUMSMART movie data")
        val movie = title.toTitleItem() as? Movie ?: error("Title is not a movie")
        return movie.copy(
            id = PremiumSmartHtmlParser.buildTitleId(
                isSeries = false,
                titleId = parsed.titleId,
                primaryVideoId = parsed.primaryVideoId ?: title.primaryVideoId(),
                slug = parsed.slug ?: PremiumSmartHtmlParser.slugify(title.string("name")),
            ),
            genres = title.toGenres(),
            directors = titlePage.obj("credits").toDirectors(),
            cast = titlePage.obj("credits").toCast(),
        )
    }

    override suspend fun getTvShowFromProvider(id: String): TvShow {
        val parsed = PremiumSmartHtmlParser.parseTitleId(id)
        val titlePage = getTitlePage(parsed)
        val title = titlePage.obj("title") ?: error("Missing PREMIUMSMART series data")
        val show = title.toTitleItem() as? TvShow ?: error("Title is not a series")
        val slug = parsed.slug ?: PremiumSmartHtmlParser.slugify(title.string("name")).orEmpty()
        val seasons = titlePage.obj("seasons")
            ?.array("data")
            .orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { season ->
                val number = season.int("number") ?: return@mapNotNull null
                Season(
                    id = PremiumSmartHtmlParser.buildSeasonId(parsed.titleId, slug, number),
                    number = number,
                    title = "Sezon $number",
                    poster = season.string("poster") ?: title.string("poster"),
                )
            }
            .sortedBy { it.number }

        return show.copy(
            id = PremiumSmartHtmlParser.buildTitleId(
                isSeries = true,
                titleId = parsed.titleId,
                primaryVideoId = null,
                slug = slug,
            ),
            seasons = seasons,
            genres = title.toGenres(),
            directors = titlePage.obj("credits").toDirectors(),
            cast = titlePage.obj("credits").toCast(),
        )
    }

    override suspend fun getEpisodesByProvider(seasonId: String): List<Episode> {
        val parsed = PremiumSmartHtmlParser.parseSeasonId(seasonId) ?: return emptyList()
        val root = getRoot(
            "$baseUrl/titles/${parsed.titleId}/${parsed.slug}/season/${parsed.number}"
        )
        val seasonPage = root.obj("loaders")?.obj("seasonPage") ?: return emptyList()
        val fallbackPoster = seasonPage.obj("season")?.string("poster")
            ?: seasonPage.obj("title")?.string("poster")

        return seasonPage.obj("episodes")
            ?.array("data")
            .orEmpty()
            .mapNotNull { (it as? JsonObject)?.toEpisode(fallbackPoster) }
            .sortedBy { it.number }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        if (id == "/filmy") {
            return Genre(id, "Filmy", getMovies(page))
        }
        if (id == "/seriale") {
            return Genre(id, "Seriale", getTvShows(page))
        }

        val slug = id.removePrefix("/genres/").removePrefix("genres/")
        val items = getChannelItems(pagedUrl("/genres/$slug", page)).filterIsInstance<Show>()
        return Genre(id = id, name = slug.replace('-', ' '), shows = items)
    }

    override suspend fun getPeople(id: String, page: Int): People =
        People(id = id, name = id)

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val watchId = when (videoType) {
            is Video.Type.Movie -> PremiumSmartHtmlParser.parseTitleId(id).primaryVideoId
            is Video.Type.Episode -> id.toIntOrNull()
        } ?: return emptyList()

        val watchPage = getRoot("$baseUrl/watch/$watchId")
            .obj("loaders")
            ?.obj("watchPage")
            ?: return emptyList()

        val candidates = buildList {
            watchPage.obj("video")?.let(::add)
            addAll(watchPage.array("alternative_videos").orEmpty().mapNotNull { it as? JsonObject })
        }

        return candidates.mapNotNull { video ->
            val src = video.string("src") ?: return@mapNotNull null
            val label = listOfNotNull(
                video.string("name"),
                video.string("quality")?.let { "[$it]" },
                video.string("language_type")?.let { "[$it]" },
            ).joinToString(" ").ifBlank { "Server" }
            Video.Server(
                id = video.int("id")?.toString() ?: src,
                name = label,
                src = src,
            )
        }
            .distinctBy { it.src }
            // StreamCastHub entries can remain indexed after the upstream video has expired.
            // Prefer a native extractor when PREMIUMSMART publishes an alternative.
            .sortedBy { server ->
                if (server.src.contains("streamcasthub", ignoreCase = true)) 1 else 0
            }
    }

    override suspend fun getVideo(server: Video.Server): Video =
        Extractor.extract(server.src.ifBlank { server.id }, server)

    private suspend fun getTitlePage(parsed: PremiumSmartHtmlParser.TitleId): JsonObject {
        val slug = parsed.slug ?: resolveSlug(parsed)
            ?: error("Unable to resolve PREMIUMSMART title slug")
        return getRoot("$baseUrl/titles/${parsed.titleId}/$slug")
            .obj("loaders")
            ?.obj("titlePage")
            ?: error("Missing PREMIUMSMART title page state")
    }

    private suspend fun resolveSlug(parsed: PremiumSmartHtmlParser.TitleId): String? {
        val watchId = parsed.primaryVideoId ?: return null
        val watchPage = getRoot("$baseUrl/watch/$watchId")
            .obj("loaders")
            ?.obj("watchPage")
        return PremiumSmartHtmlParser.slugify(
            watchPage?.obj("title")?.string("name")
                ?: watchPage?.obj("video")?.obj("title")?.string("name")
        )
    }

    private suspend fun getChannelItems(url: String): List<AppAdapter.Item> =
        getRoot(url)
            .obj("loaders")
            ?.obj("channelPage")
            ?.obj("channel")
            ?.obj("content")
            ?.array("data")
            .orEmpty()
            .toTitleItems()

    private suspend fun getRoot(url: String): JsonObject =
        PremiumSmartHtmlParser.bootstrap(service.getDocument(url))

    private fun pagedUrl(path: String, page: Int): String =
        "$baseUrl$path" + if (page > 1) "?page=$page" else ""
}

internal object PremiumSmartHtmlParser {
    private val json = Json { ignoreUnknownKeys = true }

    data class TitleId(
        val isSeries: Boolean,
        val titleId: Int,
        val primaryVideoId: Int?,
        val slug: String?,
    )

    data class SeasonId(val titleId: Int, val slug: String, val number: Int)

    fun bootstrap(document: Document): JsonObject = bootstrap(document.outerHtml())

    fun bootstrap(html: String): JsonObject {
        val marker = "window.bootstrapData"
        val markerIndex = html.indexOf(marker)
        require(markerIndex >= 0) { "Unable to find PREMIUMSMART bootstrap data" }
        val assignmentIndex = html.indexOf('=', markerIndex + marker.length)
        val startIndex = html.indexOf('{', assignmentIndex + 1)
        require(assignmentIndex >= 0 && startIndex >= 0) {
            "Unable to find PREMIUMSMART bootstrap JSON"
        }

        var depth = 0
        var inString = false
        var escaped = false
        for (index in startIndex until html.length) {
            val char = html[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return json.parseToJsonElement(
                            html.substring(startIndex, index + 1)
                        ) as JsonObject
                    }
                }
            }
        }
        error("Unterminated PREMIUMSMART bootstrap JSON")
    }

    fun buildTitleId(
        isSeries: Boolean,
        titleId: Int,
        primaryVideoId: Int?,
        slug: String?,
    ): String = listOf(
        if (isSeries) "tv" else "movie",
        titleId.toString(),
        primaryVideoId?.toString().orEmpty(),
        slug.orEmpty(),
    ).joinToString("|")

    fun parseTitleId(id: String): TitleId {
        val parts = id.split('|')
        val titleId = parts.getOrNull(1)?.toIntOrNull()
            ?: id.substringAfter("/titles/", id).substringBefore('/').toIntOrNull()
            ?: error("Invalid PREMIUMSMART title id: $id")
        return TitleId(
            isSeries = parts.firstOrNull() == "tv",
            titleId = titleId,
            primaryVideoId = parts.getOrNull(2)?.toIntOrNull(),
            slug = parts.getOrNull(3)?.takeIf(String::isNotBlank)
                ?: id.substringAfter("/titles/$titleId/", "").substringBefore('/')
                    .takeIf(String::isNotBlank),
        )
    }

    fun buildSeasonId(titleId: Int, slug: String, number: Int): String =
        "$titleId|$slug|$number"

    fun parseSeasonId(id: String): SeasonId? {
        val parts = id.split('|')
        if (parts.size < 3) return null
        return SeasonId(
            titleId = parts[0].toIntOrNull() ?: return null,
            slug = parts[1].takeIf(String::isNotBlank) ?: return null,
            number = parts[2].toIntOrNull() ?: return null,
        )
    }

    fun slugify(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return Normalizer.normalize(
            value.replace('ł', 'l').replace('Ł', 'L'),
            Normalizer.Form.NFD,
        )
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { null }
    }
}

private fun List<JsonElement>.toTitleItems(): List<AppAdapter.Item> =
    mapNotNull { (it as? JsonObject)?.toTitleItem() }
        .distinctBy {
            when (it) {
                is Movie -> "movie:${it.id}"
                is TvShow -> "tv:${it.id}"
                else -> it.toString()
            }
        }

private fun JsonObject.toTitleItem(): AppAdapter.Item? {
    if (string("model_type") != "title") return null
    val titleId = int("id") ?: return null
    val isSeries = boolean("is_series") ?: false
    val id = PremiumSmartHtmlParser.buildTitleId(
        isSeries = isSeries,
        titleId = titleId,
        primaryVideoId = if (isSeries) null else primaryVideoId(),
        slug = PremiumSmartHtmlParser.slugify(string("name")),
    )
    val released = string("release_date") ?: int("year")?.toString()
    val rating = double("rating")?.takeIf { it > 0 }
    val runtime = int("runtime")?.takeIf { it > 0 }

    return if (isSeries) {
        TvShow(
            id = id,
            title = string("name").orEmpty(),
            overview = string("description"),
            released = released,
            runtime = runtime,
            rating = rating,
            poster = string("poster"),
            banner = string("backdrop"),
            imdbId = string("imdb_id"),
        )
    } else {
        Movie(
            id = id,
            title = string("name").orEmpty(),
            overview = string("description"),
            released = released,
            runtime = runtime,
            trailer = string("trailer"),
            quality = obj("primary_video")?.string("quality"),
            rating = rating,
            poster = string("poster"),
            banner = string("backdrop"),
            imdbId = string("imdb_id"),
        )
    }
}

private fun JsonObject.toEpisode(fallbackPoster: String?): Episode? {
    val number = int("episode_number") ?: return null
    val watchId = obj("primary_video")?.int("id") ?: return null
    return Episode(
        id = watchId.toString(),
        number = number,
        title = string("name") ?: "Odcinek $number",
        released = string("release_date"),
        poster = string("poster") ?: fallbackPoster,
        overview = string("description"),
    )
}

private fun JsonObject.toGenres(): List<Genre> =
    array("genres").orEmpty().mapNotNull { element ->
        val genre = element as? JsonObject ?: return@mapNotNull null
        val slug = genre.string("name") ?: return@mapNotNull null
        Genre(
            id = "/genres/$slug",
            name = genre.string("display_name") ?: slug.replace('-', ' '),
        )
    }

private fun JsonObject?.toDirectors(): List<People> {
    if (this == null) return emptyList()
    val people = array("directing").orEmpty() + array("creators").orEmpty()
    return people.mapNotNull { (it as? JsonObject)?.toPerson() }.distinctBy { it.id }
}

private fun JsonObject?.toCast(): List<People> =
    this?.array("actors").orEmpty()
        .mapNotNull { (it as? JsonObject)?.toPerson() }
        .distinctBy { it.id }

private fun JsonObject.toPerson(): People? {
    val id = int("id")?.toString() ?: return null
    return People(id = id, name = string("name").orEmpty(), image = string("poster"))
}

private fun JsonObject.primaryVideoId(): Int? =
    obj("primary_video")?.int("id")
        ?: array("videos").orEmpty()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it.string("category") == "full" }
            ?.int("id")

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
