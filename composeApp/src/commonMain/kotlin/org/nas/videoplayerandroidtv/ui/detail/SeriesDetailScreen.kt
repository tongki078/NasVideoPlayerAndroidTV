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
    onPositionUpdate: (Long) -> Unit,
    onBack: () -> Unit,
    onPlay: (Movie, List<Movie>, Long) -> Unit,
    onPreviewPlay: (Movie) -> Unit = {}
) {
    var state by remember { mutableStateOf(SeriesDetailState()) }
    
    // 시청 중인 회차 정보 (App.kt에서 보낸 첫 번째 에피소드 기준)
    val currentWatchingMovie = remember(series) { series.episodes.firstOrNull() }
    
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
                if (initialPlaybackPosition > 0) {
                    resumeButtonFocusRequester.requestFocus()
                } else {
                    playButtonFocusRequester.requestFocus()
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
                enter = { if (initialPlaybackPosition > 0) resumeButtonFocusRequester else playButtonFocusRequester } 
            }
            .focusGroup()
    ) {
        // 배경 이미지 레이어
        Box(modifier = Modifier.fillMaxSize().alpha(if (state.showEpisodeOverlay) 0f else 1f)) {
            val backgroundUrl = state.metadata?.backdropUrl ?: state.metadata?.posterUrl ?: series.posterPath?.let { "$TMDB_IMAGE_BASE$TMDB_POSTER_SIZE_LARGE$it" }
            if (backgroundUrl != null) {
                AsyncImage(model = backgroundUrl, contentDescription = null, modifier = Modifier.fillMaxSize().alpha(0.5f), contentScale = ContentScale.Crop)
                Box(modifier = Modifier.fillMaxSize().background(Brush.horizontalGradient(colors = listOf(Color.Black, Color.Black.copy(alpha = 0.9f), Color.Black.copy(alpha = 0.4f), Color.Transparent), endX = 1800f)))
            }
        }

        Row(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
                    .padding(start = 60.dp, top = 60.dp, end = 40.dp)
                    .focusProperties { 
                        canFocus = !state.showEpisodeOverlay 
                    }
            ) {
                item {
                    Text(text = series.title.cleanTitle(false), color = Color.White, style = TextStyle(fontSize = 38.sp, fontWeight = FontWeight.Black, lineHeight = 46.sp, shadow = Shadow(Color.Black.copy(alpha = 0.8f), Offset(2f, 2f), 8f)), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp))
                }
                
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!series.year.isNullOrEmpty()) {
                            Text(series.year!!, color = Color.LightGray, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(16.dp))
                        }
                        
                        Text(text = series.rating ?: "15+", color = Color.LightGray, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        
                        if (isUhd) {
                            Spacer(Modifier.width(12.dp))
                            Text(text = "UHD", color = Color(0xFFE50914), fontSize = 16.sp, fontWeight = FontWeight.Black)
                        }
                        
                        Spacer(Modifier.width(16.dp))
                        state.metadata?.genreIds?.take(3)?.mapNotNull { genreMap[it] }?.joinToString(", ")?.let {
                            if (it.isNotEmpty()) Text(it, color = Color.LightGray, fontSize = 14.sp)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }

                item {
                    Text(
                        text = if (state.isLoading) "상세 정보를 불러오는 중입니다..." else (state.metadata?.overview ?: "정보가 없습니다."), 
                        color = Color.White.copy(alpha = 0.8f), 
                        fontSize = 14.sp, 
                        lineHeight = 22.sp, 
                        maxLines = 3, 
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(32.dp))
                }
                
                item {
                    if (state.credits.isNotEmpty()) {
                        Text(text = "출연: " + state.credits.take(4).joinToString { it.name }, color = Color.Gray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(32.dp))
                    }
                }

                item {
                    val allEpisodes = state.seasons.flatMap { it.episodes }
                    // 시청 중인 영상 정보를 우선적으로 사용하여 버튼 노출 보장
                    val playableEpisode = currentWatchingMovie ?: allEpisodes.firstOrNull()
                    
                    Column(
                        modifier = Modifier.focusGroup(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (playableEpisode != null) {
                            // 1. 이어서 보기 버튼 (시즌 정보 및 게이지 포함)
                            if (initialPlaybackPosition > 0) {
                                val epTitle = playableEpisode.title ?: ""
                                val season = epTitle.extractSeason()
                                val episode = epTitle.extractEpisode()
                                val resumeText = "시즌 $season : $episode 재생"
                                
                                // 진행률 계산 (DB에 저장된 duration이 있다면 활용, 없으면 임시 0.5f)
                                // 실제 서비스에서는 App.kt에서 넘겨받은 watchHistory를 통해 계산하거나 movie 객체에 duration을 담아야 함
                                val progress = 0.5f 

                                TvButton(
                                    text = resumeText,
                                    icon = Icons.Default.PlayArrow,
                                    isPrimary = true,
                                    progress = progress,
                                    modifier = Modifier.focusRequester(resumeButtonFocusRequester),
                                    onClick = {
                                        val playlist = if (allEpisodes.isNotEmpty()) {
                                            state.seasons.find { it.episodes.contains(playableEpisode) }?.episodes ?: allEpisodes
                                        } else {
                                            listOf(playableEpisode)
                                        }
                                        onPlay(playableEpisode, playlist, initialPlaybackPosition)
                                    }
                                )
                            }

                            // 2. 처음부터 보기 / 재생 버튼
                            TvButton(
                                text = if (initialPlaybackPosition > 0) "처음부터 보기" else "재생",
                                icon = if (initialPlaybackPosition > 0) Icons.Default.Refresh else Icons.Default.PlayArrow,
                                isPrimary = initialPlaybackPosition <= 0,
                                modifier = Modifier.focusRequester(playButtonFocusRequester),
                                onClick = {
                                    val playlist = if (allEpisodes.isNotEmpty()) {
                                        state.seasons.find { it.episodes.contains(playableEpisode) }?.episodes ?: allEpisodes
                                    } else {
                                        listOf(playableEpisode)
                                    }
                                    onPlay(playableEpisode, playlist, 0L)
                                }
                            )
                        }

                        // 3. 회차 정보 버튼 (데이터가 로드된 후에만 표시)
                        if (state.seasons.isNotEmpty()) { 
                            TvButton(
                                text = "회차 정보 보기",
                                icon = Icons.AutoMirrored.Filled.List,
                                isPrimary = false,
                                modifier = Modifier.focusRequester(infoButtonFocusRequester),
                                onClick = { state = state.copy(showEpisodeOverlay = true) }
                            )
                        }
                    }
                }
                
                // 포커스 트랩 방지 및 레이아웃 안정성을 위한 하단 여백
                item {
                    Spacer(Modifier.height(150.dp))
                }
            }
            Box(modifier = Modifier.weight(1.2f))
        }

        // 로딩 인디케이터
        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { 
                CircularProgressIndicator(color = Color.Red) 
            }
        }

        // 회차 정보 오버레이
        if (state.showEpisodeOverlay && state.seasons.isNotEmpty()) {
            AnimatedVisibility(
                visible = state.showEpisodeOverlay, 
                enter = fadeIn() + slideInHorizontally(initialOffsetX = { it }), 
                exit = fadeOut() + slideOutHorizontally(targetOffsetX = { it }), 
                modifier = Modifier.zIndex(10f)
            ) {
                EpisodeOverlay(
                    seriesTitle = series.title, 
                    state = state, 
                    focusRequester = overlayFocusRequester, 
                    onSeasonChange = { state = state.copy(selectedSeasonIndex = it) }, 
                    onEpisodeClick = { ep ->
                        onPositionUpdate(0L)
                        onPlay(ep, state.seasons.getOrNull(state.selectedSeasonIndex)?.episodes ?: emptyList(), 0L)
                    }, 
                    onClose = { state = state.copy(showEpisodeOverlay = false) }
                )
            }
        }
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
            .widthIn(min = 240.dp, max = 450.dp)
            .height(56.dp)
            .focusable()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                Icon(icon, null, tint = contentColor, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    text = text, 
                    color = contentColor, 
                    fontSize = 16.sp, 
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            if (progress != null && progress > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color.Gray.copy(alpha = 0.3f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(Color.Red)
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
                Text(text = seriesTitle.cleanTitle(false), color = Color.White, style = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 42.sp, shadow = Shadow(Color.Black, Offset(2f, 2f), 4f)), maxLines = 2, overflow = TextOverflow.Ellipsis)
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
        val subFolders = content.filter { it.movies.isNullOrEmpty() && !it.name.isNullOrBlank() }
        val directMovies = content.flatMap { it.movies ?: emptyList() }
        when {
            subFolders.isNotEmpty() -> {
                coroutineScope {
                    val loadedSeasons = subFolders.map { folder -> async {
                        val folderMovies = repository.getCategoryList("$path/${folder.name ?: ""}").flatMap { it.movies ?: emptyList() }
                        if (folderMovies.isNotEmpty()) Season(folder.name ?: "알 수 없음", folderMovies.sortedByEpisode()) else null
                    } }.awaitAll().filterNotNull().sortedBy { it.name }
                    if (loadedSeasons.isEmpty()) listOfNotNull(defaultSeason) else loadedSeasons
                }
            }
            directMovies.isNotEmpty() -> listOf(Season("에피소드", directMovies.sortedByEpisode()))
            else -> listOfNotNull(defaultSeason)
        }
    } catch (_: Exception) { listOfNotNull(defaultSeason) }
}

private suspend fun loadMetadataAndCredits(title: String): Pair<TmdbMetadata?, List<TmdbCast>> {
    val metadata = fetchTmdbMetadata(title)
    val credits = if (metadata.tmdbId != null) fetchTmdbCredits(metadata.tmdbId, metadata.mediaType) else emptyList()
    return metadata to credits
}

private val genreMap = mapOf(28 to "액션", 12 to "모험", 16 to "애니", 35 to "코미디", 80 to "범죄", 99 to "다큐", 18 to "드라마", 10751 to "가족", 14 to "판타지", 36 to "역사", 27 to "공포", 10402 to "음악", 9648 to "미스터리", 10749 to "로맨스", 878 to "SF", 10770 to "TV영화", 53 to "스릴러", 10752 to "전쟁", 37 to "서부", 10759 to "액션&어드벤처", 10762 to "키즈", 10763 to "뉴스", 10764 to "리얼리티", 10765 to "SF&판타지", 10766 to "소프", 10767 to "토크", 10768 to "전쟁&정치")
