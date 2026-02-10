package org.nas.videoplayerandroidtv.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Category(
    val name: String? = "",
    val movies: List<Movie>? = emptyList(),
    val path: String? = null,
    val genreIds: List<Int>? = emptyList(), // 서버측 캐싱된 장르 정보
    val posterPath: String? = null,         // 서버측 캐싱된 TMDB 포스터 경로
    val overview: String? = null,           // 추가: 메인 줄거리
    val year: String? = null,               // 추가: 제작 년도
    val rating: String? = null              // 추가: 연령 제한
)

@Serializable
data class Movie(
    val id: String? = "",
    val title: String? = "",
    val videoUrl: String? = "",
    val thumbnailUrl: String? = null,
    val overview: String? = null            // 추가: 회차별 줄거리
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
    val genreIds: List<Int> = emptyList(), 
    val posterPath: String? = null,        
    val year: String? = null,              
    val overview: String? = null,          
    val rating: String? = null,            
    val seasonCount: Int? = null
)
