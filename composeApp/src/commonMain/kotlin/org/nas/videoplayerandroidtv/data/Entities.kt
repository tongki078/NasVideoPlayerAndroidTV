package org.nas.videoplayerandroidtv.data

data class SearchHistory(
    val query: String,
    val timestamp: Long
)

data class WatchHistory(
    val id: String,
    val title: String,
    val videoUrl: String,
    val thumbnailUrl: String?,
    val timestamp: Long,
    val screenType: String,
    val pathStackJson: String
)
