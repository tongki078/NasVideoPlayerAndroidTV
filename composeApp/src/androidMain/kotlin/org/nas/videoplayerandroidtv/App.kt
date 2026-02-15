package org.nas.videoplayerandroidtv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.request.crossfade
import kotlinx.coroutines.Dispatchers
import okio.Path.Companion.toPath
import org.nas.videoplayerandroidtv.data.*
import org.nas.videoplayerandroidtv.data.network.NasApiClient
import org.nas.videoplayerandroidtv.data.repository.VideoRepositoryImpl
import org.nas.videoplayerandroidtv.domain.model.*
import org.nas.videoplayerandroidtv.domain.repository.VideoRepository
import org.nas.videoplayerandroidtv.ui.common.NetflixTopBar
import org.nas.videoplayerandroidtv.ui.detail.SeriesDetailScreen
import org.nas.videoplayerandroidtv.ui.home.HomeScreen
import org.nas.videoplayerandroidtv.ui.player.VideoPlayerScreen
import org.nas.videoplayerandroidtv.ui.search.SearchScreen
import org.nas.videoplayerandroidtv.db.AppDatabase
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.launch

@Composable
fun App(driver: SqlDriver) {
    val repository: VideoRepository = remember { VideoRepositoryImpl() }
    val repoImpl = repository as VideoRepositoryImpl
    
    setSingletonImageLoaderFactory { ctx -> 
        ImageLoader.Builder(ctx).diskCache { DiskCache.Builder().directory(getImageCacheDirectory(ctx).toPath().resolve("coil_cache")).maxSizeBytes(100 * 1024 * 1024).build() }.crossfade(true).build() 
    }
    
    val db = remember { AppDatabase(driver) }
    val searchHistoryDataSource = remember { SearchHistoryDataSource(db) }
    val watchHistoryDataSource = remember { WatchHistoryDataSource(db) }
    val tmdbCacheDataSource = remember { TmdbCacheDataSource(db) }
    LaunchedEffect(Unit) { persistentCache = tmdbCacheDataSource }
    
    val recentQueries by searchHistoryDataSource.getRecentQueries().collectAsState(initial = emptyList())
    val watchHistory by watchHistoryDataSource.getWatchHistory().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var currentScreen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchResultSeries by remember { mutableStateOf<List<Series>>(emptyList()) }
    var isSearchLoading by remember { mutableStateOf(false) }
    var selectedSeries by remember { mutableStateOf<Series?>(null) }
    var selectedMovie by remember { mutableStateOf<Movie?>(null) }
    var moviePlaylist by remember { mutableStateOf<List<Movie>>(emptyList()) }
    var lastPlaybackPosition by rememberSaveable { mutableLongStateOf(0L) }
    var lastVideoDuration by rememberSaveable { mutableLongStateOf(0L) }

    // 세부 탭 상태 (화면별로 기억하되, 화면 전환 시 0으로 초기화하지 않음)
    val subModeStates = remember { mutableStateMapOf<Screen, Int>() }
    val selectedSubMode = subModeStates.getOrDefault(currentScreen, 0)

    val subModes = when(currentScreen) {
        Screen.MOVIES -> listOf("UHD", "최신", "제목")
        Screen.KOREAN_TV -> listOf("드라마", "예능", "시트콤", "교양", "다큐멘터리")
        Screen.FOREIGN_TV -> listOf("미국", "중국", "일본", "다큐", "기타")
        Screen.ANIMATIONS -> listOf("라프텔", "시리즈")
        Screen.ON_AIR -> listOf("애니메이션", "드라마")
        else -> emptyList()
    }

    val allCategorySections = remember { mutableStateMapOf<String, List<HomeSection>>() }
    val categoryLoadingStates = remember { mutableStateMapOf<String, Boolean>() }
    
    LaunchedEffect(currentScreen, selectedSubMode) {
        val cacheKey = "${currentScreen.name}_$selectedSubMode"
        if (!allCategorySections.containsKey(cacheKey)) {
            categoryLoadingStates[cacheKey] = true
            try {
                val catKey = when(currentScreen) {
                    Screen.HOME -> "home"
                    Screen.MOVIES -> "movies"
                    Screen.KOREAN_TV -> "koreantv"
                    Screen.FOREIGN_TV -> "foreigntv"
                    Screen.ANIMATIONS -> "animations_all"
                    Screen.ON_AIR -> "air"
                    else -> ""
                }
                if (catKey.isNotEmpty()) {
                    val sections = if (catKey == "home") repository.getHomeRecommendations() 
                                   else repoImpl.fetchCategorySections(catKey)
                    
                    val filteredSections = if (subModes.isEmpty()) sections 
                    else {
                        val target = subModes[selectedSubMode]
                        sections.map { s -> 
                            s.copy(items = s.items.filter { 
                                it.path?.contains(target) == true || it.name?.contains(target) == true 
                            }) 
                        }.filter { it.items.isNotEmpty() }
                    }
                    
                    allCategorySections[cacheKey] = if (filteredSections.isEmpty() && selectedSubMode == 0) sections else filteredSections
                }
            } catch (_: Exception) {} finally { categoryLoadingStates[cacheKey] = false }
        }
    }

    val lazyListStates = remember { mutableMapOf<String, androidx.compose.foundation.lazy.LazyListState>() }

    BackHandler(enabled = selectedMovie != null || selectedSeries != null || currentScreen != Screen.HOME) {
        when {
            selectedMovie != null -> { selectedMovie = null }
            selectedSeries != null -> { selectedSeries = null }
            currentScreen != Screen.HOME -> { currentScreen = Screen.HOME }
        }
    }

    val saveWatchHistory: (Movie, String?, Long, Long, String?, String?) -> Unit = { movie, posterPath, pos, dur, sTitle, sPath ->
        scope.launch(Dispatchers.Default) {
            watchHistoryDataSource.insertWatchHistory(movie.id ?: "", movie.title ?: "", movie.videoUrl ?: "", movie.thumbnailUrl ?: "", currentTimeMillis(), "movie", "", posterPath, pos, dur, sTitle, sPath)
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Color.Red, background = Color.Black)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (selectedMovie == null) {
                    NetflixTopBar(currentScreen) { screen ->
                        currentScreen = screen
                        selectedSeries = null 
                    }
                    
                    if (subModes.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            contentPadding = PaddingValues(horizontal = 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            itemsIndexed(subModes) { index, title ->
                                SophisticatedTabChip(
                                    title = title,
                                    isSelected = selectedSubMode == index,
                                    onClick = { subModeStates[currentScreen] = index }
                                )
                            }
                        }
                    }
                }
                
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val currentCacheKey = "${currentScreen.name}_$selectedSubMode"
                    when {
                        selectedMovie != null -> {
                            VideoPlayerScreen(
                                movie = selectedMovie!!, 
                                playlist = moviePlaylist, 
                                initialPosition = lastPlaybackPosition, 
                                onPositionUpdate = { pos: Long, dur: Long -> 
                                    lastPlaybackPosition = pos; lastVideoDuration = dur; 
                                    saveWatchHistory(selectedMovie!!, selectedSeries?.posterPath, pos, dur, selectedSeries?.title, selectedSeries?.fullPath)
                                }, 
                                onBack = { selectedMovie = null }
                            )
                        }
                        selectedSeries != null -> {
                            SeriesDetailScreen(
                                series = selectedSeries!!, 
                                repository = repository, 
                                initialPlaybackPosition = lastPlaybackPosition, 
                                initialDuration = lastVideoDuration, 
                                onPositionUpdate = { pos: Long -> lastPlaybackPosition = pos }, 
                                onBackPressed = { selectedSeries = null }, 
                                onPlay = { m: Movie, p: List<Movie>, pos: Long ->
                                    selectedMovie = m; moviePlaylist = p; lastPlaybackPosition = pos; 
                                    saveWatchHistory(m, selectedSeries?.posterPath, pos, lastVideoDuration, selectedSeries?.title, selectedSeries?.fullPath)
                                }
                            )
                        }
                        currentScreen == Screen.SEARCH -> {
                            SearchScreen(searchQuery, { searchQuery = it }, recentQueries, searchResultSeries, isSearchLoading, { q: String -> scope.launch { searchHistoryDataSource.insertQuery(q, currentTimeMillis()) } }, { q: String -> scope.launch { searchHistoryDataSource.deleteQuery(q) } }, { selectedSeries = it })
                        }
                        else -> {
                            HomeScreen(
                                watchHistory = if (currentScreen == Screen.HOME) watchHistory else emptyList(), 
                                homeSections = allCategorySections[currentCacheKey] ?: emptyList(),
                                isLoading = categoryLoadingStates[currentCacheKey] ?: false,
                                lazyListState = lazyListStates.getOrPut(currentCacheKey) { androidx.compose.foundation.lazy.LazyListState() },
                                onSeriesClick = { selectedSeries = it }, 
                                onPlayClick = { m: Movie -> selectedMovie = m; moviePlaylist = listOf(m); lastPlaybackPosition = 0L },
                                onHistoryClick = { h: org.nas.videoplayerandroidtv.data.WatchHistory -> 
                                    selectedSeries = Series(h.seriesTitle ?: h.title, listOf(Movie(h.id, h.title, h.videoUrl, h.thumbnailUrl)), null, h.seriesPath, emptyList(), emptyList(), null, emptyList(), h.posterPath, null, null, null, null)
                                    lastPlaybackPosition = h.lastPosition; lastVideoDuration = h.duration
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SophisticatedTabChip(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) 1.12f else 1.0f)
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isFocused -> Color.White
            isSelected -> Color.Red
            else -> Color.White.copy(alpha = 0.15f)
        }
    )
    val contentColor by animateColorAsState(
        targetValue = if (isFocused) Color.Black else Color.White
    )

    Box(
        modifier = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(24.dp))
            .background(backgroundColor)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = contentColor,
            fontSize = 14.sp,
            fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium
        )
    }
}
