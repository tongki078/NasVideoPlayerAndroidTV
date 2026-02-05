package org.nas.videoplayerandroidtv.ui.home

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nas.videoplayerandroidtv.ui.common.TmdbAsyncImage
import org.nas.videoplayerandroidtv.domain.model.Series
import org.nas.videoplayerandroidtv.domain.model.Movie
import org.nas.videoplayerandroidtv.data.WatchHistory
import org.nas.videoplayerandroidtv.*

private object HomeThemeConfig {
    const val ACTION_ID = 10759
    const val COMEDY_ID = 35
    const val DRAMA_ID = 18
    const val ITEM_LIMIT = 10
}

@Composable
fun HomeScreen(
    watchHistory: List<WatchHistory>,
    latestMovies: List<Series>,
    animations: List<Series>,
    isLoading: Boolean,
    lazyListState: LazyListState = rememberLazyListState(),
    onSeriesClick: (Series) -> Unit,
    onPlayClick: (Movie) -> Unit,
    onHistoryClick: (WatchHistory) -> Unit = {}
) {
    val horizontalPadding = 48.dp

    // 테마 분류 데이터 상태
    var themedData by remember { mutableStateOf<List<Pair<String, List<Series>>>>(emptyList()) }

    // 히어로 데이터 추출
    val heroMovie = remember(latestMovies) {
        latestMovies.firstOrNull()?.episodes?.firstOrNull()
    }

    // [콘텐츠 복원 및 병렬 프리페칭]
    LaunchedEffect(latestMovies, animations, watchHistory) {
        if (latestMovies.isEmpty() && animations.isEmpty()) return@LaunchedEffect
        
        withContext(Dispatchers.Default) {
            // 1. 이미지 메타데이터 일괄 병렬 프리페칭 (Dispatchers.Default 사용)
            val allTitles = (latestMovies.map { it.title } + animations.map { it.title } + watchHistory.map { it.title }).distinct()
            allTitles.forEach { title -> launch { fetchTmdbMetadata(title) } }

            // 2. 장르 분류 연산 (백그라운드)
            val action = mutableListOf<Series>()
            val comedy = mutableListOf<Series>()
            val drama = mutableListOf<Series>()
            val limit = HomeThemeConfig.ITEM_LIMIT

            val searchPool = (latestMovies.take(50) + animations.take(50)).distinctBy { it.title }
            for (series in searchPool) {
                if (action.size >= limit && comedy.size >= limit && drama.size >= limit) break
                val meta = tmdbCache[series.title] ?: tmdbCache["ani_${series.title}"]
                val genreIds = meta?.genreIds ?: emptyList()
                if (action.size < limit && HomeThemeConfig.ACTION_ID in genreIds) action.add(series)
                if (comedy.size < limit && HomeThemeConfig.COMEDY_ID in genreIds) comedy.add(series)
                if (drama.size < limit && HomeThemeConfig.DRAMA_ID in genreIds) drama.add(series)
            }
            
            val popular = (latestMovies.take(5) + animations.take(5)).shuffled()
            val finalData = listOf(
                "지금 가장 핫한 인기작" to popular,
                "최근 업데이트" to latestMovies.take(limit),
                "시간 순삭! 액션 & 판타지" to action,
                "유쾌한 웃음! 코미디 & 일상" to comedy,
                "가슴 뭉클! 감동 드라마" to drama
            ).filter { it.second.isNotEmpty() }
            
            withContext(Dispatchers.Main) { themedData = finalData }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0F0F0F))) {
        // 상단 가로 로딩바 (데이터 수신 중일 때 표시)
        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .statusBarsPadding()
                    .align(Alignment.TopCenter),
                color = Color.Red,
                trackColor = Color.Transparent
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = lazyListState,
            contentPadding = PaddingValues(bottom = 40.dp)
        ) {
            // 1. 히어로 섹션
            item {
                if (heroMovie != null) {
                    HeroSection(
                        movie = heroMovie,
                        isAnimation = false,
                        onClick = { onSeriesClick(latestMovies.first()) },
                        onPlay = onPlayClick,
                        horizontalPadding = horizontalPadding
                    )
                } else {
                    SkeletonHero()
                }
            }

            // 2. 시청 중인 콘텐츠 (로컬 데이터 - 최우선)
            if (watchHistory.isNotEmpty()) {
                item { SectionTitle("시청 중인 콘텐츠", horizontalPadding) }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = horizontalPadding),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(watchHistory.take(20), key = { "h_${it.id}" }) { history ->
                            MovieCard(
                                title = history.title, 
                                onClick = { onHistoryClick(history) }
                            )
                        }
                    }
                }
            }

            // 3. 복원된 네트워크 추천 섹션들 (준비 안됐을 시 스켈레톤 노출)
            if (themedData.isEmpty() && isLoading) {
                // 로딩 중 스켈레톤 섹션들
                repeat(3) { index ->
                    item { SectionTitle(if (index == 0) "지금 가장 핫한 인기작" else "추천 콘텐츠", horizontalPadding) }
                    item { SkeletonMovieRow(horizontalPadding) }
                }
            } else {
                themedData.forEach { (title, list) ->
                    item(key = title) { SectionTitle(title, horizontalPadding) }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = horizontalPadding),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(list, key = { "${title}_${it.title}" }) { series ->
                                MovieCard(
                                    title = series.title, 
                                    isAnimation = animations.any { it.title == series.title },
                                    onClick = { onSeriesClick(series) }
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
private fun SkeletonHero() {
    val infiniteTransition = rememberInfiniteTransition(label = "hero")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "alpha"
    )
    Box(modifier = Modifier.fillMaxWidth().height(320.dp).background(Color(0xFF1A1A1A).copy(alpha = alpha)))
}

@Composable
private fun SkeletonMovieRow(horizontalPadding: androidx.compose.ui.unit.Dp) {
    Row(
        modifier = Modifier.padding(horizontal = horizontalPadding).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        repeat(6) {
            Column(modifier = Modifier.width(130.dp)) {
                Box(modifier = Modifier.fillMaxWidth().aspectRatio(0.68f).background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp)))
                Spacer(Modifier.height(8.dp))
                Box(modifier = Modifier.fillMaxWidth(0.6f).height(12.dp).background(Color(0xFF1A1A1A)))
            }
        }
    }
}

