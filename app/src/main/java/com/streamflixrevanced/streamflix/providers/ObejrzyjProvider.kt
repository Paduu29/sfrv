package com.streamflixrevanced.streamflix.providers

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

object ObejrzyjProvider : Provider {
    override val name = "Obejrzyj.to"
    override val baseUrl = "https://www.obejrzyj.to"
    override val logo = "$baseUrl/storage/branding_media/ead386d3-fca5-4082-8754-2a0992ae8c22.png"
    override val language = "pl"

    private interface Service { @GET suspend fun getDocument(@Url url: String): Document }

    private val service = Retrofit.Builder()
        .baseUrl("$baseUrl/")
        .client(NetworkClient.default.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder()
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Referer", "$baseUrl/").build()) }
            .dns(DnsResolver.doh).build())
        .addConverterFactory(JsoupConverterFactory.create()).build().create(Service::class.java)

    override suspend fun getHome(): List<Category> = getRoot(baseUrl).obj("loaders")
        ?.obj("channelPage")?.obj("channel")?.obj("content")?.array("data")
        .orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { channel ->
            val items = channel.obj("content")?.array("data").orEmpty().toItems().take(20)
            if (items.isEmpty()) null else Category(
                name = if (channel.isSliderCategory()) {
                    Category.FEATURED
                } else {
                    channel.string("name") ?: name
                },
                list = items,
                stableKey = "obejrzyj:${channel.int("id") ?: channel.string("slug")}",
            )
        }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return listOf(Genre("/filmy", "Filmy"), Genre("/seriale", "Seriale"))
        return getRoot(paged("/search/${URLEncoder.encode(query, "UTF-8").replace("+", "%20")}", page))
            .obj("loaders")?.obj("searchPage")?.array("results").orEmpty().toItems()
    }

    override suspend fun getMovies(page: Int): List<Movie> = getChannel(paged("/movies", page)).filterIsInstance<Movie>()
    override suspend fun getTvShows(page: Int): List<TvShow> = getChannel(paged("/series", page)).filterIsInstance<TvShow>()

    override suspend fun getMovieFromProvider(id: String): Movie {
        val parsed = ObejrzyjIds.parseTitle(id)
        val page = titlePage(parsed)
        val title = page.obj("title") ?: error("Missing Obejrzyj movie data")
        return (title.toItem() as? Movie ?: error("Title is not a movie")).copy(
            id = ObejrzyjIds.title(false, parsed.titleId, title.primaryVideoId(), parsed.slug ?: slug(title.string("name"))),
            genres = title.genres(), directors = page.obj("credits").people("directing", "creators"),
            cast = page.obj("credits").people("actors"),
        )
    }

    override suspend fun getTvShowFromProvider(id: String): TvShow {
        val parsed = ObejrzyjIds.parseTitle(id); val page = titlePage(parsed)
        val title = page.obj("title") ?: error("Missing Obejrzyj series data")
        val show = title.toItem() as? TvShow ?: error("Title is not a series")
        val titleSlug = parsed.slug ?: slug(title.string("name")).orEmpty()
        val seasons = page.obj("seasons")?.array("data").orEmpty().mapNotNull { (it as? JsonObject)?.let { s ->
            val n = s.int("number") ?: return@let null
            Season(ObejrzyjIds.season(parsed.titleId, titleSlug, n), n, "Sezon $n", s.string("poster") ?: title.string("poster"))
        } }.sortedBy { it.number }
        return show.copy(id = ObejrzyjIds.title(true, parsed.titleId, null, titleSlug), seasons = seasons,
            genres = title.genres(), directors = page.obj("credits").people("directing", "creators"),
            cast = page.obj("credits").people("actors"))
    }

    override suspend fun getEpisodesByProvider(seasonId: String): List<Episode> {
        val s = ObejrzyjIds.parseSeason(seasonId) ?: return emptyList()
        val page = getRoot("$baseUrl/titles/${s.titleId}/${s.slug}/season/${s.number}").obj("loaders")?.obj("seasonPage") ?: return emptyList()
        val poster = page.obj("season")?.string("poster") ?: page.obj("title")?.string("poster")
        return page.obj("episodes")?.array("data").orEmpty().mapNotNull { (it as? JsonObject)?.episode(poster) }.sortedBy { it.number }
    }

    override suspend fun getGenre(id: String, page: Int): Genre = when (id) {
        "/filmy" -> Genre(id, "Filmy", getMovies(page))
        "/seriale" -> Genre(id, "Seriale", getTvShows(page))
        else -> Genre(id, id.removePrefix("/genres/").replace('-', ' '), shows = getChannel(paged("/genres/${id.removePrefix("/genres/")}", page)).filterIsInstance<com.streamflixrevanced.streamflix.models.Show>())
    }
    override suspend fun getPeople(id: String, page: Int) = People(id, id)

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val watchId = when (videoType) { is Video.Type.Movie -> ObejrzyjIds.parseTitle(id).videoId; is Video.Type.Episode -> id.toIntOrNull() } ?: return emptyList()
        val page = getRoot("$baseUrl/watch/$watchId").obj("loaders")?.obj("watchPage") ?: return emptyList()
        return buildList { page.obj("video")?.let(::add); addAll(page.array("alternative_videos").orEmpty().mapNotNull { it as? JsonObject }) }
            .mapNotNull { v -> v.string("src")?.let { src -> Video.Server(v.int("id")?.toString() ?: src, v.string("name") ?: "Server", src) } }
            .distinctBy { it.src }
    }
    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.src.ifBlank { server.id }, server)

    private suspend fun titlePage(id: ObejrzyjIds.Title): JsonObject = getRoot("$baseUrl/titles/${id.titleId}/${id.slug ?: error("Missing title slug")}").obj("loaders")?.obj("titlePage") ?: error("Missing Obejrzyj title page")
    private suspend fun getChannel(url: String) = getRoot(url).obj("loaders")?.obj("channelPage")?.obj("channel")?.obj("content")?.array("data").orEmpty().toItems()
    private suspend fun getRoot(url: String) = ObejrzyjHtml.bootstrap(service.getDocument(url))
    private fun paged(path: String, page: Int) = "$baseUrl$path" + if (page > 1) "?page=$page" else ""
}

