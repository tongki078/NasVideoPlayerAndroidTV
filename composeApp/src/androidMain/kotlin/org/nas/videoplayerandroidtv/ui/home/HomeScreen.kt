package org.nas.videoplayerandroidtv.ui.home

import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.tv.foundation.ExperimentalTvFoundationApi
import androidx.tv.foundation.PivotOffsets
import androidx.tv.foundation.lazy.list.*
import org.nas.videoplayerandroidtv.ui.common.TmdbAsyncImage
import org.nas.videoplayerandroidtv.domain.model.Series
import org.nas.videoplayerandroidtv.domain.model.Movie
import org.nas.videoplayerandroidtv.domain.model.HomeSection
import org.nas.videoplayerandroidtv.domain.model.Category
import org.nas.videoplayerandroidtv.data.WatchHistory
import org.nas.videoplayerandroidtv.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvFoundationApi::class)
@Composable
fun HomeScreen(
    watchHistory: List<WatchHistory>,
    homeSections: List<HomeSection>,
    isLoading: Boolean,
    tvLazyListState: TvLazyListState, // App.kt에서 전달받은 TV 전용 상태
    onSeriesClick: (Series) -> Unit,
    onPlayClick: (Movie) -> Unit,
    onHistoryClick: (WatchHistory) -> Unit = {}
) {
    val horizontalPadding = 52.dp

    val heroItem = remember(homeSections) { 
        homeSections.find { it.title.contains("인기작") }?.items?.firstOrNull() 
        ?: homeSections.firstOrNull()?.items?.firstOrNull()
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0F0F0F))) {
        if (isLoading && homeSections.isEmpty()) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(3.dp).statusBarsPadding().align(Alignment.TopCenter), 
                color = Color.Red, 
                trackColor = Color.Transparent
            )
        }

        // [구조적 해결 1] 전체 리스트를 TvLazyColumn으로 변경하여 세로 스크롤 제어
        TvLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = tvLazyListState,
            contentPadding = PaddingValues(bottom = 100.dp),
            // [구조적 해결 2] 세로 피벗 고정: 포커스된 행이 항상 화면 상단 15% 지점에 고정됨 (중앙 하락 방지)
            pivotOffsets = PivotOffsets(parentFraction = 0.15f)
        ) {
            // 0: 히어로 섹션
            item {
                if (heroItem != null) {
                    HeroSection(
                        series = Series(
                            title = heroItem.name ?: "",
                            episodes = emptyList(),
                            fullPath = heroItem.path,
                            posterPath = heroItem.posterPath,
                            genreIds = heroItem.genreIds ?: emptyList()
                        ),
                        onClick = onSeriesClick,
                        horizontalPadding = horizontalPadding
                    )
                } else {
                    SkeletonHero()
                }
            }

            // 1: 시청 기록
            if (watchHistory.isNotEmpty()) {
                item(key = "row_history") {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        SectionTitle("시청 중인 콘텐츠", horizontalPadding)
                        NetflixTvPivotRow(
                            items = watchHistory.take(20),
                            horizontalPadding = horizontalPadding
                        ) { history ->
                            NetflixPivotItem(
                                title = history.title, 
                                posterPath = history.posterPath, 
                                onClick = { onHistoryClick(history) }
                            )
                        }
                        Spacer(Modifier.height(30.dp))
                    }
                }
            }

            // 2+: 홈 섹션들
            itemsIndexed(homeSections, key = { _, s -> "row_${s.title}" }) { _, section ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    SectionTitle(section.title, horizontalPadding)
                    NetflixTvPivotRow(
                        items = section.items,
                        horizontalPadding = horizontalPadding
                    ) { item ->
                        NetflixPivotItem(
                            title = item.name ?: "", 
                            posterPath = item.posterPath, 
                            onClick = { 
                                onSeriesClick(Series(
                                    title = item.name ?: "", 
                                    episodes = emptyList(), 
                                    fullPath = item.path,
                                    posterPath = item.posterPath,
                                    genreIds = item.genreIds ?: emptyList()
                                )) 
                            }
                        )
                    }
                    Spacer(Modifier.height(30.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalTvFoundationApi::class)
@Composable
private fun <T> NetflixTvPivotRow(
    items: List<T>,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    itemContent: @Composable (item: T) -> Unit
) {
    val rowState = rememberTvLazyListState()

    // [구조적 해결 3] 가로 피벗 고정: 포커스된 아이템을 항상 리스트 좌측(0% 지점)에 고정
    TvLazyRow(
        modifier = Modifier.fillMaxWidth().height(240.dp),
        state = rowState,
        contentPadding = PaddingValues(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
        pivotOffsets = PivotOffsets(parentFraction = 0f)
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
    
    Column(
        modifier = Modifier
            .width(130.dp)
            .focusable(interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
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
                modifier = Modifier.fillMaxSize()
            )
        }

        Box(modifier = Modifier.fillMaxWidth().height(50.dp)) {
            if (isFocused) {
                Text(
                    text = title,
                    modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    lineHeight = 18.sp,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun HeroSection(
    series: Series,
    onClick: (Series) -> Unit, 
    horizontalPadding: androidx.compose.ui.unit.Dp
) {
    var isPlayFocused by remember { mutableStateOf(false) }
    var isInfoFocused by remember { mutableStateOf(false) }
    val title = series.title
    
    Box(modifier = Modifier.fillMaxWidth().height(320.dp).background(Color.Black)) {
        TmdbAsyncImage(
            title = title, 
            posterPath = series.posterPath,
            modifier = Modifier.fillMaxSize(), 
            contentScale = ContentScale.Crop, 
            isLarge = true
        )
        Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(0.0f to Color.Transparent, 0.5f to Color(0xFF0F0F0F).copy(alpha = 0.5f), 1.0f to Color(0xFF0F0F0F))))
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
                    Spacer(Modifier.width(8.dp)); Text("시청하기", color = if (isPlayFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Spacer(Modifier.width(16.dp))
                Button(
                    onClick = { onClick(series) }, 
                    shape = RoundedCornerShape(8.dp), 
                    colors = ButtonDefaults.buttonColors(containerColor = if (isInfoFocused) Color.Gray.copy(alpha = 0.6f) else Color.Gray.copy(alpha = 0.3f)), 
                    modifier = Modifier.height(40.dp).onFocusChanged { isInfoFocused = it.isFocused }
                ) {
                    Icon(Icons.Default.Info, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp)); Text("상세 정보", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, horizontalPadding: androidx.compose.ui.unit.Dp) {
    Text(
        text = title, 
        modifier = Modifier.padding(start = horizontalPadding, top = 0.dp, bottom = 8.dp), 
        color = Color(0xFFE0E0E0), 
        fontWeight = FontWeight.Bold, 
        fontSize = 20.sp,
        letterSpacing = 0.5.sp
    )
}

@Composable
private fun SkeletonHero() {
    Box(modifier = Modifier.fillMaxWidth().height(320.dp).background(Color(0xFF1A1A1A)))
}

@Composable
private fun SkeletonMovieRow(horizontalPadding: androidx.compose.ui.unit.Dp) {
    Row(modifier = Modifier.padding(horizontal = horizontalPadding, vertical = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(6) { Box(modifier = Modifier.width(130.dp).aspectRatio(0.68f).background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp))) }
    }
}
