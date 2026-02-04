package org.nas.videoplayerandroidtv.ui.detail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.nas.videoplayerandroidtv.*
import org.nas.videoplayerandroidtv.domain.model.Movie
import org.nas.videoplayerandroidtv.domain.model.Series
import org.nas.videoplayerandroidtv.domain.repository.VideoRepository
import org.nas.videoplayerandroidtv.ui.common.TmdbAsyncImage
import org.nas.videoplayerandroidtv.ui.common.shimmerBrush
import org.nas.videoplayerandroidtv.ui.player.VideoPlayer

// ==========================================================
// 1. Data Models (데이터 구조)
// ==========================================================
private data class Season(val name: String, val episodes: List<Movie>)

private data class SeriesDetailState(
    val seasons: List<Season> = emptyList(),
    val metadata: TmdbMetadata? = null,
    val credits: List<TmdbCast> = emptyList(),
    val isLoading: Boolean = true,
    val selectedSeasonIndex: Int = 0
)

// ==========================================================
// 2. Business Logic (비즈니스 로직 세분화)
// ==========================================================

private fun List<Movie>.sortedByEpisode(): List<Movie> = this.sortedWith(
    compareBy<Movie> { it.title.extractSeason() }
        .thenBy { it.title.extractEpisode()?.filter { char -> char.isDigit() }?.toIntOrNull() ?: 0 }
)

