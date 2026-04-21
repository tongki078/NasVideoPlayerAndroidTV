package org.nas.videoplayerandroidtv.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Category(
    val name: String? = "",
    val cleanedName: String? = "",
    val movies: List<Movie>? = emptyList(),
    val seasons: Map<String, List<Movie>>? = emptyMap(),
    val path: String? = null,
    val category: String? = null,
    val genreIds: List<Int>? = emptyList(),
    val genreNames: List<String>? = emptyList(),
    val director: String? = null,
    val actors: List<Cast>? = emptyList(),
    val posterPath: String? = null,
    val overview: String? = null,
    val year: String? = null,
    val rating: String? = null,
    val seasonCount: Int? = null,
    val tmdbTitle: String? = null,
    val tmdbId: String? = null,
    val chosung: String? = null
)

@Serializable
data class Movie(
    val id: String? = "",
    val title: String? = "",
    val videoUrl: String? = "",
    val thumbnailUrl: String? = null,
    val overview: String? = null,
    val air_date: String? = null,
    val season_number: Int? = null,
    val episode_number: Int? = null,
    val introStart: Long? = null,
    val introEnd: Long? = null,
    val position: Double? = 0.0,
    val duration: Double? = 0.0,
    val runtime: Int? = null
)

@Serializable
data class Cast(
    val name: String,
    val profile: String? = null,
    val role: String? = null
)

@Serializable
data class HomeSection(
    val title: String,
    val items: List<Category>,
    val is_full_list: Boolean = false
)

data class Series(
    val title: String,
    val cleanedName: String? = null,
    val episodes: List<Movie>,
    val seasons: Map<String, List<Movie>> = emptyMap(),
    val thumbnailUrl: String? = null,
    val fullPath: String? = null,
    val category: String? = null,
    val genreIds: List<Int> = emptyList(),
    val genreNames: List<String> = emptyList(),
    val director: String? = null,
    val actors: List<Cast> = emptyList(),
    val posterPath: String? = null,
    val year: String? = null,
    val overview: String? = null,
    val rating: String? = null,
    val seasonCount: Int? = null,
    val tmdbTitle: String? = null,
    val extras: List<Series> = emptyList()
)

@Serializable
data class SubtitleInfo(
    val external: List<SubtitleTrack> = emptyList(),
    val embedded: List<EmbeddedSubtitleStream> = emptyList(),
    val extraction_triggered: Boolean = false
)

@Serializable
data class SubtitleTrack(
    val name: String,
    val path: String
)

@Serializable
data class EmbeddedSubtitleStream(
    val index: Int,
    val codec_name: String? = null,
    val tags: Map<String, String>? = null
)
