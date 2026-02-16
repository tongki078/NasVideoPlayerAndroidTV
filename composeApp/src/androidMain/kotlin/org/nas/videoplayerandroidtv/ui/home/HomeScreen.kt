package org.nas.videoplayerandroidtv.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.nas.videoplayerandroidtv.data.WatchHistory
import org.nas.videoplayerandroidtv.data.repository.VideoRepositoryImpl
import org.nas.videoplayerandroidtv.domain.model.Category
import org.nas.videoplayerandroidtv.domain.model.HomeSection
import org.nas.videoplayerandroidtv.domain.model.Movie
import org.nas.videoplayerandroidtv.domain.model.Series
import org.nas.videoplayerandroidtv.domain.repository.VideoRepository
import org.nas.videoplayerandroidtv.util.TitleUtils.isGenericTitle
import org.nas.videoplayerandroidtv.ui.common.TmdbAsyncImage

@Composable
fun HomeScreen(
    watchHistory: List<WatchHistory>,
    homeSections: List<HomeSection>,
    isLoading: Boolean,
    lazyListState: LazyListState, 
    onSeriesClick: (Series) -> Unit,
    onPlayClick: (Movie) -> Unit,
    onHistoryClick: (WatchHistory) -> Unit = {}
) {
    val repository: VideoRepository = remember { VideoRepositoryImpl() }
    val standardMargin = 20.dp 
    val rowStates = remember { mutableMapOf<String, LazyListState>() }
    val rowFocusIndices = remember { mutableStateMapOf<String, Int>() }

    var extraSections by remember { mutableStateOf<List<HomeSection>>(emptyList()) }

    LaunchedEffect(Unit) {
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
    }

    val combinedSections = remember(homeSections, extraSections) {
        (homeSections + extraSections).map { section ->
            section.copy(
                items = section.items.filter { item ->
                    val name = (item.name ?: "").trim()
                    val path = (item.path ?: "").trim()
                    if (isGenericTitle(name)) return@filter false
                    val lowerPath = path.lowercase()
                    !(lowerPath.contains("/다큐") || lowerPath.contains("/교양"))
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
                    val heroSeries = Series(
                        title = heroItem.name ?: "", 
                        episodes = heroItem.movies ?: emptyList(), 
                        fullPath = heroItem.path, 
                        posterPath = heroItem.posterPath, 
                        genreIds = heroItem.genreIds ?: emptyList(),
                        genreNames = heroItem.genreNames ?: emptyList(),
                        director = heroItem.director,
                        actors = heroItem.actors ?: emptyList(),
                        overview = heroItem.overview,
                        year = heroItem.year,
                        rating = heroItem.rating
                    )
                    
                    val heroHistory = watchHistory.find { 
                        it.seriesPath == heroItem.path || it.title == heroItem.name 
                    }

                    HeroSection(
                        series = heroSeries,
                        watchHistory = heroHistory,
                        onWatchClick = { 
                            if (heroHistory != null) {
                                onHistoryClick(heroHistory)
                            } else {
                                // [UX 개선] 시청 기록이 없으면 첫 에피소드 즉시 재생 시도
                                val firstEpisode = heroSeries.episodes.firstOrNull()
                                if (firstEpisode != null) {
                                    onPlayClick(firstEpisode)
                                } else {
                                    // 에피소드가 없으면 어쩔 수 없이 상세 페이지로 이동
                                    onSeriesClick(heroSeries)
                                }
                            }
                        },
                        horizontalPadding = standardMargin
                    )
                } else {
                    SkeletonHero()
                }
            }

            if (isLoading && combinedSections.isEmpty()) {
                items(3) { SkeletonRow(standardMargin) }
            } else {
                if (watchHistory.isNotEmpty()) {
                    item(key = "watch_history_row") {
                        val rowKey = "watch_history"
                        val historyRowState = rowStates.getOrPut(rowKey) { LazyListState() }

                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) { 
                            SectionTitle("시청 중인 콘텐츠", standardMargin)
                            
                            LazyRow(
                                state = historyRowState,
                                contentPadding = PaddingValues(horizontal = standardMargin),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth().height(220.dp)
                            ) {
                                itemsIndexed(watchHistory, key = { _, h -> h.id }) { _, history ->
                                    NetflixWatchHistoryItem(
                                        history = history,
                                        onClick = { onHistoryClick(history) }
                                    )
                                }
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
                                    onSeriesClick(Series(
                                        title = item.name ?: "", 
                                        episodes = item.movies ?: emptyList(), 
                                        fullPath = item.path, 
                                        posterPath = item.posterPath, 
                                        genreIds = item.genreIds ?: emptyList(),
                                        genreNames = item.genreNames ?: emptyList(),
                                        director = item.director,
                                        actors = item.actors ?: emptyList(),
                                        overview = item.overview, 
                                        year = item.year, 
                                        rating = item.rating
                                    ))
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NetflixWatchHistoryItem(
    history: WatchHistory,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier
            .width(140.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(4.dp))
                .border(
                    width = if (isFocused) 3.dp else 0.dp,
                    color = if (isFocused) Color.White else Color.Transparent,
                    shape = RoundedCornerShape(4.dp)
                )
        ) {
            TmdbAsyncImage(
                title = history.title,
                posterPath = history.posterPath,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (isFocused) Color.Black.copy(alpha = 0.2f) else Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.6f),
                    border = BorderStroke(1.dp, Color.White),
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }

            val progress = if (history.duration > 0) history.lastPosition.toFloat() / history.duration else 0f
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Color.Gray.copy(alpha = 0.5f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(Color.Red)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = history.title,
            color = if (isFocused) Color.White else Color.Gray,
            fontSize = 13.sp,
            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun List<Series>.toCategories(): List<Category> {
    return this.map { series ->
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
