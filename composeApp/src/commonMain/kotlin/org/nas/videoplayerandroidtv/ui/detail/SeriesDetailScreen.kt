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

// 공통 모듈에서 BackHandler를 사용하기 위한 별도의 컴포저블 정의가 필요할 수 있으나,
// 여기서는 일반적인 UI 로직으로 대체하거나 필요한 import를 추가합니다.
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
    val playButtonFocusRequester = remember { FocusRequester() }
    val resumeButtonFocusRequester = remember { FocusRequester() }
    val infoButtonFocusRequester = remember { FocusRequester() }
    val overlayFocusRequester = remember { FocusRequester() }

    LaunchedEffect(series) {
        state = state.copy(isLoading = true)
        val seasons = withContext(Dispatchers.IO) {
            loadSeasons(series, repository)
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

    // 오버레이가 켜져 있을 때 뒤로가기 누르면 오버레이만 닫히도록 처리 (UI 내부 상태 활용)
    // Compose Multiplatform 환경에서는 플랫폼별 처리가 다를 수 있으므로 
    // 여기서는 간단하게 onBackPressed 콜백 내에서 상태를 체크하는 방식을 제안합니다.

    val allEpisodes = state.seasons.flatMap { it.episodes }
    val playableEpisode = allEpisodes.firstOrNull()

    Box(modifier = Modifier.fillMaxSize().background(color = Color.Black)) {
        val backdropUrl = series.posterPath?.let { if (it.startsWith("http")) it else "https://image.tmdb.org/t/p/original$it" }
        AnimatedContent(
            targetState = backdropUrl,
            transitionSpec = { fadeIn(animationSpec = tween(1000)) togetherWith fadeOut(animationSpec = tween(1000)) },
            label = "BackdropTransition"
        ) { url ->
            if (url != null) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().alpha(0.4f),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black))
            }
        }

        Box(modifier = Modifier.fillMaxSize().background(brush = Brush.horizontalGradient(0.0f to Color.Black, 0.5f to Color.Black.copy(alpha = 0.8f), 1.0f to Color.Transparent)))

        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 60.dp, vertical = 40.dp)) {
            Column(modifier = Modifier.weight(1.2f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                Text(
                    text = series.title.cleanTitle(includeYear = false),
                    color = Color.White,
                    style = TextStyle(fontSize = 48.sp, fontWeight = FontWeight.Bold, shadow = Shadow(color = Color.Black, offset = Offset(4f, 4f), blurRadius = 8f)),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(modifier = Modifier.height(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (state.isLoading) {
                        Box(modifier = Modifier.width(100.dp).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.1f)))
                    } else {
                        series.year?.let { InfoBadge(text = it) }
                        if (state.seasons.isNotEmpty()) InfoBadge(text = "시즌 ${state.seasons.size}개")
                        series.genreNames.take(3).forEach { InfoBadge(text = it, color = Color.White.copy(alpha = 0.1f)) }
                    }
                }
                
                Spacer(modifier = Modifier.height(20.dp))
                
                Box(modifier = Modifier.height(70.dp).fillMaxWidth()) {
                    if (state.isLoading) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(modifier = Modifier.fillMaxWidth(0.9f).height(14.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.05f)))
                            Box(modifier = Modifier.fillMaxWidth(0.7f).height(14.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.05f)))
                        }
                    } else {
                        Text(text = series.overview ?: "정보가 없습니다.", color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp, lineHeight = 22.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Box(modifier = Modifier.height(20.dp)) {
                    if (!state.isLoading && series.actors.isNotEmpty()) {
                        Text(text = "출연: " + series.actors.take(4).joinToString { it.name }, color = Color.Gray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Box(modifier = Modifier.fillMaxWidth().height(150.dp)) {
                    if (!state.isLoading && playableEpisode != null) {
                        Column(modifier = Modifier.align(Alignment.CenterStart), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (initialPlaybackPosition > 0) {
                                val epTitle = playableEpisode.title ?: ""
                                val seasonNum = epTitle.extractSeason()
                                val episodeStr = epTitle.extractEpisode()
                                TvButton(text = if (episodeStr != null) "시즌 $seasonNum : $episodeStr 이어보기" else "계속 보기", icon = Icons.Default.PlayArrow, isPrimary = true, progress = if (initialDuration > 0) initialPlaybackPosition.toFloat() / initialDuration else 0f, modifier = Modifier.focusRequester(resumeButtonFocusRequester), onClick = { onPlay(playableEpisode, allEpisodes, initialPlaybackPosition) })
                            }
                            TvButton(text = if (initialPlaybackPosition > 0) "처음부터 재생" else "재생", icon = if (initialPlaybackPosition > 0) Icons.Default.Refresh else Icons.Default.PlayArrow, isPrimary = initialPlaybackPosition <= 0, modifier = Modifier.focusRequester(playButtonFocusRequester), onClick = { onPlay(playableEpisode, allEpisodes, 0L) })
                            if (state.seasons.isNotEmpty()) {
                                TvButton(text = "회차 정보", icon = Icons.AutoMirrored.Filled.List, isPrimary = false, modifier = Modifier.focusRequester(infoButtonFocusRequester), onClick = { state = state.copy(showEpisodeOverlay = true) })
                            }
                        }
                    }
                }
            }
            Box(modifier = Modifier.weight(1f))
        }

        if (state.showEpisodeOverlay && state.seasons.isNotEmpty()) {
            AnimatedVisibility(visible = state.showEpisodeOverlay, enter = fadeIn() + slideInHorizontally { it }, exit = fadeOut() + slideOutHorizontally { it }, modifier = Modifier.zIndex(10f)) {
                EpisodeOverlay(seriesTitle = series.title, state = state, seriesOverview = series.overview, focusRequester = overlayFocusRequester, onSeasonChange = { state = state.copy(selectedSeasonIndex = it) }, onEpisodeClick = { ep ->
                    onPositionUpdate(0L)
                    val currentEpisodes = state.seasons.getOrNull(state.selectedSeasonIndex)?.episodes ?: emptyList()
                    onPlay(ep, currentEpisodes, 0L)
                }, onClose = { state = state.copy(showEpisodeOverlay = false) })
            }
        }
    }
}

@Composable
private fun InfoBadge(text: String, color: Color = Color.White.copy(alpha = 0.15f), textColor: Color = Color.White) {
    Surface(color = color, shape = RoundedCornerShape(4.dp), border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)), modifier = Modifier.padding(end = 8.dp)) {
        Text(text = text, color = textColor, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
private fun TvButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, isPrimary: Boolean, progress: Float? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (isFocused) 1.05f else 1.0f, label = "ButtonScale")
    Surface(onClick = onClick, color = if (isFocused) Color.White else if (isPrimary) Color.White.copy(alpha = 0.2f) else Color.Transparent, shape = RoundedCornerShape(8.dp), border = BorderStroke(width = 2.dp, color = if (isFocused) Color.White else Color.Gray.copy(alpha = 0.3f)), modifier = modifier.onFocusChanged { isFocused = it.isFocused }.graphicsLayer { scaleX = scale; scaleY = scale }.widthIn(min = 160.dp, max = 320.dp).height(44.dp).focusable()) {
        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
            Icon(imageVector = icon, contentDescription = null, tint = if (isFocused) Color.Black else Color.White, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = text, color = if (isFocused) Color.Black else Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (progress != null && progress > 0f) {
                Spacer(modifier = Modifier.weight(1f))
                Box(modifier = Modifier.width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(color = Color.Gray.copy(alpha = 0.3f))) {
                    Box(modifier = Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(color = if (isFocused) Color.Red else Color.Red.copy(alpha = 0.7f)))
                }
            }
        }
    }
}

