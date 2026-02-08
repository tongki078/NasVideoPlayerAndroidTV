package org.nas.videoplayerandroidtv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.foundation.ExperimentalTvFoundationApi
import androidx.tv.foundation.PivotOffsets
import androidx.tv.foundation.lazy.list.*
import org.nas.videoplayerandroidtv.ui.common.TmdbAsyncImage
import org.nas.videoplayerandroidtv.domain.model.Series
import org.nas.videoplayerandroidtv.domain.model.Movie
import org.nas.videoplayerandroidtv.domain.model.HomeSection
import org.nas.videoplayerandroidtv.data.WatchHistory
import org.nas.videoplayerandroidtv.cleanTitle

@OptIn(ExperimentalTvFoundationApi::class)
@Composable
fun HomeScreen(
    watchHistory: List<WatchHistory>,
    homeSections: List<HomeSection>,
    isLoading: Boolean,
    tvLazyListState: TvLazyListState,
    onSeriesClick: (Series) -> Unit,
    onPlayClick: (Movie) -> Unit,
    onHistoryClick: (WatchHistory) -> Unit = {}
) {
    // 사용자 요청: 여백 및 간격을 20dp로 조정
    val standardMargin = 20.dp 

    val heroItem = remember(homeSections) { 
        homeSections.find { it.title.contains("인기작") }?.items?.firstOrNull() 
        ?: homeSections.firstOrNull()?.items?.firstOrNull()
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0F0F0F))) {
        TvLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = tvLazyListState,
            contentPadding = PaddingValues(bottom = 100.dp),
            pivotOffsets = PivotOffsets(parentFraction = 0.15f)
        ) {
            item {
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
                item(key = "row_history") {
                    Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                        SectionTitle("시청 중인 콘텐츠", standardMargin)
                        NetflixTvPivotRow(items = watchHistory.take(20), marginValue = standardMargin) { history ->
                            NetflixPivotItem(title = history.title, posterPath = history.posterPath, onClick = { onHistoryClick(history) })
                        }
                    }
                }
            }

            itemsIndexed(homeSections, key = { _, s -> "row_${s.title}" }) { _, section ->
                Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                    SectionTitle(section.title, standardMargin)
                    NetflixTvPivotRow(items = section.items, marginValue = standardMargin) { item ->
                        NetflixPivotItem(title = item.name ?: "", posterPath = item.posterPath, onClick = { 
                            onSeriesClick(Series(title = item.name ?: "", episodes = emptyList(), fullPath = item.path, posterPath = item.posterPath, genreIds = item.genreIds ?: emptyList())) 
                        })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvFoundationApi::class)
@Composable
private fun <T> NetflixTvPivotRow(
    items: List<T>,
    marginValue: androidx.compose.ui.unit.Dp,
    itemContent: @Composable (item: T) -> Unit
) {
    val rowState = rememberTvLazyListState()

    TvLazyRow(
        modifier = Modifier.fillMaxWidth().height(240.dp),
        state = rowState,
        // [20dp 여백 적용] 오타 수정: marginMargin -> marginValue
        contentPadding = PaddingValues(start = marginValue, end = marginValue), 
        // [20dp 간격 적용] 아이템 사이의 간격을 여백과 동일하게 20dp로 설정
        horizontalArrangement = Arrangement.spacedBy(marginValue), 
        verticalAlignment = Alignment.Top,
        // [여백 사수] 포커스된 아이템의 왼쪽 끝을 여백 시작점에 박제
        pivotOffsets = PivotOffsets(parentFraction = 0f, childFraction = 0f)
    ) {
        items(items) { item ->
            itemContent(item)
        }
    }
}

@Composable
private fun NetflixPivotItem(
    title: String,
    posterPath: String?,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    
    // 포커스 시 가로형(320dp), 미포커스 시 세로형(140dp) - 애니메이션 없이 스냅
    val itemWidth = if (isFocused) 320.dp else 140.dp
    val itemHeight = 210.dp

    Box(
        modifier = Modifier
            .width(itemWidth)
            .height(itemHeight)
            .zIndex(if (isFocused) 10f else 1f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .focusable(interactionSource = interactionSource)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
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
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)),
                                    startY = 100f
                                )
                            )
                    )
                    Text(
                        text = title,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(12.dp),
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
        Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(0.0f to Color.Transparent, 0.5f to Color(0xFF0F0F0F).copy(alpha = 0.5f), 1.0f to Color(0xFF0F0F0F))))
        Column(modifier = Modifier.align(Alignment.BottomStart).padding(start = horizontalPadding, bottom = 32.dp).fillMaxWidth(0.6f)) {
            Text(text = title.cleanTitle(), color = Color.White, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black, shadow = Shadow(color = Color.Black.copy(alpha = 0.8f), blurRadius = 10f)))
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { onClick(series) }, colors = ButtonDefaults.buttonColors(containerColor = if (isPlayFocused) Color.White else Color.Red), shape = RoundedCornerShape(8.dp), modifier = Modifier.height(40.dp).onFocusChanged { isPlayFocused = it.isFocused }) {
                    Icon(Icons.Default.PlayArrow, null, tint = if (isPlayFocused) Color.Black else Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp)); Text("시청하기", color = if (isPlayFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Spacer(Modifier.width(16.dp))
                Button(onClick = { onClick(series) }, shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = if (isInfoFocused) Color.Gray.copy(alpha = 0.6f) else Color.Gray.copy(alpha = 0.3f)), modifier = Modifier.height(40.dp).onFocusChanged { isInfoFocused = it.isFocused } ) {
                    Icon(Icons.Default.Info, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp)); Text("상세 정보", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, horizontalPadding: androidx.compose.ui.unit.Dp) {
    Text(text = title, modifier = Modifier.padding(start = horizontalPadding, bottom = 12.dp), color = Color(0xFFE0E0E0), fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 0.5.sp)
}

@Composable
private fun SkeletonHero() {
    Box(modifier = Modifier.fillMaxWidth().height(320.dp).background(Color(0xFF1A1A1A)))
}
