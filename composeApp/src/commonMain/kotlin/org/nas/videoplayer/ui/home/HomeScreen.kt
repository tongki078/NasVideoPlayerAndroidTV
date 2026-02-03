package org.nas.videoplayer.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

@Composable
fun HomeScreen(
    watchHistory: List<WatchHistory>,
    latestMovies: List<Series>,
    animations: List<Series>,
    onSeriesClick: (Series) -> Unit,
    onPlayClick: (Movie) -> Unit
) {
    val heroMovie = remember(latestMovies, animations) {
        latestMovies.firstOrNull()?.episodes?.firstOrNull() 
            ?: animations.firstOrNull()?.episodes?.firstOrNull()
    }

    LazyColumn(Modifier.fillMaxSize().background(Color.Black)) {
        // 1. 히어로 섹션 (중앙 대형 포스터)
        if (heroMovie != null) {
            item {
                HeroSection(
                    movie = heroMovie,
                    onClick = { 
                        val target = latestMovies.find { it.episodes.contains(heroMovie) } ?: animations.find { it.episodes.contains(heroMovie) }
                        target?.let { onSeriesClick(it) }
                    },
                    onPlay = onPlayClick
                )
            }
        }
        
        // 2. 시청 중인 콘텐츠
        if (watchHistory.isNotEmpty()) {
            item { SectionTitle("시청 중인 콘텐츠") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp)) {
                    items(watchHistory) { history ->
                        MovieCard(title = history.title, onClick = { onSeriesClick(Series(history.title, emptyList())) })
                    }
                }
            }
        }

        // 3. 최신 영화
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

        // 4. 애니메이션
        if (animations.isNotEmpty()) {
            item { SectionTitle("라프텔 애니메이션") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp)) {
                    items(animations) { series ->
                        MovieCard(title = series.title, typeHint = "tv", onClick = { onSeriesClick(series) })
                    }
                }
            }
        }
        
        item { Spacer(Modifier.height(100.dp)) }
    }
}

@Composable
private fun HeroSection(movie: Movie, onClick: () -> Unit, onPlay: (Movie) -> Unit) {
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
            isLarge = true
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
            TmdbAsyncImage(title = movie.title, modifier = Modifier.fillMaxSize(), isLarge = true)
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
private fun MovieCard(title: String, typeHint: String? = null, onClick: () -> Unit) {
    Card(
        Modifier
            .size(130.dp, 200.dp)
            .padding(end = 12.dp)
            .clickable(onClick = onClick)
    ) {
        TmdbAsyncImage(title, Modifier.fillMaxSize(), typeHint = typeHint)
    }
}
