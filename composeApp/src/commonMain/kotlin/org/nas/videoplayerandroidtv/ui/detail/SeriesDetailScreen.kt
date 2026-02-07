package org.nas.videoplayerandroidtv.ui.detail

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import org.nas.videoplayerandroidtv.*
import org.nas.videoplayerandroidtv.domain.model.Movie
import org.nas.videoplayerandroidtv.domain.model.Series
import org.nas.videoplayerandroidtv.domain.repository.VideoRepository
import org.nas.videoplayerandroidtv.ui.detail.components.*

// ==========================================================
// 1. Data Models
// ==========================================================
data class Season(val name: String, val episodes: List<Movie>)

private data class SeriesDetailState(
    val seasons: List<Season> = emptyList(),
    val metadata: TmdbMetadata? = null,
    val credits: List<TmdbCast> = emptyList(),
    val isLoading: Boolean = true,
    val selectedSeasonIndex: Int = 0,
    val showEpisodeOverlay: Boolean = false
)

// ==========================================================
// 2. Main Screen
// ==========================================================
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
    var currentPlaybackTime by remember { mutableStateOf(initialPlaybackPosition) }
    val overlayFocusRequester = remember { FocusRequester() }

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

    LaunchedEffect(state.showEpisodeOverlay) {
        // Focus request logic was moved inside EpisodeOverlay for safety to avoid IllegalStateException.
    }

    BackHandler(enabled = state.showEpisodeOverlay || !state.isLoading) {
        if (state.showEpisodeOverlay) {
            state = state.copy(showEpisodeOverlay = false)
        } else {
            onBack()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val backgroundAlpha by animateFloatAsState(if (state.showEpisodeOverlay) 0f else 1f)
        
        Box(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = backgroundAlpha }) {
            val backgroundUrl = state.metadata?.backdropUrl ?: state.metadata?.posterUrl ?: series.posterPath?.let { "$TMDB_IMAGE_BASE$TMDB_POSTER_SIZE_LARGE$it" }
            if (backgroundUrl != null) {
                AsyncImage(
                    model = backgroundUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().alpha(0.5f),
                    contentScale = ContentScale.Crop
                )
                Box(
                    modifier = Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(
                            colors = listOf(Color.Black, Color.Black.copy(alpha = 0.9f), Color.Black.copy(alpha = 0.4f), Color.Transparent),
                            startX = 0f,
                            endX = 1800f
                        )
                    )
                )
            }
        }

        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.Red)
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = backgroundAlpha }
                    .focusProperties { canFocus = !state.showEpisodeOverlay } 
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                        .padding(start = 60.dp, top = 60.dp, end = 40.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = series.title.cleanTitle(includeYear = false),
                        color = Color.White,
                        style = TextStyle(
                            fontSize = 38.sp,
                            fontWeight = FontWeight.Black,
                            lineHeight = 46.sp,
                            letterSpacing = (-0.5).sp,
                            shadow = Shadow(
                                color = Color.Black.copy(alpha = 0.8f),
                                offset = Offset(2f, 2f),
                                blurRadius = 8f
                            )
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    Spacer(Modifier.height(12.dp))
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val year = series.year ?: ""
                        if (year.isNotEmpty()) {
                            Text(year, color = Color.LightGray, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(12.dp))
                        }
                        val rating = series.rating ?: "15+"
                        Surface(
                            color = Color.DarkGray,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = rating,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        val genres = state.metadata?.genreIds?.take(3)?.mapNotNull { genreMap[it] }?.joinToString(", ")
                        if (!genres.isNullOrEmpty()) {
                            Text(genres, color = Color.LightGray, fontSize = 14.sp)
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Text(
                        text = state.metadata?.overview ?: "상세 정보를 불러오는 중입니다...",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        maxLines = 3, // 버튼 표시 공간 확보를 위해 5줄에서 3줄로 수정됨
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(16.dp))

                    if (state.credits.isNotEmpty()) {
                        Text(
                            text = "출연: " + state.credits.take(4).joinToString { it.name },
                            color = Color.Gray,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(Modifier.height(28.dp))

                    // 에피소드 정보 계산
                    val allEpisodes = state.seasons.flatMap { it.episodes }
                    val firstEpisodeFromDiscovery = allEpisodes.firstOrNull()

                    // 동적 로딩에 실패한 경우, Series 객체에 포함된 기본 에피소드를 최종 재생 에피소드로 간주합니다.
                    val fallbackEpisodes = series.episodes.sortedByEpisode()
                    val playableEpisode = firstEpisodeFromDiscovery ?: fallbackEpisodes.firstOrNull()
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        // 1. 영상 보기 버튼: 재생할 영상이 하나라도 있으면 무조건 노출
                        if (playableEpisode != null) {
                            TvButton(
                                text = "영상 보기",
                                icon = Icons.Default.PlayArrow,
                                isPrimary = true,
                                enabled = !state.showEpisodeOverlay,
                                onClick = {
                                    val episodeToPlay = playableEpisode
                                    val initialPlaylist = if (firstEpisodeFromDiscovery != null) {
                                        // 동적으로 발견된 에피소드가 있으면 해당 시즌 플레이리스트를 사용합니다.
                                        state.seasons.find { it.episodes.contains(episodeToPlay) }?.episodes ?: allEpisodes
                                    } else {
                                        // 발견된 에피소드가 없으면 Series 객체의 에피소드 목록을 사용합니다.
                                        fallbackEpisodes
                                    }
                                    onPlay(episodeToPlay, initialPlaylist, currentPlaybackTime)
                                }
                            )
                        } else {
                            Text(
                                text = "재생 가능한 에피소드 파일이 없습니다.",
                                color = Color.Gray,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 10.dp)
                            )
                        }

                        // 2. 회차 정보 버튼: 에피소드가 2개 이상인 시리즈물일 때만 노출
                        // 회차 정보는 동적으로 발견된 시즌/에피소드 목록(allEpisodes)을 기반으로 판단합니다.
                        if (allEpisodes.size > 1) { 
                            TvButton(
                                text = "회차 정보",
                                icon = Icons.AutoMirrored.Filled.List,
                                isPrimary = false,
                                enabled = !state.showEpisodeOverlay,
                                onClick = { state = state.copy(showEpisodeOverlay = true) }
                            )
                        }
                    }
                    
                    Spacer(Modifier.height(40.dp))
                }

                Box(modifier = Modifier.weight(1.2f))
            }
        }

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
                    onPlay(ep, state.seasons[state.selectedSeasonIndex].episodes, 0L)
                },
                onClose = { state = state.copy(showEpisodeOverlay = false) }
            )
        }
    }
}

