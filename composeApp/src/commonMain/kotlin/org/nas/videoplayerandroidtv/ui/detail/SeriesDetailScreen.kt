package org.nas.videoplayerandroidtv.ui.detail

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import kotlinx.coroutines.*
import org.nas.videoplayerandroidtv.data.WatchHistory
import org.nas.videoplayerandroidtv.data.network.NasApiClient
import org.nas.videoplayerandroidtv.domain.model.Movie
import org.nas.videoplayerandroidtv.domain.model.Series
import org.nas.videoplayerandroidtv.domain.repository.VideoRepository
import org.nas.videoplayerandroidtv.ui.detail.components.EpisodeItem
import org.nas.videoplayerandroidtv.util.TitleUtils
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
    var currentSeries by remember(series.fullPath) { mutableStateOf(series) }
    
    val playButtonFocusRequester = remember { FocusRequester() }
    val resumeButtonFocusRequester = remember { FocusRequester() }
    val infoButtonFocusRequester = remember { FocusRequester() }
    val overlayFocusRequester = remember { FocusRequester() }

    LaunchedEffect(series.fullPath) {
        state = state.copy(isLoading = true)
        
        if (series.episodes.isNotEmpty() || series.seasons.isNotEmpty()) {
            val initialSeasons = withContext(Dispatchers.Default) { loadSeasons(series) }
            state = state.copy(seasons = initialSeasons, isLoading = initialSeasons.isEmpty())
        }

        val fullSeries = if (series.fullPath != null) {
            try {
                repository.getSeriesDetail(series.fullPath) ?: series
            } catch (e: Exception) {
                series
            }
        } else series
        
        currentSeries = fullSeries
        val finalSeasons = withContext(Dispatchers.Default) { loadSeasons(fullSeries) }
        state = state.copy(seasons = finalSeasons, isLoading = false)
    }

    val allEpisodes = state.seasons.flatMap { it.episodes }
    val resumeInfo = remember(allEpisodes, watchHistory, currentSeries.fullPath) {
        if (allEpisodes.isEmpty()) return@remember null
        val history = watchHistory.filter { it.seriesPath == currentSeries.fullPath }
            .sortedByDescending { it.timestamp }.firstOrNull()
            
        if (history == null) ResumeInfo(allEpisodes.first(), 0L, isNew = true)
        else {
            val lastEpIndex = allEpisodes.indexOfFirst { it.videoUrl == history.videoUrl || it.id == history.id }
            if (lastEpIndex == -1) ResumeInfo(allEpisodes.first(), 0L, isNew = true)
            else {
                val lastEp = allEpisodes[lastEpIndex]
                val isFinished = history.duration > 0 && history.lastPosition > history.duration * 0.95
                if (isFinished && lastEpIndex < allEpisodes.size - 1) ResumeInfo(allEpisodes[lastEpIndex + 1], 0L, isNext = true)
                else if (isFinished) ResumeInfo(lastEp, 0L, isFinished = true)
                else ResumeInfo(lastEp, history.lastPosition)
            }
        }
    }

    LaunchedEffect(state.isLoading, resumeInfo) {
        if (!state.isLoading && resumeInfo != null) {
            delay(300) 
            try {
                if (!resumeInfo.isNew) resumeButtonFocusRequester.requestFocus()
                else playButtonFocusRequester.requestFocus()
            } catch (_: Exception) { }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(color = Color.Black)) {
        val backdropUrl = currentSeries.posterPath?.let { 
            if (it.startsWith("http")) it else if (it.startsWith("/")) "https://image.tmdb.org/t/p/original$it" else "${NasApiClient.BASE_URL}/$it"
        }
        
        AsyncImage(model = backdropUrl, contentDescription = null, modifier = Modifier.fillMaxSize().alpha(0.4f), contentScale = ContentScale.Crop)
        Box(modifier = Modifier.fillMaxSize().background(brush = Brush.horizontalGradient(listOf(Color.Black, Color.Black.copy(alpha = 0.8f), Color.Transparent), endX = 1000f)))

        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 64.dp).padding(top = 48.dp)) { 
            Column(modifier = Modifier.weight(1.5f).fillMaxHeight(), verticalArrangement = Arrangement.Top) {
                Text(
                    text = currentSeries.title, color = Color.White, 
                    style = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, shadow = Shadow(color = Color.Black.copy(alpha = 0.5f), offset = Offset(0f, 4f), blurRadius = 12f)), 
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(12.dp))
                
                // --- 넷플릭스 스타일 한 줄 메타데이터 로우 ---
                Row(modifier = Modifier.height(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (state.isLoading) {
                        Box(modifier = Modifier.width(100.dp).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.1f)))
                    } else {
                        val metadataItems = mutableListOf<@Composable () -> Unit>()

                        // 1. 타입 (시리즈/영화)
                        metadataItems.add { MetadataText(text = if (currentSeries.category == "movies") "영화" else "시리즈") }

                        // 2. 연도
                        currentSeries.year?.let { y -> metadataItems.add { MetadataText(text = y) } }
                        
                        // 3. 시즌 정보
                        if (currentSeries.category != "movies" && state.seasons.isNotEmpty()) {
                            metadataItems.add { MetadataText(text = "시즌 ${state.seasons.size}개") }
                        }
                        
                        // 4. 화질
                        metadataItems.add { InfoBadge(text = "HD", isOutlined = true) }

                        // 5. 연령 등급 뱃지 (가장 우측)
                        currentSeries.rating?.let { r -> metadataItems.add { DetailRatingBadge(r) } }

                        // 구분점(·)과 함께 렌더링
                        metadataItems.forEachIndexed { index, component ->
                            component()
                            if (index < metadataItems.size - 1) {
                                MetadataSeparator()
                            }
                        }
                    }
                }
                
                // 장르 목록
                if (!state.isLoading && currentSeries.genreNames.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        currentSeries.genreNames.take(3).forEachIndexed { index, genre ->
                            Text(text = genre, color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            if (index < currentSeries.genreNames.take(3).size - 1) {
                                Text(text = " • ", color = Color.White.copy(alpha = 0.4f), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                Text(text = currentSeries.overview ?: "정보가 없습니다.", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, lineHeight = 20.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.9f))
                Spacer(modifier = Modifier.height(14.dp))
                if (!state.isLoading && currentSeries.actors.isNotEmpty()) {
                    Text(text = "출연: " + currentSeries.actors.take(4).joinToString { it.name }, color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) 
                }
                Spacer(modifier = Modifier.height(32.dp))
                
                if (!state.isLoading) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (resumeInfo != null) {
                            if (!resumeInfo.isNew) {
                                val epTitle = resumeInfo.episode.title ?: ""
                                val seasonNum = epTitle.extractSeason()
                                val episodeStr = epTitle.extractEpisode() ?: "회차"

                                val btnLabel = when {
                                    resumeInfo.isNext -> "시즌 $seasonNum : $episodeStr 재생"
                                    resumeInfo.isFinished -> "다시 보기"
                                    else -> "시즌 $seasonNum : $episodeStr 이어보기"
                                }
                                PremiumTvButton(text = btnLabel, icon = Icons.Default.PlayArrow, isPrimary = true, modifier = Modifier.focusRequester(resumeButtonFocusRequester), onClick = { onPlay(resumeInfo.episode, allEpisodes, resumeInfo.position) })
                            }
                            PremiumTvButton(text = if (!resumeInfo.isNew) "처음부터" else "재생", icon = if (!resumeInfo.isNew) Icons.Default.Refresh else Icons.Default.PlayArrow, isPrimary = resumeInfo.isNew, modifier = Modifier.focusRequester(playButtonFocusRequester), onClick = { onPlay(allEpisodes.first(), allEpisodes, 0L) })
                            
                            if (currentSeries.category != "movies" && state.seasons.isNotEmpty()) {
                                PremiumTvButton(
                                    text = "회차 정보",
                                    icon = Icons.AutoMirrored.Filled.List,
                                    isPrimary = false,
                                    modifier = Modifier.focusRequester(infoButtonFocusRequester),
                                    onClick = { state = state.copy(showEpisodeOverlay = true) }
                                )
                            }
                        } else if (allEpisodes.isNotEmpty()) {
                             PremiumTvButton(text = "재생", icon = Icons.Default.PlayArrow, isPrimary = true, modifier = Modifier.focusRequester(playButtonFocusRequester), onClick = { onPlay(allEpisodes.first(), allEpisodes, 0L) })
                        }
                    }
                }
            }
            Box(modifier = Modifier.weight(0.6f))
        }

        if (state.showEpisodeOverlay && state.seasons.isNotEmpty()) {
            EpisodeOverlay(seriesTitle = currentSeries.title, state = state, seriesOverview = currentSeries.overview, seriesPosterPath = currentSeries.posterPath, focusRequester = overlayFocusRequester, onSeasonChange = { state = state.copy(selectedSeasonIndex = it) }, onEpisodeClick = { ep ->
                val currentEpisodes = state.seasons.getOrNull(state.selectedSeasonIndex)?.episodes ?: emptyList()
                onPlay(ep, currentEpisodes, 0L)
            }, onClose = { state = state.copy(showEpisodeOverlay = false) })
        }
    }
}

