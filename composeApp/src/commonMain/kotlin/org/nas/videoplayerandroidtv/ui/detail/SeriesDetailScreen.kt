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
import org.nas.videoplayerandroidtv.*
import org.nas.videoplayerandroidtv.data.network.NasApiClient
import org.nas.videoplayerandroidtv.domain.model.Movie
import org.nas.videoplayerandroidtv.domain.model.Series
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
    val playButtonFocusRequester = remember { FocusRequester() }
    val resumeButtonFocusRequester = remember { FocusRequester() }
    val infoButtonFocusRequester = remember { FocusRequester() }
    val overlayFocusRequester = remember { FocusRequester() }

    LaunchedEffect(series) {
        state = state.copy(isLoading = true)
        
        val loadedData = withContext(Dispatchers.IO) {
            val seasonsDeferred = async { loadSeasons(series, repository) }
            val metaDeferred = async { loadMetadataAndCredits(series.title) }
            
            val seasons = seasonsDeferred.await()
            val (metadata, credits) = metaDeferred.await()
            Triple(seasons, metadata, credits)
        }

        state = state.copy(
            seasons = loadedData.first,
            metadata = loadedData.second,
            credits = loadedData.third,
            isLoading = false
        )
    }

    LaunchedEffect(state.isLoading) {
        if (!state.isLoading) {
            delay(500) 
            try {
                val playableEpisode = state.seasons.flatMap { it.episodes }.firstOrNull()
                if (playableEpisode != null) {
                    if (initialPlaybackPosition > 0) {
                        resumeButtonFocusRequester.requestFocus()
                    } else {
                        playButtonFocusRequester.requestFocus()
                    }
                }
            } catch (e: Exception) { }
        }
    }

    BackHandler(enabled = state.showEpisodeOverlay, onBack = { state = state.copy(showEpisodeOverlay = false) })

    if (state.isLoading) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.Red)
        }
    } else {
        val allEpisodes = state.seasons.flatMap { it.episodes }
        val playableEpisode = allEpisodes.firstOrNull()

        Box(modifier = Modifier.fillMaxSize().background(color = Color.Black)) {
            state.metadata?.backdropUrl?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().alpha(0.4f),
                    contentScale = ContentScale.Crop
                )
            }

            Box(
                modifier = Modifier.fillMaxSize().background(
                    brush = Brush.horizontalGradient(
                        0.0f to Color.Black,
                        0.5f to Color.Black.copy(alpha = 0.8f),
                        1.0f to Color.Transparent
                    )
                )
            )

            Row(modifier = Modifier.fillMaxSize().padding(horizontal = 60.dp, vertical = 40.dp)) {
                Column(modifier = Modifier.weight(1.2f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                    Text(
                        text = series.title.cleanTitle(includeYear = false),
                        color = Color.White,
                        style = TextStyle(fontSize = 48.sp, fontWeight = FontWeight.Bold, shadow = Shadow(color = Color.Black, offset = Offset(4f, 4f), blurRadius = 8f)),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        state.metadata?.year?.let { InfoBadge(text = it) }
                        if (state.seasons.isNotEmpty()) InfoBadge(text = "시즌 ${state.seasons.size}개")
                        state.metadata?.genreIds?.mapNotNull { genreMap[it] }?.take(3)?.forEach { InfoBadge(text = it, color = Color.White.copy(alpha = 0.1f)) }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    val overview = state.metadata?.overview?.replace(Regex("[\\r\\n]+"), " ")?.trim() ?: "정보가 없습니다."
                    Text(
                        text = overview,
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (state.credits.isNotEmpty()) {
                        Text(
                            text = "출연: " + state.credits.take(4).joinToString { it.name },
                            color = Color.Gray,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    } else {
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Box(modifier = Modifier.fillMaxWidth().height(150.dp)) {
                        if (playableEpisode != null) {
                            Column(modifier = Modifier.align(Alignment.CenterStart), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                if (state.seasons.isNotEmpty()) {
                                    TvButton(
                                        text = "회차 정보",
                                        icon = Icons.AutoMirrored.Filled.List,
                                        isPrimary = false,
                                        modifier = Modifier.focusRequester(infoButtonFocusRequester),
                                        onClick = { state = state.copy(showEpisodeOverlay = true) }
                                    )
                                }
                            }
                        } else {
                            Box(modifier = Modifier.fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                                Text(text = "재생 가능한 영상을 찾을 수 없습니다.", color = Color.Gray, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
                Box(modifier = Modifier.weight(1f))
            }

            if (state.showEpisodeOverlay && state.seasons.isNotEmpty()) {
                AnimatedVisibility(
                    visible = state.showEpisodeOverlay,
                    enter = fadeIn() + slideInHorizontally { it },
                    exit = fadeOut() + slideOutHorizontally { it },
                    modifier = Modifier.zIndex(10f)
                ) {
                    EpisodeOverlay(
                        seriesTitle = series.title,
                        state = state,
                        focusRequester = overlayFocusRequester,
                        onSeasonChange = { state = state.copy(selectedSeasonIndex = it) },
                        onEpisodeClick = { ep ->
                            onPositionUpdate(0L)
                            val currentEpisodes = state.seasons.getOrNull(state.selectedSeasonIndex)?.episodes ?: emptyList()
                            onPlay(ep, currentEpisodes, 0L)
                        },
                        onClose = { state = state.copy(showEpisodeOverlay = false) }
                    )
                }
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
        Text(text = text, color = textColor, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
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
    val scale by animateFloatAsState(targetValue = if (isFocused) 1.05f else 1.0f, label = "ButtonScale")
    val backgroundColor = when { isFocused -> Color.White; isPrimary -> Color.White.copy(alpha = 0.2f); else -> Color.Transparent }
    val contentColor = if (isFocused) Color.Black else Color.White
    Surface(
        onClick = onClick,
        color = backgroundColor,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(width = 2.dp, color = if (isFocused) Color.White else Color.Gray.copy(alpha = 0.3f)),
        modifier = modifier
            .onFocusChanged { isFocused = it.isFocused }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .widthIn(min = 112.dp, max = 252.dp)
            .height(40.dp)
            .focusable()
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text(text = text, color = contentColor, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (progress != null && progress > 0f) {
                Spacer(modifier = Modifier.weight(1f))
                Box(modifier = Modifier.width(36.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(color = Color.Gray.copy(alpha = 0.3f))) {
                    Box(modifier = Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(color = if (isFocused) Color.Red else Color.Red.copy(alpha = 0.7f)))
                }
            }
        }
    }
}

data class Season(val name: String, val episodes: List<Movie>)
private data class SeriesDetailState(
    val seasons: List<Season> = emptyList(),
    val metadata: TmdbMetadata? = null,
    val credits: List<TmdbCast> = emptyList(),
    val isLoading: Boolean = true,
    val selectedSeasonIndex: Int = 0,
    val showEpisodeOverlay: Boolean = false
)

@Composable
private fun EpisodeOverlay(
    seriesTitle: String,
    state: SeriesDetailState,
    focusRequester: FocusRequester,
    onSeasonChange: (Int) -> Unit,
    onEpisodeClick: (Movie) -> Unit,
    onClose: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Row(modifier = Modifier.fillMaxSize().padding(60.dp)) {
            // 왼쪽: 시즌 리스트 (넷플릭스 스타일)
            Column(modifier = Modifier.weight(0.35f)) {
                Text(
                    text = seriesTitle.cleanTitle(includeYear = false),
                    color = Color.White,
                    style = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 42.sp, shadow = Shadow(color = Color.Black, offset = Offset(2f, 2f), blurRadius = 4f)),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "시즌 ${state.seasons.size}개", color = Color.Gray, fontSize = 18.sp)
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
                LaunchedEffect(Unit) { delay(150); try { focusRequester.requestFocus() } catch(_: Exception) {} }
            }

            Spacer(modifier = Modifier.width(60.dp))

            // 오른쪽: 에피소드 리스트
            Column(modifier = Modifier.weight(0.65f)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(text = state.seasons.getOrNull(state.selectedSeasonIndex)?.name ?: "회차 정보", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(text = "[뒤로가기] 닫기", color = Color.Gray, fontSize = 14.sp)
                }
                Spacer(Modifier.height(24.dp))
                val episodes = state.seasons.getOrNull(state.selectedSeasonIndex)?.episodes ?: emptyList()
                LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                    items(episodes) { movie ->
                        EpisodeItem(movie = movie, seriesMeta = state.metadata, onPlay = { onEpisodeClick(movie) })
                    }
                }
            }
        }
    }
}

private fun List<Movie>.sortedByEpisode(): List<Movie> = sortedWith(
    compareBy<Movie> { it.title?.extractSeason() ?: 1 }
        .thenBy { it.title?.extractEpisode()?.filter { c -> c.isDigit() }?.toIntOrNull() ?: 0 }
)

private suspend fun loadSeasons(series: Series, repository: VideoRepository): List<Season> = coroutineScope {
    val collectedEpisodes = mutableListOf<Movie>()
    val cleanTitle = series.title.cleanTitle(includeYear = false)
    val rawTitle = series.title.toNfc()
    
    // 1. 이미 series 객체에 포함된 에피소드들 추가
    collectedEpisodes.addAll(series.episodes)

    // 2. 제목 기반 전역 검색 및 경로 탐색 병렬 진행
    val searchTask = async(Dispatchers.IO) {
        val searchResults = mutableListOf<Movie>()
        try { 
            // 다양한 쿼리 변이형으로 검색 범위 극대화
            val queries = listOf(rawTitle, cleanTitle, rawTitle.replace("-", " "), rawTitle.replace("-", ""))
            queries.distinct().forEach { query ->
                repository.searchVideos(query).forEach { foundSeries ->
                    searchResults.addAll(foundSeries.episodes)
                }
            }
        } catch (e: Exception) { }
        searchResults
    }
    
    val pathTask = async(Dispatchers.IO) {
        val path = series.fullPath ?: return@async emptyList<Movie>()
        val foundMovies = mutableListOf<Movie>()
        try {
            val parts = path.split("/").filter { it.isNotBlank() }
            if (parts.isEmpty()) return@async emptyList<Movie>()
            
            // 마지막 경로가 시즌 형태(S01, Season 1 등)인지 확인
            val lastPart = parts.last().toNfc()
            val isSeasonPart = isGenericTitle(lastPart) || 
                               lastPart.contains(Regex("(?i)Season|시즌|S\\d+|Part|파트|\\d+기"))
            
            // 시리즈 루트 경로 결정
            val rootPath = if (isSeasonPart && parts.size > 1) path.substringBeforeLast("/") else path
            
            // 루트 폴더의 콘텐츠를 가져옴
            val rootContent = repository.getCategoryList(rootPath)
            
            // [강화] 루트 폴더 자체에 이미 영상이 있는 경우 (시즌 구분 없는 경우)
            val rootMovies = rootContent.flatMap { it.movies ?: emptyList() }
            foundMovies.addAll(rootMovies)

            // 각 하위 폴더(시즌 폴더들) 탐색
            rootContent.filter { it.movies == null || it.movies!!.isEmpty() }.map { cat ->
                async {
                    val subPath = cat.path ?: "$rootPath/${cat.name}"
                    // 자기 자신(루트)을 다시 호출하지 않도록 방지
                    if (subPath != rootPath) {
                        repository.getCategoryList(subPath).flatMap { it.movies ?: emptyList() }
                    } else emptyList()
                }
            }.awaitAll().forEach { foundMovies.addAll(it) }
            
        } catch (e: Exception) { }
        foundMovies
    }

    // 3. 결과 통합 및 정제 (URL 및 ID 기준 중복 제거)
    val totalMovies = collectedEpisodes + searchTask.await() + pathTask.await()
    val uniqueEpisodes = totalMovies.distinctBy { it.videoUrl ?: it.id ?: it.title }.map { movie ->
        if (movie.videoUrl?.startsWith("http") == false) {
            movie.copy(videoUrl = NasApiClient.BASE_URL + (if (movie.videoUrl.startsWith("/")) "" else "/") + movie.videoUrl)
        } else movie
    }
    
    // 4. 시즌별 그룹화 (파일명 정밀 분석 활용)
    val seasonsMap = uniqueEpisodes.groupBy { it.title?.extractSeason() ?: 1 }
    
    val finalSeasons = seasonsMap.map { (num, eps) ->
        Season(name = "시즌 $num", episodes = eps.sortedByEpisode())
    }.sortedBy { it.name }

    if (finalSeasons.isEmpty() && uniqueEpisodes.isNotEmpty()) {
        listOf(Season(name = "시즌 1", episodes = uniqueEpisodes.sortedByEpisode()))
    } else {
        finalSeasons
    }
}

private suspend fun loadMetadataAndCredits(title: String): Pair<TmdbMetadata?, List<TmdbCast>> {
    val metadata = fetchTmdbMetadata(title)
    val credits = if (metadata.tmdbId != null) fetchTmdbCredits(metadata.tmdbId, metadata.mediaType) else emptyList()
    return metadata to credits
}

private val genreMap = mapOf(
    28 to "액션", 12 to "모험", 16 to "애니", 35 to "코미디", 80 to "범죄", 99 to "다큐", 18 to "드라마", 10751 to "가족",
    14 to "판타지", 36 to "역사", 27 to "공포", 10402 to "음악", 9648 to "미스터리", 10749 to "로맨스", 878 to "SF",
    10770 to "TV영화", 53 to "스릴러", 10752 to "전쟁", 37 to "서부", 10759 to "액션&어드벤처", 10762 to "키즈",
    10763 to "뉴스", 10764 to "리얼리티", 10765 to "SF&판타지", 10766 to "소프", 10767 to "토크", 10768 to "전쟁&정치"
)
