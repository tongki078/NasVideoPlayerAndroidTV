package org.nas.videoplayerandroidtv.ui.home

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.nas.videoplayerandroidtv.Screen
import org.nas.videoplayerandroidtv.data.WatchHistory
import org.nas.videoplayerandroidtv.domain.model.Category
import org.nas.videoplayerandroidtv.domain.model.HomeSection
import org.nas.videoplayerandroidtv.domain.model.Movie
import org.nas.videoplayerandroidtv.domain.model.Series
import org.nas.videoplayerandroidtv.domain.repository.VideoRepository
import org.nas.videoplayerandroidtv.data.repository.VideoRepositoryImpl
import org.nas.videoplayerandroidtv.util.TitleUtils.isGenericTitle

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
    onPlayClick: (Movie, List<Movie>, Series?, Long) -> Unit,
    onHistoryClick: (WatchHistory) -> Unit = {}
) {
    val repository: VideoRepository = remember { VideoRepositoryImpl() }
    val standardMargin = 20.dp 
    val coroutineScope = rememberCoroutineScope()

    val combinedSections = remember(homeSections) {
        homeSections.map { section: HomeSection ->
            section.copy(
                items = section.items.filter { item: Category ->
                    val name = (item.name ?: "").trim()
                    if (isGenericTitle(name)) return@filter false
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

    // [포커스 복구 로직]
    LaunchedEffect(lastFocusedPath, combinedSections, isLoading) {
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
                delay(100) 
                try {
                    val isVisible = lazyListState.layoutInfo.visibleItemsInfo.any { it.index == foundRowIndex }
                    if (!isVisible) {
                        lazyListState.scrollToItem(foundRowIndex)
                    }
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
                Box(modifier = Modifier.onFocusChanged { 
                    if (it.isFocused) { 
                        coroutineScope.launch {
                            // 지연을 주어 시스템 포커스 스크롤과 충돌 방지 및 부드러운 위치 확보
                            delay(50)
                            if (lazyListState.firstVisibleItemIndex != 0 || lazyListState.firstVisibleItemScrollOffset != 0) {
                                lazyListState.animateScrollToItem(0)
                            }
                        }
                    }
                }) {
                    if (heroItem != null) {
                        AnimatedContent(
                            targetState = heroItem,
                            transitionSpec = {
                                fadeIn(animationSpec = tween(1000)) togetherWith fadeOut(animationSpec = tween(1000))
                            },
                            label = "HeroTransition"
                        ) { targetItem: Category ->
                            val heroSeries = targetItem.toSeries()
                            val heroHistory = remember(targetItem, watchHistory) {
                                watchHistory.find { 
                                    (it.seriesPath != null && it.seriesPath == targetItem.path) || 
                                    it.id == targetItem.path ||
                                    it.title == targetItem.name ||
                                    it.seriesTitle == targetItem.name
                                }
                            }

                            HeroSection(
                                series = heroSeries,
                                watchHistory = heroHistory,
                                onPlayClick = { 
                                    coroutineScope.launch {
                                        val seriesDetail = if (heroSeries.episodes.isEmpty()) {
                                            heroSeries.fullPath?.let { repository.getSeriesDetail(it) }
                                        } else heroSeries
                                        val fullSeries = seriesDetail ?: heroSeries
                                        val episodes = fullSeries.episodes

                                        if (heroHistory != null) {
                                            val currentIndex = episodes.indexOfFirst { 
                                                it.videoUrl?.substringAfterLast("/") == heroHistory.videoUrl.substringAfterLast("/") 
                                            }
                                            val isFinished = heroHistory.duration > 0 && heroHistory.lastPosition > heroHistory.duration * 0.95
                                            
                                            if (isFinished && currentIndex != -1 && currentIndex < episodes.size - 1) {
                                                onPlayClick(episodes[currentIndex + 1], episodes, fullSeries, 0L)
                                            } else if (currentIndex != -1) {
                                                onPlayClick(episodes[currentIndex], episodes, fullSeries, heroHistory.lastPosition)
                                            } else {
                                                val fallbackMovie = Movie(heroHistory.id, heroHistory.title, heroHistory.videoUrl, heroHistory.thumbnailUrl)
                                                onPlayClick(fallbackMovie, episodes.ifEmpty { listOf(fallbackMovie) }, fullSeries, heroHistory.lastPosition)
                                            }
                                        } else {
                                            val firstEpisode = episodes.firstOrNull()
                                            if (firstEpisode != null) onPlayClick(firstEpisode, episodes, fullSeries, 0L)
                                        }
                                    }
                                },
                                onNextEpisodeClick = if (heroHistory != null && heroSeries.category != "movies") {
                                    {
                                        coroutineScope.launch {
                                            val seriesDetail = if (heroSeries.episodes.isEmpty()) {
                                                heroSeries.fullPath?.let { repository.getSeriesDetail(it) }
                                            } else heroSeries
                                            val fullSeries = seriesDetail ?: heroSeries
                                            val episodes = fullSeries.episodes
                                            
                                            val currentUrl = heroHistory.videoUrl
                                            val currentIndex = episodes.indexOfFirst { 
                                                it.videoUrl?.substringAfterLast("/") == currentUrl.substringAfterLast("/") 
                                            }
                                            
                                            if (currentIndex != -1 && currentIndex < episodes.size - 1) {
                                                onPlayClick(episodes[currentIndex + 1], episodes, fullSeries, 0L)
                                            }
                                        }
                                    }
                                } else null,
                                onDetailClick = { onSeriesClick(heroSeries) },
                                horizontalPadding = standardMargin,
                                isFirstLoad = false
                            )
                        }
                    } else if (isLoading) {
                        SkeletonHero()
                    }
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
                                    title = history.seriesTitle ?: history.title,
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
                                    onClick = { 
                                        if (history.seriesPath != null) {
                                            val series = Series(
                                                title = history.seriesTitle ?: history.title,
                                                episodes = emptyList(),
                                                fullPath = history.seriesPath,
                                                posterPath = history.posterPath
                                            )
                                            onSeriesClick(series)
                                        } else {
                                            onHistoryClick(history)
                                        }
                                    }
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
                            isFullList = section.is_full_list, 
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
    category = this.category,
    genreIds = this.genreIds ?: emptyList(),
    genreNames = this.genreNames ?: emptyList(),
    year = this.year,
    rating = this.rating,
    seasonCount = this.seasonCount,
    overview = this.overview
)
