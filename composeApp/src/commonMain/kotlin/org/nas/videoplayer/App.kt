package org.nas.videoplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import coil3.request.ImageRequest
import coil3.disk.DiskCache
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.compose.setSingletonImageLoaderFactory
import okio.Path.Companion.toPath
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.Composable
import kotlinx.serialization.json.Json
import org.nas.videoplayer.data.*
import org.nas.videoplayer.data.network.NasApiClient
import org.nas.videoplayer.data.repository.VideoRepositoryImpl
import org.nas.videoplayer.domain.model.*
import org.nas.videoplayer.domain.repository.VideoRepository
import org.nas.videoplayer.ui.home.HomeScreen
import org.nas.videoplayer.ui.search.SearchScreen
import org.nas.videoplayer.ui.detail.SeriesDetailScreen
import org.nas.videoplayer.ui.player.VideoPlayerScreen
import org.nas.videoplayer.ui.common.NetflixTopBar
import org.nas.videoplayer.db.AppDatabase
import com.squareup.sqldelight.db.SqlDriver

// --- 앱 설정 및 기본 클라이언트 (화면 로더용) ---
val client = NasApiClient.client

@Composable
fun App(driver: SqlDriver) {
    // 1. 초기화 및 Repository 설정
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
    
    val recentQueries by searchHistoryDataSource.getRecentQueries()
        .map { list -> list.map { it.toData() } }
        .collectAsState(initial = emptyList<SearchHistory>())
        
    val watchHistory by watchHistoryDataSource.getWatchHistory()
        .map { list -> list.map { it.toData() } }
        .collectAsState(initial = emptyList<WatchHistory>())

    val scope = rememberCoroutineScope()

    // 2. 전역 상태 관리 (domain.model.Screen 사용)
    var currentScreen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchCategory by rememberSaveable { mutableStateOf("전체") }
    var searchResultSeries by remember { mutableStateOf<List<Series>>(emptyList()) }
    var isSearchLoading by remember { mutableStateOf(false) }
    
    var selectedSeries by remember { mutableStateOf<Series?>(null) }
    var selectedMovie by remember { mutableStateOf<Movie?>(null) }
    var moviePlaylist by remember { mutableStateOf<List<Movie>>(emptyList()) }

    // 추천 섹션 데이터
    var homeLatestSeries by remember { mutableStateOf<List<Series>>(emptyList()) }
    var homeAnimations by remember { mutableStateOf<List<Series>>(emptyList()) }

    // 3. 데이터 로딩 (Side Effects)
    LaunchedEffect(currentScreen) {
        if (currentScreen == Screen.HOME && homeLatestSeries.isEmpty()) {
            homeLatestSeries = repository.getLatestMovies()
            homeAnimations = repository.getAnimations()
        }
    }

    LaunchedEffect(searchQuery, searchCategory) {
        if (searchQuery.length >= 2) {
            delay(500)
            isSearchLoading = true
            searchResultSeries = repository.searchVideos(searchQuery, searchCategory)
            isSearchLoading = false
        } else {
            searchResultSeries = emptyList()
        }
    }

    // 4. 메인 UI 구조
    MaterialTheme(colorScheme = darkColorScheme(primary = Color.Red, background = Color.Black)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = {
                    if (selectedMovie == null && currentScreen != Screen.SEARCH) {
                        NetflixTopBar(currentScreen) { currentScreen = it }
                    }
                },
                bottomBar = { 
                    if (selectedMovie == null) {
                        NavigationBar(containerColor = Color.Black) {
                            NavigationBarItem(
                                selected = currentScreen == Screen.HOME, 
                                onClick = { currentScreen = Screen.HOME; selectedSeries = null }, 
                                icon = { Icon(Icons.Default.Home, null) }, 
                                label = { Text("홈") }
                            )
                            NavigationBarItem(
                                selected = currentScreen == Screen.SEARCH, 
                                onClick = { currentScreen = Screen.SEARCH; selectedSeries = null }, 
                                icon = { Icon(Icons.Default.Search, null) }, 
                                label = { Text("검색") }
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
                                onBack = { selectedMovie = null }
                            )
                        }
                        selectedSeries != null -> {
                            SeriesDetailScreen(
                                series = selectedSeries!!, 
                                repository = repository, 
                                onBack = { selectedSeries = null }, 
                                onPlay = { movie, playlist -> 
                                    selectedMovie = movie
                                    moviePlaylist = playlist
                                }
                            )
                        }
                        currentScreen == Screen.SEARCH -> {
                            SearchScreen(
                                query = searchQuery,
                                onQueryChange = { searchQuery = it },
                                selectedCategory = searchCategory,
                                onCategoryChange = { searchCategory = it },
                                recentQueries = recentQueries,
                                searchResults = searchResultSeries,
                                isLoading = isSearchLoading,
                                onSaveQuery = { scope.launch { searchHistoryDataSource.insertQuery(it, currentTimeMillis()) } },
                                onDeleteQuery = { scope.launch { searchHistoryDataSource.deleteQuery(it) } },
                                onSeriesClick = { selectedSeries = it }
                            )
                        }
                        else -> {
                            HomeScreen(
                                watchHistory = watchHistory,
                                latestMovies = homeLatestSeries,
                                animations = homeAnimations,
                                onSeriesClick = { selectedSeries = it },
                                onPlayClick = { selectedMovie = it }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TmdbAsyncImage(title: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop, typeHint: String? = null, isLarge: Boolean = false) {
    var metadata by remember(title) { mutableStateOf(tmdbCache[title]) }
    var isError by remember(title) { mutableStateOf(false) }
    var isLoading by remember(title) { mutableStateOf(metadata == null) }
    val imageUrl = metadata?.posterUrl?.replace(TMDB_POSTER_SIZE_MEDIUM, if (isLarge) TMDB_POSTER_SIZE_LARGE else TMDB_POSTER_SIZE_SMALL)

    LaunchedEffect(title) {
        if (metadata == null) {
            isLoading = true; metadata = fetchTmdbMetadata(title, typeHint)
            isError = metadata?.posterUrl == null; isLoading = false
        } else { isError = metadata?.posterUrl == null; isLoading = false }
    }
    
    Box(modifier = modifier.background(Color(0xFF1A1A1A))) {
        if (imageUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current)
                    .data(imageUrl)
                    .crossfade(200)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                onError = { isError = true }
            )
        }
        if (isError && !isLoading) {
            Column(
                modifier = Modifier.fillMaxSize().padding(12.dp).background(Color.Black.copy(alpha = 0.3f)),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = Color.Red.copy(alpha = 0.5f), modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(8.dp))
                Text(
                    text = title.cleanTitle(includeYear = false),
                    color = Color.LightGray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = Color.DarkGray)
            }
        }
    }
}
