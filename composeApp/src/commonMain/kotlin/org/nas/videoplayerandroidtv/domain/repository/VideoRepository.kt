package org.nas.videoplayerandroidtv.domain.repository

import org.nas.videoplayerandroidtv.domain.model.Category
import org.nas.videoplayerandroidtv.domain.model.Series
import org.nas.videoplayerandroidtv.domain.model.HomeSection
import org.nas.videoplayerandroidtv.domain.model.SubtitleInfo

interface VideoRepository {
    // 홈 화면 및 카테고리별 테마 섹션 가져오기
    suspend fun getHomeRecommendations(): List<HomeSection>
    suspend fun getCategorySections(categoryKey: String, keyword: String? = null): List<HomeSection>

    suspend fun getCategoryList(path: String, limit: Int = 20, offset: Int = 0): List<Category>
    suspend fun getCategoryVideoCount(path: String): Int
    suspend fun searchVideos(query: String, category: String = "전체"): List<Series>
    
    suspend fun getSeriesDetail(path: String): Series?

    suspend fun getLatestMovies(limit: Int = 20, offset: Int = 0): List<Series>
    suspend fun getPopularMovies(limit: Int = 20, offset: Int = 0): List<Series>
    suspend fun getUhdMovies(limit: Int = 20, offset: Int = 0): List<Series>
    suspend fun getMoviesByTitle(limit: Int = 20, offset: Int = 0): List<Series>
    
    suspend fun getAnimations(): List<Series>
    suspend fun getDramas(): List<Series>
    suspend fun getAnimationsAir(): List<Series>
    suspend fun getDramasAir(): List<Series>
    suspend fun getAnimationsAll(): List<Series>
    
    suspend fun getAnimationsRaftel(limit: Int, offset: Int): List<Series>
    suspend fun getAnimationsSeries(limit: Int, offset: Int): List<Series>

    suspend fun getLatestForeignTV(): List<Series>
    suspend fun getPopularForeignTV(): List<Series>
    
    suspend fun getFtvUs(limit: Int, offset: Int): List<Series>
    suspend fun getFtvCn(limit: Int, offset: Int): List<Series>
    suspend fun getFtvJp(limit: Int, offset: Int): List<Series>
    suspend fun getFtvDocu(limit: Int, offset: Int): List<Series>
    suspend fun getFtvEtc(limit: Int, offset: Int): List<Series>

    suspend fun getLatestKoreanTV(): List<Series>
    suspend fun getPopularKoreanTV(): List<Series>

    suspend fun getKtvDrama(limit: Int, offset: Int): List<Series>
    suspend fun getKtvSitcom(limit: Int, offset: Int): List<Series>
    suspend fun getKtvVariety(limit: Int, offset: Int): List<Series>
    suspend fun getKtvEdu(limit: Int, offset: Int): List<Series>
    suspend fun getKtvDocu(limit: Int, offset: Int): List<Series>

    // 자막 관련
    suspend fun getSubtitleInfo(videoUrl: String): SubtitleInfo
}