private fun JsonObject.isSliderCategory(): Boolean = listOf(
    string("name"),
    string("slug"),
).any { it?.trim()?.equals("slider", ignoreCase = true) == true }

private object ObejrzyjIds {
    data class Title(val isSeries: Boolean, val titleId: Int, val videoId: Int?, val slug: String?)
    data class Season(val titleId: Int, val slug: String, val number: Int)
    fun title(series: Boolean, id: Int, video: Int?, slug: String?) = listOf(if (series) "tv" else "movie", id, video ?: "", slug ?: "").joinToString("|")
    fun season(id: Int, slug: String, n: Int) = "$id|$slug|$n"
    fun parseTitle(id: String): Title { val p = id.split('|'); val n = p.getOrNull(1)?.toIntOrNull() ?: error("Invalid Obejrzyj title id: $id"); return Title(p.firstOrNull() == "tv", n, p.getOrNull(2)?.toIntOrNull(), p.getOrNull(3)?.takeIf { it.isNotBlank() }) }
    fun parseSeason(id: String): Season? { val p = id.split('|'); return if (p.size >= 3) Season(p[0].toIntOrNull() ?: return null, p[1], p[2].toIntOrNull() ?: return null) else null }
}

private object ObejrzyjHtml {
    private val json = Json { ignoreUnknownKeys = true }
    fun bootstrap(document: Document): JsonObject = bootstrap(document.outerHtml())
    fun bootstrap(html: String): JsonObject {
        val marker = "window.bootstrapData"; val start = html.indexOf('{', html.indexOf('=', html.indexOf(marker)))
        require(start >= 0) { "Unable to find Obejrzyj bootstrap data" }
        var depth = 0; var string = false; var escaped = false
        for (i in start until html.length) { val c = html[i]; if (string) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') string = false; continue }; when (c) { '"' -> string = true; '{' -> depth++; '}' -> { depth--; if (depth == 0) return json.parseToJsonElement(html.substring(start, i + 1)) as JsonObject } } }
        error("Unterminated Obejrzyj bootstrap JSON")
    }
}

private fun List<JsonElement>.toItems() = mapNotNull { (it as? JsonObject)?.toItem() }.distinctBy { it.toString() }
private fun JsonObject.toItem(): AppAdapter.Item? { if (string("model_type") != "title") return null; val id = int("id") ?: return null; val series = boolean("is_series") ?: false; val itemId = ObejrzyjIds.title(series, id, if (!series) primaryVideoId() else null, slug(string("name"))); val released = string("release_date") ?: int("year")?.toString()
    return if (series) TvShow(
        id = itemId, title = string("name").orEmpty(), overview = string("description"), released = released,
        runtime = int("runtime"), rating = double("rating"), poster = string("poster"), banner = string("backdrop"),
        imdbId = string("imdb_id"),
    ) else Movie(
        id = itemId, title = string("name").orEmpty(), overview = string("description"), released = released,
        runtime = int("runtime"), trailer = string("trailer"), quality = obj("primary_video")?.string("quality"),
        rating = double("rating"), poster = string("poster"), banner = string("backdrop"), imdbId = string("imdb_id"),
    )
}
private fun JsonObject.episode(poster: String?) = int("episode_number")?.let { n -> obj("primary_video")?.int("id")?.let { id -> Episode(id.toString(), n, string("name") ?: "Odcinek $n", string("release_date"), string("poster") ?: poster, string("description")) } }
private fun JsonObject.genres() = array("genres").orEmpty().mapNotNull { (it as? JsonObject)?.let { g -> g.string("name")?.let { n -> Genre("/genres/$n", g.string("display_name") ?: n.replace('-', ' ')) } } }
private fun JsonObject?.people(vararg keys: String): List<People> = keys.flatMap { this?.array(it).orEmpty().mapNotNull { p -> (p as? JsonObject)?.int("id")?.let { id -> People(id.toString(), p.string("name").orEmpty(), p.string("poster")) } } }.distinctBy { it.id }
private fun JsonObject.primaryVideoId() = obj("primary_video")?.int("id") ?: array("videos").orEmpty().mapNotNull { it as? JsonObject }.firstOrNull { it.string("category") == "full" }?.int("id")
private fun slug(value: String?): String? = value?.takeIf { it.isNotBlank() }?.let { Normalizer.normalize(it.replace('ł', 'l').replace('Ł', 'L'), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase().replace("&", " and ").replace(Regex("[^a-z0-9]+"), "-").trim('-').takeIf(String::isNotBlank) }
private fun JsonObject.obj(k: String) = this[k] as? JsonObject
private fun JsonObject.array(k: String) = this[k] as? JsonArray
private fun JsonObject.string(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
private fun JsonObject.double(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.boolean(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
