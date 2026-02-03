package org.nas.videoplayer

import androidx.compose.foundation.layout.*
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

    var homeLatestSeries by remember { mutableStateOf<List<Series>>(emptyList()) }
    var homeAnimations by remember { mutableStateOf<List<Series>>(emptyList()) }
    var isHomeLoading by remember { mutableStateOf(false) } // 홈 로딩 상태 추가

    LaunchedEffect(currentScreen) {
        if (currentScreen == Screen.HOME && homeLatestSeries.isEmpty()) {
            isHomeLoading = true
            try {
                // 병렬 로딩으로 속도 개선
                val latestDeferred = async { repository.getLatestMovies() }
                val animationsDeferred = async { repository.getAnimations() }
                homeLatestSeries = latestDeferred.await()
                homeAnimations = animationsDeferred.await()
            } finally {
                isHomeLoading = false
            }
        }
    }

    LaunchedEffect(searchQuery, searchCategory) {
        if (searchQuery.length >= 2) {
            delay(500); isSearchLoading = true
            searchResultSeries = repository.searchVideos(searchQuery, searchCategory)
            isSearchLoading = false
        } else searchResultSeries = emptyList()
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
                            VideoPlayerScreen(movie = selectedMovie!!, onBack = { selectedMovie = null })
                        }
                        selectedSeries != null -> {
                            SeriesDetailScreen(
                                series = selectedSeries!!, 
                                repository = repository, 
                                onBack = { selectedSeries = null }, 
                                onPlay = { movie, playlist -> selectedMovie = movie; moviePlaylist = playlist }
                            )
                        }
                        currentScreen == Screen.SEARCH -> {
                            SearchScreen(
                                query = searchQuery, onQueryChange = { searchQuery = it },
                                selectedCategory = searchCategory, onCategoryChange = { searchCategory = it },
                                recentQueries = recentQueries, searchResults = searchResultSeries, isLoading = isSearchLoading,
                                onSaveQuery = { scope.launch { searchHistoryDataSource.insertQuery(it, currentTimeMillis()) } },
                                onDeleteQuery = { scope.launch { searchHistoryDataSource.deleteQuery(it) } },
                                onSeriesClick = { selectedSeries = it }
                            )
                        }
                        currentScreen == Screen.HOME -> {
                            HomeScreen(
                                watchHistory = watchHistory, 
                                latestMovies = homeLatestSeries, 
                                animations = homeAnimations,
                                isLoading = isHomeLoading, // 로딩 상태 전달
                                onSeriesClick = { selectedSeries = it }, 
                                onPlayClick = { selectedMovie = it }
                            )
                        }
                        else -> {
                            val categoryInfo = when (currentScreen) {
                                Screen.ON_AIR -> "방송중" to "방송중"
                                Screen.ANIMATIONS -> "애니메이션" to "애니메이션"
                                Screen.MOVIES -> "영화" to "영화"
                                Screen.FOREIGN_TV -> "외국TV" to "외국TV"
                                Screen.KOREAN_TV -> "국내TV" to "국내TV"
                                else -> "" to ""
                            }
                            if (categoryInfo.first.isNotEmpty()) {
                                ThemedCategoryScreen(
                                    categoryName = categoryInfo.first, rootPath = categoryInfo.second,
                                    repository = repository, onSeriesClick = { selectedSeries = it }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
