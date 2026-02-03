package org.nas.videoplayerandroidtv.domain.repository

import org.nas.videoplayerandroidtv.domain.model.Category
import org.nas.videoplayerandroidtv.domain.model.Series

interface VideoRepository {
    suspend fun getCategoryList(path: String): List<Category>
    suspend fun searchVideos(query: String, category: String = "전체"): List<Series>
    suspend fun getLatestMovies(): List<Series>
    suspend fun getAnimations(): List<Series>
    suspend fun getDramas(): List<Series>
    suspend fun getAnimationsAll(): List<Series> // 전체 애니메이션 추가
}