private suspend fun loadSeasons(series: Series, repository: VideoRepository): List<Season> {
    val path = series.fullPath ?: return if (series.episodes.isNotEmpty()) {
        listOf(Season("에피소드", series.episodes.sortedByEpisode()))
    } else emptyList()

    return try {
        val content = repository.getCategoryList(path)
        val hasDirectMovies = content.any { it.movies.isNotEmpty() }

        if (hasDirectMovies) {
            listOf(Season("에피소드", content.flatMap { it.movies }.sortedByEpisode()))
        } else {
            coroutineScope {
                content.map { folder ->
                    async {
                        val folderMovies = repository.getCategoryList("$path/${folder.name}").flatMap { it.movies }
                        if (folderMovies.isNotEmpty()) Season(folder.name, folderMovies.sortedByEpisode()) else null
                    }
                }.awaitAll().filterNotNull().sortedBy { it.name }
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private suspend fun loadMetadataAndCredits(title: String): Pair<TmdbMetadata?, List<TmdbCast>> {
    val metadata = fetchTmdbMetadata(title)
    val credits = if (metadata.tmdbId != null) {
        fetchTmdbCredits(metadata.tmdbId, metadata.mediaType)
    } else emptyList()
    return metadata to credits
}

// ==========================================================
// 3. Main Composable (메인 화면)
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

    Scaffold(
        topBar = { DetailTopBar(series.title, onBack) },
        containerColor = Color.Black
    ) { pv ->
        if (state.isLoading) {
            Column(modifier = Modifier.fillMaxSize().padding(pv)) {
                HeaderSkeleton()
                Spacer(Modifier.height(16.dp))
                EpisodeListSkeleton()
            }
        } else {
            DetailContent(
                paddingValues = pv,
                series = series,
                state = state,
                initialPosition = currentPlaybackTime,
                onPositionUpdate = { 
                    currentPlaybackTime = it
                    onPositionUpdate(it) 
                },
                onSeasonSelected = { index -> state = state.copy(selectedSeasonIndex = index) },
                onPlay = onPlay,
                onPreviewPlay = onPreviewPlay
            )
        }
    }
}

// ==========================================================
// 4. UI Components (UI 단위 조각)
// ==========================================================

@Composable
private fun DetailTopBar(title: String, onBack: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .onFocusChanged { isFocused = it.isFocused }
                .border(
                    width = if (isFocused) 2.dp else 0.dp,
                    color = if (isFocused) Color.White else Color.Transparent,
                    shape = CircleShape
                )
        ) { 
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = if (isFocused) Color.Red else Color.White) 
        }
        Spacer(Modifier.width(8.dp))
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DetailContent(
    paddingValues: PaddingValues,
    series: Series,
    state: SeriesDetailState,
    initialPosition: Long,
    onPositionUpdate: (Long) -> Unit,
    onSeasonSelected: (Int) -> Unit,
    onPlay: (Movie, List<Movie>, Long) -> Unit,
    onPreviewPlay: (Movie) -> Unit
) {
    val firstSeason = state.seasons.firstOrNull()
    val firstEpisode = firstSeason?.episodes?.firstOrNull()

    LaunchedEffect(firstEpisode) {
        if (firstEpisode != null) {
            onPreviewPlay(firstEpisode)
        }
    }

    val playFirstEpisode = {
        if (firstEpisode != null && firstSeason != null) {
            onPlay(firstEpisode, firstSeason.episodes, initialPosition)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(paddingValues).verticalScroll(rememberScrollState())) {
        SeriesDetailHeader(
            series = series, 
            metadata = state.metadata, 
            credits = state.credits,
            previewUrl = firstEpisode?.videoUrl,
            initialPosition = initialPosition,
            onPositionUpdate = onPositionUpdate,
            onPlayClick = playFirstEpisode,
            onFullscreenClick = playFirstEpisode
        )

        Spacer(Modifier.height(24.dp))

        if (state.seasons.isNotEmpty()) {
            if (state.seasons.size > 1) {
                SeasonSelector(
                    seasons = state.seasons,
                    selectedIndex = state.selectedSeasonIndex,
                    onSeasonSelected = onSeasonSelected
                )
            }

            val currentEpisodes = state.seasons[state.selectedSeasonIndex].episodes
            EpisodeList(
                episodes = currentEpisodes,
                metadata = state.metadata,
                onPlay = { movie -> onPlay(movie, currentEpisodes, 0L) }
            )
        } else {
            Text("영상 정보를 찾을 수 없습니다.", color = Color.Gray, modifier = Modifier.padding(16.dp))
        }
        Spacer(Modifier.height(100.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeasonSelector(seasons: List<Season>, selectedIndex: Int, onSeasonSelected: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }

    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
            OutlinedTextField(
                value = seasons[selectedIndex].name,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Red, 
                    unfocusedBorderColor = if (isFocused) Color.White else Color.Gray,
                    focusedTextColor = Color.White, 
                    unfocusedTextColor = Color.White
                ),
                modifier = Modifier
                    .widthIn(min = 200.dp)
                    .menuAnchor()
                    .onFocusChanged { isFocused = it.isFocused }
                    .border(
                        width = if (isFocused) 3.dp else 0.dp,
                        color = if (isFocused) Color.Yellow else Color.Transparent,
                        shape = RoundedCornerShape(4.dp)
                    )
                    .focusable()
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                seasons.forEachIndexed { index, season ->
                    DropdownMenuItem(text = { Text(season.name) }, onClick = { onSeasonSelected(index); expanded = false })
                }
            }
        }
    }
}

@Composable
private fun SeriesDetailHeader(
    series: Series, 
    metadata: TmdbMetadata?, 
    credits: List<TmdbCast>,
    previewUrl: String? = null,
    initialPosition: Long = 0L,
    onPositionUpdate: (Long) -> Unit = {},
    onPlayClick: () -> Unit,
    onFullscreenClick: () -> Unit = {}
) {
    var isPlayButtonFocused by remember { mutableStateOf(false) }
    var isPlayerFocused by remember { mutableStateOf(false) }

    Column {
        // 상단 플레이어 영역 (포커스 가능하게 개선)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(350.dp)
                .onFocusChanged { isPlayerFocused = it.isFocused }
                .focusable()
                .clickable { onFullscreenClick() }
                .border(
                    width = if (isPlayerFocused) 4.dp else 0.dp,
                    color = if (isPlayerFocused) Color.White else Color.Transparent
                )
        ) {
            if (previewUrl != null) {
                VideoPlayer(
                    url = previewUrl,
                    modifier = Modifier.fillMaxSize(),
                    initialPosition = initialPosition,
                    onPositionUpdate = onPositionUpdate,
                    onFullscreenClick = onFullscreenClick
                )
            } else {
                TmdbAsyncImage(title = series.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, isLarge = true)
            }
            
            if (isPlayerFocused) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(80.dp), tint = Color.White)
                    Text("전체화면으로 보기", color = Color.White, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp), fontWeight = FontWeight.Bold)
                }
            }
        }
        
        Spacer(Modifier.height(16.dp))

        // 정보 영역
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.weight(1f)) {
                Text(series.title.cleanTitle(), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onPlayClick,
                    modifier = Modifier
                        .width(150.dp)
                        .height(48.dp)
                        .onFocusChanged { isPlayButtonFocused = it.isFocused }
                        .border(
                            width = if (isPlayButtonFocused) 3.dp else 0.dp,
                            color = if (isPlayButtonFocused) Color.Yellow else Color.Transparent,
                            shape = RoundedCornerShape(4.dp)
                        ),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isPlayButtonFocused) Color.White else Color.Red
                    ),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, null, tint = if (isPlayButtonFocused) Color.Black else Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("재생", color = if (isPlayButtonFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(12.dp))
                Text(metadata?.overview ?: "상세 정보를 불러오는 중입니다...", color = Color.LightGray, fontSize = 15.sp, lineHeight = 22.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }

        if (credits.isNotEmpty()) {
            Text("출연진", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), fontSize = 18.sp)
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp)) {
                items(credits) { cast ->
                    CastItem(cast)
                }
            }
        }
    }
}

