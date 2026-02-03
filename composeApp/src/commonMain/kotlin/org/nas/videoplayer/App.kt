package org.nas.videoplayer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import coil3.ImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import coil3.disk.DiskCache
import coil3.compose.setSingletonImageLoaderFactory
import okio.Path.Companion.toPath
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import org.nas.videoplayer.data.network.NasApiClient
import org.nas.videoplayer.data.repository.VideoRepositoryImpl
import org.nas.videoplayer.domain.model.*
import org.nas.videoplayer.domain.repository.VideoRepository
import org.nas.videoplayer.ui.home.HomeScreen
import org.nas.videoplayer.ui.search.SearchScreen
import org.nas.videoplayer.ui.detail.SeriesDetailScreen
import org.nas.videoplayer.ui.player.VideoPlayerScreen
import org.nas.videoplayer.ui.common.NetflixTopBar
import org.nas.videoplayer.ui.category.ThemedCategoryScreen
import org.nas.videoplayer.data.*
import org.nas.videoplayer.db.AppDatabase
import com.squareup.sqldelight.db.SqlDriver

@Composable
fun App(driver: SqlDriver) {
    val repository: VideoRepository = remember { VideoRepositoryImpl() }
    
    setSingletonImageLoaderFactory { ctx -> 
        ImageLoader.Builder(ctx)
            .components { add(KtorNetworkFetcherFactory(NasApiClient.client)) }
            .diskCache { 
                DiskCache.Builder()
                    .directory(getImageCacheDirectory(ctx).toPath().resolve("coil_cache"))
                    .maxSizeBytes(100 * 1024 * 1024) 
                    .build() 
            }
            .crossfade(true)
            .build() 
    }
    
    val db = remember { AppDatabase(driver) }
    val searchHistoryDataSource = remember { SearchHistoryDataSource(db) }
    val watchHistoryDataSource = remember { WatchHistoryDataSource(db) }
    val recentQueries by searchHistoryDataSource.getRecentQueries().map { list -> list.map { it.toData() } }.collectAsState(initial = emptyList<SearchHistory>())
    val watchHistory by watchHistoryDataSource.getWatchHistory().map { list -> list.map { it.toData() } }.collectAsState(initial = emptyList<WatchHistory>())
    val scope = rememberCoroutineScope()

    var currentScreen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchCategory by rememberSaveable { mutableStateOf("전체") }
    var searchResultSeries by remember { mutableStateOf<List<Series>>(emptyList()) }
    var isSearchLoading by remember { mutableStateOf(false) }
    var selectedSeries by remember { mutableStateOf<Series?>(null) }
    var selectedMovie by remember { mutableStateOf<Movie?>(null) }
    var moviePlaylist by remember { mutableStateOf<List<Movie>>(emptyList()) }
    
    var lastPlaybackPosition by rememberSaveable { mutableStateOf(0L) }

    var homeLatestSeries by remember { mutableStateOf<List<Series>>(emptyList()) }
    var homeAnimations by remember { mutableStateOf<List<Series>>(emptyList()) }
    var isHomeLoading by remember { mutableStateOf(false) } 
    
    val homeLazyListState = rememberLazyListState()
    val themedCategoryLazyListState = rememberLazyListState()

    var selectedAirMode by rememberSaveable { mutableStateOf(0) }
    var selectedAniMode by rememberSaveable { mutableStateOf(0) }
    var selectedMovieMode by rememberSaveable { mutableStateOf(0) }
    var selectedForeignTvMode by rememberSaveable { mutableStateOf(0) }
    var selectedKoreanTvMode by rememberSaveable { mutableStateOf(0) }

    // 시청 기록 저장 함수 (중복 제거 및 최신화)
    val saveWatchHistory: (Movie) -> Unit = { movie ->
        scope.launch(Dispatchers.Default) {
            watchHistoryDataSource.insertWatchHistory(
                id = movie.id,
                title = movie.title,
                videoUrl = movie.videoUrl,
                thumbnailUrl = movie.thumbnailUrl,
                timestamp = currentTimeMillis(),
                screenType = "movie",
                pathStackJson = ""
            )
        }
    }

    // 검색을 실행하는 공통 함수
    val performSearch: suspend (String, String) -> Unit = { query, category ->
        if (query.length >= 2) {
            isSearchLoading = true
            val results = repository.searchVideos(query, category)
            val isAniSearch = category == "애니메이션"
            coroutineScope {
                results.take(9).map { series -> 
                    async { fetchTmdbMetadata(series.title, if (category != "전체") category.lowercase() else null, isAnimation = isAniSearch) }
                }.awaitAll()
            }
            searchResultSeries = results
            isSearchLoading = false
        } else {
            searchResultSeries = emptyList()
        }
    }

    LaunchedEffect(currentScreen) {
        if (currentScreen == Screen.HOME && homeLatestSeries.isEmpty()) {
            isHomeLoading = true
            try {
                val latestDeferred = async { repository.getLatestMovies() }
                val animationsDeferred = async { repository.getAnimations() }
                val latest = latestDeferred.await()
                val animations = animationsDeferred.await()
                coroutineScope {
                    val latestJobs = latest.take(6).map { series ->
                        async { fetchTmdbMetadata(series.title) }
                    }
                    val aniJobs = animations.take(6).map { series ->
                        async { fetchTmdbMetadata(series.title, isAnimation = true) }
                    }
                    (latestJobs + aniJobs).awaitAll()
                }
                homeLatestSeries = latest
                homeAnimations = animations
            } finally {
                isHomeLoading = false
            }
        }
    }

    // 텍스트 입력 시 자동 검색 (500ms 지연)
    LaunchedEffect(searchQuery, searchCategory) {
        if (searchQuery.isNotEmpty()) {
            delay(500)
            performSearch(searchQuery, searchCategory)
        } else {
            searchResultSeries = emptyList()
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Color.Red, background = Color.Black)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = {
                    if (selectedMovie == null && currentScreen != Screen.SEARCH) {
                        NetflixTopBar(currentScreen) { 
                            currentScreen = it
                            selectedSeries = null 
                        }
                    }
                },
                bottomBar = { 
                    if (selectedMovie == null) {
                        NavigationBar(containerColor = Color.Black) {
                            NavigationBarItem(
                                selected = currentScreen == Screen.HOME, 
                                onClick = { currentScreen = Screen.HOME; selectedSeries = null }, 
                                icon = { Icon(Icons.Default.Home, null) }, label = { Text("홈") }
                            )
                            NavigationBarItem(
                                selected = currentScreen == Screen.SEARCH, 
                                onClick = { currentScreen = Screen.SEARCH; selectedSeries = null }, 
                                icon = { Icon(Icons.Default.Search, null) }, label = { Text("검색") }
                            )
                        }
                    }
                }
            ) { pv ->
                Box(Modifier.padding(pv).fillMaxSize()) {
                    when {
                        selectedMovie != null -> {
                            VideoPlayerScreen(
                                movie = selectedMovie!!, 
                                playlist = moviePlaylist,
                                initialPosition = lastPlaybackPosition,
                                onPositionUpdate = { 
                                    lastPlaybackPosition = it
                                },
                                onBack = { selectedMovie = null }
                            )
                        }
                        selectedSeries != null -> {
                            SeriesDetailScreen(
                                series = selectedSeries!!, 
                                repository = repository, 
                                initialPlaybackPosition = lastPlaybackPosition,
                                onPositionUpdate = { lastPlaybackPosition = it },
                                onBack = { selectedSeries = null }, 
                                onPlay = { movie, playlist, pos -> 
                                    selectedMovie = movie
                                    moviePlaylist = playlist
                                    lastPlaybackPosition = pos
                                    saveWatchHistory(movie)
                                },
                                onPreviewPlay = { movie -> saveWatchHistory(movie) } // 미리보기 콜백 연결
                            )
                        }
                        currentScreen == Screen.SEARCH -> {
                            SearchScreen(
                                query = searchQuery, 
                                onQueryChange = { 
                                    if (searchQuery == it && it.isNotEmpty()) {
                                        scope.launch { performSearch(it, searchCategory) }
                                    } else {
                                        searchQuery = it 
                                    }
                                },
                                selectedCategory = searchCategory, 
                                onCategoryChange = { searchCategory = it },
                                recentQueries = recentQueries, 
                                searchResults = searchResultSeries, 
                                isLoading = isSearchLoading,
                                onSaveQuery = { 
                                    scope.launch { 
                                        searchHistoryDataSource.insertQuery(it, currentTimeMillis())
                                        performSearch(it, searchCategory)
                                    }
                                },
                                onDeleteQuery = { scope.launch { searchHistoryDataSource.deleteQuery(it) } },
                                onSeriesClick = { selectedSeries = it }
                            )
                        }
                        currentScreen == Screen.HOME -> {
                            key(watchHistory) { // watchHistory 변경 시 UI 갱신 유도
                                HomeScreen(
                                    watchHistory = watchHistory, 
                                    latestMovies = homeLatestSeries, 
                                    animations = homeAnimations,
                                    isLoading = isHomeLoading, 
                                    lazyListState = homeLazyListState,
                                    onSeriesClick = { selectedSeries = it }, 
                                    onPlayClick = { movie ->
                                        val parentSeries = (homeLatestSeries + homeAnimations).find { it.episodes.contains(movie) }
                                        selectedMovie = movie
                                        moviePlaylist = parentSeries?.episodes ?: listOf(movie)
                                        lastPlaybackPosition = 0L
                                        saveWatchHistory(movie)
                                    },
                                    onHistoryClick = { history ->
                                        selectedMovie = Movie(
                                            id = history.id,
                                            title = history.title,
                                            videoUrl = history.videoUrl,
                                            thumbnailUrl = history.thumbnailUrl
                                        )
                                        moviePlaylist = listOf(selectedMovie!!)
                                        lastPlaybackPosition = 0L 
                                        saveWatchHistory(selectedMovie!!)
                                    }
                                )
                            }
                        }
                        else -> {
                            val categoryInfo = when (currentScreen) {
                                Screen.ON_AIR -> Triple("방송중", "방송중", selectedAirMode)
                                Screen.ANIMATIONS -> Triple("애니메이션", "애니메이션", selectedAniMode)
                                Screen.MOVIES -> Triple("영화", "영화", selectedMovieMode)
                                Screen.FOREIGN_TV -> Triple("외국TV", "외국TV", selectedForeignTvMode)
                                Screen.KOREAN_TV -> Triple("국내TV", "국내TV", selectedKoreanTvMode)
                                else -> Triple("", "", 0)
                            }
                            
                            val onModeChange: (Int) -> Unit = when (currentScreen) {
                                Screen.ON_AIR -> { mode -> selectedAirMode = mode }
                                Screen.ANIMATIONS -> { mode -> selectedAniMode = mode }
                                Screen.MOVIES -> { mode -> selectedMovieMode = mode }
                                Screen.FOREIGN_TV -> { mode -> selectedForeignTvMode = mode }
                                Screen.KOREAN_TV -> { mode -> selectedKoreanTvMode = mode }
                                else -> { _ -> }
                            }

                            if (categoryInfo.first.isNotEmpty()) {
                                ThemedCategoryScreen(
                                    categoryName = categoryInfo.first,
                                    rootPath = categoryInfo.second,
                                    repository = repository,
                                    selectedMode = categoryInfo.third,
                                    onModeChange = onModeChange,
                                    lazyListState = themedCategoryLazyListState,
                                    onSeriesClick = { selectedSeries = it }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
