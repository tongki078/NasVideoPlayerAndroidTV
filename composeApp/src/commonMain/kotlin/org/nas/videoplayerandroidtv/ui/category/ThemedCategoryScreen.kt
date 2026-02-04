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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.yield
import org.nas.videoplayerandroidtv.domain.model.Series
import org.nas.videoplayerandroidtv.domain.model.Movie
import org.nas.videoplayerandroidtv.domain.repository.VideoRepository
import org.nas.videoplayerandroidtv.ui.common.MovieRow
import org.nas.videoplayerandroidtv.*
import org.nas.videoplayerandroidtv.ui.common.shimmerBrush

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
    
    val selectedCategoryText = modes.getOrNull(selectedMode) ?: categoryName
    
    // 상태 관리
    var themedSections by remember(selectedMode, categoryName) { mutableStateOf<List<Pair<String, List<Series>>>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var isInitialLoading by remember(selectedMode, categoryName) { mutableStateOf(true) }
    var currentOffset by remember(selectedMode, categoryName) { mutableIntStateOf(0) }
    val pageSize = 8

    // 데이터 로딩 함수 (문법 오류 수정: return 대신 if 문 사용)
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
                yield() // 취소 여부 확인

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
                        newSections.flatMap { it.second.take(5) }.map { series ->
                            async { fetchTmdbMetadata(series.title) }
                        }.awaitAll()
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

    // 카테고리/모드 변경 시 초기화 및 첫 페이지 로드
    LaunchedEffect(selectedMode, categoryName) {
        themedSections = emptyList()
        currentOffset = 0
        isInitialLoading = true
        
        if (isAirScreen) {
            isLoading = true
            try {
                val allSeries = if (selectedMode == 0) repository.getAnimations() else repository.getDramas()
                if (allSeries.isNotEmpty()) {
                    val shuffled = allSeries.shuffled()
                    val chunkSize = (shuffled.size / 5).coerceAtLeast(1)
                    themedSections = listOf(
                        getRandomThemeName("인기", 0, false, selectedCategoryText) to shuffled.take(chunkSize),
                        getRandomThemeName("최근 업데이트", 1, false, selectedCategoryText) to shuffled.drop(chunkSize).take(chunkSize),
                        getRandomThemeName("오늘의 추천", 2, false, selectedCategoryText) to shuffled.drop(chunkSize * 2).take(chunkSize),
                        getRandomThemeName("다시보기", 3, false, selectedCategoryText) to shuffled.drop(chunkSize * 3).take(chunkSize),
                        getRandomThemeName("명작 컬렉션", 4, false, selectedCategoryText) to shuffled.drop(chunkSize * 4)
                    ).filter { it.second.isNotEmpty() }
                }
            } finally {
                isLoading = false
                isInitialLoading = false
            }
        } else {
            loadNextPage()
            // 화면이 덜 채워졌으면 한 번 더 로드
            if (themedSections.size < 3) {
                loadNextPage()
            }
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
        // 상단 모드 탭
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
                    
                    if (isLoading || isInitialLoading) {
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
