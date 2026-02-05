package org.nas.videoplayerandroidtv.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Category(
    val name: String? = "",
    val movies: List<Movie>? = emptyList(),
    val path: String? = null
)

@Serializable
data class Movie(
    val id: String? = "",
    val title: String? = "",
    val videoUrl: String? = "",
    val thumbnailUrl: String? = null
)

data class Series(
    val title: String,
    val episodes: List<Movie>,
    val thumbnailUrl: String? = null,
    val fullPath: String? = null
)
