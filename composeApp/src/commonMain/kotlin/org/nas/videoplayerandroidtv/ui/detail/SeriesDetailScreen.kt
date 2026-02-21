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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import kotlinx.coroutines.*
import org.nas.videoplayerandroidtv.data.WatchHistory
import org.nas.videoplayerandroidtv.data.network.NasApiClient
import org.nas.videoplayerandroidtv.domain.model.Movie
import org.nas.videoplayerandroidtv.domain.model.Series
import org.nas.videoplayerandroidtv.domain.repository.VideoRepository
import org.nas.videoplayerandroidtv.ui.detail.components.EpisodeItem
import org.nas.videoplayerandroidtv.util.TitleUtils.cleanTitle
import org.nas.videoplayerandroidtv.util.TitleUtils.extractEpisode
import org.nas.videoplayerandroidtv.util.TitleUtils.extractSeason

@Composable
fun SeriesDetailScreen(
    series: Series,
    repository: VideoRepository,
    watchHistory: List<WatchHistory>,
    onPlay: (Movie, List<Movie>, Long) -> Unit,
    onBackPressed: () -> Unit
) {
    var state by remember { mutableStateOf(SeriesDetailState(isLoading = true)) }
    var currentSeries by remember { mutableStateOf(series) }
    val context = LocalPlatformContext.current
    
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
        
        if (seasons.isNotEmpty()) {
            val imageLoader = SingletonImageLoader.get(context)
            seasons.forEach { season ->
                season.episodes.forEach { episode ->
                    episode.thumbnailUrl?.let { url ->
                        val request = ImageRequest.Builder(context)
                            .data(url)
                            .build()
                        imageLoader.enqueue(request)
                    }
                }
            }
        }
    }

    val allEpisodes = state.seasons.flatMap { it.episodes }
    
    // 이어보기 정보 계산
    val resumeInfo = remember(allEpisodes, watchHistory, currentSeries.fullPath) {
        if (allEpisodes.isEmpty()) return@remember null
        
        val history = watchHistory.filter { it.seriesPath == currentSeries.fullPath }
            .sortedByDescending { it.timestamp }
            .firstOrNull()
            
        if (history == null) {
            // 기록 없으면 첫 화
            ResumeInfo(allEpisodes.first(), 0L, isNew = true)
        } else {
            val lastEpIndex = allEpisodes.indexOfFirst { it.videoUrl == history.videoUrl || it.id == history.id }
            if (lastEpIndex == -1) {
                ResumeInfo(allEpisodes.first(), 0L, isNew = true)
            } else {
                val lastEp = allEpisodes[lastEpIndex]
                val isFinished = history.duration > 0 && history.lastPosition > history.duration * 0.95
                
                if (isFinished && lastEpIndex < allEpisodes.size - 1) {
                    // 다 봤으면 다음 화
                    ResumeInfo(allEpisodes[lastEpIndex + 1], 0L, isNext = true)
                } else if (isFinished) {
                    // 마지막 화까지 다 봤으면 다시 처음부터
                    ResumeInfo(lastEp, 0L, isFinished = true)
                } else {
                    // 보던 중이면 이어보기
                    ResumeInfo(lastEp, history.lastPosition)
                }
            }
        }
    }

    LaunchedEffect(state.isLoading, resumeInfo) {
        if (!state.isLoading && resumeInfo != null) {
            delay(100) 
            try {
                if (!resumeInfo.isNew) resumeButtonFocusRequester.requestFocus()
                else playButtonFocusRequester.requestFocus()
            } catch (e: Exception) { }
        }
    }

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

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 60.dp)
                .padding(top = 40.dp) 
        ) { 
            Column(
                modifier = Modifier
                    .weight(1.5f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Top
            ) {
                Text(
                    text = currentSeries.title.cleanTitle(includeYear = false), 
                    color = Color.White, 
                    style = TextStyle(
                        fontSize = 25.sp, 
                        fontWeight = FontWeight.ExtraBold, 
                        shadow = Shadow(color = Color.Black.copy(alpha = 0.5f), offset = Offset(0f, 4f), blurRadius = 12f), 
                        letterSpacing = (-1).sp
                    ), 
                    maxLines = 1, 
                    overflow = TextOverflow.Ellipsis
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.height(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (state.isLoading) {
                        Box(modifier = Modifier.width(100.dp).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.1f)))
                    } else {
                        currentSeries.year?.let { InfoBadge(text = it, isOutlined = true) }
                        if (state.seasons.isNotEmpty()) InfoBadge(text = "시즌 ${state.seasons.size}개")
                        currentSeries.rating?.let { InfoBadge(text = it, color = Color(0xFFE50914)) }
                        currentSeries.genreNames.take(3).forEach { InfoBadge(text = it, color = Color.White.copy(alpha = 0.15f)) }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Box(modifier = Modifier.wrapContentHeight().fillMaxWidth()) {
                    if (!state.isLoading) {
                        Text(text = currentSeries.overview ?: "정보가 없습니다.", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(modifier = Modifier.height(11.dp))
                if (!state.isLoading && currentSeries.actors.isNotEmpty()) {
                    Text(text = "출연: " + currentSeries.actors.take(4).joinToString { it.name }, color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) 
                }
                Spacer(modifier = Modifier.height(34.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (!state.isLoading && resumeInfo != null) {
                        if (!resumeInfo.isNew) {
                            val epTitle = resumeInfo.episode.title ?: ""
                            val seasonNum = epTitle.extractSeason()
                            val episodeStr = epTitle.extractEpisode()
                            val buttonText = when {
                                resumeInfo.isNext -> "시즌 $seasonNum : $episodeStr 재생"
                                resumeInfo.isFinished -> "다시 보기"
                                else -> "시즌 $seasonNum : $episodeStr 이어보기"
                            }
                            PremiumTvButton(
                                text = buttonText,
                                icon = Icons.Default.PlayArrow, 
                                isPrimary = true, 
                                modifier = Modifier.focusRequester(resumeButtonFocusRequester), 
                                onClick = { onPlay(resumeInfo.episode, allEpisodes, resumeInfo.position) }
                            )
                        }
                        
                        PremiumTvButton(
                            text = if (!resumeInfo.isNew) "처음부터" else "재생", 
                            icon = if (!resumeInfo.isNew) Icons.Default.Refresh else Icons.Default.PlayArrow, 
                            isPrimary = resumeInfo.isNew, 
                            modifier = Modifier.focusRequester(playButtonFocusRequester), 
                            onClick = { onPlay(allEpisodes.first(), allEpisodes, 0L) }
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
                val seriesPosterUrl = currentSeries.posterPath?.let { 
                    if (it.startsWith("http")) it else if (it.startsWith("/")) "https://image.tmdb.org/t/p/w500$it" else "${NasApiClient.BASE_URL}/$it"
                }
                EpisodeOverlay(seriesTitle = currentSeries.title, state = state, seriesOverview = currentSeries.overview, seriesPosterPath = seriesPosterUrl, focusRequester = overlayFocusRequester, onSeasonChange = { state = state.copy(selectedSeasonIndex = it) }, onEpisodeClick = { ep ->
                    val currentEpisodes = state.seasons.getOrNull(state.selectedSeasonIndex)?.episodes ?: emptyList()
                    onPlay(ep, currentEpisodes, 0L)
                }, onClose = { state = state.copy(showEpisodeOverlay = false) })
            }
        }
    }
}

@Composable
private fun InfoBadge(text: String, color: Color = Color.White.copy(alpha = 0.15f), textColor: Color = Color.White, isOutlined: Boolean = false) {
    Box(modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(4.dp)).background(if (isOutlined) Color.Transparent else color).then(if (isOutlined) Modifier.border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(4.dp)) else Modifier).padding(horizontal = 7.dp, vertical = 2.dp), contentAlignment = Alignment.Center) {
        Text(text = text, color = textColor, fontSize = 8.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, lineHeight = 8.sp)
    }
}

@Composable
private fun PremiumTvButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, isPrimary: Boolean, progress: Float? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
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
    Surface(onClick = onClick, color = backgroundColor, shape = RoundedCornerShape(10.dp), modifier = modifier.onFocusChanged { isFocused = it.isFocused }.graphicsLayer { scaleX = scale; scaleY = scale }.height(36.dp).wrapContentWidth().shadow(if (isFocused) 20.dp else 0.dp, RoundedCornerShape(10.dp), spotColor = Color.White.copy(alpha = 0.4f))) {
        Box(modifier = Modifier.wrapContentWidth(), contentAlignment = Alignment.Center) { 
            Row(modifier = Modifier.padding(horizontal = 17.dp).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) { Icon(imageVector = icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(15.dp)); Spacer(modifier = Modifier.width(7.dp)); Text(text = text, color = contentColor, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
            if (progress != null && progress > 0f) { Box(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(if (isFocused) Color.Red else Color.Red.copy(alpha = 0.8f))) }
        }
    }
}

data class Season(val number: Int, val name: String, val episodes: List<Movie>)
private data class SeriesDetailState(val seasons: List<Season> = emptyList(), val isLoading: Boolean = true, val selectedSeasonIndex: Int = 0, val showEpisodeOverlay: Boolean = false)

private data class ResumeInfo(
    val episode: Movie,
    val position: Long,
    val isNew: Boolean = false,
    val isNext: Boolean = false,
    val isFinished: Boolean = false
)

@Composable
private fun EpisodeOverlay(seriesTitle: String, state: SeriesDetailState, seriesOverview: String?, seriesPosterPath: String?, focusRequester: FocusRequester, onSeasonChange: (Int) -> Unit, onEpisodeClick: (Movie) -> Unit, onClose: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Row(modifier = Modifier.fillMaxSize().padding(42.dp)) {
            Column(modifier = Modifier.weight(0.35f)) {
                Text(text = seriesTitle.cleanTitle(includeYear = false), color = Color.White, style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 29.sp, shadow = Shadow(color = Color.Black, offset = Offset(2f, 2f), blurRadius = 4f)), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(6.dp)); Text(text = "시즌 ${state.seasons.size}개", color = Color.Gray, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(28.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    items(state.seasons.size) { index ->
                        val isSelected = index == state.selectedSeasonIndex
                        var isFocused by remember { mutableStateOf(false) }
                        Surface(onClick = { onSeasonChange(index) }, color = if (isFocused) Color.White else if (isSelected) Color.White.copy(alpha = 0.2f) else Color.Transparent, shape = RoundedCornerShape(8.dp), border = BorderStroke(width = 2.dp, color = if (isFocused) Color.White else Color.Transparent), modifier = Modifier.fillMaxWidth().height(35.dp).onFocusChanged { isFocused = it.isFocused }.then(if (index == state.selectedSeasonIndex) Modifier.focusRequester(focusRequester) else Modifier).focusable()) {
                            Text(text = state.seasons[index].name, color = if (isFocused) Color.Black else Color.White, modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp), fontSize = 13.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium)
                        }
                    }
                }
                LaunchedEffect(Unit) { delay(150); try { focusRequester.requestFocus() } catch(_: Exception) {} }
            }
            Spacer(modifier = Modifier.width(42.dp))
            Column(modifier = Modifier.weight(0.65f)) {
                Text(text = state.seasons.getOrNull(state.selectedSeasonIndex)?.name ?: "회차 정보", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(17.dp))
                val episodes = state.seasons.getOrNull(state.selectedSeasonIndex)?.episodes ?: emptyList()
                LazyColumn(verticalArrangement = Arrangement.spacedBy(11.dp), contentPadding = PaddingValues(bottom = 28.dp)) {
                    items(episodes) { movie -> EpisodeItem(movie = movie, seriesOverview = seriesOverview, seriesPosterPath = seriesPosterPath, onPlay = { onEpisodeClick(movie) }) }
                }
            }
        }
    }
}

private suspend fun loadSeasons(series: Series, repository: VideoRepository): List<Season> = coroutineScope {
    val collectedEpisodes = mutableListOf<Movie>()
    collectedEpisodes.addAll(series.episodes)
    
    val totalMovies = collectedEpisodes.distinctBy { it.videoUrl ?: it.id ?: it.title }.map { movie ->
        val updatedVideoUrl = if (movie.videoUrl?.startsWith("http") == false) {
            NasApiClient.BASE_URL + (if (movie.videoUrl.startsWith("/")) "" else "/") + movie.videoUrl
        } else movie.videoUrl
        
        // FFmpeg 생성 썸네일을 무시하고, TMDB 스틸컷만 사용하며, 없을 경우 시리즈 포스터로 대체
        val isFfmpegThumb = movie.thumbnailUrl?.contains("thumb_serve") == true || movie.thumbnailUrl?.contains("video_serve") == true
        val rawThumb = if (!movie.thumbnailUrl.isNullOrEmpty() && !isFfmpegThumb) {
            movie.thumbnailUrl
        } else {
            series.posterPath
        }
        
        val updatedThumbUrl = if (!rawThumb.isNullOrEmpty() && !rawThumb.startsWith("http")) {
            if (rawThumb.startsWith("/")) {
                "https://image.tmdb.org/t/p/w500$rawThumb"
            } else {
                NasApiClient.BASE_URL + "/" + rawThumb + "?w=320"
            }
        } else rawThumb
        
        movie.copy(videoUrl = updatedVideoUrl, thumbnailUrl = updatedThumbUrl)
    }
    val seasonsMap = totalMovies.groupBy { it.title?.extractSeason() ?: 1 }
    seasonsMap.map { (num, eps) -> 
        Season(
            number = num, 
            name = "시즌 $num", 
            episodes = eps.sortedWith(
                compareBy(
                    { it.title?.extractSeason() ?: 1 },
                    { it.title?.extractEpisode()?.filter { c -> c.isDigit() }?.toIntOrNull() ?: 0 }
                )
            )
        ) 
    }.sortedBy { it.number }
}
