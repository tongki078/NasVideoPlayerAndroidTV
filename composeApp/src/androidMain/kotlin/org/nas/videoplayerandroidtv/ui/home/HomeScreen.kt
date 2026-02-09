package org.nas.videoplayerandroidtv.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import org.nas.videoplayerandroidtv.ui.common.TmdbAsyncImage
import org.nas.videoplayerandroidtv.domain.model.Series
import org.nas.videoplayerandroidtv.domain.model.Movie
import org.nas.videoplayerandroidtv.domain.model.HomeSection
import org.nas.videoplayerandroidtv.data.WatchHistory
import org.nas.videoplayerandroidtv.cleanTitle

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
    val standardMargin = 20.dp 
    val rowStates = remember { mutableMapOf<String, LazyListState>() }
    val rowFocusIndices = remember { mutableStateMapOf<String, Int>() }

    val heroItem = remember(homeSections) { 
        homeSections.find { it.title.contains("인기작") }?.items?.firstOrNull() 
        ?: homeSections.firstOrNull()?.items?.firstOrNull()
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0F0F0F))) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = lazyListState,
            contentPadding = PaddingValues(bottom = 60.dp)
        ) {
            item(key = "hero_section") {
                if (heroItem != null) {
                    HeroSection(
                        series = Series(title = heroItem.name ?: "", episodes = emptyList(), fullPath = heroItem.path, posterPath = heroItem.posterPath, genreIds = heroItem.genreIds ?: emptyList()),
                        onWatchClick = {
                            onSeriesClick(Series(title = heroItem.name ?: "", episodes = emptyList(), fullPath = heroItem.path, posterPath = heroItem.posterPath, genreIds = heroItem.genreIds ?: emptyList()))
                        },
                        onInfoClick = {
                            onSeriesClick(Series(title = heroItem.name ?: "", episodes = emptyList(), fullPath = heroItem.path, posterPath = heroItem.posterPath, genreIds = heroItem.genreIds ?: emptyList()))
                        },
                        horizontalPadding = standardMargin
                    )
                } else {
                    SkeletonHero()
                }
            }

            // isLoading이 true이거나 서버 데이터(homeSections)가 아직 비어있을 때는 스켈레톤을 보여줌
            // watchHistory가 로컬이라 먼저 들어오더라도 isLoading 상태에 묶여서 나중에 노출됨
            if (isLoading && homeSections.isEmpty()) {
                items(3) {
                    SkeletonRow(standardMargin)
                }
            } else {
                if (watchHistory.isNotEmpty()) {
                    item(key = "watch_history_row") {
                        val rowKey = "watch_history"
                        val historyRowState = rowStates.getOrPut(rowKey) { LazyListState() }

                        Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { 
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

                itemsIndexed(homeSections, key = { _, s -> "row_${s.title}" }) { _, section ->
                    val rowKey = "row_${section.title}"
                    val sectionRowState = rowStates.getOrPut(rowKey) { LazyListState() }

                    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { 
                        SectionTitle(section.title, standardMargin)
                        
                        NetflixTvPivotRow(
                            state = sectionRowState,
                            items = section.items, 
                            marginValue = standardMargin,
                            rowKey = rowKey,
                            rowFocusIndices = rowFocusIndices,
                            keySelector = { item -> "item_${item.path ?: item.name ?: item.hashCode()}" }
                        ) { item, index, rowState, focusRequester, marginPx, focusedIndex ->
                            NetflixPivotItem(
                                title = item.name ?: "", 
                                posterPath = item.posterPath,
                                index = index,
                                focusedIndex = focusedIndex,
                                state = rowState,
                                marginPx = marginPx,
                                focusRequester = focusRequester,
                                onClick = { 
                                    onSeriesClick(Series(title = item.name ?: "", episodes = emptyList(), fullPath = item.path, posterPath = item.posterPath, genreIds = item.genreIds ?: emptyList())) 
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun <T> NetflixTvPivotRow(
    state: LazyListState,
    items: List<T>,
    marginValue: androidx.compose.ui.unit.Dp,
    rowKey: String,
    rowFocusIndices: SnapshotStateMap<String, Int>,
    keySelector: (T) -> Any,
    itemContent: @Composable (item: T, index: Int, state: LazyListState, focusRequester: FocusRequester, marginPx: Int, focusedIndex: Int) -> Unit
) {
    val focusRequesters = remember(items.size) { List(items.size) { FocusRequester() } }
    val density = LocalDensity.current
    val marginPx = with(density) { marginValue.roundToPx() }
    val focusedIndex = rowFocusIndices[rowKey] ?: 0

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(310.dp) 
            .focusProperties {
                enter = { 
                    val lastIdx = rowFocusIndices[rowKey] ?: 0
                    focusRequesters.getOrNull(lastIdx) ?: FocusRequester.Default
                }
            },
        state = state,
        contentPadding = PaddingValues(start = marginValue, end = marginValue, bottom = 4.dp), 
        horizontalArrangement = Arrangement.spacedBy(12.dp), 
        verticalAlignment = Alignment.Top
    ) {
        itemsIndexed(items, key = { _, item -> keySelector(item) }) { index, item ->
            Box(modifier = Modifier.onFocusChanged { 
                if (it.isFocused) rowFocusIndices[rowKey] = index 
            }) {
                itemContent(item, index, state, focusRequesters[index], marginPx, focusedIndex)
            }
        }
    }
}

@Composable
private fun NetflixPivotItem(
    title: String,
    posterPath: String?,
    index: Int,
    focusedIndex: Int,
    state: LazyListState,
    marginPx: Int,
    focusRequester: FocusRequester,
    rating: String = "98% 일치",
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    
    LaunchedEffect(isFocused) {
        if (isFocused) {
            state.animateScrollToItem(index, -marginPx)
        }
    }

    val fixedWidth = 150.dp
    val posterHeight = 225.dp
    val totalHeight = 290.dp 

    val alpha = when {
        isFocused -> 1f
        index < focusedIndex -> 0.05f 
        else -> 1f 
    }

    Box(
        modifier = Modifier
            .width(fixedWidth)
            .height(totalHeight)
            .zIndex(if (isFocused) 10f else 1f)
            .alpha(alpha)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Transparent) 
                .focusable(interactionSource = interactionSource)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(posterHeight)
                    .clip(RoundedCornerShape(8.dp))
                    .border(
                        width = if (isFocused) 3.dp else 0.dp,
                        color = if (isFocused) Color.White else Color.Transparent,
                        shape = RoundedCornerShape(8.dp)
                    )
            ) {
                TmdbAsyncImage(
                    title = title, 
                    posterPath = posterPath,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            
            if (isFocused) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = title.cleanTitle(),
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = rating, color = Color(0xFF46D369), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(text = "2024", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroSection(
    series: Series, 
    onWatchClick: () -> Unit, 
    onInfoClick: () -> Unit,
    horizontalPadding: androidx.compose.ui.unit.Dp
) {
    var isPlayFocused by remember { mutableStateOf(false) }
    var isInfoFocused by remember { mutableStateOf(false) }
    val title = series.title
    Box(modifier = Modifier.fillMaxWidth().height(320.dp).background(Color.Black)) {
        TmdbAsyncImage(title = title, posterPath = series.posterPath, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, isLarge = true)
        Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(colors = listOf(Color.Transparent, Color(0xFF0F0F0F).copy(alpha = 0.5f), Color(0xFF0F0F0F)))))
        Column(modifier = Modifier.align(Alignment.BottomStart).padding(start = horizontalPadding, bottom = 24.dp).fillMaxWidth(0.6f)) {
            Text(text = title.cleanTitle(), color = Color.White, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black, shadow = Shadow(color = Color.Black.copy(alpha = 0.8f), blurRadius = 10f)))
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onWatchClick, 
                    colors = ButtonDefaults.buttonColors(containerColor = if (isPlayFocused) Color.White else Color.Red), 
                    shape = RoundedCornerShape(8.dp), 
                    modifier = Modifier.height(36.dp).onFocusChanged { isPlayFocused = it.isFocused }
                ) {
                    Icon(Icons.Default.PlayArrow, null, tint = if (isPlayFocused) Color.Black else Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("시청하기", color = if (isPlayFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = onInfoClick, 
                    shape = RoundedCornerShape(8.dp), 
                    colors = ButtonDefaults.buttonColors(containerColor = if (isInfoFocused) Color.Gray.copy(alpha = 0.6f) else Color.Gray.copy(alpha = 0.3f)), 
                    modifier = Modifier.height(36.dp).onFocusChanged { isInfoFocused = it.isFocused }
                ) {
                    Icon(Icons.Default.Info, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("상세 정보", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
fun SectionTitle(title: String, horizontalPadding: androidx.compose.ui.unit.Dp) {
    Text(text = title, modifier = Modifier.padding(start = horizontalPadding, bottom = 20.dp), color = Color(0xFFE0E0E0), fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = 0.5.sp)
}

@Composable
fun SkeletonHero() {
    Box(modifier = Modifier.fillMaxWidth().height(320.dp).background(Color(0xFF1A1A1A)))
}

@Composable
fun SkeletonRow(horizontalPadding: androidx.compose.ui.unit.Dp) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Box(modifier = Modifier.padding(start = horizontalPadding, bottom = 20.dp).width(120.dp).height(20.dp).background(Color(0xFF1A1A1A)))
        LazyRow(
            contentPadding = PaddingValues(start = horizontalPadding, end = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            userScrollEnabled = false
        ) {
            items(5) {
                Box(modifier = Modifier.width(150.dp).height(225.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1A1A1A)))
            }
        }
    }
}
