package org.nas.videoplayerandroidtv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.request.crossfade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath
import org.nas.videoplayerandroidtv.data.SearchHistoryDataSource
import org.nas.videoplayerandroidtv.data.TmdbCacheDataSource
import org.nas.videoplayerandroidtv.data.WatchHistoryDataSource
import org.nas.videoplayerandroidtv.data.network.NasApiClient
import org.nas.videoplayerandroidtv.data.repository.VideoRepositoryImpl
import org.nas.videoplayerandroidtv.domain.model.*
import org.nas.videoplayerandroidtv.domain.repository.VideoRepository
import org.nas.videoplayerandroidtv.ui.category.ThemedCategoryScreen
import org.nas.videoplayerandroidtv.ui.common.NetflixTopBar
import org.nas.videoplayerandroidtv.ui.detail.SeriesDetailScreen
import org.nas.videoplayerandroidtv.ui.home.HomeScreen
import org.nas.videoplayerandroidtv.ui.player.VideoPlayerScreen
import org.nas.videoplayerandroidtv.ui.search.SearchScreen
import org.nas.videoplayerandroidtv.ui.category.processThemedSections
import org.nas.videoplayerandroidtv.ui.category.ThemeSection
import org.nas.videoplayerandroidtv.db.AppDatabase
import app.cash.sqldelight.db.SqlDriver

