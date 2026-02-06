package org.nas.videoplayerandroidtv.ui.detail

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.nas.videoplayerandroidtv.*
import org.nas.videoplayerandroidtv.domain.model.Movie
import org.nas.videoplayerandroidtv.domain.model.Series
import org.nas.videoplayerandroidtv.domain.repository.VideoRepository
import org.nas.videoplayerandroidtv.ui.detail.components.*
import kotlinx.coroutines.awaitAll

// ==========================================================
// 1. Data Models
// ==========================================================
data class Season(val name: String, val episodes: List<Movie>)

private data class SeriesDetailState(
    val seasons: List<Season> = emptyList(),
    val metadata: TmdbMetadata? = null,
    val credits: List<TmdbCast> = emptyList(),
    val isLoading: Boolean = true,
    val selectedSeasonIndex: Int = 0
)

// ==========================================================
// 2. Business Logic
// ==========================================================

private fun List<Movie>.sortedByEpisode(): List<Movie> = this.sortedWith(
    compareBy<Movie> { it.title?.extractSeason() ?: 1 }
        .thenBy { it.title?.extractEpisode()?.filter { char -> char.isDigit() }?.toIntOrNull() ?: 0 }
)

private suspend fun loadSeasons(series: Series, repository: VideoRepository): List<Season> {
    // 1. 이미 가지고 있는 에피소드가 있다면 기본으로 사용
    val defaultSeason = if (series.episodes.isNotEmpty()) {
        Season("에피소드", series.episodes.sortedByEpisode())
    } else null

    val path = series.fullPath ?: return listOfNotNull(defaultSeason)

    return try {
        // [수정] 상세 페이지 로딩 시, 해당 경로의 하위 내용을 다시 긁어와서 최신화 및 에피소드 확보
        val content = repository.getCategoryList(path)
        
        // 폴더(시즌)와 영상(에피소드)을 구분
        val subFolders = content.filter { it.movies.isNullOrEmpty() && !it.name.isNullOrBlank() }
        val directMovies = content.flatMap { it.movies ?: emptyList() }
        
        when {
            // 하위 폴더(시즌)가 있는 경우 각각 로드
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
            // 현재 폴더에 영상이 직접 있는 경우
            directMovies.isNotEmpty() -> {
                listOf(Season("에피소드", directMovies.sortedByEpisode()))
            }
            // 아무것도 없으면 기존 데이터 유지
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

// ==========================================================
// 3. Main Screen
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
            // 병렬 실행으로 속도 향상
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
        if (state.isLoading && state.seasons.isEmpty()) {
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
    val currentSeason = state.seasons.getOrNull(state.selectedSeasonIndex)
    val firstEpisode = currentSeason?.episodes?.firstOrNull()

    // [수정] 에피소드가 확보되면 즉시 상단 프리뷰 재생을 시도
    LaunchedEffect(firstEpisode) {
        if (firstEpisode != null) {
            onPreviewPlay(firstEpisode)
        }
    }

    val playFirstEpisode = {
        if (firstEpisode != null && currentSeason != null) {
            onPlay(firstEpisode, currentSeason.episodes, initialPosition)
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
            onPlayClick = { playFirstEpisode() },
            onFullscreenClick = { playFirstEpisode() }
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
            // [수정] 회차 리스트 렌더링 확인
            EpisodeList(
                episodes = currentEpisodes,
                metadata = state.metadata,
                onPlay = { movie -> onPlay(movie, currentEpisodes, 0L) }
            )
        } else if (!state.isLoading) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("영상 회차 정보를 불러올 수 없습니다.", color = Color.Gray)
            }
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
                value = seasons.getOrNull(selectedIndex)?.name ?: "",
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
