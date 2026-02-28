package org.nas.videoplayerandroidtv.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Category(
    val name: String? = "",
    val movies: List<Movie>? = emptyList(),
    val seasons: Map<String, List<Movie>>? = emptyMap(), // [추가] 시즌별 분류 데이터
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
    val tmdbTitle: String? = null
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
    val introEnd: Long? = null
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
    val initials: List<String>? = emptyList() // [추가] 초성 필터 리스트
)

data class Series(
    val title: String,
    val episodes: List<Movie>,
    val seasons: Map<String, List<Movie>> = emptyMap(), // [추가] 시즌별 분류 데이터
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
    val tmdbTitle: String? = null
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
