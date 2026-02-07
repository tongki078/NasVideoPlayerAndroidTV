package org.nas.videoplayerandroidtv.ui.home

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

private val genreMap = mapOf(
    28 to "액션", 12 to "모험", 16 to "애니", 35 to "코미디", 80 to "범죄",
    99 to "다큐", 18 to "드라마", 10751 to "가족", 14 to "판타지", 36 to "역사",
    27 to "공포", 10402 to "음악", 9648 to "미스터리", 10749 to "로맨스", 878 to "SF",
    10770 to "TV영화", 53 to "스릴러", 10752 to "전쟁", 37 to "서부",
    10759 to "액션&어드벤처", 10762 to "키즈", 10763 to "뉴스", 10764 to "리얼리티",
    10765 to "SF&판타지", 10766 to "소프", 10767 to "토크", 10768 to "전쟁&정치"
)

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
    val horizontalPadding = 48.dp

    val heroItem = remember(homeSections) { 
        homeSections.find { it.title.contains("인기작") }?.items?.firstOrNull() 
        ?: homeSections.firstOrNull()?.items?.firstOrNull() 
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0F0F0F))) {
        if (isLoading && homeSections.isEmpty()) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp).statusBarsPadding().align(Alignment.TopCenter), color = Color.Red, trackColor = Color.Transparent)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = lazyListState,
            contentPadding = PaddingValues(bottom = 40.dp)
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
                item { SectionTitle("시청 중인 콘텐츠", horizontalPadding) }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = horizontalPadding),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.height(210.dp)
                    ) {
                        items(watchHistory.take(20), key = { "h_${it.id}" }) { history ->
                            HistoryMovieCard(
                                title = history.title, 
                                posterPath = history.posterPath, 
                                onClick = { onHistoryClick(history) }
                            )
                        }
                    }
                }
            }

            if (homeSections.isEmpty() && isLoading) {
                repeat(3) { item { SkeletonMovieRow(horizontalPadding) } }
            } else {
                homeSections.forEach { section ->
                    item(key = section.title) { SectionTitle(section.title, horizontalPadding) }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = horizontalPadding),
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                            modifier = Modifier.height(340.dp), 
                            verticalAlignment = Alignment.Top
                        ) {
                            items(section.items, key = { "${section.title}_${it.path}_${it.name}" }) { item ->
                                HomeMovieListItem(
                                    category = item,
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
private fun HomeMovieListItem(category: Category, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    
    // 너비 애니메이션 재도입 (130dp -> 240dp)
    val width by animateDpAsState(
        targetValue = if (isFocused) 240.dp else 130.dp,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow),
        label = "itemWidth"
    )
    
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.1f else 1f,
        label = "itemScale"
    )

    val title = category.name ?: "Unknown"
    val metadata = tmdbCache[title]

    Column(
        modifier = Modifier
            .width(width) // 물리적 너비 조절
            .zIndex(if (isFocused) 10f else 1f)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
    ) {
        // 이미지 영역: 높이 190dp 고정
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .shadow(elevation = if (isFocused) 15.dp else 0.dp, shape = RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
                .then(
                    if (isFocused) Modifier.border(2.5.dp, Color.White, RoundedCornerShape(8.dp))
                    else Modifier
                )
        ) {
            TmdbAsyncImage(
                title = title, 
                posterPath = category.posterPath,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            
            if (isFocused) {
                Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))))
                Text(
                    text = title.cleanTitle(),
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 정보 영역 (포커스 시 하단 노출)
        if (isFocused) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp, start = 4.dp, end = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val genre = category.genreIds?.take(1)?.mapNotNull { genreMap[it] }?.firstOrNull() ?: "추천"
                    Text(text = genre, color = Color(0xFF46D369), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(text = "98% 일치", color = Color(0xFF46D369), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = metadata?.overview ?: "지금 바로 감상해보세요.",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                    maxLines = 3,
                    lineHeight = 16.sp,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun HistoryMovieCard(title: String, posterPath: String? = null, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) 1.1f else 1f, label = "scale")
    
    Box(
        modifier = Modifier
            .width(130.dp)
            .aspectRatio(0.68f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { isFocused = it.isFocused }
            .shadow(elevation = if (isFocused) 10.dp else 0.dp, shape = RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (isFocused) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp))
                else Modifier
            )
            .focusable()
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
private fun SectionTitle(title: String, horizontalPadding: androidx.compose.ui.unit.Dp) {
    Text(text = title, modifier = Modifier.padding(start = horizontalPadding, top = 24.dp, bottom = 12.dp), color = Color(0xFFE0E0E0), fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 0.5.sp)
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
