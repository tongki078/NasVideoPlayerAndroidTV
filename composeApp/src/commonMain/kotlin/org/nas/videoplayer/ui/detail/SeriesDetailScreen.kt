package org.nas.videoplayer.ui.detail

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
import org.nas.videoplayer.*
import org.nas.videoplayer.domain.model.Movie
import org.nas.videoplayer.domain.model.Series
import org.nas.videoplayer.domain.repository.VideoRepository
import org.nas.videoplayer.ui.common.TmdbAsyncImage
import org.nas.videoplayer.ui.common.shimmerBrush

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
    onPreviewPlay: (Movie) -> Unit = {} // 미리보기 재생 시 호출될 콜백 추가
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
            Column(modifier = Modifier.fillMaxSize().padding(pv).verticalScroll(rememberScrollState())) {
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
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

    // 미리보기 재생 감지를 위한 효과
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

        Spacer(Modifier.height(16.dp))

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
        Spacer(Modifier.height(50.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeasonSelector(seasons: List<Season>, selectedIndex: Int, onSeasonSelected: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
            OutlinedTextField(
                value = seasons[selectedIndex].name,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Red, unfocusedBorderColor = Color.Gray,
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White
                ),
                modifier = Modifier.widthIn(min = 160.dp).menuAnchor()
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
    Box(modifier = Modifier.fillMaxWidth().height(300.dp)) {
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
            
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Play",
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(80.dp)
                    .clickable { onPlayClick() }
                    .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                    .padding(8.dp)
            )
        }
        
        Box(
            modifier = Modifier.fillMaxSize().background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f))
                )
            )
        )
    }
    
    Button(
        onClick = onPlayClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
        shape = RoundedCornerShape(4.dp)
    ) {
        Icon(Icons.Default.PlayArrow, null, tint = Color.Black)
        Spacer(Modifier.width(8.dp))
        Text("재생", color = Color.Black, fontWeight = FontWeight.Bold)
    }

    Text(metadata?.overview ?: "상세 정보를 불러오는 중입니다...", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Color.LightGray, fontSize = 14.sp, lineHeight = 20.sp)

    if (credits.isNotEmpty()) {
        Text("성우 및 출연진", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp)) {
            items(credits) { cast ->
                Column(modifier = Modifier.width(90.dp).padding(end = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    var isLoading by remember { mutableStateOf(true) }
                    Box(
                        modifier = Modifier
                            .size(70.dp)
                            .clip(CircleShape)
                            .background(shimmerBrush(showShimmer = isLoading))
                    ) {
                        AsyncImage(
                            model = "$TMDB_IMAGE_BASE$TMDB_POSTER_SIZE_SMALL${cast.profilePath}",
                            contentDescription = cast.name,
                            onState = { state -> isLoading = state is AsyncImagePainter.State.Loading },
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(cast.name, color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun EpisodeList(episodes: List<Movie>, metadata: TmdbMetadata?, onPlay: (Movie) -> Unit) {
    Text("에피소드", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    Column(Modifier.padding(horizontal = 8.dp)) {
        episodes.forEach { ep -> EpisodeItem(ep, metadata, onPlay = { onPlay(ep) }) }
    }
}

@Composable
fun EpisodeItem(movie: Movie, seriesMeta: TmdbMetadata?, onPlay: () -> Unit) {
    var episodeDetails by remember { mutableStateOf<TmdbEpisode?>(null) }
    LaunchedEffect(movie, seriesMeta) {
        if (seriesMeta?.tmdbId != null && seriesMeta.mediaType == "tv") {
            val season = movie.title.extractSeason()
            val episodeNum = movie.title.extractEpisode()?.filter { it.isDigit() }?.toIntOrNull() ?: 1
            episodeDetails = fetchTmdbEpisodeDetails(seriesMeta.tmdbId, season, episodeNum)
        }
    }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 8.dp).clickable(onClick = onPlay), verticalAlignment = Alignment.CenterVertically) {
        val imageUrl = when {
            episodeDetails?.stillPath != null -> "$TMDB_IMAGE_BASE$TMDB_POSTER_SIZE_SMALL${episodeDetails?.stillPath}"
            seriesMeta?.posterUrl != null -> seriesMeta.posterUrl
            else -> ""
        }
        
        var isLoading by remember { mutableStateOf(true) }
        Box(
            modifier = Modifier
                .width(140.dp)
                .height(80.dp)
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

        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(movie.title.prettyTitle(), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(text = episodeDetails?.overview ?: "줄거리 정보가 없습니다.", color = Color.Gray, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
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
        ShimmeringBox(modifier = Modifier.fillMaxWidth().height(280.dp))
        Spacer(Modifier.height(16.dp))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ShimmeringBox(modifier = Modifier.fillMaxWidth().height(20.dp))
            ShimmeringBox(modifier = Modifier.fillMaxWidth(0.9f).height(20.dp))
            ShimmeringBox(modifier = Modifier.fillMaxWidth(0.7f).height(20.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text("성우 및 출연진", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp)) {
            items(5) {
                Column(Modifier.padding(end = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    ShimmeringBox(modifier = Modifier.size(70.dp).clip(CircleShape))
                    Spacer(Modifier.height(4.dp))
                    ShimmeringBox(modifier = Modifier.width(60.dp).height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun EpisodeListSkeleton() {
    Column {
        Text("에피소드", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        Column(Modifier.padding(horizontal = 8.dp)) {
            repeat(3) {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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
}