data class Season(val number: Int, val name: String, val episodes: List<Movie>)
private data class SeriesDetailState(
    val seasons: List<Season> = emptyList(),
    val isLoading: Boolean = true,
    val selectedSeasonIndex: Int = 0,
    val showEpisodeOverlay: Boolean = false
)

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
                        Surface(onClick = { onSeasonChange(index) }, color = if (isFocused) Color.White else if (isSelected) Color.White.copy(alpha = 0.2f) else Color.Transparent, shape = RoundedCornerShape(8.dp), border = BorderStroke(width = 2.dp, color = if (isFocused) Color.White else Color.Transparent), modifier = Modifier.fillMaxWidth().onFocusChanged { isFocused = it.isFocused }.then(if (index == state.selectedSeasonIndex) Modifier.focusRequester(focusRequester) else Modifier).focusable()) {
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
    val totalMovies = collectedEpisodes.distinctBy { it.videoUrl ?: it.id ?: it.title }.map { movie ->
        if (movie.videoUrl?.startsWith("http") == false) {
            movie.copy(videoUrl = NasApiClient.BASE_URL + (if (movie.videoUrl.startsWith("/")) "" else "/") + movie.videoUrl)
        } else movie
    }
    val seasonsMap = totalMovies.groupBy { it.title?.extractSeason() ?: 1 }
    seasonsMap.map { (num, eps) -> Season(number = num, name = "시즌 $num", episodes = eps.sortedWith(compareBy { it.title?.extractEpisode()?.filter { c -> c.isDigit() }?.toIntOrNull() ?: 0 })) }.sortedBy { it.number }
}
