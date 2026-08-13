package com.streamflixrevanced.streamflix.providers

import com.streamflixrevanced.streamflix.adapters.AppAdapter
import com.streamflixrevanced.streamflix.models.Category
import com.streamflixrevanced.streamflix.models.Episode
import com.streamflixrevanced.streamflix.models.Genre
import com.streamflixrevanced.streamflix.models.Movie
import com.streamflixrevanced.streamflix.models.People
import com.streamflixrevanced.streamflix.models.LiveChannel
import com.streamflixrevanced.streamflix.models.TvShow
import com.streamflixrevanced.streamflix.models.Video
import com.streamflixrevanced.streamflix.utils.TmdbUtils
import kotlinx.coroutines.sync.Mutex

interface ProviderPortalUrl {
    val portalUrl: String
    val defaultPortalUrl: String
}

interface ProviderConfigUrl {
    val defaultBaseUrl: String

    suspend fun onChangeUrl(forceRefresh: Boolean = false): String
    val changeUrlMutex: Mutex
}

interface IptvProvider : Provider

interface Provider {

    val baseUrl: String
    val name: String
    val logo: String
    val language: String

    suspend fun getHome(): List<Category>

    suspend fun getLiveChannels(page: Int = 1): List<LiveChannel> = emptyList()

    suspend fun search(query: String, page: Int = 1): List<AppAdapter.Item>

    suspend fun getMovies(page: Int = 1): List<Movie>

    suspend fun getMoviesWithTmdb(page: Int = 1): List<Movie> =
        getMovies(page).let { movies ->
            if (this is IptvProvider) movies else TmdbUtils.enrichMovies(movies, language)
        }

    suspend fun getTmdbMovieRating(movie: Movie): TmdbUtils.RatingLookup =
        if (this is IptvProvider) TmdbUtils.RatingLookup(found = false, rating = null)
        else TmdbUtils.lookupMovieRating(movie.title, movie.released?.get(java.util.Calendar.YEAR), language)

    suspend fun getTvShows(page: Int = 1): List<TvShow>

    suspend fun getTvShowsWithTmdb(page: Int = 1): List<TvShow> =
        getTvShows(page).let { tvShows ->
            if (this is IptvProvider) tvShows else TmdbUtils.enrichTvShows(tvShows, language)
        }

    suspend fun getTmdbTvShowRating(tvShow: TvShow): TmdbUtils.RatingLookup =
        if (this is IptvProvider) TmdbUtils.RatingLookup(found = false, rating = null)
        else TmdbUtils.lookupTvShowRating(tvShow.title, tvShow.released?.get(java.util.Calendar.YEAR), language)

    suspend fun getMovie(id: String): Movie = getMovieFromProvider(id).let { movie ->
        if (this is IptvProvider) movie else TmdbUtils.enrichMovie(movie, language = language)
    }

    suspend fun getMovieFromProvider(id: String): Movie =
        throw UnsupportedOperationException("Movie details are not supported by $name")

    suspend fun getTvShow(id: String): TvShow = getTvShowFromProvider(id).let { tvShow ->
        if (this is IptvProvider) tvShow else TmdbUtils.enrichTvShow(tvShow, language = language)
    }

    suspend fun getTvShowFromProvider(id: String): TvShow =
        throw UnsupportedOperationException("TV show details are not supported by $name")

    suspend fun getEpisodesBySeason(seasonId: String): List<Episode> =
        getEpisodesByProvider(seasonId).let { episodes ->
            if (this is IptvProvider) episodes else TmdbUtils.enrichEpisodes(seasonId, episodes)
        }

    suspend fun getEpisodesByProvider(seasonId: String): List<Episode> =
        throw UnsupportedOperationException("Episodes are not supported by $name")

    suspend fun getGenre(id: String, page: Int = 1): Genre

    suspend fun getPeople(id: String, page: Int = 1): People

    suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server>

    suspend fun getVideo(server: Video.Server): Video

