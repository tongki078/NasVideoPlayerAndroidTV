package org.nas.videoplayerandroidtv.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Category(
    val name: String? = "",
    val movies: List<Movie>? = emptyList(),
    val path: String? = null,
    val genreIds: List<Int>? = emptyList(), 
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
    val introStart: Long? = null, // 추가: 인트로 시작 시간 (ms)
    val introEnd: Long? = null    // 추가: 인트로 종료 시간 (ms)
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
