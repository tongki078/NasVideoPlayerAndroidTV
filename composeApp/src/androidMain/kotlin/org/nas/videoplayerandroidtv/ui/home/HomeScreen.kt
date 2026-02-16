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
    currentScreen: Screen = Screen.HOME,
    onSeriesClick: (Series) -> Unit,
    onPlayClick: (Movie) -> Unit,
    onHistoryClick: (WatchHistory) -> Unit = {}
) {
    val repository: VideoRepository = remember { VideoRepositoryImpl() }
    val standardMargin = 20.dp 
    val rowStates = remember { mutableMapOf<String, LazyListState>() }
    val rowFocusIndices = remember { mutableStateMapOf<String, Int>() }
    val coroutineScope = rememberCoroutineScope()

    var extraSections by remember { mutableStateOf<List<HomeSection>>(emptyList()) }

    LaunchedEffect(currentScreen) {
        if (currentScreen == Screen.HOME) {
            coroutineScope {
                val foreignTvDeferred = async { repository.getLatestForeignTV() }
                val koreanDramaDeferred = async { repository.getKtvDrama(20, 0) }
                
                val foreignTv = try { foreignTvDeferred.await().take(20) } catch(_: Exception) { emptyList() }
                val koreanDrama = try { koreanDramaDeferred.await().take(20) } catch(_: Exception) { emptyList() }
                
                val newSections = mutableListOf<HomeSection>()
                if (foreignTv.isNotEmpty()) newSections.add(HomeSection("인기 외국 TV 시리즈", foreignTv.toCategories()))
                if (koreanDrama.isNotEmpty()) newSections.add(HomeSection("화제의 국내 드라마", koreanDrama.toCategories()))
                extraSections = newSections
            }
        } else {
            extraSections = emptyList()
        }
    }

    val combinedSections = remember(homeSections, extraSections) {
        (homeSections + extraSections).map { section: HomeSection ->
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
                            horizontalPadding = standardMargin
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
                        SectionTitle(section.title, standardMargin)
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

private fun List<Series>.toCategories(): List<Category> {
    return this.map { series: Series ->
        Category(
            name = series.title,
            path = series.fullPath,
            posterPath = series.posterPath,
            genreIds = series.genreIds,
            genreNames = series.genreNames,
            director = series.director,
            actors = series.actors,
            overview = series.overview,
            year = series.year,
            rating = series.rating,
            movies = series.episodes
        )
    }
}
