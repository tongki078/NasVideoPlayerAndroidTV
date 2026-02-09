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
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
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
    val rowFocusIndices = remember { mutableMapOf<String, Int>() }

    val heroItem = remember(homeSections) { 
        homeSections.find { it.title.contains("인기작") }?.items?.firstOrNull() 
        ?: homeSections.firstOrNull()?.items?.firstOrNull()
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0F0F0F))) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = lazyListState,
            contentPadding = PaddingValues(bottom = 100.dp)
        ) {
            item(key = "hero_section") {
                if (heroItem != null) {
                    HeroSection(
                        series = Series(title = heroItem.name ?: "", episodes = emptyList(), fullPath = heroItem.path, posterPath = heroItem.posterPath, genreIds = heroItem.genreIds ?: emptyList()),
                        onClick = onSeriesClick,
                        horizontalPadding = standardMargin
                    )
                } else {
                    SkeletonHero()
                }
            }

            if (watchHistory.isNotEmpty()) {
                item(key = "watch_history_row") {
                    val rowKey = "watch_history"
                    val historyRowState = rowStates.getOrPut(rowKey) { LazyListState() }

                    Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                        SectionTitle("시청 중인 콘텐츠", standardMargin)
                        
                        NetflixTvPivotRow(
                            state = historyRowState,
                            items = watchHistory.take(20), 
                            marginValue = standardMargin,
                            rowKey = rowKey,
                            rowFocusIndices = rowFocusIndices,
                            keySelector = { "history_${it.id}" }
                        ) { history, index, rowState, focusRequester, marginPx ->
                            NetflixPivotItem(
                                title = history.title, 
                                posterPath = history.posterPath,
                                index = index,
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

                Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                    SectionTitle(section.title, standardMargin)
                    
                    NetflixTvPivotRow(
                        state = sectionRowState,
                        items = section.items, 
                        marginValue = standardMargin,
                        rowKey = rowKey,
                        rowFocusIndices = rowFocusIndices,
                        keySelector = { "item_${it.path ?: it.name ?: ""}" }
                    ) { item, index, rowState, focusRequester, marginPx ->
                        NetflixPivotItem(
                            title = item.name ?: "", 
                            posterPath = item.posterPath,
                            index = index,
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun <T> NetflixTvPivotRow(
    state: LazyListState,
    items: List<T>,
    marginValue: androidx.compose.ui.unit.Dp,
    rowKey: String,
    rowFocusIndices: MutableMap<String, Int>,
    keySelector: (T) -> Any,
    itemContent: @Composable (item: T, index: Int, state: LazyListState, focusRequester: FocusRequester, marginPx: Int) -> Unit
) {
    val focusRequesters = remember(items.size) { List(items.size) { FocusRequester() } }
    val density = LocalDensity.current
    val marginPx = with(density) { marginValue.roundToPx() }

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
            .focusProperties {
                enter = { 
                    val lastIdx = rowFocusIndices[rowKey] ?: 0
                    focusRequesters.getOrNull(lastIdx) ?: FocusRequester.Default
                }
            },
        state = state,
        contentPadding = PaddingValues(start = marginValue, end = marginValue, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(marginValue), 
        verticalAlignment = Alignment.Top
    ) {
        itemsIndexed(items, key = { _, item -> keySelector(item) }) { index, item ->
            Box(modifier = Modifier.onFocusChanged { 
                if (it.isFocused) rowFocusIndices[rowKey] = index 
            }) {
                itemContent(item, index, state, focusRequesters[index], marginPx)
            }
        }
    }
}

@Composable
private fun NetflixPivotItem(
    title: String,
    posterPath: String?,
    index: Int,
    state: LazyListState,
    marginPx: Int,
    focusRequester: FocusRequester,
    rating: String = "98% 일치",
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val coroutineScope = rememberCoroutineScope()
    
    LaunchedEffect(isFocused) {
        if (isFocused) {
            yield()
            state.scrollToItem(index, -marginPx)
        }
    }

    val fixedWidth = 180.dp
    val totalHeight = 300.dp 

    Box(
        modifier = Modifier
            .width(fixedWidth)
            .height(totalHeight)
            .zIndex(if (isFocused) 10f else 1f)
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
                    .height(210.dp)
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
                
                if (isFocused) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)),
                                    startY = 300f
                                )
                            )
                    )
                    Text(
                        text = title,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(12.dp),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            
            if (isFocused) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = rating, color = Color(0xFF46D369), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Spacer(Modifier.width(6.dp))
                        Box(modifier = Modifier.border(BorderStroke(1.dp, Color.Gray.copy(alpha = 0.5f))).padding(horizontal = 4.dp)) {
                            Text(text = "15+", color = Color.White, fontSize = 10.sp)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "상세 정보 보기",
                        color = Color.LightGray,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroSection(series: Series, onClick: (Series) -> Unit, horizontalPadding: androidx.compose.ui.unit.Dp) {
    var isPlayFocused by remember { mutableStateOf(false) }
    var isInfoFocused by remember { mutableStateOf(false) }
    val title = series.title
    Box(modifier = Modifier.fillMaxWidth().height(340.dp).background(Color.Black)) {
        TmdbAsyncImage(title = title, posterPath = series.posterPath, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, isLarge = true)
        Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(colors = listOf(Color.Transparent, Color(0xFF0F0F0F).copy(alpha = 0.5f), Color(0xFF0F0F0F)))))
        Column(modifier = Modifier.align(Alignment.BottomStart).padding(start = horizontalPadding, bottom = 32.dp).fillMaxWidth(0.6f)) {
            Text(text = title.cleanTitle(), color = Color.White, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black, shadow = Shadow(color = Color.Black.copy(alpha = 0.8f), blurRadius = 10f)))
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { onClick(series) }, 
                    colors = ButtonDefaults.buttonColors(containerColor = if (isPlayFocused) Color.White else Color.Red), 
                    shape = RoundedCornerShape(8.dp), 
                    modifier = Modifier.height(40.dp).onFocusChanged { isPlayFocused = it.isFocused }
                ) {
                    Icon(Icons.Default.PlayArrow, null, tint = if (isPlayFocused) Color.Black else Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("시청하기", color = if (isPlayFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Spacer(Modifier.width(16.dp))
                Button(
                    onClick = { onClick(series) }, 
                    shape = RoundedCornerShape(8.dp), 
                    colors = ButtonDefaults.buttonColors(containerColor = if (isInfoFocused) Color.Gray.copy(alpha = 0.6f) else Color.Gray.copy(alpha = 0.3f)), 
                    modifier = Modifier.height(40.dp).onFocusChanged { isInfoFocused = it.isFocused }
                ) {
                    Icon(Icons.Default.Info, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("상세 정보", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
fun SectionTitle(title: String, horizontalPadding: androidx.compose.ui.unit.Dp) {
    Text(text = title, modifier = Modifier.padding(start = horizontalPadding, bottom = 12.dp), color = Color(0xFFE0E0E0), fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 0.5.sp)
}

@Composable
fun SkeletonHero() {
    Box(modifier = Modifier.fillMaxWidth().height(320.dp).background(Color(0xFF1A1A1A)))
}
