package org.nas.videoplayerandroidtv.ui.home

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = lazyListState,
            contentPadding = PaddingValues(bottom = 60.dp)
        ) {
            // 시청 기록 화면이 아닐 때만 히어로 섹션 표시
            if (currentScreen != Screen.WATCH_HISTORY) {
                item(key = "hero_section") {
                    Box(modifier = Modifier.onFocusChanged { 
                        if (it.isFocused) { 
                            coroutineScope.launch {
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
                                val heroHistory = watchHistory.find { 
                                    (it.seriesPath != null && it.seriesPath == targetItem.path) || 
                                    it.id == targetItem.path ||
                                    it.title == targetItem.name ||
                                    it.seriesTitle == targetItem.name
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
            } else {
                // 시청 기록 전용 타이틀
                item {
                    Column(modifier = Modifier.padding(horizontal = 48.dp, vertical = 24.dp)) {
                        Text(
                            text = "내가 본 영상",
                            color = Color.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (isLoading && combinedSections.isEmpty() && currentScreen != Screen.WATCH_HISTORY) {
                items(3) { SkeletonRow(standardMargin) }
            } else {
                // 시청 기록 표시 (홈 화면 상단 혹은 시청기록 전용 화면)
                if ((currentScreen == Screen.HOME || currentScreen == Screen.WATCH_HISTORY) && watchHistory.isNotEmpty()) {
                    item(key = "watch_history_row") {
                        val rowKey = "watch_history"
                        val historyRowState = rowStates.getOrPut(rowKey) { LazyListState() }
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) { 
                            if (currentScreen == Screen.HOME) {
                                SectionTitle("시청 중인 콘텐츠", standardMargin)
                            }
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

                // 일반 카테고리 (시청 기록 화면이 아닐 때만 표시)
                if (currentScreen != Screen.WATCH_HISTORY) {
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
