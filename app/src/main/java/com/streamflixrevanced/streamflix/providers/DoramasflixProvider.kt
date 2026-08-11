package com.streamflixrevanced.streamflix.providers

import com.google.gson.Gson
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixrevanced.streamflix.adapters.AppAdapter
import com.streamflixrevanced.streamflix.extractors.Extractor
import com.streamflixrevanced.streamflix.models.*
import com.streamflixrevanced.streamflix.models.doramasflix.ApiResponse
import com.streamflixrevanced.streamflix.utils.DnsResolver
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Cache
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Url
import java.io.File
import java.net.URL
import java.util.Locale
import java.util.concurrent.TimeUnit

object DoramasflixProvider : Provider {

    override val name = "Doramasflix"
    override val baseUrl = "https://doramasflix.in"
    private const val apiUrl = "https://sv1.fluxcedene.net/api/"
    override val language = "es"

    private val client = getOkHttpClient()

    private val service = Retrofit.Builder()
        .baseUrl(apiUrl)
        .addConverterFactory(GsonConverterFactory.create(Gson()))
        .client(client)
        .build()
        .create(DoramasflixService::class.java)

    private val serviceHtml = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(JsoupConverterFactory.create())
        .client(client)
        .build()
        .create(DoramasflixService::class.java)

    private fun getOkHttpClient(): OkHttpClient {
        val appCache = Cache(File("cacheDir", "okhttpcache"), 10 * 1024 * 1024)

        val clientBuilder = OkHttpClient.Builder()
            .cache(appCache)
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)

