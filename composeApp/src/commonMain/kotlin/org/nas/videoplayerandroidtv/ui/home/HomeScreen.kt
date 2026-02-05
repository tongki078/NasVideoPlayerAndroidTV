package org.nas.videoplayerandroidtv.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import org.nas.videoplayerandroidtv.ui.common.TmdbAsyncImage
import org.nas.videoplayerandroidtv.domain.model.Series
import org.nas.videoplayerandroidtv.domain.model.Movie
import org.nas.videoplayerandroidtv.data.WatchHistory
import org.nas.videoplayerandroidtv.cleanTitle
import org.nas.videoplayerandroidtv.ui.common.shimmerBrush

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

    if (isLoading) {
        LazyColumn(Modifier.fillMaxSize().background(Color(0xFF0F0F0F))) {
            item { HeroSectionSkeleton(horizontalPadding) }
            repeat(3) {
                item { SectionSkeleton(horizontalPadding) }
            }
        }
    } else {
        val heroMovie = remember(latestMovies, animations) {
            latestMovies.firstOrNull()?.episodes?.firstOrNull() 
                ?: animations.firstOrNull()?.episodes?.firstOrNull()
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().background(Color(0xFF0F0F0F)),
            state = lazyListState,
            contentPadding = PaddingValues(bottom = 40.dp)
        ) {
            if (heroMovie != null) {
                item {
                    val isAniHero = animations.any { it.episodes.contains(heroMovie) }
                    HeroSection(
                        movie = heroMovie,
                        isAnimation = isAniHero,
                        onClick = { 
                            val target = latestMovies.find { it.episodes.contains(heroMovie) } ?: animations.find { it.episodes.contains(heroMovie) }
                            target?.let { onSeriesClick(it) }
                        },
                        onPlay = onPlayClick,
                        horizontalPadding = horizontalPadding
                    )
                }
            }
            
            if (watchHistory.isNotEmpty()) {
                item { SectionTitle("시청 중인 콘텐츠", horizontalPadding) }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = horizontalPadding),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(watchHistory) { history ->
                            val cleanedHistoryTitle = history.title.cleanTitle(includeYear = false)
                            val isAniHistory = history.screenType == "animation" || 
                                               history.videoUrl.contains("애니메이션") ||
                                               animations.any { it.title.cleanTitle(includeYear = false).equals(cleanedHistoryTitle, ignoreCase = true) }

                            MovieCard(
                                title = history.title, 
                                isAnimation = isAniHistory,
                                onClick = { onHistoryClick(history) }
                            )
                        }
                    }
                }
            }

            if (latestMovies.isNotEmpty()) {
                item { SectionTitle("최신 업데이트", horizontalPadding) }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = horizontalPadding),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(latestMovies) { series ->
                            MovieCard(title = series.title, typeHint = "movie", onClick = { onSeriesClick(series) })
                        }
                    }
                }
            }

            if (animations.isNotEmpty()) {
                item { SectionTitle("인기 애니메이션", horizontalPadding) }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = horizontalPadding),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(animations) { series ->
                            MovieCard(title = series.title, typeHint = "tv", isAnimation = true, onClick = { onSeriesClick(series) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroSection(
    movie: Movie, 
    isAnimation: Boolean = false, 
    onClick: () -> Unit, 
    onPlay: (Movie) -> Unit,
    horizontalPadding: androidx.compose.ui.unit.Dp
) {
    var isPlayFocused by remember { mutableStateOf(false) }
    var isInfoFocused by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
            .background(Color.Black)
    ) {
        TmdbAsyncImage(
            title = movie.title ?: "",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            isLarge = true,
            isAnimation = isAnimation
        )
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.4f to Color(0xFF0F0F0F).copy(alpha = 0.3f),
                        0.8f to Color(0xFF0F0F0F).copy(alpha = 0.8f),
                        1.0f to Color(0xFF0F0F0F)
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = horizontalPadding, bottom = 32.dp)
                .fillMaxWidth(0.6f)
        ) {
            Text(
                text = (movie.title ?: "").cleanTitle(),
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Black,
                    shadow = Shadow(color = Color.Black.copy(alpha = 0.8f), blurRadius = 10f)
                )
            )
            Spacer(Modifier.height(16.dp))
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { onPlay(movie) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isPlayFocused) Color.White else Color.Red
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .height(40.dp)
                        .onFocusChanged { isPlayFocused = it.isFocused }
                        .scale(if (isPlayFocused) 1.05f else 1f)
                ) {
                    Icon(
                        Icons.Default.PlayArrow, 
                        null, 
                        tint = if (isPlayFocused) Color.Black else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "시청하기", 
                        color = if (isPlayFocused) Color.Black else Color.White, 
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
                
                Spacer(Modifier.width(16.dp))
                
                Button(
                    onClick = onClick,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isInfoFocused) Color.Gray.copy(alpha = 0.6f) else Color.Gray.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier
                        .height(40.dp)
                        .onFocusChanged { isInfoFocused = it.isFocused }
                        .scale(if (isInfoFocused) 1.05f else 1f)
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
private fun SectionTitle(title: String, horizontalPadding: androidx.compose.ui.unit.Dp) {
    Text(
        text = title,
        modifier = Modifier.padding(start = horizontalPadding, top = 20.dp, bottom = 8.dp),
        color = Color(0xFFE0E0E0),
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        letterSpacing = 0.5.sp
    )
}

@Composable
private fun MovieCard(title: String, typeHint: String? = null, isAnimation: Boolean = false, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) 1.1f else 1f)

    Column(
        modifier = Modifier
            .width(110.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .scale(scale),
            shape = RoundedCornerShape(6.dp),
            color = Color.DarkGray,
            tonalElevation = if (isFocused) 8.dp else 0.dp,
            border = if (isFocused) BorderStroke(2.dp, Color.White) else null
        ) {
            TmdbAsyncImage(title, Modifier.fillMaxSize(), typeHint = typeHint, isAnimation = isAnimation)
        }
        
        Spacer(Modifier.height(8.dp))
        Text(
            text = title.cleanTitle(),
            color = if (isFocused) Color.White else Color(0xFFAAAAAA),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).scale(if (isFocused) 1.05f else 1f)
        )
    }
}

@Composable
private fun HeroSectionSkeleton(horizontalPadding: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier.fillMaxWidth().height(320.dp).background(Color(0xFF1A1A1A)),
        contentAlignment = Alignment.BottomStart
    ) {
        Box(modifier = Modifier.fillMaxSize().background(shimmerBrush()))
    }
}

@Composable
private fun SectionSkeleton(horizontalPadding: androidx.compose.ui.unit.Dp) {
    Column(Modifier.padding(vertical = 12.dp)) {
        Box(Modifier.padding(horizontal = horizontalPadding).width(140.dp).height(20.dp).clip(RoundedCornerShape(4.dp)).background(shimmerBrush()))
        Spacer(Modifier.height(12.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(6) {
                Box(
                    modifier = Modifier
                        .size(110.dp, 162.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(shimmerBrush())
                )
            }
        }
    }
}