@Composable
private fun HeroSection(
    movie: Movie, 
    isAnimation: Boolean, 
    onClick: () -> Unit, 
    onPlay: (Movie) -> Unit,
    horizontalPadding: androidx.compose.ui.unit.Dp
) {
    var isPlayFocused by remember { mutableStateOf(false) }
    var isInfoFocused by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth().height(320.dp).background(Color.Black)) {
        TmdbAsyncImage(title = movie.title ?: "", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, isLarge = true, isAnimation = isAnimation)
        Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(0.0f to Color.Transparent, 0.5f to Color(0xFF0F0F0F).copy(alpha = 0.5f), 1.0f to Color(0xFF0F0F0F))))
        Column(modifier = Modifier.align(Alignment.BottomStart).padding(start = horizontalPadding, bottom = 32.dp).fillMaxWidth(0.6f)) {
            Text(text = (movie.title ?: "").cleanTitle(), color = Color.White, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black, shadow = Shadow(color = Color.Black.copy(alpha = 0.8f), blurRadius = 10f)))
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { onPlay(movie) }, colors = ButtonDefaults.buttonColors(containerColor = if (isPlayFocused) Color.White else Color.Red), shape = RoundedCornerShape(8.dp), modifier = Modifier.height(40.dp).onFocusChanged { isPlayFocused = it.isFocused }.scale(if (isPlayFocused) 1.05f else 1f)) {
                    Icon(Icons.Default.PlayArrow, null, tint = if (isPlayFocused) Color.Black else Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp)); Text("시청하기", color = if (isPlayFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Spacer(Modifier.width(16.dp))
                Button(onClick = onClick, shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = if (isInfoFocused) Color.Gray.copy(alpha = 0.6f) else Color.Gray.copy(alpha = 0.3f)), modifier = Modifier.height(40.dp).onFocusChanged { isInfoFocused = it.isFocused }.scale(if (isInfoFocused) 1.05f else 1f)) {
                    Icon(Icons.Default.Info, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp)); Text("상세 정보", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, horizontalPadding: androidx.compose.ui.unit.Dp) {
    Text(text = title, modifier = Modifier.padding(start = horizontalPadding, top = 24.dp, bottom = 8.dp), color = Color(0xFFE0E0E0), fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 0.5.sp)
}

@Composable
private fun MovieCard(title: String, isAnimation: Boolean = false, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) 1.1f else 1f, label = "scale")
    Column(modifier = Modifier.width(130.dp).onFocusChanged { isFocused = it.isFocused }.focusable().clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(modifier = Modifier.fillMaxWidth().aspectRatio(0.68f).scale(scale), shape = RoundedCornerShape(8.dp), color = Color.DarkGray, border = if (isFocused) BorderStroke(2.dp, Color.White) else null) {
            TmdbAsyncImage(title, Modifier.fillMaxSize(), isAnimation = isAnimation)
        }
        Spacer(Modifier.height(8.dp))
        Text(text = title.cleanTitle(), color = if (isFocused) Color.White else Color(0xFFAAAAAA), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).scale(if (isFocused) 1.05f else 1f))
    }
}