        return clientBuilder.dns(DnsResolver.doh).build()
    }

    private const val accessPlatform = "RxARncfg1S_MdpSrCvreoLu_SikCGMzE1NzQzODc3NjE2MQ=="

    private val languages = arrayOf(
        Pair("36", "[ENG]"),
        Pair("37", "[CAST]"),
        Pair("38", "[LAT]"),
        Pair("192", "[SUB]"),
        Pair("1327", "[POR]"),
        Pair("13109", "[COR]"),
        Pair("13110", "[JAP]"),
        Pair("13111", "[MAN]"),
        Pair("13112", "[TAI]"),
        Pair("13113", "[FIL]"),
        Pair("13114", "[IND]"),
        Pair("343422", "[VIET]"),
    )

    private fun String.getLang(): String {
        return languages.firstOrNull { it.first == this }?.second ?: ""
    }

    private interface DoramasflixService {
        @POST("gql")
        @Headers(
            "accept: application/json, text/plain, */*",
            "platform: doramasflix",
            "authorization: Bear",
            "x-access-jwt-token: ",
            "x-access-platform: $accessPlatform"
        )
        suspend fun getApiResponse(@Body body: okhttp3.RequestBody): ApiResponse

        @GET
        suspend fun getPage(@Url url: String): Document

    }

    private fun getPosterUrl(path: String?): String? = when {
        path.isNullOrBlank() -> null
        path.startsWith("http") -> path
        else -> "https://image.tmdb.org/t/p/w500$path"
    }

    private fun getBackdropUrl(path: String?): String? = when {
        path.isNullOrBlank() -> null
        path.startsWith("http") -> path
        else -> "https://image.tmdb.org/t/p/w1280$path"
    }

    private fun routeId(id: String, prefix: String): String {
        val path = if (id.startsWith("http")) {
            runCatching { id.toHttpUrl().encodedPath.trim('/') }
                .getOrDefault(id.trim('/'))
        } else {
            id.trim('/')
        }
        return if (path.startsWith("$prefix/")) path else "$prefix/${path.substringAfterLast('/')}"
    }

    private fun detailSlug(id: String): String = id.trimEnd('/').substringAfterLast('/')

    private fun displayTitle(name: String, spanishName: String?): String =
        spanishName?.takeIf { it.isNotBlank() && !it.equals(name, ignoreCase = true) }
            ?.let { "$name ($it)" }
            ?: name

    private fun trailerUrl(trailer: String?): String? = trailer
        ?.takeIf(String::isNotBlank)
        ?.let { if (it.startsWith("http")) it else "https://www.youtube.com/watch?v=$it" }

    private fun appRating(rating: Double?): Double? = rating
        ?.takeIf { it > 0.0 }
        ?.let { if (it <= 5.0) it * 2.0 else it }

    override suspend fun getHome(): List<Category> {
        return try {
            coroutineScope {
                val homeDeferred = async { serviceHtml.getPage(baseUrl) }
                val popularDoramasDeferred = async { getTvShows(1) }
                val popularMoviesDeferred = async { getMovies(1) }

                val homeDocument = homeDeferred.await()
                val bannerShows = homeDocument.select("article.styles__Article-nxyw6x-3").mapNotNull { element ->
                    val href = element.selectFirst("div.styles__Buttons-sc-78uayx-17 a")?.attr("href") ?: return@mapNotNull null
                    val bannerUrl = element.selectFirst("noscript img")?.attr("src")
                    val title = element.selectFirst("h2.styles__Title-sc-78uayx-1")?.text() ?: return@mapNotNull null

                    val id = href.removePrefix("/")

                    if (href.contains("/peliculas-online/")) {
                        Movie(
                            id = id,
                            title = title,
                            banner = getPosterUrl(bannerUrl)
                        )
                    } else {
                        TvShow(
                            id = id,
                            title = title,
                            banner = getPosterUrl(bannerUrl)
                        )
                    }
                }

                val categories = mutableListOf(
                    Category(name = Category.FEATURED, list = bannerShows),
                    Category(name = "Doramas Populares", list = popularDoramasDeferred.await()),
                    Category(name = "Películas Populares", list = popularMoviesDeferred.await())
                )
                categories
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            return listOf(
                Genre("doramas", "Doramas"),
                Genre("peliculas", "Películas"),
                Genre("variedades", "Variedades")
            )
        }

        val searchQuery = """
            {"operationName":"searchAll","variables":{"input":"$query"},"query":"query searchAll(${'$'}input: String!) {\n  searchDorama(input: ${'$'}input, limit: 32) {\n    _id\n    slug\n    name\n    name_es\n    poster_path\n    poster\n    __typename\n  }\n  searchMovie(input: ${'$'}input, limit: 32) {\n    _id\n    name\n    name_es\n    slug\n    poster_path\n    poster\n    __typename\n  }\n}\n"}
        """.trimIndent()
        val body = searchQuery.toRequestBody("application/json".toMediaType())

        return try {
            val response = service.getApiResponse(body)
            val results = mutableListOf<AppAdapter.Item>()

            response.data?.searchDorama?.forEach { show ->
                results.add(
                    TvShow(
                        id = "doramas-online/${show.slug}",
                        title = "${show.name} (${show.nameEs ?: ""})".trim(),
                        poster = getPosterUrl(show.posterPath ?: show.poster)
                    )
                )
            }

            response.data?.searchMovie?.forEach { show ->
                results.add(
                    Movie(
                        id = "peliculas-online/${show.slug}",
                        title = "${show.name} (${show.nameEs ?: ""})".trim(),
                        poster = getPosterUrl(show.posterPath ?: show.poster)
                    )
                )
            }

            results
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        val query = """
            {"operationName":"listMovies","variables":{"perPage":20,"sort":"POPULARITY_DESC","filter":{},"page":$page},"query":"query listMovies(${'$'}page: Int, ${'$'}perPage: Int, ${'$'}sort: SortFindManyMovieInput, ${'$'}filter: FilterFindManyMovieInput) {\n  paginationMovie(page: ${'$'}page, perPage: ${'$'}perPage, sort: ${'$'}sort, filter: ${'$'}filter) {\n    items {\n      _id\n      name\n      name_es\n      slug\n      poster_path\n      poster\n      __typename\n    }\n  }\n}\n"}
        """.trimIndent()
        val body = query.toRequestBody("application/json".toMediaType())

        return try {
            val response = service.getApiResponse(body)
            response.data?.paginationMovie?.items?.map {
                Movie(
                    id = "peliculas-online/${it.slug}",
                    title = "${it.name} (${it.nameEs ?: ""})".trim(),
                    poster = getPosterUrl(it.posterPath ?: it.poster)
                )
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val query = """
            {"operationName":"listDoramas","variables":{"page":$page,"sort":"POPULARITY_DESC","perPage":20,"filter":{"isTVShow":false}},"query":"query listDoramas(${'$'}page: Int, ${'$'}perPage: Int, ${'$'}sort: SortFindManyDoramaInput, ${'$'}filter: FilterFindManyDoramaInput) {\n  paginationDorama(page: ${'$'}page, perPage: ${'$'}perPage, sort: ${'$'}sort, filter: ${'$'}filter) {\n    items {\n      _id\n      name\n      name_es\n      slug\n      poster_path\n      poster\n      __typename\n    }\n  }\n}\n"}
        """.trimIndent()
        val body = query.toRequestBody("application/json".toMediaType())

        return try {
            val response = service.getApiResponse(body)
            response.data?.paginationDorama?.items?.map {
                TvShow(
                    id = "doramas-online/${it.slug}",
                    title = "${it.name} (${it.nameEs ?: ""})".trim(),
                    poster = getPosterUrl(it.posterPath ?: it.poster)
                )
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getMovieFromProvider(id: String): Movie {
        return try {
            val slug = detailSlug(id)
            val query = """
                {"operationName":"DetailMovieSlug","variables":{"slug":"$slug"},"query":"query DetailMovieSlug(${'$'}slug: String!) { detailMovie(filter: { slug: ${'$'}slug }) { _id name name_es slug overview poster_path poster backdrop_path backdrop rating release_date runtime trailer genres { name slug } } }"}
            """.trimIndent()
            val movieData = service.getApiResponse(
                query.toRequestBody("application/json".toMediaType())
            ).data?.detailMovie ?: throw Exception("No se encontraron datos de la película.")

            Movie(
                id = routeId(id, "peliculas-online"),
                title = displayTitle(movieData.name, movieData.nameEs),
                overview = movieData.overview,
                released = movieData.releaseDate,
                runtime = movieData.runtime,
                trailer = trailerUrl(movieData.trailer),
                rating = appRating(movieData.rating),
                poster = getPosterUrl(movieData.posterPath ?: movieData.poster),
                banner = getBackdropUrl(movieData.backdropPath ?: movieData.backdrop),
                genres = movieData.genres.map { genre ->
                    Genre(id = genre.slug.orEmpty(), name = genre.name.orEmpty())
                },
            )
        } catch (e: Exception) {
            throw Exception("No se pudieron cargar los detalles de la película: ${e.message}", e)
        }
    }

    override suspend fun getTvShowFromProvider(id: String): TvShow {
        return try {
            val slug = detailSlug(id)
            val query = """
                {"operationName":"DetailDoramaSlug","variables":{"slug":"$slug"},"query":"query DetailDoramaSlug(${'$'}slug: String!) { detailDorama(filter: { slug: ${'$'}slug }) { _id name name_es slug overview poster_path poster backdrop_path backdrop rating first_air_date episode_time trailer genres { name slug } seasons { ref slug season_number } } }"}
            """.trimIndent()
            val doramaData = service.getApiResponse(
                query.toRequestBody("application/json".toMediaType())
            ).data?.detailDorama ?: throw Exception("No se encontraron datos del dorama.")

            val seasons = doramaData.seasons.map { season ->
                Season(
                    id = "${doramaData.id}/${season.seasonNumber}",
                    number = season.seasonNumber,
                    title = "Temporada ${season.seasonNumber}",
                    poster = getPosterUrl(doramaData.posterPath ?: doramaData.poster),
                )
            }

            TvShow(
                id = routeId(id, "doramas-online"),
                title = displayTitle(doramaData.name, doramaData.nameEs),
                overview = doramaData.overview,
                released = doramaData.firstAirDate,
                runtime = doramaData.episodeTime,
                trailer = trailerUrl(doramaData.trailer),
                rating = appRating(doramaData.rating),
                poster = getPosterUrl(doramaData.posterPath ?: doramaData.poster),
                banner = getBackdropUrl(doramaData.backdropPath ?: doramaData.backdrop),
                genres = doramaData.genres.map { genre ->
                    Genre(id = genre.slug.orEmpty(), name = genre.name.orEmpty())
                },
                seasons = seasons,
            )
        } catch (e: Exception) {
            throw Exception("No se pudieron cargar los detalles del dorama: ${e.message}", e)
        }
    }

    override suspend fun getEpisodesByProvider(seasonId: String): List<Episode> {
        val doramaId = seasonId.substringBefore("/")
        val seasonNumber = seasonId.substringAfter("/").toInt()

        val episodeQuery = """
            {"operationName":"listEpisodes","variables":{"serie_id":"$doramaId","season_number":$seasonNumber},"query":"query listEpisodes(${'$'}season_number: Float!, ${'$'}serie_id: MongoID!) {\n  listEpisodes(sort: NUMBER_ASC, filter: {type_serie: \"dorama\", serie_id: ${'$'}serie_id, season_number: ${'$'}season_number}) {\n    _id\n    name\n    slug\n    episode_number\n    season_number\n    still_path\n    __typename\n  }\n}\n"}
        """.trimIndent()
        val body = episodeQuery.toRequestBody("application/json".toMediaType())

        return try {
            val response = service.getApiResponse(body)
            response.data?.listEpisodes?.map {
                Episode(
                    id = it.slug,
                    number = it.episodeNumber ?: 0,
                    title = "Episodio ${it.episodeNumber ?: 0}: ${it.name ?: ""}".trim(),
                    poster = getPosterUrl(it.stillPath)
                )
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        return try {
            val links = when (videoType) {
                is Video.Type.Movie -> {
                    val slug = detailSlug(id)
                    val query = """
                        {"operationName":"DetailMovieSlug","variables":{"slug":"$slug"},"query":"query DetailMovieSlug(${'$'}slug: String!) { detailMovie(filter: { slug: ${'$'}slug }) { _id links_online } }"}
                    """.trimIndent()
                    service.getApiResponse(
                        query.toRequestBody("application/json".toMediaType())
                    ).data?.detailMovie?.linksOnline.orEmpty()
                }

                is Video.Type.Episode -> {
                    val slug = detailSlug(id)
                    val detailQuery = """
                        {"operationName":"EpisodeDetailSlug","variables":{"slug":"$slug"},"query":"query EpisodeDetailSlug(${'$'}slug: String!) { detailEpisode(filter: { slug: ${'$'}slug }) { _id slug } }"}
                    """.trimIndent()
                    val episodeId = service.getApiResponse(
                        detailQuery.toRequestBody("application/json".toMediaType())
                    ).data?.detailEpisode?.id ?: return emptyList()
                    val linksQuery = """
                        {"operationName":"EpisodeLinksOnline","variables":{"episode_id":"$episodeId"},"query":"query EpisodeLinksOnline(${'$'}episode_id: MongoID!) { getEpisodeLinks(id: ${'$'}episode_id) { links_online } }"}
                    """.trimIndent()
                    service.getApiResponse(
                        linksQuery.toRequestBody("application/json".toMediaType())
                    ).data?.getEpisodeLinks?.linksOnline.orEmpty()
                }
            }

            links.mapNotNull { link ->
                val wrappedUrl = link.link?.takeIf(String::isNotBlank)
                val directEmbed = link.embed?.takeIf(String::isNotBlank)
                val serverUrl = if (wrappedUrl?.contains("fkplayer.xyz") == true) {
                    directEmbed ?: return@mapNotNull null
                } else {
                    wrappedUrl ?: directEmbed ?: return@mapNotNull null
                }
                val host = runCatching { URL(serverUrl).host }
                    .getOrDefault("")
                    .split('.')
                    .firstOrNull { it.isNotBlank() && it != "www" }
                    ?: "Server"
                val serverName = host.replaceFirstChar { it.titlecase(Locale.ROOT) }
                val language = link.lang?.getLang().orEmpty()
                Video.Server(id = serverUrl, name = "$serverName $language".trim())
            }.distinctBy { it.id }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.id, server)
    override val logo: String = "https://doramasflix.in/img/logo.png"

    override suspend fun getGenre(id: String, page: Int): Genre {
        val list: List<Show> = when (id) {
            "peliculas" -> getMovies(page)
            "variedades" -> {
                val query = """
                    {"operationName":"listDoramas","variables":{"page":$page,"sort":"CREATEDAT_DESC","perPage":32,"filter":{"isTVShow":true}},"query":"query listDoramas(${'$'}page: Int, ${'$'}perPage: Int, ${'$'}sort: SortFindManyDoramaInput, ${'$'}filter: FilterFindManyDoramaInput) {\n  paginationDorama(page: ${'$'}page, perPage: ${'$'}perPage, sort: ${'$'}sort, filter: ${'$'}filter) {\n    items {\n      _id\n      name\n      name_es\n      slug\n      poster_path\n      poster\n      __typename\n    }\n  }\n}\n"}
                """.trimIndent()
                val body = query.toRequestBody("application/json".toMediaType())
                val response = service.getApiResponse(body)
                response.data?.paginationDorama?.items?.map {
                    TvShow(
                        id = it.slug,
                        title = "${it.name} (${it.nameEs ?: ""})".trim(),
                        poster = getPosterUrl(it.posterPath ?: it.poster)
                    )
                } ?: emptyList()
            }
            else -> getTvShows(page)
        }
        return Genre(id = id, name = id.replaceFirstChar { it.uppercase() }, shows = list)
    }

    override suspend fun getPeople(id: String, page: Int): People = throw Exception("Not yet implemented")
}
