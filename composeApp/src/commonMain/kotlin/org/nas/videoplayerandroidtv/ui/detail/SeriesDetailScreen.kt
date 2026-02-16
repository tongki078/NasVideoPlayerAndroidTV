package org.nas.videoplayerandroidtv.ui.detail

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import kotlinx.coroutines.*
import org.nas.videoplayerandroidtv.data.network.NasApiClient
import org.nas.videoplayerandroidtv.domain.model.Movie
import org.nas.videoplayerandroidtv.domain.model.Series
import org.nas.videoplayerandroidtv.domain.model.Cast
import org.nas.videoplayerandroidtv.domain.repository.VideoRepository
import org.nas.videoplayerandroidtv.ui.detail.components.EpisodeItem
import org.nas.videoplayerandroidtv.util.TitleUtils.cleanTitle
import org.nas.videoplayerandroidtv.util.TitleUtils.extractEpisode
import org.nas.videoplayerandroidtv.util.TitleUtils.extractSeason
import org.nas.videoplayerandroidtv.util.TitleUtils.isGenericTitle

@Composable
fun SeriesDetailScreen(
    series: Series,
    repository: VideoRepository,
    initialPlaybackPosition: Long = 0L,
    initialDuration: Long = 0L,
    onPlay: (Movie, List<Movie>, Long) -> Unit,
    onPositionUpdate: (Long) -> Unit,
    onBackPressed: () -> Unit
) {
    var state by remember { mutableStateOf(SeriesDetailState(isLoading = true)) }
    var currentSeries by remember { mutableStateOf(series) }
    
    val playButtonFocusRequester = remember { FocusRequester() }
    val resumeButtonFocusRequester = remember { FocusRequester() }
    val infoButtonFocusRequester = remember { FocusRequester() }
    val overlayFocusRequester = remember { FocusRequester() }

    LaunchedEffect(series) {
        state = state.copy(isLoading = true)
        val fullSeries = if (series.episodes.isEmpty() && series.fullPath != null) {
            repository.getSeriesDetail(series.fullPath) ?: series
        } else series
        
        currentSeries = fullSeries
        val seasons = withContext(Dispatchers.IO) {
            loadSeasons(fullSeries, repository)
        }
        state = state.copy(seasons = seasons, isLoading = false)
    }

    LaunchedEffect(state.isLoading) {
        if (!state.isLoading) {
            delay(100) 
            try {
                val playableEpisode = state.seasons.flatMap { it.episodes }.firstOrNull()
                if (playableEpisode != null) {
                    if (initialPlaybackPosition > 0) resumeButtonFocusRequester.requestFocus()
                    else playButtonFocusRequester.requestFocus()
                }
            } catch (e: Exception) { }
        }
    }

    val allEpisodes = state.seasons.flatMap { it.episodes }
    val playableEpisode = allEpisodes.firstOrNull()

    Box(modifier = Modifier.fillMaxSize().background(color = Color.Black)) {
        val backdropUrl = currentSeries.posterPath?.let { 
            if (it.startsWith("http")) it else if (it.startsWith("/")) "https://image.tmdb.org/t/p/original$it" else "${NasApiClient.BASE_URL}/$it"
        }
        AnimatedContent(targetState = backdropUrl, transitionSpec = { fadeIn(animationSpec = tween(1000)) togetherWith fadeOut(animationSpec = tween(1000)) }, label = "BackdropTransition") { url ->
            if (url != null) {
                AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize().alpha(0.4f), contentScale = ContentScale.Crop)
            } else {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black))
            }
        }

        Box(modifier = Modifier.fillMaxSize().background(brush = Brush.horizontalGradient(0.0f to Color.Black, 0.5f to Color.Black.copy(alpha = 0.8f), 1.0f to Color.Transparent)))

        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 60.dp, vertical = 40.dp)) {
            Column(modifier = Modifier.weight(1.5f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                Text(
                    text = currentSeries.title.cleanTitle(includeYear = false), 
                    color = Color.White, 
                    style = TextStyle(
                        fontSize = 36.sp, 
                        fontWeight = FontWeight.ExtraBold, 
                        shadow = Shadow(color = Color.Black.copy(alpha = 0.5f), offset = Offset(0f, 4f), blurRadius = 12f), 
                        letterSpacing = (-1).sp
                    ), 
                    maxLines = 1, 
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(18.dp))
                Row(modifier = Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (state.isLoading) {
                        Box(modifier = Modifier.width(100.dp).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.1f)))
                    } else {
                        currentSeries.year?.let { InfoBadge(text = it, isOutlined = true) }
                        if (state.seasons.isNotEmpty()) InfoBadge(text = "시즌 ${state.seasons.size}개")
                        currentSeries.rating?.let { InfoBadge(text = it, color = Color(0xFFE50914)) }
                        currentSeries.genreNames.take(3).forEach { InfoBadge(text = it, color = Color.White.copy(alpha = 0.15f)) }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                
                Box(modifier = Modifier.wrapContentHeight().fillMaxWidth()) {
                    if (!state.isLoading) {
                        Text(
                            text = currentSeries.overview ?: "정보가 없습니다.", 
                            color = Color.White.copy(alpha = 0.7f), 
                            fontSize = 16.sp, 
                            lineHeight = 24.sp, 
                            maxLines = 2, 
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                if (!state.isLoading && currentSeries.actors.isNotEmpty()) {
                    Text(text = "출연: " + currentSeries.actors.take(4).joinToString { it.name }, color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                
                Spacer(modifier = Modifier.height(48.dp))
                
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (!state.isLoading && playableEpisode != null) {
                        if (initialPlaybackPosition > 0) {
                            val epTitle = playableEpisode.title ?: ""
                            val seasonNum = epTitle.extractSeason()
                            val episodeStr = epTitle.extractEpisode()
                            PremiumTvButton(
                                text = if (episodeStr != null) "시즌 $seasonNum : $episodeStr 이어보기" else "계속 시청", 
                                icon = Icons.Default.PlayArrow, 
                                isPrimary = true, 
                                progress = if (initialDuration > 0) initialPlaybackPosition.toFloat() / initialDuration else 0f,
                                modifier = Modifier.focusRequester(resumeButtonFocusRequester), 
                                onClick = { onPlay(playableEpisode, allEpisodes, initialPlaybackPosition) }
                            )
                        }
                        
                        PremiumTvButton(
                            text = if (initialPlaybackPosition > 0) "처음부터" else "재생", 
                            icon = if (initialPlaybackPosition > 0) Icons.Default.Refresh else Icons.Default.PlayArrow, 
                            isPrimary = initialPlaybackPosition <= 0, 
                            modifier = Modifier.focusRequester(playButtonFocusRequester), 
                            onClick = { onPlay(playableEpisode, allEpisodes, 0L) }
                        )
                        
                        if (state.seasons.isNotEmpty()) {
                            PremiumTvButton(
                                text = "회차 정보", 
                                icon = Icons.AutoMirrored.Filled.List, 
                                isPrimary = false, 
                                modifier = Modifier.focusRequester(infoButtonFocusRequester), 
                                onClick = { state = state.copy(showEpisodeOverlay = true) }
                            )
                        }
                    }
                }
            }
            Box(modifier = Modifier.weight(0.6f))
        }

        if (state.showEpisodeOverlay && state.seasons.isNotEmpty()) {
            AnimatedVisibility(visible = state.showEpisodeOverlay, enter = fadeIn() + slideInHorizontally { it }, exit = fadeOut() + slideOutHorizontally { it }, modifier = Modifier.zIndex(10f)) {
                EpisodeOverlay(seriesTitle = currentSeries.title, state = state, seriesOverview = currentSeries.overview, focusRequester = overlayFocusRequester, onSeasonChange = { state = state.copy(selectedSeasonIndex = it) }, onEpisodeClick = { ep ->
                    onPositionUpdate(0L)
                    val currentEpisodes = state.seasons.getOrNull(state.selectedSeasonIndex)?.episodes ?: emptyList()
                    onPlay(ep, currentEpisodes, 0L)
                }, onClose = { state = state.copy(showEpisodeOverlay = false) })
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "${h}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    else "${m}:${s.toString().padStart(2, '0')}"
}

@Composable
private fun InfoBadge(text: String, color: Color = Color.White.copy(alpha = 0.15f), textColor: Color = Color.White, isOutlined: Boolean = false) {
    Surface(color = if (isOutlined) Color.Transparent else color, shape = RoundedCornerShape(4.dp), border = if (isOutlined) BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)) else null, modifier = Modifier.padding(end = 8.dp)) {
        Text(text = text, color = textColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp))
    }
}

@Composable
private fun PremiumTvButton(
    text: String, 
    icon: androidx.compose.ui.graphics.vector.ImageVector, 
    isPrimary: Boolean, 
    progress: Float? = null, 
    modifier: Modifier = Modifier, 
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (isFocused) 1.08f else 1.0f)
    
    val backgroundColor by animateColorAsState(when { 
        isFocused -> Color.White 
        isPrimary -> Color.White.copy(alpha = 0.95f) 
        else -> Color.White.copy(alpha = 0.15f) 
    })
    val contentColor by animateColorAsState(when { 
        isFocused -> Color.Black 
        isPrimary -> Color.Black 
        else -> Color.White 
    })

    Surface(
        onClick = onClick, 
        color = backgroundColor,
        shape = RoundedCornerShape(10.dp), 
        modifier = modifier
            .onFocusChanged { isFocused = it.isFocused }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .height(52.dp)
            .wrapContentWidth()
            .shadow(if (isFocused) 20.dp else 0.dp, RoundedCornerShape(10.dp), spotColor = Color.White.copy(alpha = 0.4f))
    ) {
        Box(modifier = Modifier.wrapContentWidth()) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp).fillMaxHeight(), 
                verticalAlignment = Alignment.CenterVertically, 
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(text = text, color = contentColor, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            
            if (progress != null && progress > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(4.dp)
                        .background(if (isFocused) Color.Red else Color.Red.copy(alpha = 0.8f))
                )
            }
        }
    }
}

