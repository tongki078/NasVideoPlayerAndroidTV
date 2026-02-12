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
    val pathStackJson: String,
    val posterPath: String? = null,
    val lastPosition: Long = 0L,
    val duration: Long = 0L,
    val seriesTitle: String? = null, // 추가: 시리즈 제목
    val seriesPath: String? = null   // 추가: 시리즈 경로
)
