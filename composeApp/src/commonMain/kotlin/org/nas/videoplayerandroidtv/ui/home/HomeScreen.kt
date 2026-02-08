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
import androidx.compose.foundation.lazy.*
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import org.nas.videoplayerandroidtv.ui.common.TmdbAsyncImage
import org.nas.videoplayerandroidtv.domain.model.Series
import org.nas.videoplayerandroidtv.domain.model.Movie
import org.nas.videoplayerandroidtv.domain.model.HomeSection
import org.nas.videoplayerandroidtv.domain.model.Category
import org.nas.videoplayerandroidtv.data.WatchHistory
import org.nas.videoplayerandroidtv.*
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    watchHistory: List<WatchHistory>,
    homeSections: List<HomeSection>,
    isLoading: Boolean,
    lazyListState: LazyListState = rememberLazyListState(),
    onSeriesClick: (Series) -> Unit,
    onPlayClick: (Movie) -> Unit,
    onHistoryClick: (WatchHistory) -> Unit = {}
) {
    val horizontalPadding = 52.dp
    var activeRowId by rememberSaveable { mutableStateOf("") }

    val heroItem = remember(homeSections) { 
        homeSections.find { it.title.contains("인기작") }?.items?.firstOrNull() 
        ?: homeSections.firstOrNull()?.items?.firstOrNull() 
    }

    // 자연스러운 세로 Pivot 스크롤 로직
    LaunchedEffect(activeRowId) {
        if (activeRowId.isNotEmpty()) {
            val targetIndex = when {
                activeRowId == "history" -> 1
                activeRowId.startsWith("section_") -> {
                    val sectionTitle = activeRowId.removePrefix("section_")
                    val idx = homeSections.indexOfFirst { it.title == sectionTitle }
                    if (idx != -1) (if (watchHistory.isNotEmpty()) 2 else 1) + idx else -1
                }
                else -> -1
            }
            if (targetIndex != -1) {
                // 상단에서 약 180dp 위치에 행을 고정하여 자연스러운 넷플릭스 스타일 구현
                lazyListState.animateScrollToItem(targetIndex, -180)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0F0F0F))) {
        if (isLoading && homeSections.isEmpty()) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp).statusBarsPadding().align(Alignment.TopCenter), color = Color.Red, trackColor = Color.Transparent)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = lazyListState,
            contentPadding = PaddingValues(bottom = 150.dp)
        ) {
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

            if (watchHistory.isNotEmpty()) {
                item(key = "row_history") {
                    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        SectionTitle("시청 중인 콘텐츠", horizontalPadding)
                        NetflixPivotRow(
                            rowId = "history",
                            items = watchHistory.take(20),
                            isActive = activeRowId == "history",
                            horizontalPadding = horizontalPadding,
                            onActive = { activeRowId = "history" }
                        ) { history, isFocused, onFocus ->
                            HistoryMovieCard(
                                title = history.title, 
                                posterPath = history.posterPath, 
                                isFocused = isFocused,
                                onFocus = onFocus,
                                onClick = { onHistoryClick(history) }
                            )
                        }
                    }
                }
            }

            homeSections.forEachIndexed { _, section ->
                val sectionId = "section_${section.title}"
                item(key = "row_$sectionId") {
                    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        SectionTitle(section.title, horizontalPadding)
                        NetflixPivotRow(
                            rowId = sectionId,
                            items = section.items,
                            isActive = activeRowId == sectionId,
                            horizontalPadding = horizontalPadding,
                            onActive = { activeRowId = sectionId }
                        ) { item, isFocused, onFocus ->
                            HomeMovieListItem(
                                category = item,
                                isFocused = isFocused,
                                onFocus = onFocus,
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
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> NetflixPivotRow(
    rowId: String,
    items: List<T>,
    isActive: Boolean,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    onActive: () -> Unit,
    itemContent: @Composable (item: T, isFocused: Boolean, onFocus: () -> Unit) -> Unit
) {
    val listState = rememberLazyListState()
    var focusedIndex by rememberSaveable(rowId) { mutableIntStateOf(0) }

    LaunchedEffect(focusedIndex, isActive) {
        if (isActive) {
            listState.animateScrollToItem(focusedIndex, 0)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(310.dp) // 제목 가림 방지를 위해 충분한 높이 확보
            .onFocusChanged { if (it.hasFocus) onActive() }
    ) {
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(start = horizontalPadding, end = horizontalPadding, top = 36.dp), // 제목과 영상 간격 확보
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = false,
            verticalAlignment = Alignment.Top
        ) {
            itemsIndexed(items) { index, item ->
                itemContent(item, isActive && focusedIndex == index) {
                    if (focusedIndex != index) {
                        focusedIndex = index
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeMovieListItem(
    category: Category, 
    isFocused: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isItemFocused by interactionSource.collectIsFocusedAsState()
    
    LaunchedEffect(isItemFocused) {
        if (isItemFocused) onFocus()
    }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.15f else 1f,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow),
        label = "scale"
    )

    val title = category.name ?: "Unknown"

    Column(
        modifier = Modifier
            .width(130.dp)
            .zIndex(if (isFocused) 10f else 1f)
            .focusable(interactionSource = interactionSource)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0.5f)
                }
                .shadow(elevation = if (isFocused) 20.dp else 0.dp, shape = RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
                .then(
                    if (isFocused) Modifier.border(2.5.dp, Color.White, RoundedCornerShape(8.dp))
                    else Modifier
                )
        ) {
            TmdbAsyncImage(
                title = title, 
                posterPath = category.posterPath,
                modifier = Modifier.fillMaxSize()
            )
        }

        Box(modifier = Modifier.fillMaxWidth().height(85.dp)) {
            if (isFocused) {
                Text(
                    text = title,
                    modifier = Modifier.padding(top = 26.dp, start = 4.dp, end = 4.dp), // 간격 대폭 확보
                    color = Color.White,
                    fontSize = 17.sp, // 글자 크기 확대
                    fontWeight = FontWeight.Black,
                    maxLines = 2,
                    lineHeight = 22.sp,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun HistoryMovieCard(
    title: String, 
    posterPath: String? = null, 
    isFocused: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isItemFocused by interactionSource.collectIsFocusedAsState()
    
    LaunchedEffect(isItemFocused) {
        if (isItemFocused) onFocus()
    }

    val scale by animateFloatAsState(if (isFocused) 1.12f else 1f, label = "scale")
    
    Box(
        modifier = Modifier
            .width(130.dp)
            .aspectRatio(0.68f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (isFocused) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp))
                else Modifier
            )
            .focusable(interactionSource = interactionSource)
            .clickable(onClick = onClick)
    ) {
        TmdbAsyncImage(
            title = title,
            posterPath = posterPath,
            modifier = Modifier.fillMaxSize()
        )
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
                Button(onClick = { onClick(series) }, colors = ButtonDefaults.buttonColors(containerColor = if (isPlayFocused) Color.White else Color.Red), shape = RoundedCornerShape(8.dp), modifier = Modifier.height(40.dp).onFocusChanged { isPlayFocused = it.isFocused }.scale(if (isPlayFocused) 1.05f else 1f)) {
                    Icon(Icons.Default.PlayArrow, null, tint = if (isPlayFocused) Color.Black else Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp)); Text("시청하기", color = if (isPlayFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Spacer(Modifier.width(16.dp))
                Button(onClick = { onClick(series) }, shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = if (isInfoFocused) Color.Gray.copy(alpha = 0.6f) else Color.Gray.copy(alpha = 0.3f)), modifier = Modifier.height(40.dp).onFocusChanged { isInfoFocused = it.isFocused }.scale(if (isInfoFocused) 1.05f else 1f)) {
                    Icon(Icons.Default.Info, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp)); Text("상세 정보", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, horizontalPadding: androidx.compose.ui.unit.Dp) {
    Text(text = title, modifier = Modifier.padding(start = horizontalPadding, top = 12.dp, bottom = 0.dp), color = Color(0xFFE0E0E0), fontWeight = FontWeight.Bold, fontSize = 21.sp, letterSpacing = 0.5.sp)
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
