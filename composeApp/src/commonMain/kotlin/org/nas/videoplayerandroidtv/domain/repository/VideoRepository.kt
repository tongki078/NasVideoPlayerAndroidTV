package org.nas.videoplayerandroidtv.domain.repository

import org.nas.videoplayerandroidtv.domain.model.Category
import org.nas.videoplayerandroidtv.domain.model.Series

interface VideoRepository {
    suspend fun getCategoryList(path: String, limit: Int = 20, offset: Int = 0): List<Category>
    suspend fun getCategoryVideoCount(path: String): Int
    suspend fun searchVideos(query: String, category: String = "전체"): List<Series>
    suspend fun getLatestMovies(): List<Series>
    suspend fun getAnimations(): List<Series>
    suspend fun getDramas(): List<Series>
    suspend fun getAnimationsAll(): List<Series> // 전체 애니메이션 추가
}