@Composable
private fun MetadataText(text: String, color: Color = Color.White.copy(alpha = 0.9f)) {
    Text(
        text = text,
        color = color,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun MetadataSeparator() {
    Text(
        text = " · ",
        color = Color.White.copy(alpha = 0.4f),
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
private fun DetailRatingBadge(rating: String) {
    val backgroundColor = when {
        rating.contains("19") || rating.contains("18") || rating.contains("청불") -> Color(0xFFE50914)
        rating.contains("15") -> Color(0xFFF5A623)
        rating.contains("12") -> Color(0xFFF8E71C)
        rating.contains("전체") || rating.contains("All") -> Color(0xFF46D369)
        else -> Color.Gray.copy(alpha = 0.5f)
    }
    
    Surface(
        color = backgroundColor,
        shape = RoundedCornerShape(2.dp),
        modifier = Modifier.padding(vertical = 1.dp)
    ) {
        val displayRating = rating.filter { it.isDigit() }.ifEmpty { if(rating.contains("전체")) "All" else rating.take(2) }
        Text(
            text = displayRating,
            color = if (backgroundColor == Color(0xFFF8E71C)) Color.Black else Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
        )
    }
}

@Composable
private fun InfoBadge(text: String, isOutlined: Boolean = true) {
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(2.dp),
        border = if (isOutlined) BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)) else null,
        modifier = Modifier.padding(vertical = 1.dp)
    ) {
        Text(
            text = text, 
            color = Color.White.copy(alpha = 0.9f), 
            fontSize = 11.sp, 
            fontWeight = FontWeight.ExtraBold, 
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp) 
        )
    }
}

