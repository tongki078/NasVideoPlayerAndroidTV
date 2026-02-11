package org.nas.videoplayerandroidtv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.nas.videoplayerandroidtv.data.WatchHistory
import org.nas.videoplayerandroidtv.data.repository.VideoRepositoryImpl
import org.nas.videoplayerandroidtv.domain.model.Category
import org.nas.videoplayerandroidtv.domain.model.HomeSection
import org.nas.videoplayerandroidtv.domain.model.Movie
import org.nas.videoplayerandroidtv.domain.model.Series
import org.nas.videoplayerandroidtv.domain.repository.VideoRepository
import org.nas.videoplayerandroidtv.isGenericTitle

@Composable
fun HomeScreen(
    watchHistory: List<WatchHistory>,
    homeSections: List<HomeSection>,
    isLoading: Boolean,
    lazyListState: LazyListState, 
    onSeriesClick: (Series) -> Unit,
    @Suppress("UNUSED_PARAMETER") onPlayClick: (Movie) -> Unit,
    onHistoryClick: (WatchHistory) -> Unit = {}
) {
    val repository: VideoRepository = remember { VideoRepositoryImpl() }
    val standardMargin = 20.dp 
    val rowStates = remember { mutableMapOf<String, LazyListState>() }
    val rowFocusIndices = remember { mutableStateMapOf<String, Int>() }

    // [추가] 외국TV / 국내드라마 데이터를 담을 상태
    var extraSections by remember { mutableStateOf<List<HomeSection>>(emptyList()) }

    LaunchedEffect(Unit) {
        coroutineScope {
            // 외국TV와 국내 드라마(KtvDrama)를 로딩
            val foreignTvDeferred = async { repository.getLatestForeignTV() }
            val koreanDramaDeferred = async { repository.getKtvDrama(20, 0) }
            
            val foreignTv = try { foreignTvDeferred.await().take(20) } catch(e: Exception) { emptyList() }
            val koreanDrama = try { koreanDramaDeferred.await().take(20) } catch(e: Exception) { emptyList() }
            
            val newSections = mutableListOf<HomeSection>()
            
            if (foreignTv.isNotEmpty()) {
                newSections.add(HomeSection(
                    title = "인기 외국 TV 시리즈",
                    items = foreignTv.toCategories()
                ))
            }
            
            if (koreanDrama.isNotEmpty()) {
                newSections.add(HomeSection(
                    title = "화제의 국내 드라마",
                    items = koreanDrama.toCategories()
                ))
            }
            
            extraSections = newSections
        }
    }

    // 모든 섹션을 합치고 필터링 적용
    val combinedSections = remember(homeSections, extraSections) {
        (homeSections + extraSections).map { section ->
            section.copy(
                items = section.items.filter { item ->
                    val name = (item.name ?: "").trim()
                    val path = (item.path ?: "").trim()
                    
                    // 1. 관리용 폴더 제외 (시즌 xxxx 등)
                    if (isGenericTitle(name)) return@filter false
                    
                    // 2. 다큐/교양 폴더 제외 (단, 제목에 포함된 경우는 허용하도록 경로 위주 체크)
                    val lowerPath = path.lowercase()
                    val isDokuOrEduFolder = lowerPath.contains("/다큐") || lowerPath.contains("/교양")
                    
                    !isDokuOrEduFolder
                }
            )
        }.filter { it.items.isNotEmpty() }
    }

    val heroItem = remember(combinedSections) { 
        combinedSections.find { it.title.contains("인기작") }?.items?.firstOrNull() 
        ?: combinedSections.firstOrNull()?.items?.firstOrNull()
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = lazyListState,
            contentPadding = PaddingValues(bottom = 60.dp)
        ) {
            item(key = "hero_section") {
                if (heroItem != null) {
                    HeroSection(
                        series = Series(
                            title = heroItem.name ?: "", 
                            episodes = emptyList(), 
                            fullPath = heroItem.path, 
                            posterPath = heroItem.posterPath, 
                            genreIds = heroItem.genreIds ?: emptyList(),
                            overview = heroItem.overview,
                            year = heroItem.year,
                            rating = heroItem.rating
                        ),
                        onWatchClick = {
                            onSeriesClick(Series(title = heroItem.name ?: "", episodes = emptyList(), fullPath = heroItem.path, posterPath = heroItem.posterPath, genreIds = heroItem.genreIds ?: emptyList(), overview = heroItem.overview, year = heroItem.year, rating = heroItem.rating))
                        },
                        onInfoClick = {
                            onSeriesClick(Series(title = heroItem.name ?: "", episodes = emptyList(), fullPath = heroItem.path, posterPath = heroItem.posterPath, genreIds = heroItem.genreIds ?: emptyList(), overview = heroItem.overview, year = heroItem.year, rating = heroItem.rating))
                        },
                        horizontalPadding = standardMargin
                    )
                } else {
                    SkeletonHero()
                }
            }

            if (isLoading && combinedSections.isEmpty()) {
                items(3) { 
                    SkeletonRow(standardMargin)
                }
            } else {
                if (watchHistory.isNotEmpty()) {
                    item(key = "watch_history_row") {
                        val rowKey = "watch_history"
                        val historyRowState = rowStates.getOrPut(rowKey) { LazyListState() }

                        Column(modifier = Modifier.fillMaxWidth()) { 
                            SectionTitle("시청 중인 콘텐츠", standardMargin)
                            
                            NetflixTvPivotRow(
                                state = historyRowState,
                                items = watchHistory.take(20), 
                                marginValue = standardMargin,
                                rowKey = rowKey,
                                rowFocusIndices = rowFocusIndices,
                                keySelector = { "history_${it.id}" }
                            ) { history, index, rowState, focusRequester, marginPx, focusedIndex ->
                                NetflixPivotItem(
                                    title = history.title, 
                                    posterPath = history.posterPath,
                                    initialVideoUrl = history.videoUrl,
                                    categoryPath = null,
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

                itemsIndexed(combinedSections, key = { _, s -> "row_${s.title}_${s.items.size}" }) { _, section ->
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
                            keySelector = { item -> "item_${item.path ?: item.name ?: item.hashCode()}" }
                        ) { item, index, rowState, focusRequester, marginPx, focusedIndex ->
                            val firstMovieUrl = item.movies?.find { !it.videoUrl.isNullOrEmpty() }?.videoUrl
                            
                            NetflixPivotItem(
                                title = item.name ?: "", 
                                posterPath = item.posterPath,
                                initialVideoUrl = firstMovieUrl,
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
                                onClick = { 
                                    onSeriesClick(Series(title = item.name ?: "", episodes = item.movies ?: emptyList(), fullPath = item.path, posterPath = item.posterPath, genreIds = item.genreIds ?: emptyList(), overview = item.overview, year = item.year, rating = item.rating))
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun List<Series>.toCategories(): List<Category> {
    return this.map { series ->
        Category(
            name = series.title,
            path = series.fullPath,
            posterPath = series.posterPath,
            genreIds = series.genreIds,
            overview = series.overview,
            year = series.year,
            rating = series.rating,
            movies = series.episodes
        )
    }
}