@Composable
private fun TvButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isPrimary: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val backgroundColor = when {
        !enabled -> if (isPrimary) Color.White.copy(alpha = 0.1f) else Color.Transparent
        isFocused -> Color.White
        isPrimary -> Color.White.copy(alpha = 0.2f)
        else -> Color.Transparent
    }
    val contentColor = when {
        !enabled -> Color.Gray
        isFocused -> Color.Black
        else -> Color.White
    }

    Surface(
        onClick = if (enabled) onClick else ({}),
        color = backgroundColor,
        shape = RoundedCornerShape(8.dp),
        border = if (enabled && !isFocused && !isPrimary) BorderStroke(1.dp, Color.Gray) else null,
        modifier = Modifier
            .onFocusChanged { isFocused = it.isFocused }
            .width(160.dp)
            .height(48.dp)
            .focusable(enabled)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, null, tint = contentColor, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, color = contentColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun EpisodeOverlay(
    seriesTitle: String,
    state: SeriesDetailState,
    focusRequester: FocusRequester,
    onSeasonChange: (Int) -> Unit,
    onEpisodeClick: (Movie) -> Unit,
    onClose: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {
        Row(modifier = Modifier.fillMaxSize().padding(60.dp)) {
            Column(modifier = Modifier.weight(0.35f)) {
                Text(
                    text = seriesTitle.cleanTitle(includeYear = false), 
                    color = Color.White, 
                    style = TextStyle(
                        fontSize = 32.sp, 
                        fontWeight = FontWeight.Bold,
                        lineHeight = 42.sp,
                        shadow = Shadow(Color.Black, Offset(2f, 2f), 4f)
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "시즌 ${state.seasons.size}개",
                    color = Color.Gray,
                    fontSize = 18.sp
                )
                
                Spacer(Modifier.height(40.dp))
                
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
                    items(state.seasons.size) { index ->
                        val isSelected = index == state.selectedSeasonIndex
                        var isFocused by remember { mutableStateOf(false) }

                        // FIX: Move focus request here. When index == 0 is composed, 
                        // the focusRequester is correctly attached and ready to receive requests.
                        if (index == 0) {
                            LaunchedEffect(Unit) {
                                delay(150) // Delay for TV focus stability
                                focusRequester.requestFocus()
                            }
                        }
                        
                        Surface(
                            onClick = { onSeasonChange(index) },
                            color = if (isFocused) Color.White else if (isSelected) Color.White.copy(alpha = 0.2f) else Color.Transparent,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { isFocused = it.isFocused }
                                .then(if (index == 0) Modifier.focusRequester(focusRequester) else Modifier)
                                .focusable()
                        ) {
                            Text(
                                text = state.seasons[index].name,
                                color = if (isFocused) Color.Black else Color.White,
                                modifier = Modifier.padding(16.dp),
                                fontSize = 18.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }
            
            Spacer(Modifier.width(60.dp))
            
            Column(modifier = Modifier.weight(0.65f)) {
                Text("회차 정보", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(24.dp))
                
                val episodes = state.seasons.getOrNull(state.selectedSeasonIndex)?.episodes ?: emptyList()
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 40.dp)
                ) {
                    items(episodes) { movie ->
                        EpisodeItem(
                            movie = movie,
                            seriesMeta = state.metadata,
                            onPlay = { onEpisodeClick(movie) }
                        )
                    }
                }
            }
        }
    }
}

// ==========================================================
// 3. Utils & Loaders
// ==========================================================

private fun List<Movie>.sortedByEpisode(): List<Movie> = this.sortedWith(
    compareBy<Movie> { it.title?.extractSeason() ?: 1 }
        .thenBy { it.title?.extractEpisode()?.filter { char -> char.isDigit() }?.toIntOrNull() ?: 0 }
)

private suspend fun loadSeasons(series: Series, repository: VideoRepository): List<Season> {
    val defaultSeason = if (series.episodes.isNotEmpty()) {
        Season("에피소드", series.episodes.sortedByEpisode())
    } else null
    val path = series.fullPath ?: return listOfNotNull(defaultSeason)
    return try {
        val content = repository.getCategoryList(path)
        val subFolders = content.filter { it.movies.isNullOrEmpty() && !it.name.isNullOrBlank() }
        val directMovies = content.flatMap { it.movies ?: emptyList() }
        when {
            subFolders.isNotEmpty() -> {
                coroutineScope {
                    val loadedSeasons = subFolders.map { folder ->
                        async {
                            val folderMovies = repository.getCategoryList("$path/${folder.name ?: ""}").flatMap { it.movies ?: emptyList() }
                            if (folderMovies.isNotEmpty()) Season(folder.name ?: "알 수 없음", folderMovies.sortedByEpisode()) else null
                        }
                    }.awaitAll().filterNotNull().sortedBy { it.name }
                    if (loadedSeasons.isEmpty()) listOfNotNull(defaultSeason) else loadedSeasons
                }
            }
            directMovies.isNotEmpty() -> listOf(Season("에피소드", directMovies.sortedByEpisode()))
            else -> listOfNotNull(defaultSeason)
        }
    } catch (_: Exception) {
        listOfNotNull(defaultSeason)
    }
}

private suspend fun loadMetadataAndCredits(title: String): Pair<TmdbMetadata?, List<TmdbCast>> {
    val metadata = fetchTmdbMetadata(title)
    val credits = if (metadata.tmdbId != null) {
        fetchTmdbCredits(metadata.tmdbId, metadata.mediaType)
    } else emptyList()
    return metadata to credits
}

private val genreMap = mapOf(
    28 to "액션", 12 to "모험", 16 to "애니", 35 to "코미디", 80 to "범죄",
    99 to "다큐", 18 to "드라마", 10751 to "가족", 14 to "판타지", 36 to "역사",
    27 to "공포", 10402 to "음악", 9648 to "미스터리", 10749 to "로맨스", 878 to "SF",
    10770 to "TV영화", 53 to "스릴러", 10752 to "전쟁", 37 to "서부",
    10759 to "액션&어드벤처", 10762 to "키즈", 10763 to "뉴스", 10764 to "리얼리티",
    10765 to "SF&판타지", 10766 to "소프", 10767 to "토크", 10768 to "전쟁&정치"
)