@Composable
private fun PremiumTvButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, isPrimary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) 1.08f else 1.0f)
    
    val backgroundColor = if (isFocused) Color.White else if (isPrimary) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.15f)
    val contentColor = if (isFocused || isPrimary) Color.Black else Color.White

    Surface(onClick = onClick, color = backgroundColor, shape = RoundedCornerShape(8.dp), modifier = modifier.onFocusChanged { isFocused = it.isFocused }.graphicsLayer { scaleX = scale; scaleY = scale }.height(44.dp).wrapContentWidth().shadow(if (isFocused) 20.dp else 0.dp, RoundedCornerShape(8.dp))) {
        Row(modifier = Modifier.padding(horizontal = 24.dp).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = contentColor, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(text = text, color = contentColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

data class Season(val number: Int, val name: String, val episodes: List<Movie>)
private data class SeriesDetailState(val seasons: List<Season> = emptyList(), val isLoading: Boolean = true, val selectedSeasonIndex: Int = 0, val showEpisodeOverlay: Boolean = false)

private data class ResumeInfo(val episode: Movie, val position: Long, val isNew: Boolean = false, val isNext: Boolean = false, val isFinished: Boolean = false)

@Composable
private fun EpisodeOverlay(seriesTitle: String, state: SeriesDetailState, seriesOverview: String?, seriesPosterPath: String?, focusRequester: FocusRequester, onSeasonChange: (Int) -> Unit, onEpisodeClick: (Movie) -> Unit, onClose: () -> Unit) {
    val episodeListState = rememberLazyListState()
    
    LaunchedEffect(state.selectedSeasonIndex) {
        episodeListState.scrollToItem(0)
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Row(modifier = Modifier.fillMaxSize().padding(48.dp)) {
                Column(modifier = Modifier.weight(0.35f)) {
                    Text(text = seriesTitle, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(24.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                        items(state.seasons.size) { index ->
                            val isSelected = index == state.selectedSeasonIndex
                            var isFocused by remember { mutableStateOf(false) }
                            Surface(onClick = { onSeasonChange(index) }, color = if (isFocused) Color.White else if (isSelected) Color.White.copy(alpha = 0.2f) else Color.Transparent, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().height(44.dp).onFocusChanged { isFocused = it.isFocused }.then(if (isSelected) Modifier.focusRequester(focusRequester) else Modifier).focusable()) {
                                Text(text = state.seasons[index].name, color = if (isFocused) Color.Black else Color.White, modifier = Modifier.padding(10.dp), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    LaunchedEffect(Unit) { delay(200); try { focusRequester.requestFocus() } catch(_: Exception) {} }
                }
                Spacer(modifier = Modifier.width(42.dp))
                Column(modifier = Modifier.weight(0.65f)) {
                    val currentSeason = state.seasons.getOrNull(state.selectedSeasonIndex)
                    Text(text = currentSeason?.name ?: "회차 정보", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(20.dp))
                    LazyColumn(state = episodeListState, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                        items(currentSeason?.episodes ?: emptyList()) { movie -> 
                            EpisodeItem(movie = movie, seriesOverview = seriesOverview, seriesPosterPath = seriesPosterPath, onPlay = { onEpisodeClick(movie) }) 
                        }
                    }
                }
            }
        }
    }
}

private fun loadSeasons(series: Series): List<Season> {
    if (series.seasons.isNotEmpty()) {
        return series.seasons.entries.map { entry ->
            val seasonName = entry.key 
            val seasonNum = seasonName.filter { it.isDigit() }.toIntOrNull() ?: 1
            
            val processedEpisodes = entry.value.map { movie ->
                val videoUrl = if (movie.videoUrl?.startsWith("http") == false) NasApiClient.BASE_URL + (if (movie.videoUrl.startsWith("/")) "" else "/") + movie.videoUrl else movie.videoUrl ?: ""
                val rawThumb = if (!movie.thumbnailUrl.isNullOrEmpty() && !movie.thumbnailUrl.contains("thumb_serve")) movie.thumbnailUrl else series.posterPath
                val thumbUrl = if (!rawThumb.isNullOrEmpty() && !rawThumb.startsWith("http")) {
                    if (rawThumb.startsWith("/")) "https://image.tmdb.org/t/p/w500$rawThumb"
                    else NasApiClient.BASE_URL + "/" + rawThumb
                } else rawThumb
                movie.copy(videoUrl = videoUrl, thumbnailUrl = thumbUrl)
            }.sortedBy { it.episode_number ?: it.title?.let { t -> t.extractEpisode()?.filter { c -> c.isDigit() }?.toIntOrNull() } ?: 0 }
            
            Season(number = seasonNum, name = seasonName, episodes = processedEpisodes)
        }.sortedBy { it.number }
    }

    if (series.episodes.isNotEmpty()) {
        val processedMovies = series.episodes.map { movie ->
            val videoUrl = if (movie.videoUrl?.startsWith("http") == false) NasApiClient.BASE_URL + (if (movie.videoUrl.startsWith("/")) "" else "/") + movie.videoUrl else movie.videoUrl ?: ""
            val rawThumb = if (!movie.thumbnailUrl.isNullOrEmpty() && !movie.thumbnailUrl.contains("thumb_serve")) movie.thumbnailUrl else series.posterPath
            val thumbUrl = if (!rawThumb.isNullOrEmpty() && !rawThumb.startsWith("http")) {
                if (rawThumb.startsWith("/")) "https://image.tmdb.org/t/p/w500$rawThumb"
                else NasApiClient.BASE_URL + "/" + rawThumb
            } else rawThumb
            movie.copy(videoUrl = videoUrl, thumbnailUrl = thumbUrl)
        }

        val distinctMovies = processedMovies.distinctBy { it.videoUrl }
        val seasonsMap = distinctMovies.groupBy { movie ->
            movie.season_number ?: movie.videoUrl?.extractSeason() ?: movie.title?.extractSeason() ?: 1
        }

        return seasonsMap.entries.map { entry -> 
            val num = entry.key
            Season(
                number = num, 
                name = "${num}시즌", 
                episodes = entry.value.sortedBy { it.episode_number ?: it.title?.let { t -> t.extractEpisode()?.filter { c -> c.isDigit() }?.toIntOrNull() } ?: 0 }
            )
        }.sortedBy { it.number }
    }

    return emptyList()
}
