package org.nas.videoplayerandroidtv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
import org.nas.videoplayerandroidtv.ui.common.SophisticatedTabChip
import org.nas.videoplayerandroidtv.ui.detail.SeriesDetailScreen
import org.nas.videoplayerandroidtv.ui.home.HomeScreen
import org.nas.videoplayerandroidtv.ui.player.VideoPlayerScreen
import org.nas.videoplayerandroidtv.ui.search.SearchScreen
import org.nas.videoplayerandroidtv.db.AppDatabase
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun App(driver: SqlDriver) {
    val repository: VideoRepository = remember { VideoRepositoryImpl() }
    
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
    
    // 마지막으로 선택했던 시리즈의 경로를 저장하여 포커스 복구에 사용
    var lastSelectedSeriesPath by rememberSaveable { mutableStateOf<String?>(null) }

    // 상단바 포커스 제어용
    val topBarHomeFocusRequester = remember { FocusRequester() }
    var isTopBarFocused by remember { mutableStateOf(false) }

    // 검색 실행 로직
    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            searchResultSeries = emptyList()
            isSearchLoading = false
            return@LaunchedEffect
        }
        
        isSearchLoading = true
        delay(500)
        try {
            searchResultSeries = repository.searchVideos(searchQuery, "")
        } catch (e: Exception) {
            searchResultSeries = emptyList()
        } finally {
            isSearchLoading = false
        }
    }

    val subModeStates = remember { mutableStateMapOf<Screen, Int>() }
    val selectedSubMode = subModeStates.getOrDefault(currentScreen, 0)

    val subModes = when(currentScreen) {
        Screen.MOVIES -> listOf("최신", "UHD", "제목") 
        Screen.KOREAN_TV -> listOf("드라마", "시트콤", "예능", "교양", "다큐멘터리")
        Screen.FOREIGN_TV -> listOf("미국 드라마", "일본 드라마", "중국 드라마", "기타국가 드라마", "다큐")
        Screen.ANIMATIONS -> listOf("라프텔", "시리즈")
        Screen.ON_AIR -> listOf("라프텔 애니메이션", "드라마", "외국")
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
                    val subModeTitle = if (subModes.isNotEmpty()) subModes[selectedSubMode] else null
                    val sections = if (catKey == "home") repository.getHomeRecommendations() 
                                   else repository.getCategorySections(catKey, subModeTitle)
                    
                    allCategorySections[cacheKey] = sections
                }
            } catch (_: Exception) {} finally { categoryLoadingStates[cacheKey] = false }
        }
    }

    val lazyListStates = remember { mutableStateMapOf<String, LazyListState>() }
    val allRowStates = remember { mutableStateMapOf<String, MutableMap<String, LazyListState>>() }
    val allRowFocusIndices = remember { mutableStateMapOf<String, SnapshotStateMap<String, Int>>() }

    // [UX 개선] 뒤로가기 동작 정의
    BackHandler(enabled = selectedMovie != null || selectedSeries != null || currentScreen != Screen.HOME || !isTopBarFocused) {
        when {
            selectedMovie != null -> { selectedMovie = null }
            selectedSeries != null -> { selectedSeries = null }
            currentScreen != Screen.HOME -> { 
                currentScreen = Screen.HOME 
                topBarHomeFocusRequester.requestFocus()
            }
            !isTopBarFocused -> { 
                topBarHomeFocusRequester.requestFocus() 
            }
        }
    }

    val saveWatchHistory: (Movie, String?, Long, Long, String?, String?) -> Unit = { movie, posterPath, pos, dur, sTitle, sPath ->
        scope.launch(Dispatchers.Default) {
            // 시리즈물인 경우 시리즈 경로를 고유 ID로 사용하여 중복 방지
            val recordId = sPath ?: movie.id ?: ""
            watchHistoryDataSource.insertWatchHistory(recordId, movie.title ?: "", movie.videoUrl ?: "", movie.thumbnailUrl ?: "", currentTimeMillis(), "movie", "", posterPath, pos, dur, sTitle, sPath)
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Color.Red, background = Color.Black)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (selectedMovie == null) {
                    NetflixTopBar(
                        currentScreen = currentScreen,
                        homeFocusRequester = topBarHomeFocusRequester,
                        onFocusChanged = { isTopBarFocused = it },
                        onScreenSelected = { screen ->
                            if (currentScreen != screen) {
                                lastSelectedSeriesPath = null 
                            }
                            currentScreen = screen
                            selectedSeries = null 
                            selectedMovie = null
                        }
                    )
                    
                    if (subModes.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            contentPadding = PaddingValues(horizontal = 48.dp), 
                            horizontalArrangement = Arrangement.spacedBy(16.dp), 
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            itemsIndexed(subModes) { index, title ->
                                SophisticatedTabChip(
                                    title = title, 
                                    isSelected = selectedSubMode == index, 
                                    onClick = { 
                                        if (selectedSubMode != index) {
                                            lastSelectedSeriesPath = null
                                        }
                                        subModeStates[currentScreen] = index
                                        selectedSeries = null 
                                        selectedMovie = null
                                    }
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
                                repository = repository,
                                onPositionUpdate = { pos: Long, dur: Long -> 
                                    lastPlaybackPosition = pos; 
                                    saveWatchHistory(selectedMovie!!, selectedSeries?.posterPath, pos, dur, selectedSeries?.title, selectedSeries?.fullPath)
                                },
                                onNextMovie = { nextM -> 
                                    selectedMovie = null
                                    scope.launch {
                                        delay(10)
                                        selectedMovie = nextM.copy()
                                        lastPlaybackPosition = 0L 
                                    }
                                },
                                onBack = { selectedMovie = null }
                            )
                        }
                        selectedSeries != null -> {
                            SeriesDetailScreen(
                                series = selectedSeries!!, 
                                repository = repository,
                                watchHistory = watchHistory,
                                onBackPressed = { selectedSeries = null }, 
                                onPlay = { m: Movie, p: List<Movie>, pos: Long ->
                                    selectedMovie = m; moviePlaylist = p; lastPlaybackPosition = pos; 
                                    saveWatchHistory(m, selectedSeries?.posterPath, pos, 0L, selectedSeries?.title, selectedSeries?.fullPath)
                                }
                            )
                        }
                        currentScreen == Screen.SEARCH -> {
                            SearchScreen(
                                query = searchQuery, 
                                onQueryChange = { searchQuery = it }, 
                                recentQueries = recentQueries, 
                                searchResults = searchResultSeries, 
                                isLoading = isSearchLoading, 
                                onSaveQuery = { q: String -> scope.launch { searchHistoryDataSource.insertQuery(q, currentTimeMillis()) } }, 
                                onDeleteQuery = { q: String -> scope.launch { searchHistoryDataSource.deleteQuery(q) } }, 
                                onSeriesClick = { 
                                    lastSelectedSeriesPath = it.fullPath
                                    selectedSeries = it 
                                },
                                lastFocusedPath = if (selectedSeries == null) lastSelectedSeriesPath else null,
                                onFocusRestored = { lastSelectedSeriesPath = null }
                            )
                        }
                        else -> {
                            HomeScreen(
                                currentScreen = currentScreen, 
                                watchHistory = if (currentScreen == Screen.HOME) watchHistory else emptyList(), 
                                homeSections = allCategorySections[currentCacheKey] ?: emptyList(),
                                isLoading = categoryLoadingStates[currentCacheKey] ?: false,
                                lazyListState = lazyListStates.getOrPut(currentCacheKey) { LazyListState() },
                                rowStates = allRowStates.getOrPut(currentCacheKey) { mutableMapOf() },
                                rowFocusIndices = allRowFocusIndices.getOrPut(currentCacheKey) { mutableStateMapOf() },
                                onSeriesClick = { 
                                    lastSelectedSeriesPath = it.fullPath
                                    selectedSeries = it 
                                }, 
                                onPlayClick = { m: Movie -> 
                                    selectedMovie = m; moviePlaylist = listOf(m); lastPlaybackPosition = 0L 
                                },
                                onHistoryClick = { h: org.nas.videoplayerandroidtv.data.WatchHistory -> 
                                    val movie = Movie(h.id, h.title, h.videoUrl, h.thumbnailUrl)
                                    selectedMovie = movie
                                    moviePlaylist = listOf(movie)
                                    lastPlaybackPosition = h.lastPosition
                                },
                                lastFocusedPath = if (selectedSeries == null) lastSelectedSeriesPath else null,
                                onFocusRestored = { lastSelectedSeriesPath = null }
                            )
                        }
                    }
                }
            }
        }
    }
}