data class Season(val number: Int, val name: String, val episodes: List<Movie>)
private data class SeriesDetailState(val seasons: List<Season> = emptyList(), val isLoading: Boolean = true, val selectedSeasonIndex: Int = 0, val showEpisodeOverlay: Boolean = false)

@Composable
private fun EpisodeOverlay(seriesTitle: String, state: SeriesDetailState, seriesOverview: String?, focusRequester: FocusRequester, onSeasonChange: (Int) -> Unit, onEpisodeClick: (Movie) -> Unit, onClose: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Row(modifier = Modifier.fillMaxSize().padding(60.dp)) {
            Column(modifier = Modifier.weight(0.35f)) {
                Text(text = seriesTitle.cleanTitle(includeYear = false), color = Color.White, style = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 42.sp, shadow = Shadow(color = Color.Black, offset = Offset(2f, 2f), blurRadius = 4f)), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(8.dp)); Text(text = "시즌 ${state.seasons.size}개", color = Color.Gray, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(40.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
                    items(state.seasons.size) { index ->
                        val isSelected = index == state.selectedSeasonIndex
                        var isFocused by remember { mutableStateOf(false) }
                        Surface(
                            onClick = { onSeasonChange(index) }, 
                            color = if (isFocused) Color.White else if (isSelected) Color.White.copy(alpha = 0.2f) else Color.Transparent, 
                            shape = RoundedCornerShape(8.dp), 
                            border = BorderStroke(width = 2.dp, color = if (isFocused) Color.White else Color.Transparent), 
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { isFocused = it.isFocused }
                                .then(if (index == state.selectedSeasonIndex) Modifier.focusRequester(focusRequester) else Modifier)
                                .focusable()
                        ) {
                            Text(text = state.seasons[index].name, color = if (isFocused) Color.Black else Color.White, modifier = Modifier.padding(16.dp), fontSize = 18.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium)
                        }
                    }
                }
                LaunchedEffect(Unit) { delay(150); try { focusRequester.requestFocus() } catch(_: Exception) {} }
            }
            Spacer(modifier = Modifier.width(60.dp))
            Column(modifier = Modifier.weight(0.65f)) {
                Text(text = state.seasons.getOrNull(state.selectedSeasonIndex)?.name ?: "회차 정보", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(24.dp))
                val episodes = state.seasons.getOrNull(state.selectedSeasonIndex)?.episodes ?: emptyList()
                LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                    items(episodes) { movie -> EpisodeItem(movie = movie, seriesOverview = seriesOverview, onPlay = { onEpisodeClick(movie) }) }
                }
            }
        }
    }
}

