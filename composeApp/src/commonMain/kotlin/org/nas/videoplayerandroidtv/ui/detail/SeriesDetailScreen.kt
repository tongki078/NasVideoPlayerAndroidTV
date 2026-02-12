package org.nas.videoplayerandroidtv.ui.detail

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.nas.videoplayerandroidtv.*
import org.nas.videoplayerandroidtv.domain.model.Movie
import org.nas.videoplayerandroidtv.domain.model.Series
import org.nas.videoplayerandroidtv.domain.repository.VideoRepository
import org.nas.videoplayerandroidtv.ui.detail.components.*

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SeriesDetailScreen(
    series: Series,
    repository: VideoRepository,
    initialPlaybackPosition: Long = 0L,
    initialDuration: Long = 0L, 
    onPositionUpdate: (Long) -> Unit,
    onBack: () -> Unit,
    onPlay: (Movie, List<Movie>, Long) -> Unit,
    onPreviewPlay: (Movie) -> Unit = {}
) {
    var state by remember { mutableStateOf(SeriesDetailState()) }
    
    // 로드된 시즌 정보가 있으면 거기서 에피소드를 가져오고, 없으면 초기 전달된 에피소드 사용
    val allEpisodes = remember(state.seasons, series.episodes) {
        if (state.seasons.isNotEmpty()) state.seasons.flatMap { it.episodes } else series.episodes
    }
    
    // 현재 재생 가능한 에피소드 결정
    val playableEpisode = remember(allEpisodes, initialPlaybackPosition) {
        if (initialPlaybackPosition > 0 && allEpisodes.isNotEmpty()) {
            val resumeTarget = series.episodes.firstOrNull()
            allEpisodes.find { it.videoUrl == resumeTarget?.videoUrl } ?: allEpisodes.firstOrNull()
        } else {
            allEpisodes.firstOrNull()
        }
    }
    
    val resumeButtonFocusRequester = remember { FocusRequester() }
    val playButtonFocusRequester = remember { FocusRequester() }
    val infoButtonFocusRequester = remember { FocusRequester() }
    val overlayFocusRequester = remember { FocusRequester() }

    val isUhd = remember(series.fullPath) {
        series.fullPath?.contains("UHD", ignoreCase = true) == true
    }

    LaunchedEffect(series) {
        state = state.copy(isLoading = true)
        coroutineScope {
            val seasonsDeferred = async { loadSeasons(series, repository) }
            val metaDeferred = async { loadMetadataAndCredits(series.title) }

            val seasons = seasonsDeferred.await()
            val (metadata, credits) = metaDeferred.await()

            state = state.copy(
                seasons = seasons,
                metadata = metadata,
                credits = credits,
                isLoading = false,
                selectedSeasonIndex = 0
            )
        }
    }

    LaunchedEffect(state.isLoading, state.showEpisodeOverlay) {
        if (!state.isLoading && !state.showEpisodeOverlay) {
            delay(500)
            try {
                if (playableEpisode != null) {
                    if (initialPlaybackPosition > 0) {
                        resumeButtonFocusRequester.requestFocus()
                    } else {
                        playButtonFocusRequester.requestFocus()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    BackHandler(enabled = state.showEpisodeOverlay || !state.isLoading) {
        if (state.showEpisodeOverlay) state = state.copy(showEpisodeOverlay = false)
        else onBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusProperties { 
                enter = { 
                    if (initialPlaybackPosition > 0) resumeButtonFocusRequester else playButtonFocusRequester 
                } 
            }
            .focusGroup()
    ) {
        // 배경 이미지
        Box(modifier = Modifier.fillMaxSize().alpha(if (state.showEpisodeOverlay) 0f else 1f)) {
            val backgroundUrl = state.metadata?.backdropUrl ?: state.metadata?.posterUrl ?: series.posterPath?.let { "$TMDB_IMAGE_BASE$TMDB_POSTER_SIZE_LARGE$it" }
            if (backgroundUrl != null) {
                AsyncImage(model = backgroundUrl, contentDescription = null, modifier = Modifier.fillMaxSize().alpha(0.5f), contentScale = ContentScale.Crop)
                Box(modifier = Modifier.fillMaxSize().background(Brush.horizontalGradient(colors = listOf(Color.Black, Color.Black.copy(alpha = 0.9f), Color.Black.copy(alpha = 0.4f), Color.Transparent), endX = 1800f)))
            }
        }

        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1.5f)
                    .padding(start = 60.dp, top = 60.dp, end = 40.dp)
                    .focusProperties { 
                        canFocus = !state.showEpisodeOverlay 
                    }
            ) {
                // 제목
                Text(
                    text = series.title.cleanTitle(includeYear = false), 
                    color = Color.White, 
                    style = TextStyle(fontSize = 42.sp, fontWeight = FontWeight.Black, lineHeight = 50.sp, shadow = Shadow(Color.Black.copy(alpha = 0.8f), Offset(2f, 2f), 8f)), 
                    maxLines = 2, 
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                
                // 정보 뱃지
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val displayYear = state.metadata?.year ?: series.year
                    if (!displayYear.isNullOrEmpty()) {
                        InfoBadge(text = displayYear)
                    }
                    
                    InfoBadge(text = series.rating ?: "15+", color = Color.Gray.copy(alpha = 0.3f))
                    
                    if (isUhd) {
                        InfoBadge(text = "UHD", color = Color(0xFFE50914).copy(alpha = 0.2f), textColor = Color(0xFFE50914))
                    }
                    
                    state.metadata?.genreIds?.take(3)?.mapNotNull { genreMap[it] }?.forEach { genre ->
                        InfoBadge(text = genre, color = Color.White.copy(alpha = 0.1f))
                    }
                }
                Spacer(Modifier.height(12.dp))

                // 줄거리
                val overview = if (state.isLoading && state.metadata?.overview.isNullOrEmpty()) {
                    "상세 정보를 불러오는 중입니다..."
                } else {
                    state.metadata?.overview?.replace(Regex("[\\r\\n]+"), " ")?.trim() ?: "정보가 없습니다."
                }

                Text(
                    text = overview, 
                    color = Color.White.copy(alpha = 0.8f), 
                    fontSize = 15.sp, 
                    lineHeight = 22.sp, 
                    maxLines = 3, 
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                
                // 출연진
                if (state.credits.isNotEmpty()) {
                    Text(
                        text = "출연: " + state.credits.take(4).joinToString { it.name }, 
                        color = Color.Gray, 
                        fontSize = 13.sp, 
                        maxLines = 1, 
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(16.dp))
                } else {
                    Spacer(Modifier.height(8.dp))
                }

                // 버튼 섹션
                Box(modifier = Modifier.fillMaxWidth().height(150.dp)) {
                    if (playableEpisode != null) {
                        Column(
                            modifier = Modifier.focusGroup().align(Alignment.CenterStart),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (initialPlaybackPosition > 0) {
                                val epTitle = playableEpisode.title ?: ""
                                val seasonNum = epTitle.extractSeason()
                                val episodeStr = epTitle.extractEpisode()
                                
                                val resumeText = if (episodeStr != null) "시즌 $seasonNum : $episodeStr" else "계속 보기"
                                val progress = if (initialDuration > 0) initialPlaybackPosition.toFloat() / initialDuration else 0f

                                TvButton(
                                    text = resumeText,
                                    icon = Icons.Default.PlayArrow,
                                    isPrimary = true,
                                    progress = progress, 
                                    modifier = Modifier.focusRequester(resumeButtonFocusRequester),
                                    onClick = { onPlay(playableEpisode, allEpisodes, initialPlaybackPosition) }
                                )
                            }

                            TvButton(
                                text = if (initialPlaybackPosition > 0) "처음부터" else "재생",
                                icon = if (initialPlaybackPosition > 0) Icons.Default.Refresh else Icons.Default.PlayArrow,
                                isPrimary = initialPlaybackPosition <= 0,
                                modifier = Modifier.focusRequester(playButtonFocusRequester),
                                onClick = { onPlay(playableEpisode, allEpisodes, 0L) }
                            )

                            if (state.seasons.isNotEmpty() && allEpisodes.size > 1) { 
                                TvButton(
                                    text = "회차 정보",
                                    icon = Icons.AutoMirrored.Filled.List,
                                    isPrimary = false,
                                    modifier = Modifier.focusRequester(infoButtonFocusRequester),
                                    onClick = { state = state.copy(showEpisodeOverlay = true) }
                                )
                            }
                        }
                    } else if (state.isLoading) {
                        Box(modifier = Modifier.fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                            CircularProgressIndicator(color = Color.Red, modifier = Modifier.size(32.dp))
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                            Text(
                                text = "재생 가능한 영상을 찾을 수 없습니다.",
                                color = Color.Gray,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
            Box(modifier = Modifier.weight(1f))
        }

        if (state.showEpisodeOverlay && state.seasons.isNotEmpty()) {
            AnimatedVisibility(visible = state.showEpisodeOverlay, enter = fadeIn() + slideInHorizontally(initialOffsetX = { it }), exit = fadeOut() + slideOutHorizontally(targetOffsetX = { it }), modifier = Modifier.zIndex(10f)) {
                EpisodeOverlay(seriesTitle = series.title, state = state, focusRequester = overlayFocusRequester, onSeasonChange = { state = state.copy(selectedSeasonIndex = it) }, onEpisodeClick = { ep ->
                    onPositionUpdate(0L)
                    onPlay(ep, state.seasons.getOrNull(state.selectedSeasonIndex)?.episodes ?: emptyList(), 0L)
                }, onClose = { state = state.copy(showEpisodeOverlay = false) })
            }
        }
    }
}

@Composable
private fun InfoBadge(text: String, color: Color = Color.White.copy(alpha = 0.15f), textColor: Color = Color.White) {
    Surface(
        color = color,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
        modifier = Modifier.padding(end = 8.dp)
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun TvButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isPrimary: Boolean,
    progress: Float? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) 1.05f else 1.0f, label = "ButtonScale")
    val backgroundColor = when {
        isFocused -> Color.White
        isPrimary -> Color.White.copy(alpha = 0.2f)
        else -> Color.Transparent
    }
    val contentColor = if (isFocused) Color.Black else Color.White

    Surface(
        onClick = onClick,
        color = backgroundColor,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(2.dp, if (isFocused) Color.White else Color.Gray.copy(alpha = 0.3f)),
        modifier = modifier
            .onFocusChanged { isFocused = it.isFocused }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .widthIn(min = 112.dp, max = 252.dp)
            .height(40.dp)
            .focusable()
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Icon(icon, null, tint = contentColor, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(text = text, color = contentColor, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            
            if (progress != null && progress > 0f) {
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.Gray.copy(alpha = 0.3f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(if (isFocused) Color.Red else Color.Red.copy(alpha = 0.7f))
                    )
                }
            }
        }
    }
}

data class Season(val name: String, val episodes: List<Movie>)
private data class SeriesDetailState(val seasons: List<Season> = emptyList(), val metadata: TmdbMetadata? = null, val credits: List<TmdbCast> = emptyList(), val isLoading: Boolean = true, val selectedSeasonIndex: Int = 0, val showEpisodeOverlay: Boolean = false)

@Composable
private fun EpisodeOverlay(seriesTitle: String, state: SeriesDetailState, focusRequester: FocusRequester, onSeasonChange: (Int) -> Unit, onEpisodeClick: (Movie) -> Unit, onClose: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Row(modifier = Modifier.fillMaxSize().padding(60.dp)) {
            Column(modifier = Modifier.weight(0.35f)) {
                Text(text = seriesTitle.cleanTitle(includeYear = false), color = Color.White, style = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 42.sp, shadow = Shadow(Color.Black, Offset(2f, 2f), 4f)), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Text(text = "시즌 ${state.seasons.size}개", color = Color.Gray, fontSize = 18.sp)
                Spacer(Modifier.height(40.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
                    items(state.seasons.size) { index ->
                        val isSelected = index == state.selectedSeasonIndex
                        var isFocused by remember { mutableStateOf(false) }
                        if (index == 0) { LaunchedEffect(Unit) { delay(150); focusRequester.requestFocus() } }
                        Surface(
                            onClick = { onSeasonChange(index) }, 
                            color = if (isFocused) Color.White else if (isSelected) Color.White.copy(alpha = 0.2f) else Color.Transparent, 
                            shape = RoundedCornerShape(8.dp), 
                            border = BorderStroke(2.dp, if (isFocused) Color.White else Color.Transparent),
                            modifier = Modifier.fillMaxWidth().onFocusChanged { isFocused = it.isFocused }.then(if (index == 0) Modifier.focusRequester(focusRequester) else Modifier).focusable()
                        ) {
                            Text(text = state.seasons[index].name, color = if (isFocused) Color.Black else Color.White, modifier = Modifier.padding(16.dp), fontSize = 18.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium)
                        }
                    }
                }
            }
            Spacer(Modifier.width(60.dp))
            Column(modifier = Modifier.weight(0.65f)) {
                Text("회차 정보", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(24.dp))
                val episodes = state.seasons.getOrNull(state.selectedSeasonIndex)?.episodes ?: emptyList()
                LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                    items(episodes) { movie -> EpisodeItem(movie = movie, seriesMeta = state.metadata, onPlay = { onEpisodeClick(movie) }) }
                }
            }
        }
    }
}

private fun List<Movie>.sortedByEpisode(): List<Movie> = this.sortedWith(compareBy<Movie> { it.title?.extractSeason() ?: 1 }.thenBy { it.title?.extractEpisode()?.filter { char -> char.isDigit() }?.toIntOrNull() ?: 0 })

private suspend fun loadSeasons(series: Series, repository: VideoRepository): List<Season> {
    val defaultSeason = if (series.episodes.isNotEmpty()) Season("에피소드", series.episodes.sortedByEpisode()) else null
    val path = series.fullPath ?: return listOfNotNull(defaultSeason)
    
    return try {
        val content = repository.getCategoryList(path)
        if (content.isEmpty()) return listOfNotNull(defaultSeason)

        val seasonsResult = mutableListOf<Season>()
        
        // 서버에서 이미 에피소드를 담아서 보낸 경우 (movies가 있는 항목들)
        val readySeasons = content.filter { !it.movies.isNullOrEmpty() }.map { 
            Season(it.name ?: "알 수 없음", it.movies!!.sortedByEpisode())
        }
        seasonsResult.addAll(readySeasons)
        
        // 서버에서 폴더 정보만 보낸 경우 (기존 방식 유지)
        val emptyFolders = content.filter { it.movies.isNullOrEmpty() && !it.name.isNullOrBlank() }
        if (emptyFolders.isNotEmpty()) {
            coroutineScope {
                val loaded = emptyFolders.map { folder -> async {
                    val folderPath = folder.path ?: "$path/${folder.name}"
                    val folderMovies = repository.getCategoryList(folderPath).flatMap { it.movies ?: emptyList() }
                    if (folderMovies.isNotEmpty()) Season(folder.name ?: "알 수 없음", folderMovies.sortedByEpisode()) else null
                } }.awaitAll().filterNotNull()
                seasonsResult.addAll(loaded)
            }
        }
        
        // 결과가 비어있으면 기본 데이터 사용
        if (seasonsResult.isEmpty()) listOfNotNull(defaultSeason) else seasonsResult.distinctBy { it.name }
    } catch (_: Exception) { 
        listOfNotNull(defaultSeason) 
    }
}

private suspend fun loadMetadataAndCredits(title: String): Pair<TmdbMetadata?, List<TmdbCast>> {
    val metadata = fetchTmdbMetadata(title)
    val credits = if (metadata.tmdbId != null) fetchTmdbCredits(metadata.tmdbId, metadata.mediaType) else emptyList()
    return metadata to credits
}

private val genreMap = mapOf(28 to "액션", 12 to "모험", 16 to "애니", 35 to "코미디", 80 to "범죄", 99 to "다큐", 18 to "드라마", 10751 to "가족", 14 to "판타지", 36 to "역사", 27 to "공포", 10402 to "음악", 9648 to "미스터리", 10749 to "로맨스", 878 to "SF", 10770 to "TV영화", 53 to "스릴러", 10752 to "전쟁", 37 to "서부", 10759 to "액션&어드벤처", 10762 to "키즈", 10763 to "뉴스", 10764 to "리얼리티", 10765 to "SF&판타지", 10766 to "소프", 10767 to "토크", 10768 to "전쟁&정치")
