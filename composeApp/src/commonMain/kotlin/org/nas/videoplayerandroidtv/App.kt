package org.nas.videoplayerandroidtv

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import coil3.ImageLoader
import coil3.network.ktor2.KtorNetworkFetcherFactory
import coil3.request.crossfade
import coil3.disk.DiskCache
import coil3.compose.setSingletonImageLoaderFactory
import okio.Path.Companion.toPath
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import org.nas.videoplayerandroidtv.data.network.NasApiClient
import org.nas.videoplayerandroidtv.data.repository.VideoRepositoryImpl
import org.nas.videoplayerandroidtv.domain.model.*
import org.nas.videoplayerandroidtv.domain.repository.VideoRepository
import org.nas.videoplayerandroidtv.ui.home.HomeScreen
import org.nas.videoplayerandroidtv.ui.search.SearchScreen
import org.nas.videoplayerandroidtv.ui.detail.SeriesDetailScreen
import org.nas.videoplayerandroidtv.ui.player.VideoPlayerScreen
import org.nas.videoplayerandroidtv.ui.common.NetflixTopBar
import org.nas.videoplayerandroidtv.ui.category.ThemedCategoryScreen
import org.nas.videoplayerandroidtv.data.*
import org.nas.videoplayerandroidtv.db.AppDatabase
import app.cash.sqldelight.db.SqlDriver

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
    
    val tmdbCacheDataSource = remember { TmdbCacheDataSource(db) }
    LaunchedEffect(Unit) {
        persistentCache = tmdbCacheDataSource
    }
    
    val recentQueriesState = searchHistoryDataSource.getRecentQueries()
        .map { list -> list.map { it.toData() } }
        .collectAsState(initial = emptyList())
    val recentQueries by recentQueriesState

    val watchHistoryState = watchHistoryDataSource.getWatchHistory()
        .map { list -> list.map { it.toData() } }
        .collectAsState(initial = emptyList())
    val watchHistory by watchHistoryState

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

    var selectedAirMode by rememberSaveable { mutableIntStateOf(0) }
    var selectedAniMode by rememberSaveable { mutableIntStateOf(0) }
    var selectedMovieMode by rememberSaveable { mutableIntStateOf(0) }
    var selectedForeignTvMode by rememberSaveable { mutableIntStateOf(0) }
    var selectedKoreanTvMode by rememberSaveable { mutableIntStateOf(0) }

    BackHandler(enabled = selectedMovie != null || selectedSeries != null || currentScreen != Screen.HOME) {
        when {
            selectedMovie != null -> {
                selectedMovie = null
            }
            selectedSeries != null -> {
                selectedSeries = null
            }
            currentScreen != Screen.HOME -> {
                currentScreen = Screen.HOME
            }
        }
    }

    val saveWatchHistory: (Movie) -> Unit = { movie ->
        scope.launch(Dispatchers.Default) {
            val videoUrl = movie.videoUrl ?: ""
            val title = movie.title ?: ""
            val isAni = videoUrl.contains("애니메이션") || title.contains("애니메이션")
            watchHistoryDataSource.insertWatchHistory(
                id = movie.id ?: "",
                title = title,
                videoUrl = videoUrl,
                thumbnailUrl = movie.thumbnailUrl ?: "",
                timestamp = currentTimeMillis(),
                screenType = if (isAni) "animation" else "movie",
                pathStackJson = ""
            )
        }
    }

    val performSearch: suspend (String, String) -> Unit = { query, category ->
        if (query.length >= 2) {
            isSearchLoading = true
            val results = repository.searchVideos(query, category)
            searchResultSeries = results
            isSearchLoading = false
        } else {
            searchResultSeries = emptyList()
        }
    }

    LaunchedEffect(currentScreen) {
        if (currentScreen == Screen.HOME && homeLatestSeries.isEmpty()) {
            isHomeLoading = true
            
            // [초고속 개선] 병렬 요청 후, 최신작 목록이 오는 즉시 화면을 먼저 엽니다.
            val latestDeferred = async { repository.getLatestMovies() }
            val animationsDeferred = async { repository.getAnimationsAir() }
            
            val latest = latestDeferred.await()
            homeLatestSeries = latest
            
            // 최신작만 도착해도 로딩을 해제하여 스켈레톤 UI가 실제 데이터로 빠르게 바뀌게 함
            if (latest.isNotEmpty()) isHomeLoading = false
            
            val animations = animationsDeferred.await()
            homeAnimations = animations
            
            // 모든 데이터 수신 완료 시 최종 로딩 해제
            isHomeLoading = false
        }
    }

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
                    if (selectedMovie == null) {
                        NetflixTopBar(currentScreen) { 
                            currentScreen = it
                            selectedSeries = null 
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
                                onPreviewPlay = { movie -> saveWatchHistory(movie) }
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
                            key(watchHistory) {
                                HomeScreen(
                                    watchHistory = watchHistory, 
                                    latestMovies = homeLatestSeries, 
                                    animations = homeAnimations,
                                    isLoading = isHomeLoading, 
                                    lazyListState = homeLazyListState,
                                    onSeriesClick = { selectedSeries = it }, 
                                    onPlayClick = { movie ->
                                        val parentSeries = (homeLatestSeries + homeAnimations).find { series -> 
                                            series.episodes.any { it.id == movie.id }
                                        }
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
                            
                            val onModeChange: (Int) -> Unit = { mode ->
                                when (currentScreen) {
                                    Screen.ON_AIR -> selectedAirMode = mode
                                    Screen.ANIMATIONS -> selectedAniMode = mode
                                    Screen.MOVIES -> selectedMovieMode = mode
                                    Screen.FOREIGN_TV -> selectedForeignTvMode = mode
                                    Screen.KOREAN_TV -> selectedKoreanTvMode = mode
                                    else -> {}
                                }
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
