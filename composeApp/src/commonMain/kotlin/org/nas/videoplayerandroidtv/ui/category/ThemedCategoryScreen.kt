package org.nas.videoplayerandroidtv.ui.category

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import org.nas.videoplayerandroidtv.domain.model.Movie
import org.nas.videoplayerandroidtv.domain.repository.VideoRepository
import org.nas.videoplayerandroidtv.ui.common.MovieRow
import org.nas.videoplayerandroidtv.*

// TMDB 장르 ID 매핑 (한국어)
private val TMDB_GENRE_MAP = mapOf(
    28 to "액션", 12 to "모험", 16 to "애니메이션", 35 to "코미디", 80 to "범죄",
    99 to "다큐멘터리", 18 to "드라마", 10751 to "가족", 14 to "판타지", 36 to "역사",
    27 to "공포", 10402 to "음악", 9648 to "미스터리", 10749 to "로맨스", 878 to "SF",
    10770 to "TV 영화", 53 to "스릴러", 10752 to "전쟁", 37 to "서부",
    10759 to "액션 & 어드벤처", 10762 to "키즈", 10763 to "뉴스", 10764 to "리얼리티",
    10765 to "SF & 판타지", 10766 to "소프", 10767 to "토크", 10768 to "전쟁 & 정치"
)

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
    val isAirScreen = categoryName == "방송중"
    val isAniScreen = categoryName == "애니메이션"
    val isMovieScreen = categoryName == "영화"
    val isForeignTvScreen = categoryName == "외국TV"
    val isKoreanTvScreen = categoryName == "국내TV"

    val modes = when {
        isAirScreen -> listOf("라프텔 애니메이션", "드라마")
        isAniScreen -> listOf("라프텔", "시리즈")
        isMovieScreen -> listOf("최신", "UHD", "제목")
        isForeignTvScreen -> listOf("중국 드라마", "일본 드라마", "미국 드라마", "기타국가 드라마", "다큐")
        isKoreanTvScreen -> listOf("드라마", "시트콤", "교양", "다큐멘터리", "예능")
        else -> emptyList()
    }
    
    // 상태 관리
    var themedSections by remember(selectedMode, categoryName) { mutableStateOf<List<Pair<String, List<Series>>>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var isInitialLoading by remember(selectedMode, categoryName) { mutableStateOf(!isAirScreen) }
    var currentOffset by remember(selectedMode, categoryName) { mutableIntStateOf(0) }
    val pageSize = 8

    // 데이터 로딩 함수 (기존 로직 유지)
    val loadNextPage: suspend () -> Unit = {
        if (!isLoading) {
            isLoading = true
            try {
                val currentRootPath = when {
                    isAniScreen && selectedMode == 0 -> "애니메이션/라프텔"
                    isAniScreen && selectedMode == 1 -> "애니메이션/시리즈"
                    isMovieScreen && selectedMode == 0 -> "영화/최신"
                    isMovieScreen && selectedMode == 1 -> "영화/UHD"
                    isMovieScreen && selectedMode == 2 -> "영화/제목"
                    isForeignTvScreen && selectedMode == 0 -> "외국TV/중국 드라마"
                    isForeignTvScreen && selectedMode == 1 -> "외국TV/일본 드라마"
                    isForeignTvScreen && selectedMode == 2 -> "외국TV/미국 드라마"
                    isForeignTvScreen && selectedMode == 3 -> "외국TV/기타국가 드라마"
                    isForeignTvScreen && selectedMode == 4 -> "외국TV/다큐"
                    isKoreanTvScreen && selectedMode == 0 -> "국내TV/드라마"
                    isKoreanTvScreen && selectedMode == 1 -> "국내TV/시트콤"
                    isKoreanTvScreen && selectedMode == 2 -> "국내TV/교양"
                    isKoreanTvScreen && selectedMode == 3 -> "국내TV/다큐멘터리"
                    isKoreanTvScreen && selectedMode == 4 -> "국내TV/예능"
                    else -> rootPath
                }

                val themeFolders = repository.getCategoryList(currentRootPath, limit = pageSize, offset = currentOffset)
                yield()

                if (themeFolders.isNotEmpty()) {
                    val newSections = coroutineScope {
                        themeFolders.mapIndexed { index, folder ->
                            async {
                                val folderPath = "$currentRootPath/${folder.name}"
                                val content = repository.getCategoryList(folderPath)
                                val isThemeFolder = content.any { it.movies.isNotEmpty() }

                                val seriesList: List<Series> = if (isThemeFolder) {
                                    content.flatMap { it.movies }.groupBySeries(folderPath)
                                } else {
                                    content.map { subFolder ->
                                        Series(
                                            title = subFolder.name.cleanTitle(includeYear = false),
                                            episodes = emptyList(),
                                            fullPath = "$folderPath/${subFolder.name}"
                                        )
                                    }.filter { it.title.length > 1 }
                                }
                                
                                val isLatestMode = isMovieScreen && selectedMode == 0
                                val shouldApplyLimit = isThemeFolder && !isLatestMode
                                
                                if (!shouldApplyLimit || seriesList.size >= 10) {
                                    if (seriesList.isNotEmpty()) {
                                        getRandomThemeName(folder.name, currentOffset + index, currentRootPath.contains("영화"), categoryName) to seriesList
                                    } else null
                                } else null
                            }
                        }.awaitAll().filterNotNull()
                    }
                    
                    themedSections = themedSections + newSections
                    currentOffset += pageSize
                    
                    // TMDB 프리페칭
                    coroutineScope {
                        newSections.flatMap { it.second.take(3) }.forEach { series ->
                            launch { fetchTmdbMetadata(series.title) }
                        }
                    }
                }
            } catch (e: Exception) {
                println("Error loading page: ${e.message}")
            } finally {
                isLoading = false
                isInitialLoading = false
            }
        }
    }

    // [방송중] 카테고리 실시간 점진적 로딩 로직 (모든 영상 로드 버전)
    LaunchedEffect(selectedMode, categoryName) {
        if (!isAirScreen) {
            themedSections = emptyList()
            currentOffset = 0
            isInitialLoading = true
            loadNextPage()
            return@LaunchedEffect
        }

        themedSections = emptyList()
        isLoading = true
        // isInitialLoading은 이미 false (isAirScreen일 때)

        try {
            val isAnimationMode = selectedMode == 0
            val allSeries = (if (isAnimationMode) repository.getAnimations() else repository.getDramas()).shuffled()
            if (allSeries.isEmpty()) return@LaunchedEffect

            // 분석 대상: 서버의 모든 영상 (제한 해제)
            val targetSeries = allSeries
            val genreGroups = mutableMapOf<String, MutableList<Series>>()
            val noGenreList = mutableListOf<Series>()

            // UI 갱신용 헬퍼 함수
            fun updateUI() {
                val finalSections = mutableListOf<Pair<String, List<Series>>>()
                
                // 1. 장르별 그룹 정렬 (콘텐츠 많은 순으로 상단 배치)
                genreGroups.toList()
                    .sortedByDescending { it.second.size }
                    .forEach { (genre, list) ->
                        finalSections.add("$genre 시리즈" to list.toList())
                    }

                // 2. 분류 대기 중이거나 장르가 없는 전체 영상 (하단 배치)
                if (noGenreList.isNotEmpty()) {
                    val label = if (isAnimationMode) "전체 애니메이션" else "전체 드라마"
                    finalSections.add(label to noGenreList.toList())
                }
                
                themedSections = finalSections
            }

            // 1단계: 즉시 모든 데이터 표시 (기다림 없이 서버 리스트 전체 노출)
            noGenreList.addAll(targetSeries)
            updateUI()
            yield()

            // 2단계: 캐시된 데이터 즉시 분류 (메모리에 있는 정보 활용)
            targetSeries.forEach { series ->
                val cacheKey = if (isAnimationMode) "ani_${series.title}" else series.title
                tmdbCache[cacheKey]?.let { cached ->
                    val genres = cached.genreIds.mapNotNull { TMDB_GENRE_MAP[it] }
                    if (genres.isNotEmpty()) {
                        noGenreList.remove(series)
                        genreGroups.getOrPut(genres.first()) { mutableListOf() }.add(series)
                    }
                }
            }
            updateUI()
            yield()

            // 3단계: 나머지 데이터 백그라운드 배치 처리 (10개씩 분석하며 실시간 이동)
            val remaining = targetSeries.filter { series ->
                val cacheKey = if (isAnimationMode) "ani_${series.title}" else series.title
                tmdbCache[cacheKey] == null
            }

            remaining.chunked(10).forEach { batch ->
                coroutineScope {
                    batch.map { series ->
                        async {
                            try {
                                fetchTmdbMetadata(series.title, isAnimation = isAnimationMode) to series
                            } catch (e: Exception) {
                                null to series
                            }
                        }
                    }.awaitAll().forEach { (metadata, series) ->
                        val genres = metadata?.genreIds?.mapNotNull { TMDB_GENRE_MAP[it] } ?: emptyList()
                        if (genres.isNotEmpty()) {
                            noGenreList.remove(series)
                            genreGroups.getOrPut(genres.first()) { mutableListOf() }.add(series)
                        }
                        // 장르 없는 경우 이미 noGenreList에 있으므로 그대로 둠
                    }
                }
                updateUI()
                yield()
            }

        } catch (e: Exception) {
            println("AirScreen loading error: ${e.message}")
        } finally {
            isLoading = false
            isInitialLoading = false
        }
    }

    // 무한 스크롤 감지
    val isAtBottom = remember {
        derivedStateOf {
            val layoutInfo = lazyListState.layoutInfo
            val visibleItemsInfo = layoutInfo.visibleItemsInfo
            if (layoutInfo.totalItemsCount == 0) false
            else {
                val lastVisibleItem = visibleItemsInfo.lastOrNull()
                lastVisibleItem != null && lastVisibleItem.index >= layoutInfo.totalItemsCount - 2
            }
        }
    }

    LaunchedEffect(isAtBottom.value) {
        if (isAtBottom.value && !isLoading && !isInitialLoading && !isAirScreen) {
            loadNextPage()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0F0F0F))) {
        if (modes.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp, horizontal = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(modes.size) { index ->
                    CategoryTabItem(
                        text = modes[index],
                        isSelected = selectedMode == index,
                        onClick = { onModeChange(index) }
                    )
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (themedSections.isEmpty() && !isLoading && !isInitialLoading) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("표시할 영상이 없습니다.", color = Color.Gray)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(), 
                    state = lazyListState,
                    contentPadding = PaddingValues(bottom = 100.dp)
                ) {
                    items(themedSections) { (title, seriesList) ->
                        MovieRow(
                            title = title,
                            seriesList = seriesList,
                            onSeriesClick = onSeriesClick
                        )
                    }
                    
                    // 방송중 카테고리가 아닐 때만 하단 로딩 스피너 표시
                    if ((isLoading || isInitialLoading) && !isAirScreen) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = Color.Red, modifier = Modifier.size(32.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryTabItem(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    
    val backgroundColor by animateColorAsState(
        when {
            isFocused -> Color.White
            isSelected -> Color.Red
            else -> Color.Gray.copy(alpha = 0.2f)
        }
    )
    
    val textColor by animateColorAsState(
        when {
            isFocused -> Color.Black
            isSelected -> Color.White
            else -> Color.Gray
        }
    )

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
        Text(
            text = text,
            color = textColor,
            fontSize = 16.sp,
            fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium
        )
    }
}

private fun List<Movie>.groupBySeries(basePath: String? = null): List<Series> = 
    this.groupBy { it.title.cleanTitle(includeYear = false) }
        .map { (title, eps) -> 
            Series(
                title = title, 
                episodes = eps.sortedWith(
                    compareBy<Movie> { it.title.extractSeason() }
                        .thenBy { it.title.extractEpisode()?.filter { char -> char.isDigit() }?.toIntOrNull() ?: 0 }
                ),
                fullPath = basePath
            ) 
        }
        .sortedBy { it.title }
