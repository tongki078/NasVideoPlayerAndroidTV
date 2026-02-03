package org.nas.videoplayer.domain.repository

import org.nas.videoplayer.domain.model.Category
import org.nas.videoplayer.domain.model.Series

interface VideoRepository {
    suspend fun getCategoryList(path: String): List<Category>
    suspend fun searchVideos(query: String, category: String = "전체"): List<Series>
    suspend fun getLatestMovies(): List<Series>
    suspend fun getAnimations(): List<Series>
}
