package org.nas.videoplayer.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nas.videoplayer.ui.common.TmdbAsyncImage
import org.nas.videoplayer.domain.model.Series
import org.nas.videoplayer.domain.model.Movie
import org.nas.videoplayer.data.WatchHistory
import org.nas.videoplayer.cleanTitle
import org.nas.videoplayer.ui.common.shimmerBrush

@Composable
fun HomeScreen(
    watchHistory: List<WatchHistory>,
    latestMovies: List<Series>,
    animations: List<Series>,
    isLoading: Boolean,
    lazyListState: LazyListState = rememberLazyListState(),
    onSeriesClick: (Series) -> Unit,
    onPlayClick: (Movie) -> Unit,
    onHistoryClick: (WatchHistory) -> Unit = {} // 추가
) {
    if (isLoading) {
        LazyColumn(Modifier.fillMaxSize().background(Color.Black)) {
            item { HeroSectionSkeleton() }
            repeat(3) {
                item { SectionSkeleton() }
            }
        }
    } else {
        val heroMovie = remember(latestMovies, animations) {
            latestMovies.firstOrNull()?.episodes?.firstOrNull() 
                ?: animations.firstOrNull()?.episodes?.firstOrNull()
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            state = lazyListState
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
                        onPlay = onPlayClick
                    )
                }
            }
            
            if (watchHistory.isNotEmpty()) {
                item { SectionTitle("시청 중인 콘텐츠") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp)) {
                        items(watchHistory) { history ->
                            // 기존 기록 중에서도 애니메이션 여부를 제목 대조를 통해 다시 확인
                            val cleanedHistoryTitle = history.title.cleanTitle(includeYear = false)
                            val isAniHistory = history.screenType == "animation" || 
                                               history.videoUrl.contains("애니메이션") ||
                                               animations.any { it.title.cleanTitle(includeYear = false).equals(cleanedHistoryTitle, ignoreCase = true) }

                            MovieCard(
                                title = history.title, 
                                isAnimation = isAniHistory,
                                onClick = { onHistoryClick(history) } // history 클릭 핸들러 사용
                            )
                        }
                    }
                }
            }

            if (latestMovies.isNotEmpty()) {
                item { SectionTitle("최신 영화") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp)) {
                        items(latestMovies) { series ->
                            MovieCard(title = series.title, typeHint = "movie", onClick = { onSeriesClick(series) })
                        }
                    }
                }
            }

            if (animations.isNotEmpty()) {
                item { SectionTitle("라프텔 애니메이션") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp)) {
                        items(animations) { series ->
                            MovieCard(title = series.title, typeHint = "tv", isAnimation = true, onClick = { onSeriesClick(series) })
                        }
                    }
                }
            }
            
            item { Spacer(Modifier.height(100.dp)) }
        }
    }
}

@Composable
private fun HeroSection(movie: Movie, isAnimation: Boolean = false, onClick: () -> Unit, onPlay: (Movie) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(550.dp)
            .clickable { onClick() }
            .background(Color.Black)
    ) {
        TmdbAsyncImage(
            title = movie.title,
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
                        colors = listOf(
                            Color.Black.copy(alpha = 0.4f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.6f),
                            Color.Black
                        )
                    )
                )
        )

        Card(
            modifier = Modifier
                .width(280.dp)
                .height(400.dp)
                .align(Alignment.TopCenter)
                .padding(top = 40.dp),
            shape = RoundedCornerShape(8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
        ) {
            TmdbAsyncImage(title = movie.title, modifier = Modifier.fillMaxSize(), isLarge = true, isAnimation = isAnimation)
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = movie.title.cleanTitle(),
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    shadow = Shadow(color = Color.Black, blurRadius = 8f)
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { onPlay(movie) },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.width(120.dp).height(45.dp)
            ) {
                Icon(Icons.Default.PlayArrow, null, tint = Color.Black)
                Spacer(Modifier.width(8.dp))
                Text("재생", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(16.dp),
        color = Color.White,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp
    )
}

@Composable
private fun MovieCard(title: String, typeHint: String? = null, isAnimation: Boolean = false, onClick: () -> Unit) {
    Card(
        Modifier
            .size(130.dp, 200.dp)
            .padding(end = 12.dp)
            .clickable(onClick = onClick)
    ) {
        TmdbAsyncImage(title, Modifier.fillMaxSize(), typeHint = typeHint, isAnimation = isAnimation)
    }
}

// ==========================================================
// 스켈레톤 UI 컴포넌트
// ==========================================================

@Composable
private fun HeroSectionSkeleton() {
    Box(
        modifier = Modifier.fillMaxWidth().height(550.dp).background(Color.Black),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(modifier = Modifier.fillMaxSize().background(shimmerBrush()))
        Box(
            modifier = Modifier
                .padding(top = 40.dp)
                .width(280.dp)
                .height(400.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(shimmerBrush())
        )
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.width(200.dp).height(30.dp).clip(RoundedCornerShape(4.dp)).background(shimmerBrush()))
            Spacer(Modifier.height(20.dp))
            Box(Modifier.width(120.dp).height(45.dp).clip(RoundedCornerShape(4.dp)).background(shimmerBrush()))
        }
    }
}

@Composable
private fun SectionSkeleton() {
    Column(Modifier.padding(vertical = 16.dp)) {
        Box(Modifier.padding(horizontal = 16.dp).width(150.dp).height(24.dp).clip(RoundedCornerShape(4.dp)).background(shimmerBrush()))
        Spacer(Modifier.height(16.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp)) {
            items(5) {
                Box(
                    modifier = Modifier
                        .size(130.dp, 200.dp)
                        .padding(end = 12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(shimmerBrush())
                )
            }
        }
    }
}