    companion object {
        data class ProviderSupport(
            val movies: Boolean,
            val tvShows: Boolean,
            val liveTv: Boolean = false,
        )

        val providers = mapOf(
            AzMoviesProvider to ProviderSupport(movies = true, tvShows = false),
            NoxxProvider to ProviderSupport(movies = false, tvShows = true),
            SflixProvider to ProviderSupport(movies = true, tvShows = true),
            RedflixProvider to ProviderSupport(movies = true, tvShows = true),
            FanpelisProvider to ProviderSupport(movies = true, tvShows = true),
            SerienStreamProvider to ProviderSupport(movies = false, tvShows = true),
            SerialeROProvider to ProviderSupport(movies = true, tvShows = true),
            StreamingCommunityProvider("it") to ProviderSupport(movies = true, tvShows = true),
            StreamingCommunityProvider("en") to ProviderSupport(movies = true, tvShows = true),
            AnimeWorldProvider to ProviderSupport(movies = true, tvShows = true),
            MkissaProvider to ProviderSupport(movies = true, tvShows = true),
            AniWorldProvider to ProviderSupport(movies = false, tvShows = true),
            RidomoviesProvider to ProviderSupport(movies = true, tvShows = true),
            AniXXProvider to ProviderSupport(movies = true, tvShows = true),
            AnikotoProvider to ProviderSupport(movies = true, tvShows = true),
            WiflixProvider to ProviderSupport(movies = true, tvShows = true),
            MStreamProvider to ProviderSupport(movies = true, tvShows = true),
            FrenchAnimeProvider to ProviderSupport(movies = true, tvShows = true),
            FilmPalastProvider to ProviderSupport(movies = true, tvShows = true),
            PoseidonHD2Provider to ProviderSupport(movies = true, tvShows = true),
            CuevanaEuProvider to ProviderSupport(movies = true, tvShows = true),
            LatanimeProvider to ProviderSupport(movies = true, tvShows = true),
            DoramasflixProvider to ProviderSupport(movies = true, tvShows = true),
            CineCalidadProvider to ProviderSupport(movies = true, tvShows = true),
            SeriesFlixProvider to ProviderSupport(movies = false, tvShows = true),
            SeriesTurcasProvider to ProviderSupport(movies = false, tvShows = true),
            FlixLatamProvider to ProviderSupport(movies = true, tvShows = true),
            LaCartoonsProvider to ProviderSupport(movies = false, tvShows = true),
            AnimefenixProvider to ProviderSupport(movies = false, tvShows = true),
            AnimeFlvProvider to ProviderSupport(movies = false, tvShows = true),
            TioAnimeProvider to ProviderSupport(movies = true, tvShows = true),
            JKAnimeProvider to ProviderSupport(movies = true, tvShows = true),
            AnimeAv1Provider to ProviderSupport(movies = false, tvShows = true),
            AnimeOnlineNinjaProvider to ProviderSupport(movies = true, tvShows = true),
            SoloLatinoProvider to ProviderSupport(movies = true, tvShows = true),
            Cine24hProvider to ProviderSupport(movies = true, tvShows = true),
            PelisplustoProvider to ProviderSupport(movies = true, tvShows = true),
            PelisflixHdProvider to ProviderSupport(movies = true, tvShows = true),
            CableVisionHDProvider to ProviderSupport(movies = false, tvShows = true),
            Altadefinizione01Provider to ProviderSupport(movies = true, tvShows = true),
            GuardaFlixProvider to ProviderSupport(movies = true, tvShows = false),
            CB01Provider to ProviderSupport(movies = true, tvShows = true),
            AnimeUnityProvider to ProviderSupport(movies = true, tvShows = true),
            AnimeSaturnProvider to ProviderSupport(movies = false, tvShows = true),
            FrenchStreamProvider to ProviderSupport(movies = true, tvShows = true),
            GuardaSerieProvider to ProviderSupport(movies = true, tvShows = true),
            EinschaltenProvider to ProviderSupport(movies = true, tvShows = false),
            HDFilmeProvider to ProviderSupport(movies = true, tvShows = true),
            MEGAKinoProvider to ProviderSupport(movies = true, tvShows = true),
            FilmyOnlineCcProvider to ProviderSupport(movies = true, tvShows = true),
            PremiumSmartProvider to ProviderSupport(movies = true, tvShows = true),
            ZaluknijProvider to ProviderSupport(movies = true, tvShows = true),
            ObejrzyjProvider to ProviderSupport(movies = true, tvShows = true),
            HdFullProvider to ProviderSupport(movies = true, tvShows = true),
            FilmeHDProvider to ProviderSupport(movies = true, tvShows = false),
            FilmeOnlineUkProvider to ProviderSupport(movies = true, tvShows = true),
            TvporinternetHDProvider to ProviderSupport(movies = false, tvShows = true),
            FrembedProvider to ProviderSupport(movies = true, tvShows = true),
            KidrazProvider to ProviderSupport(movies = true, tvShows = false),
            FrenchMangaProvider to ProviderSupport(movies = false, tvShows = true),
            IptvOrgProvider to ProviderSupport(movies = false, tvShows = true),
            IptvSpainProvider to ProviderSupport(movies = false, tvShows = true),
            TvLibrefutbolProvider to ProviderSupport(movies = false, tvShows = true),
            PelotaLibreTvHdProvider to ProviderSupport(movies = false, tvShows = true),
            PlutoTvMxProvider to ProviderSupport(movies = false, tvShows = true),
            PlutoTvArProvider to ProviderSupport(movies = false, tvShows = true),
            PlutoTvDeProvider to ProviderSupport(movies = false, tvShows = true),
            PlutoTvEsProvider to ProviderSupport(movies = false, tvShows = true),
            PlutoTvFrProvider to ProviderSupport(movies = false, tvShows = true),
            PlutoTvItProvider to ProviderSupport(movies = false, tvShows = true),
            PlutoTvUsProvider to ProviderSupport(movies = false, tvShows = true),
            CineCityProvider to ProviderSupport(movies = false, tvShows = true),
            CineHaxProvider to ProviderSupport(movies = true, tvShows = true),
            VavooProvider("de") to ProviderSupport(movies = false, tvShows = true, liveTv = true),
            VavooProvider("it") to ProviderSupport(movies = false, tvShows = true, liveTv = true),
            VavooProvider("fr") to ProviderSupport(movies = false, tvShows = true, liveTv = true),
            VavooProvider("es") to ProviderSupport(movies = false, tvShows = true, liveTv = true),
            VavooProvider("pl") to ProviderSupport(movies = false, tvShows = true, liveTv = true),
            VavooProvider("ro") to ProviderSupport(movies = false, tvShows = true, liveTv = true),
            HuhuProvider("de") to ProviderSupport(movies = false, tvShows = true, liveTv = true),
            HuhuProvider("it") to ProviderSupport(movies = false, tvShows = true, liveTv = true),
            HuhuProvider("fr") to ProviderSupport(movies = false, tvShows = true, liveTv = true),
            HuhuProvider("es") to ProviderSupport(movies = false, tvShows = true, liveTv = true),
            HuhuProvider("pl") to ProviderSupport(movies = false, tvShows = true, liveTv = true),
            HuhuProvider("ro") to ProviderSupport(movies = false, tvShows = true, liveTv = true),
            FilmoProvider to ProviderSupport(movies = true, tvShows = false)
        )

        // Helper functions to check support
        fun supportsMovies(provider: Provider): Boolean {
            val support = providers[provider] ?: ProviderSupport(movies = true, tvShows = true)
            return support.movies
        }

        fun supportsTvShows(provider: Provider): Boolean {
            val support = providers[provider] ?: ProviderSupport(movies = true, tvShows = true)
            return support.tvShows
        }

        fun supportsLiveTv(provider: Provider): Boolean {
            return providers[provider]?.liveTv ?: false
        }

        fun findByName(name: String): Provider? {
            return providers.keys.find { it.name == name }
        }
    }
}
