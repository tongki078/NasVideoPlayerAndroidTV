package org.nas.videoplayerandroidtv.ui.home

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.nas.videoplayerandroidtv.data.WatchHistory
import org.nas.videoplayerandroidtv.data.repository.VideoRepositoryImpl
import org.nas.videoplayerandroidtv.domain.model.Category
import org.nas.videoplayerandroidtv.domain.model.HomeSection
import org.nas.videoplayerandroidtv.domain.model.Movie
import org.nas.videoplayerandroidtv.domain.model.Series
import org.nas.videoplayerandroidtv.domain.repository.VideoRepository
import org.nas.videoplayerandroidtv.util.TitleUtils.isGenericTitle
import org.nas.videoplayerandroidtv.Screen
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    watchHistory: List<WatchHistory>,
    homeSections: List<HomeSection>,
    isLoading: Boolean,
    lazyListState: LazyListState, 
    rowStates: MutableMap<String, LazyListState>,
    rowFocusIndices: SnapshotStateMap<String, Int>,
    currentScreen: Screen = Screen.HOME,
    lastFocusedPath: String? = null,
    onFocusRestored: () -> Unit = {},
    onSeriesClick: (Series) -> Unit,
    onPlayClick: (Movie) -> Unit,
    onHistoryClick: (WatchHistory) -> Unit = {}
) {
    val repository: VideoRepository = remember { VideoRepositoryImpl() }
    val standardMargin = 20.dp 
    val coroutineScope = rememberCoroutineScope()

    // [강력한 필터링] 모든 카테고리(애니 포함)에서 포스터가 없는 항목은 절대 노출 안함
    val combinedSections = remember(homeSections, currentScreen) {
        homeSections.map { section: HomeSection ->
            section.copy(
                items = section.items.filter { item: Category ->
                    val name = (item.name ?: "").trim()
                    // 1. 의미 없는 제목 필터링
                    if (isGenericTitle(name)) return@filter false
                    
                    // 2. [필터링 강화] 포스터가 없는 항목 제외
                    // posterPath가 null이거나, 비었거나, 문자열 "null"인 경우 모두 필터링
                    val hasPoster = !item.posterPath.isNullOrBlank() && item.posterPath != "null"
                    
                    // "방송중" 화면이 아니고, 섹션 제목에도 "방영/방송"이 없을 때만 필터링 수행
                    val isAirSection = currentScreen == Screen.ON_AIR || 
                                     section.title.contains("방영") || 
                                     section.title.contains("방송")
                    
                    if (!isAirSection && !hasPoster) return@filter false
                    
                    true
                }
            )
        }.filter { it.items.isNotEmpty() }
    }

    val heroPool = remember(combinedSections) {
        val poolSource = combinedSections.find { it.title.contains("인기작") } ?: combinedSections.firstOrNull()
        poolSource?.items?.take(10) ?: emptyList()
    }

    var currentHeroIndex by remember { mutableStateOf(0) }
    LaunchedEffect(heroPool) {
        if (heroPool.size > 1) {
            while (true) {
                delay(10000) 
                currentHeroIndex = (currentHeroIndex + 1) % heroPool.size
            }
        }
    }

    val heroItem = heroPool.getOrNull(currentHeroIndex)

    LaunchedEffect(currentScreen, lastFocusedPath, combinedSections, isLoading) {
        if (lastFocusedPath == null && !isLoading) {
            try {
                rowFocusIndices.clear()
                lazyListState.scrollToItem(0)
            } catch (_: Exception) {}
            return@LaunchedEffect
        }

        if (lastFocusedPath != null && combinedSections.isNotEmpty() && !isLoading) {
            var foundRowIndex = -1
            var foundItemIndex = -1
            var foundRowKey = ""

            if (currentScreen == Screen.HOME && watchHistory.isNotEmpty()) {
                val idx = watchHistory.indexOfFirst { it.seriesPath == lastFocusedPath }
                if (idx != -1) {
                    foundRowIndex = 1 
                    foundItemIndex = idx
                    foundRowKey = "watch_history"
                }
            }

            if (foundRowIndex == -1) {
                combinedSections.forEachIndexed { sIdx, section ->
                    val iIdx = section.items.indexOfFirst { it.path == lastFocusedPath }
                    if (iIdx != -1) {
                        foundRowIndex = sIdx + (if (currentScreen == Screen.HOME && watchHistory.isNotEmpty()) 2 else 1)
                        foundItemIndex = iIdx
                        foundRowKey = "row_${section.title}"
                    }
                }
            }

            if (foundRowIndex != -1) {
                delay(200)
                try {
                    lazyListState.scrollToItem(foundRowIndex)
                    rowFocusIndices[foundRowKey] = foundItemIndex
                } catch (_: Exception) {}
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = lazyListState,
            contentPadding = PaddingValues(bottom = 60.dp)
        ) {
            item(key = "hero_section") {
                if (heroItem != null) {
                    AnimatedContent(
                        targetState = heroItem,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(1000)) togetherWith fadeOut(animationSpec = tween(1000))
                        },
                        label = "HeroTransition"
                    ) { targetItem: Category ->
                        val heroSeries = targetItem.toSeries()
                        val heroHistory = watchHistory.find { 
                            it.seriesPath == targetItem.path || it.title == targetItem.name 
                        }

                        HeroSection(
                            series = heroSeries,
                            watchHistory = heroHistory,
                            onPlayClick = { 
                                coroutineScope.launch {
                                    if (heroHistory != null && heroHistory.lastPosition > 0) {
                                        onHistoryClick(heroHistory)
                                    } else {
                                        val seriesDetail = if (heroSeries.episodes.isEmpty()) {
                                            heroSeries.fullPath?.let { repository.getSeriesDetail(it) }
                                        } else heroSeries

                                        val firstEpisode = seriesDetail?.episodes?.firstOrNull()
                                        if (firstEpisode != null) onPlayClick(firstEpisode)
                                    }
                                }
                            },
                            onDetailClick = { onSeriesClick(heroSeries) },
                            horizontalPadding = standardMargin,
                            isFirstLoad = false
                        )
                    }
                } else if (isLoading) {
                    SkeletonHero()
                }
            }

            if (isLoading && combinedSections.isEmpty()) {
                items(3) { SkeletonRow(standardMargin) }
            } else {
                if (currentScreen == Screen.HOME && watchHistory.isNotEmpty()) {
                    item(key = "watch_history_row") {
                        val rowKey = "watch_history"
                        val historyRowState = rowStates.getOrPut(rowKey) { LazyListState() }
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) { 
                            SectionTitle("시청 중인 콘텐츠", standardMargin)
                            NetflixTvPivotRow(
                                state = historyRowState,
                                items = watchHistory, 
                                marginValue = standardMargin,
                                rowKey = rowKey,
                                rowFocusIndices = rowFocusIndices,
                                keySelector = { history: WatchHistory -> "history_${history.id}" }
                            ) { history: WatchHistory, index: Int, rowState: LazyListState, focusRequester: androidx.compose.ui.focus.FocusRequester, marginPx: Int, focusedIndex: Int ->
                                NetflixPivotItem(
                                    title = history.title, 
                                    posterPath = history.posterPath,
                                    initialVideoUrl = null,
                                    categoryPath = history.seriesPath,
                                    repository = repository,
                                    index = index,
                                    focusedIndex = focusedIndex,
                                    state = rowState,
                                    marginPx = marginPx,
                                    focusRequester = focusRequester,
                                    shouldRequestFocus = lastFocusedPath != null && history.seriesPath == lastFocusedPath,
                                    onFocusRestored = onFocusRestored,
                                    onClick = { onHistoryClick(history) }
                                )
                            }
                        }
                    }
                }

                itemsIndexed(combinedSections, key = { _: Int, s: HomeSection -> "row_${s.title}_${s.items.size}" }) { _: Int, section: HomeSection ->
                    val rowKey = "row_${section.title}"
                    val sectionRowState = rowStates.getOrPut(rowKey) { LazyListState() }
                    Column(modifier = Modifier.fillMaxWidth()) { 
                        SectionTitle(
                            title = section.title, 
                            horizontalPadding = standardMargin,
                            items = section.items,
                            onIndexClick = { targetIndex ->
                                coroutineScope.launch {
                                    sectionRowState.animateScrollToItem(targetIndex)
                                    rowFocusIndices[rowKey] = targetIndex
                                }
                            }
                        )

                        NetflixTvPivotRow(
                            state = sectionRowState,
                            items = section.items, 
                            marginValue = standardMargin,
                            rowKey = rowKey,
                            rowFocusIndices = rowFocusIndices,
                            keySelector = { item: Category -> "item_${item.path ?: item.name ?: item.hashCode()}" }
                        ) { item: Category, index: Int, rowState: LazyListState, focusRequester: androidx.compose.ui.focus.FocusRequester, marginPx: Int, focusedIndex: Int ->
                            NetflixPivotItem(
                                title = item.name ?: "", 
                                posterPath = item.posterPath,
                                initialVideoUrl = item.movies?.find { !it.videoUrl.isNullOrEmpty() }?.videoUrl,
                                categoryPath = item.path,
                                repository = repository,
                                index = index,
                                focusedIndex = focusedIndex,
                                state = rowState,
                                marginPx = marginPx,
                                focusRequester = focusRequester,
                                overview = item.overview,
                                year = item.year,
                                rating = item.rating,
                                shouldRequestFocus = lastFocusedPath != null && item.path != null && item.path == lastFocusedPath,
                                onFocusRestored = onFocusRestored,
                                onClick = { onSeriesClick(item.toSeries()) }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun Category.toSeries() = Series(
    title = this.name ?: "",
    episodes = this.movies ?: emptyList(),
    fullPath = this.path,
    posterPath = this.posterPath,
    genreIds = this.genreIds ?: emptyList(),
    genreNames = this.genreNames ?: emptyList(),
    director = this.director,
    actors = this.actors ?: emptyList(),
    overview = this.overview,
    year = this.year,
    rating = this.rating
)
