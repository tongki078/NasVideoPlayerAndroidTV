package org.nas.videoplayerandroidtv.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Category(
    val name: String? = "",
    val movies: List<Movie>? = emptyList(),
    val path: String? = null,
    val category: String? = null,                // 추가: 메인 카테고리 코드
    val genreIds: List<Int>? = emptyList(),
    val genreNames: List<String>? = emptyList(), // 추가: 한글 장르명
    val director: String? = null,                // 추가: 감독
    val actors: List<Cast>? = emptyList(),       // 추가: 출연진
    val posterPath: String? = null,
    val overview: String? = null,
    val year: String? = null,
    val rating: String? = null
)

@Serializable
data class Movie(
    val id: String? = "",
    val title: String? = "",
    val videoUrl: String? = "",
    val thumbnailUrl: String? = null,
    val overview: String? = null,
    val air_date: String? = null,   // 추가: 방영일
    val season_number: Int? = null,  // 추가: 시즌 번호
    val episode_number: Int? = null, // 추가: 에피소드 번호
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
    val items: List<Category>
)

data class Series(
    val title: String,
    val episodes: List<Movie>,
    val thumbnailUrl: String? = null,
    val fullPath: String? = null,
    val category: String? = null,              // 추가
    val genreIds: List<Int> = emptyList(),
    val genreNames: List<String> = emptyList(), // 추가
    val director: String? = null,              // 추가
    val actors: List<Cast> = emptyList(),      // 추가
    val posterPath: String? = null,
    val year: String? = null,
    val overview: String? = null,
    val rating: String? = null,
    val seasonCount: Int? = null
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
