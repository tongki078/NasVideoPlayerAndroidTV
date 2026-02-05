package org.nas.videoplayerandroidtv.ui.category

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import org.nas.videoplayerandroidtv.domain.model.Series
import org.nas.videoplayerandroidtv.domain.repository.VideoRepository
import org.nas.videoplayerandroidtv.ui.common.MovieRow
import org.nas.videoplayerandroidtv.*

private object ThemeConfig {
    val ACTION_ADVENTURE = listOf(28, 12, 10759)
    val FANTASY_SCI_FI = listOf(14, 878, 10765)
    val COMEDY_LIFE = listOf(35, 10762)
    val MYSTERY_THRILLER = listOf(9648, 53, 27, 80)
    val DRAMA_ROMANCE = listOf(18, 10749)
    val FAMILY_ANIMATION = listOf(10751, 16)
}

private data class ThemeSection(val id: String, val title: String, val seriesList: List<Series>)

@Composable
fun ThemedCategoryScreen(
    categoryName: String,
    rootPath: String,
    repository: VideoRepository,
    selectedMode: Int,
    onModeChange: (Int) -> Unit,
    lazyListState: LazyListState = rememberLazyListState(),
    onSeriesClick: (Series) -> Unit
) {
    val isMovieScreen = categoryName == "영화"
    val isAniScreen = categoryName == "애니메이션"
    val isAirScreen = categoryName == "방송중"
    val isForeignTVScreen = categoryName == "외국TV"
    val isKoreanTVScreen = categoryName == "국내TV"

    val modes = when {
        isAirScreen -> listOf("라프텔 애니메이션", "드라마")
        isAniScreen -> listOf("라프텔", "시리즈")
        isMovieScreen -> listOf("제목", "UHD", "최신")
        isForeignTVScreen -> listOf("최신", "인기")
        isKoreanTVScreen -> listOf("최신", "인기")
        else -> emptyList()
    }

    val scope = rememberCoroutineScope()
    
    // 테마별 리스트 상태 관리
    var actionList by remember { mutableStateOf(listOf<Series>()) }
    var fantasyList by remember { mutableStateOf(listOf<Series>()) }
    var comedyList by remember { mutableStateOf(listOf<Series>()) }
    var thrillerList by remember { mutableStateOf(listOf<Series>()) }
    var romanceList by remember { mutableStateOf(listOf<Series>()) }
    var familyList by remember { mutableStateOf(listOf<Series>()) }
    var etcList by remember { mutableStateOf(listOf<Series>()) }

    val themedSections = remember(actionList, fantasyList, comedyList, thrillerList, romanceList, familyList, etcList) {
        listOf(
            ThemeSection("action", "박진감 넘치는 액션 & 어드벤처", actionList),
            ThemeSection("fantasy", "상상 그 이상! 판타지 & SF", fantasyList),
            ThemeSection("comedy", "유쾌한 즐거움! 코미디 & 키즈", comedyList),
            ThemeSection("thriller", "숨막히는 미스터리 & 스릴러", thrillerList),
            ThemeSection("romance", "달콤하고 절절한 로맨스 & 드라마", romanceList),
            ThemeSection("family", "온 가족이 함께! 패밀리 & 애니", familyList),
            ThemeSection("etc", "놓치면 아쉬운 더 많은 작품들", etcList)
        ).filter { it.seriesList.isNotEmpty() }
    }

    var isInitialLoading by remember(selectedMode, categoryName) { mutableStateOf(true) }
    var isPagingLoading by remember { mutableStateOf(false) }
    var currentOffset by remember(selectedMode, categoryName) { mutableStateOf(0) }
    var hasMoreData by remember(selectedMode, categoryName) { mutableStateOf(true) }
    val pageSize = 200

    // [핵심 로직] 데이터를 조각내어 병렬 처리하고 즉시 화면에 뿌림
    suspend fun processAndDistribute(newSeries: List<Series>) = coroutineScope {
        newSeries.chunked(20).forEach { chunk ->
            // 1. 서버 장르 정보가 부족한 아이템만 병렬로 보충
            chunk.map { series ->
                async(Dispatchers.Default) {
                    if (series.genreIds.isEmpty() && !tmdbCache.containsKey(series.title) && !tmdbCache.containsKey("ani_${series.title}")) {
                        fetchTmdbMetadata(series.title)
                    }
                    series
                }
            }.awaitAll()

            // 2. 조각 로딩이 끝날 때마다 즉시 UI 배분 (기다림 없음)
            withContext(Dispatchers.Main) {
                val tAction = mutableListOf<Series>()
                val tFantasy = mutableListOf<Series>()
                val tComedy = mutableListOf<Series>()
                val tThriller = mutableListOf<Series>()
                val tRomance = mutableListOf<Series>()
                val tFamily = mutableListOf<Series>()
                val tEtc = mutableListOf<Series>()

                chunk.forEach { series ->
                    val genreIds = if (series.genreIds.isNotEmpty()) series.genreIds 
                                   else (tmdbCache[series.title] ?: tmdbCache["ani_${series.title}"])?.genreIds ?: emptyList()

                    when {
                        genreIds.any { it in ThemeConfig.ACTION_ADVENTURE } -> tAction.add(series)
                        genreIds.any { it in ThemeConfig.FANTASY_SCI_FI } -> tFantasy.add(series)
                        genreIds.any { it in ThemeConfig.COMEDY_LIFE } -> tComedy.add(series)
                        genreIds.any { it in ThemeConfig.MYSTERY_THRILLER } -> tThriller.add(series)
                        genreIds.any { it in ThemeConfig.DRAMA_ROMANCE } -> tRomance.add(series)
                        genreIds.any { it in ThemeConfig.FAMILY_ANIMATION } -> tFamily.add(series)
                        else -> tEtc.add(series)
                    }
                }

                if (tAction.isNotEmpty()) actionList = (actionList + tAction).distinctBy { it.title }
                if (tFantasy.isNotEmpty()) fantasyList = (fantasyList + tFantasy).distinctBy { it.title }
                if (tComedy.isNotEmpty()) comedyList = (comedyList + tComedy).distinctBy { it.title }
                if (tThriller.isNotEmpty()) thrillerList = (thrillerList + tThriller).distinctBy { it.title }
                if (tRomance.isNotEmpty()) romanceList = (romanceList + tRomance).distinctBy { it.title }
                if (tFamily.isNotEmpty()) familyList = (familyList + tFamily).distinctBy { it.title }
                if (tEtc.isNotEmpty()) etcList = (etcList + tEtc).distinctBy { it.title }
                
                isInitialLoading = false
            }
            yield() // 스크롤 시 부드러운 성능 유지
        }
    }

    suspend fun loadMore() {
        if (!hasMoreData || isPagingLoading) return
        if (currentOffset == 0) isInitialLoading = true else isPagingLoading = true

        try {
            val result = withContext(Dispatchers.Default) {
                when {
                    isMovieScreen -> when (selectedMode) {
                        0 -> repository.getMoviesByTitle(pageSize, currentOffset)
                        1 -> repository.getUhdMovies(pageSize, currentOffset)
                        else -> repository.getLatestMovies(pageSize, currentOffset)
                    }
                    isAniScreen -> if (selectedMode == 0) repository.getAnimationsRaftel(pageSize, currentOffset) else repository.getAnimationsSeries(pageSize, currentOffset)
                    isAirScreen -> if (selectedMode == 0) repository.getAnimationsAir() else repository.getDramasAir()
                    isForeignTVScreen -> if (selectedMode == 0) repository.getLatestForeignTV() else repository.getPopularForeignTV()
                    isKoreanTVScreen -> if (selectedMode == 0) repository.getLatestKoreanTV() else repository.getPopularKoreanTV()
                    else -> emptyList()
                }
            }

            if (result.isEmpty()) {
                hasMoreData = false
                isInitialLoading = false
            } else {
                processAndDistribute(result)
                currentOffset += pageSize
                if (result.size < pageSize || isAirScreen) hasMoreData = false
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            isInitialLoading = false
        } finally {
            isPagingLoading = false
        }
    }

    LaunchedEffect(selectedMode, categoryName) {
        actionList = emptyList()
        fantasyList = emptyList()
        comedyList = emptyList()
        thrillerList = emptyList()
        romanceList = emptyList()
        familyList = emptyList()
        etcList = emptyList()
        currentOffset = 0
        hasMoreData = true
        loadMore()
    }

    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisibleItemIndex = lazyListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisibleItemIndex >= themedSections.size - 1 && themedSections.isNotEmpty()
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && hasMoreData && !isInitialLoading && !isPagingLoading) {
            loadMore()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0F0F0F))) {
        if (modes.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp, start = 48.dp, end = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(modes.size) { index ->
                    CategoryTabItem(text = modes[index], isSelected = selectedMode == index, onClick = { onModeChange(index) })
                }
            }
        }

        Box(modifier = Modifier.fillMaxWidth().height(2.dp)) {
            if (isInitialLoading || isPagingLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Color.Red, trackColor = Color.Transparent)
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (!isInitialLoading && themedSections.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("영상을 불러올 수 없습니다.", color = Color.Gray)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = lazyListState,
                    contentPadding = PaddingValues(bottom = 100.dp)
                ) {
                    items(themedSections, key = { it.id }) { section ->
                        MovieRow(
                            title = section.title, 
                            seriesList = section.seriesList, 
                            onSeriesClick = onSeriesClick,
                            onReachEnd = {
                                if (hasMoreData && !isPagingLoading) {
                                    scope.launch { loadMore() }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryTabItem(text: String, isSelected: Boolean, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val backgroundColor by animateColorAsState(if (isFocused) Color.White else if (isSelected) Color.Red else Color.Gray.copy(alpha = 0.2f))
    val textColor by animateColorAsState(if (isFocused) Color.Black else if (isSelected) Color.White else Color.Gray)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(backgroundColor)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .scale(if (isFocused) 1.1f else 1.0f),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = textColor, fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium, fontSize = 15.sp)
    }
}