private suspend fun loadSeasons(series: Series, repository: VideoRepository): List<Season> = coroutineScope {
    val collectedEpisodes = mutableListOf<Movie>()
    collectedEpisodes.addAll(series.episodes)
    
    // 에피소드 가공 시 썸네일 경로를 완벽하게 보정
    val totalMovies = collectedEpisodes.distinctBy { it.videoUrl ?: it.id ?: it.title }.map { movie ->
        val updatedVideoUrl = if (movie.videoUrl?.startsWith("http") == false) {
            NasApiClient.BASE_URL + (if (movie.videoUrl.startsWith("/")) "" else "/") + movie.videoUrl
        } else movie.videoUrl
        
        // 썸네일 보정: 에피소드 썸네일 우선, 없으면 시리즈 포스터 사용
        val rawThumb = if (!movie.thumbnailUrl.isNullOrEmpty()) movie.thumbnailUrl else series.posterPath
        val updatedThumbUrl = if (!rawThumb.isNullOrEmpty() && !rawThumb.startsWith("http")) {
            if (rawThumb.startsWith("/")) {
                // 서버 이미지인지 TMDB 이미지인지 판단
                if (rawThumb.contains("thumb_serve") || rawThumb.contains("video_serve")) {
                    // 회차 목록용 썸네일은 가로 320으로 최적화 요청
                    NasApiClient.BASE_URL + rawThumb + (if (rawThumb.contains("?")) "&" else "?") + "w=320"
                } else {
                    "https://image.tmdb.org/t/p/w500$rawThumb"
                }
            } else {
                NasApiClient.BASE_URL + "/" + rawThumb + "?w=320"
            }
        } else rawThumb
        
        movie.copy(videoUrl = updatedVideoUrl, thumbnailUrl = updatedThumbUrl)
    }
    val seasonsMap = totalMovies.groupBy { it.title?.extractSeason() ?: 1 }
    seasonsMap.map { (num, eps) -> Season(number = num, name = "시즌 $num", episodes = eps.sortedWith(compareBy { it.title?.extractEpisode()?.filter { c -> c.isDigit() }?.toIntOrNull() ?: 0 })) }.sortedBy { it.number }
}
