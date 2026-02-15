package org.nas.videoplayerandroidtv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import org.nas.videoplayerandroidtv.ui.common.NetflixTopBar
import org.nas.videoplayerandroidtv.ui.detail.SeriesDetailScreen
import org.nas.videoplayerandroidtv.ui.home.HomeScreen
import org.nas.videoplayerandroidtv.ui.player.VideoPlayerScreen
import org.nas.videoplayerandroidtv.ui.search.SearchScreen
import org.nas.videoplayerandroidtv.db.AppDatabase
import app.cash.sqldelight.db.SqlDriver

@Composable
fun App(driver: SqlDriver) {
    val repository: VideoRepository = remember { VideoRepositoryImpl() }
    val repoImpl = repository as VideoRepositoryImpl
    
    setSingletonImageLoaderFactory { ctx -> 
        ImageLoader.Builder(ctx)
            .diskCache { DiskCache.Builder().directory(getImageCacheDirectory(ctx).toPath().resolve("coil_cache")).maxSizeBytes(100 * 1024 * 1024).build() }
            .crossfade(true).build() 
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

    val allCategorySections = remember { mutableStateMapOf<Screen, List<HomeSection>>() }
    val categoryLoadingStates = remember { mutableStateMapOf<Screen, Boolean>() }
    
    LaunchedEffect(currentScreen) {
        if (!allCategorySections.containsKey(currentScreen)) {
            categoryLoadingStates[currentScreen] = true
            try {
                val sections = when(currentScreen) {
                    Screen.HOME -> repository.getHomeRecommendations()
                    Screen.MOVIES -> repoImpl.fetchCategorySections("movies")
                    Screen.KOREAN_TV -> repoImpl.fetchCategorySections("koreantv")
                    Screen.FOREIGN_TV -> repoImpl.fetchCategorySections("foreigntv")
                    Screen.ANIMATIONS -> repoImpl.fetchCategorySections("animations_all")
                    Screen.ON_AIR -> repoImpl.fetchCategorySections("air")
                    else -> emptyList()
                }
                allCategorySections[currentScreen] = sections
            } catch (_: Exception) {
            } finally {
                categoryLoadingStates[currentScreen] = false
            }
        }
    }

    val lazyListStates = remember { mutableMapOf<Screen, androidx.compose.foundation.lazy.LazyListState>() }

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
                                onPlay = { movie: Movie, playlist: List<Movie>, pos: Long ->
                                    selectedMovie = movie; moviePlaylist = playlist; lastPlaybackPosition = pos; 
                                    saveWatchHistory(movie, selectedSeries?.posterPath, pos, lastVideoDuration, selectedSeries?.title, selectedSeries?.fullPath)
                                }
                            )
                        }
                        currentScreen == Screen.SEARCH -> {
                            SearchScreen(searchQuery, { searchQuery = it }, recentQueries, searchResultSeries, isSearchLoading, { q -> scope.launch { searchHistoryDataSource.insertQuery(q, currentTimeMillis()) } }, { q -> scope.launch { searchHistoryDataSource.deleteQuery(q) } }, { selectedSeries = it })
                        }
                        else -> {
                            HomeScreen(
                                watchHistory = if (currentScreen == Screen.HOME) watchHistory else emptyList(), 
                                homeSections = allCategorySections[currentScreen] ?: emptyList(),
                                isLoading = categoryLoadingStates[currentScreen] ?: false,
                                lazyListState = lazyListStates.getOrPut(currentScreen) { androidx.compose.foundation.lazy.LazyListState() },
                                onSeriesClick = { selectedSeries = it }, 
                                onPlayClick = { m -> selectedMovie = m; moviePlaylist = listOf(m); lastPlaybackPosition = 0L },
                                onHistoryClick = { h -> 
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
