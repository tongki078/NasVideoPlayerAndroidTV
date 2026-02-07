package org.nas.videoplayerandroidtv.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Category(
    val name: String? = "",
    val movies: List<Movie>? = emptyList(),
    val path: String? = null,
    val genreIds: List<Int>? = emptyList(), // 서버측 캐싱된 장르 정보
    val posterPath: String? = null          // 서버측 캐싱된 TMDB 포스터 경로
)

@Serializable
data class Movie(
    val id: String? = "",
    val title: String? = "",
    val videoUrl: String? = "",
    val thumbnailUrl: String? = null
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
    val genreIds: List<Int> = emptyList(), // 즉각적인 분류에 사용
    val posterPath: String? = null,        // TMDB 포스터 직접 경로
    val year: String? = null,              // 개봉/방송 년도
    val overview: String? = null,          // 줄거리
    val rating: String? = null,            // 연령 제한 (예: 15+, 19+)
    val seasonCount: Int? = null           // 시즌 개수
)