@Composable
private fun CastItem(cast: TmdbCast) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) 1.1f else 1f)

    Column(
        modifier = Modifier
            .width(100.dp)
            .padding(end = 16.dp)
            .scale(scale)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable(), 
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        var isLoading by remember { mutableStateOf(true) }
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(shimmerBrush(showShimmer = isLoading))
                .border(
                    width = if (isFocused) 3.dp else 0.dp,
                    color = if (isFocused) Color.White else Color.Transparent,
                    shape = CircleShape
                )
        ) {
            AsyncImage(
                model = "$TMDB_IMAGE_BASE$TMDB_POSTER_SIZE_SMALL${cast.profilePath}",
                contentDescription = cast.name,
                onState = { state -> isLoading = state is AsyncImagePainter.State.Loading },
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(cast.name, color = if (isFocused) Color.Red else Color.White, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2, fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun EpisodeList(episodes: List<Movie>, metadata: TmdbMetadata?, onPlay: (Movie) -> Unit) {
    Text("에피소드", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 12.dp), fontSize = 20.sp)
    Column(Modifier.padding(horizontal = 8.dp)) {
        episodes.forEach { ep -> EpisodeItem(ep, metadata, onPlay = { onPlay(ep) }) }
    }
}

@Composable
fun EpisodeItem(movie: Movie, seriesMeta: TmdbMetadata?, onPlay: () -> Unit) {
    var episodeDetails by remember { mutableStateOf<TmdbEpisode?>(null) }
    var isFocused by remember { mutableStateOf(false) }

    LaunchedEffect(movie, seriesMeta) {
        if (seriesMeta?.tmdbId != null && seriesMeta.mediaType == "tv") {
            val season = movie.title.extractSeason()
            val episodeNum = movie.title.extractEpisode()?.filter { it.isDigit() }?.toIntOrNull() ?: 1
            episodeDetails = fetchTmdbEpisodeDetails(seriesMeta.tmdbId, season, episodeNum)
        }
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 8.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(onClick = onPlay)
            .background(if (isFocused) Color.White.copy(alpha = 0.15f) else Color.Transparent, RoundedCornerShape(8.dp))
            .border(
                width = if (isFocused) 3.dp else 0.dp,
                color = if (isFocused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(12.dp), 
        verticalAlignment = Alignment.CenterVertically
    ) {
        val imageUrl = when {
            episodeDetails?.stillPath != null -> "$TMDB_IMAGE_BASE$TMDB_POSTER_SIZE_SMALL${episodeDetails?.stillPath}"
            seriesMeta?.posterUrl != null -> seriesMeta.posterUrl
            else -> ""
        }
        
        var isLoading by remember { mutableStateOf(true) }
        Box(
            modifier = Modifier
                .width(160.dp)
                .height(90.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(shimmerBrush(showShimmer = isLoading && imageUrl.isNotEmpty()))
        ) {
            if (imageUrl.isNotEmpty()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = movie.title,
                    onState = { state -> isLoading = state is AsyncImagePainter.State.Loading },
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }

        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(movie.title.prettyTitle(), color = if (isFocused) Color.Yellow else Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(text = episodeDetails?.overview ?: "줄거리 정보가 없습니다.", color = Color.Gray, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
        }
    }
}

// ==========================================================
// 5. Skeleton UI Components (스켈레톤 UI)
// ==========================================================
@Composable
private fun ShimmeringBox(modifier: Modifier) {
    Box(modifier = modifier.background(shimmerBrush()))
}

@Composable
private fun HeaderSkeleton() {
    Column {
        ShimmeringBox(modifier = Modifier.fillMaxWidth().height(300.dp))
        Spacer(Modifier.height(16.dp))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ShimmeringBox(modifier = Modifier.width(200.dp).height(30.dp))
            ShimmeringBox(modifier = Modifier.fillMaxWidth().height(20.dp))
            ShimmeringBox(modifier = Modifier.fillMaxWidth(0.7f).height(20.dp))
        }
    }
}

@Composable
private fun EpisodeListSkeleton() {
    Column(Modifier.padding(16.dp)) {
        repeat(3) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ShimmeringBox(modifier = Modifier.width(140.dp).height(80.dp).clip(RoundedCornerShape(4.dp)))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ShimmeringBox(modifier = Modifier.fillMaxWidth(0.8f).height(14.dp))
                    ShimmeringBox(modifier = Modifier.fillMaxWidth(0.95f).height(12.dp))
                }
            }
        }
    }
}
