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
    // [수정] 아래 두 함수는 서버에서 더 이상 사용하지 않으므로 삭제 또는 AIR_DIR 내의 서브 카테고리를 반환하도록 변경 필요
    // 현재는 AIR_DIR 하위의 "라프텔 애니메이션"과 "드라마"를 에뮬레이션해야 하므로,
    // /air 엔드포인트의 결과를 받아서 필터링하는 방식으로 변경이 필요합니다.
    
    // AIR_DIR 통합에 맞춰 getAnimations/getDramas 대신 getAirSubCategory를 사용하도록 변경을 고려해야 하나
    // 클라이언트 코드를 유지하기 위해 아래 함수들을 /air 엔드포인트에서 필터링하도록 수정하겠습니다.
    suspend fun getAnimationsAir(): List<Series> // '방송중/라프텔 애니메이션' 데이터
    suspend fun getDramasAir(): List<Series> // '방송중/드라마' 데이터
    suspend fun getAnimationsAll(): List<Series>
}
