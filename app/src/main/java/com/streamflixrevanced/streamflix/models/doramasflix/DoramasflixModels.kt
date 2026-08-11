package com.streamflixrevanced.streamflix.models.doramasflix

import com.google.gson.annotations.SerializedName

data class ApiResponse(
    val data: Data? = null,
)

data class Data(
    val paginationDorama: Pagination? = null,
    val paginationMovie: Pagination? = null,
    val searchDorama: List<Show>? = null,
    val searchMovie: List<Show>? = null,
    val detailDorama: Show? = null,
    val detailMovie: Show? = null,
    val detailEpisode: Episode? = null,
    val getEpisodeLinks: Links? = null,
    val listSeasons: List<Season>? = null,
    val listEpisodes: List<Episode>? = null,
)

data class Pagination(
    val items: List<Show> = emptyList(),
    val pageInfo: PageInfo? = null,
)

data class Show(
    @SerializedName("_id")
    val id: String,
    val name: String,
    @SerializedName("name_es")
    val nameEs: String? = null,
    val slug: String,
    val overview: String? = null,
    @SerializedName("poster_path")
    val posterPath: String? = null,
    val poster: String? = null,
    @SerializedName("backdrop_path")
    val backdropPath: String? = null,
    val backdrop: String? = null,
    val rating: Double? = null,
    @SerializedName("release_date")
    val releaseDate: String? = null,
    @SerializedName("first_air_date")
    val firstAirDate: String? = null,
    val runtime: Int? = null,
    @SerializedName("episode_time")
    val episodeTime: Int? = null,
    val trailer: String? = null,
    val genres: List<Genre> = emptyList(),
    val seasons: List<Season> = emptyList(),
    @SerializedName("links_online")
    val linksOnline: List<Link> = emptyList(),
    @SerializedName("__typename")
    val typename: String = "",
)

data class Genre(
    val name: String? = null,
    val slug: String? = null,
)

data class PageInfo(
    val hasNextPage: Boolean? = false,
)

data class Season(
    val ref: String? = null,
    val slug: String = "",
    @SerializedName("season_number")
    val seasonNumber: Int,
    @SerializedName("poster_path")
    val posterPath: String? = null,
)

data class Episode(
    @SerializedName("_id")
    val id: String,
    val name: String?,
    val slug: String,
    @SerializedName("episode_number")
    val episodeNumber: Int?,
    @SerializedName("season_number")
    val seasonNumber: Int?,
    @SerializedName("still_path")
    val stillPath: String? = null,
)

data class Links(
    @SerializedName("links_online")
    val linksOnline: List<Link> = emptyList(),
)

data class Link(
    val link: String? = null,
    val embed: String? = null,
    val lang: String? = null,
    val server: String? = null,
)
