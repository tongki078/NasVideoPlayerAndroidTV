package org.nas.videoplayerandroidtv.ui.category

import org.nas.videoplayerandroidtv.domain.model.Series
import org.nas.videoplayerandroidtv.util.TitleUtils.cleanTitle

// 공유 데이터 클래스 정의
data class ThemeSection(val id: String, val title: String, val seriesList: List<Series>)

object ThemeConfig {
    val ACTION_ADVENTURE = listOf(28, 12, 10759, 10765)
    val FANTASY_SCI_FI = listOf(14, 878)
    val COMEDY_LIFE = listOf(35, 10762, 10763, 10767)
    val MYSTERY_THRILLER = listOf(9648, 53, 27, 80)
    val DRAMA_ROMANCE = listOf(18, 10749, 10766, 10764)
    val FAMILY_ANIMATION = listOf(10751, 16)
}

suspend fun processThemedSections(result: List<Series>): List<ThemeSection> {
    if (result.isEmpty()) return emptyList()

    // 1. 제목 기반 그룹화 (시즌별로 나뉜 영상들을 하나로 묶음)
    val groupedResult = result.groupBy { 
        it.title.cleanTitle(includeYear = false) 
    }.map { (baseTitle, seriesList) ->
        if (seriesList.size > 1) {
            // 여러 시즌이 있는 경우 에피소드를 합침
            seriesList[0].copy(
                title = baseTitle,
                episodes = seriesList.flatMap { it.episodes }.distinctBy { it.videoUrl ?: it.id }
            )
        } else {
            seriesList[0]
        }
    }

    val distinctResult = groupedResult.distinctBy { it.fullPath ?: it.title }
    val sectionsList = mutableListOf<ThemeSection>()
    val usedPaths = mutableSetOf<String>()

    // 2. 방금 업데이트된 신작 (상위 15개)
    val newArrivals = distinctResult.take(15)
    if (newArrivals.isNotEmpty()) {
        sectionsList.add(ThemeSection("new_arrival", "방금 업데이트된 신작", newArrivals))
        usedPaths.addAll(newArrivals.map { it.fullPath ?: it.title ?: "" })
    }

    // 3. 실시간 인기 추천 (중복 제외 후 15개)
    val poolAfterNew = distinctResult.filter { (it.fullPath ?: it.title ?: "") !in usedPaths }
    if (poolAfterNew.isNotEmpty()) {
        val todayPicks = poolAfterNew.shuffled().take(15)
        sectionsList.add(ThemeSection("today_pick", "실시간 인기 추천", todayPicks))
        usedPaths.addAll(todayPicks.map { it.fullPath ?: it.title ?: "" })
    }

    // 4. 장르별 분류
    val remainingPool = distinctResult.filter { (it.fullPath ?: it.title ?: "") !in usedPaths }
    
    val tA = mutableListOf<Series>(); val tF = mutableListOf<Series>()
    val tC = mutableListOf<Series>(); val tT = mutableListOf<Series>()
    val tR = mutableListOf<Series>(); val tM = mutableListOf<Series>()
    val tE = mutableListOf<Series>()

    remainingPool.forEach { s ->
        val gIds = s.genreIds
        when {
            gIds.any { it in ThemeConfig.ACTION_ADVENTURE } -> tA.add(s)
            gIds.any { it in ThemeConfig.FANTASY_SCI_FI } -> tF.add(s)
            gIds.any { it in ThemeConfig.COMEDY_LIFE } -> tC.add(s)
            gIds.any { it in ThemeConfig.MYSTERY_THRILLER } -> tT.add(s)
            gIds.any { it in ThemeConfig.DRAMA_ROMANCE } -> tR.add(s)
            gIds.any { it in ThemeConfig.FAMILY_ANIMATION } -> tM.add(s)
            else -> tE.add(s)
        }
    }
    
    if (tA.isNotEmpty()) sectionsList.add(ThemeSection("action", "박진감 넘치는 액션 & 어드벤처", tA))
    if (tF.isNotEmpty()) sectionsList.add(ThemeSection("fantasy", "판타지 & SF", tF))
    if (tC.isNotEmpty()) sectionsList.add(ThemeSection("comedy", "코미디 & 라이프", tC))
    if (tT.isNotEmpty()) sectionsList.add(ThemeSection("thriller", "미스터리 & 스릴러", tT))
    if (tR.isNotEmpty()) sectionsList.add(ThemeSection("romance", "로맨스 & 드라마", tR))
    if (tM.isNotEmpty()) sectionsList.add(ThemeSection("family", "가족과 함께", tM))
    if (tE.isNotEmpty()) sectionsList.add(ThemeSection("etc", "추천 콘텐츠", tE))
    
    return sectionsList
}