@Composable
fun App(driver: SqlDriver) {
    val repository: VideoRepository = remember { VideoRepositoryImpl() }
    
    // Coil 3 안정적인 설정 적용
    setSingletonImageLoaderFactory { ctx -> 
        ImageLoader.Builder(ctx)
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
        .collectAsState(initial = emptyList())
    val recentQueries by recentQueriesState

    val watchHistoryState = watchHistoryDataSource.getWatchHistory()
        .collectAsState(initial = emptyList())
    val watchHistory by watchHistoryState

    val scope = rememberCoroutineScope()

    var currentScreen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchCategory by rememberSaveable { mutableStateOf("전체") }
    var searchResultSeries by remember { mutableStateOf<List<Series>>(emptyList()) }
    var isSearchLoading by remember { mutableStateOf(false) }
    
    var lastExecutedQuery by remember { mutableStateOf("") }

    var selectedSeries by remember { mutableStateOf<Series?>(null) }
    var selectedMovie by remember { mutableStateOf<Movie?>(null) }
    var moviePlaylist by remember { mutableStateOf<List<Movie>>(emptyList()) }
    
    var lastPlaybackPosition by rememberSaveable { mutableLongStateOf(0L) }
    var lastVideoDuration by rememberSaveable { mutableLongStateOf(0L) }

    var homeSections by remember { mutableStateOf<List<HomeSection>>(emptyList()) }
    var isHomeLoading by remember { mutableStateOf(false) } 
    
    val categoryCache = remember { mutableMapOf<String, List<ThemeSection>>() }

    LaunchedEffect(homeSections) {
        if (homeSections.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                val prefetchTargets = listOf(
                    Triple("영화", Screen.MOVIES, 2),
                    Triple("애니메이션", Screen.ANIMATIONS, 0)
                )
                
                prefetchTargets.forEach { (name, screen, mode) ->
                    val cacheKey = "category_${name}_mode_${mode}"
                    if (!categoryCache.containsKey(cacheKey)) {
                        try {
                            val rawData = when(screen) {
                                Screen.MOVIES -> repository.getLatestMovies(200, 0)
                                Screen.ANIMATIONS -> repository.getAnimationsRaftel(200, 0)
                                else -> emptyList()
                            }
                            if (rawData.isNotEmpty()) {
                                val processed = processThemedSections(rawData)
                                categoryCache[cacheKey] = processed
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    val homeTvLazyListState = rememberLazyListState()
    val themedCategoryLazyListState = rememberLazyListState()

    var selectedAirMode by rememberSaveable { mutableIntStateOf(0) }
    var selectedAniMode by rememberSaveable { mutableIntStateOf(0) }
    var selectedMovieMode by rememberSaveable { mutableIntStateOf(0) }
    var selectedForeignTvMode by rememberSaveable { mutableIntStateOf(0) }
    var selectedKoreanTvMode by rememberSaveable { mutableIntStateOf(0) }

    BackHandler(enabled = selectedMovie != null || selectedSeries != null || currentScreen != Screen.HOME) {
        when {
            selectedMovie != null -> { selectedMovie = null }
            selectedSeries != null -> { selectedSeries = null }
            currentScreen != Screen.HOME -> { currentScreen = Screen.HOME }
        }
    }

    val saveWatchHistory: (Movie, String?, Long, Long, String?, String?) -> Unit = 
        { movie, posterPath, pos, dur, sTitle, sPath ->
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
                pathStackJson = "",
                posterPath = posterPath,
                lastPosition = pos,
                duration = dur,
                seriesTitle = sTitle,
                seriesPath = sPath
            )
        }
    }

    val performSearch: suspend (String, String) -> Unit = { query, category ->
        val trimmedQuery = query.trim()
        if (trimmedQuery.isNotEmpty()) {
            isSearchLoading = true
            try {
                val results = repository.searchVideos(trimmedQuery, category)
                searchResultSeries = results
                lastExecutedQuery = trimmedQuery
            } catch (e: Exception) {
                searchResultSeries = emptyList()
            } finally {
                isSearchLoading = false
            }
        } else {
            searchResultSeries = emptyList()
            isSearchLoading = false
            lastExecutedQuery = ""
        }
    }

    LaunchedEffect(currentScreen) {
        if (currentScreen == Screen.HOME && homeSections.isEmpty()) {
            isHomeLoading = true
            try {
                homeSections = repository.getHomeRecommendations()
            } catch (_: Exception) {
            } finally {
                isHomeLoading = false
            }
        }
    }

    LaunchedEffect(searchQuery, searchCategory) {
        val trimmed = searchQuery.trim()
        if (trimmed.isNotEmpty() && trimmed != lastExecutedQuery) {
            delay(500)
            if (trimmed != lastExecutedQuery) {
                performSearch(trimmed, searchCategory)
            }
        } else if (trimmed.isEmpty()) {
            searchResultSeries = emptyList()
            isSearchLoading = false
            lastExecutedQuery = ""
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Color.Red, background = Color.Black)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (selectedMovie == null) {
                    NetflixTopBar(currentScreen) { 
                        currentScreen = it
                        selectedSeries = null 
                    }
                }
                
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        selectedMovie != null -> {
                            VideoPlayerScreen(
                                movie = selectedMovie!!, 
                                playlist = moviePlaylist,
                                initialPosition = lastPlaybackPosition,
                                onPositionUpdate = { pos, dur -> 
                                    lastPlaybackPosition = pos 
                                    lastVideoDuration = dur
                                    saveWatchHistory(
                                        selectedMovie!!, 
                                        selectedSeries?.posterPath, 
                                        pos, 
                                        dur,
                                        selectedSeries?.title,
                                        selectedSeries?.fullPath
                                    )
                                },
                                onBack = { selectedMovie = null }
                            )
                        }
                        selectedSeries != null -> {
                            SeriesDetailScreen(
                                series = selectedSeries!!, 
                                repository = repository, 
                                initialPlaybackPosition = lastPlaybackPosition,
                                onPositionUpdate = { pos -> lastPlaybackPosition = pos },
                                onBack = { selectedSeries = null }, 
                                onPlay = { movie: Movie, playlist: List<Movie>, pos: Long ->
                                    selectedMovie = movie
                                    moviePlaylist = playlist
                                    lastPlaybackPosition = pos
                                    saveWatchHistory(
                                        movie, 
                                        selectedSeries?.posterPath, 
                                        pos, 
                                        0L,
                                        selectedSeries?.title,
                                        selectedSeries?.fullPath
                                    )
                                },
                                onPreviewPlay = { movie -> 
                                    saveWatchHistory(
                                        movie, 
                                        selectedSeries?.posterPath, 
                                        0L, 
                                        0L,
                                        selectedSeries?.title,
                                        selectedSeries?.fullPath
                                    ) 
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
                                onSaveQuery = { queryText -> 
                                    scope.launch { 
                                        searchHistoryDataSource.insertQuery(queryText, currentTimeMillis())
                                        performSearch(queryText, searchCategory)
                                    }
                                },
                                onDeleteQuery = { queryToDelete -> scope.launch { searchHistoryDataSource.deleteQuery(queryToDelete) } },
                                onSeriesClick = { selectedSeries = it }
                            )
                        }
                        currentScreen == Screen.HOME -> {
                            HomeScreen(
                                watchHistory = watchHistory, 
                                homeSections = homeSections,
                                isLoading = isHomeLoading,
                                lazyListState = homeTvLazyListState,
                                onSeriesClick = { selectedSeries = it }, 
                                onPlayClick = { movie ->
                                    selectedMovie = movie
                                    moviePlaylist = listOf(movie)
                                    lastPlaybackPosition = 0L
                                },
                                onHistoryClick = { history ->
                                    // 상세 페이지 이동 시 시청 중이던 회차 정보를 미리 넣어주어 버튼이 즉시 나타나게 함
                                    selectedSeries = Series(
                                        title = history.seriesTitle ?: history.title,
                                        fullPath = history.seriesPath,
                                        posterPath = history.posterPath,
                                        episodes = listOf(
                                            Movie(
                                                id = history.id,
                                                title = history.title,
                                                videoUrl = history.videoUrl,
                                                thumbnailUrl = history.thumbnailUrl
                                            )
                                        )
                                    )
                                    lastPlaybackPosition = history.lastPosition
                                }
                            )
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
                                    cache = categoryCache,
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
