package org.nas.videoplayerandroidtv.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Category(val name: String, val movies: List<Movie> = emptyList())

@Serializable
data class Movie(
    val id: String,
    val title: String,
    val thumbnailUrl: String? = null,
    val videoUrl: String,
    val duration: String? = null
)

data class Series(
    val title: String,
    val episodes: List<Movie>,
    val thumbnailUrl: String? = null,
    val fullPath: String? = null
)

enum class Screen { HOME, SEARCH, ON_AIR, ANIMATIONS, MOVIES, FOREIGN_TV, KOREAN_TV, LATEST }
