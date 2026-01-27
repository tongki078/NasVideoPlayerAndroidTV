package org.nas.videoplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.compose.setSingletonImageLoaderFactory
import coil3.ImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Category(
    val name: String,
    val movies: List<Movie> = emptyList()
)

@Serializable
data class Movie(
    val id: String,
    val title: String,
    val thumbnailUrl: String? = null,
    val videoUrl: String,
    val duration: String? = null
)

data class Series(
    val title: String,
    val episodes: List<Movie>,
    val thumbnailUrl: String? = null
)

enum class Screen { HOME, ANIMATIONS, FOREIGN_TV }

val client = HttpClient {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            isLenient = true
        })
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 100000
        connectTimeoutMillis = 60000
        socketTimeoutMillis = 100000
    }
}

fun String.toSafeUrl(): String {
    if (this.isBlank()) return this
    return this.replace(" ", "%20")
}

fun String.cleanTitle(): String {
    var cleaned = this
    
    // 1. 확장자 제거
    if (cleaned.contains(".")) {
        val ext = cleaned.substringAfterLast('.')
        if (ext.length in 2..4) cleaned = cleaned.substringBeforeLast('.')
    }

    // 2. (태그) -> [태그] 변경
    cleaned = cleaned.replace(Regex("^\\(([^)]+)\\)"), "[$1]")

    // 3. 날짜 패턴(.YYMMDD.)에서 연도 추출 (예: .251124. -> 2025)
    val dateMatch = Regex("\\.(\\d{2})\\d{4}(\\.|$)").find(cleaned)
    var extractedYear = ""
    if (dateMatch != null) {
        extractedYear = "20" + dateMatch.groupValues[1]
        cleaned = cleaned.replace(dateMatch.value, ".")
    }

    // 4. 에피소드 정보 제거 (그룹화를 위해 E01 등 제거)
    cleaned = cleaned.replace(Regex("(?i)\\.?[Ee]\\d+"), "")

    // 5. 불필요한 메타데이터 제거 (1080p, 720p, Bluray, KL 등)
    cleaned = cleaned.replace(Regex("\\.?\\d{3,4}p.*", RegexOption.IGNORE_CASE), "")
    cleaned = cleaned.replace(Regex("\\.Bluray.*", RegexOption.IGNORE_CASE), "")
    cleaned = cleaned.replace(Regex("-KL$", RegexOption.IGNORE_CASE), "")

    // 6. 점(.)을 공백으로 바꾸고 정리
    cleaned = cleaned.replace(".", " ").trim()
    
    // 7. 연도 형식 표준화
    cleaned = cleaned.replace(Regex("\\s*\\((\\d{4})\\)"), " - $1")
    if (extractedYear.isNotEmpty() && !cleaned.contains(extractedYear)) {
        cleaned = if (cleaned.contains(" - ")) cleaned else "$cleaned - $extractedYear"
    }

    return cleaned.trim().replace(Regex("\\s+"), " ")
}

fun String.extractEpisode(): String? {
    val eMatch = Regex("(?i)[Ee](\\d+)").find(this)
    if (eMatch != null) return "${eMatch.groupValues[1].toInt()}화"
    return null
}

fun String.prettyTitle(): String {
    val ep = this.extractEpisode()
    val base = this.cleanTitle()
    if (ep == null) return base
    
    return if (base.contains(" - ")) {
        val split = base.split(" - ", limit = 2)
        "${split[0]} $ep - ${split[1]}"
    } else {
        "$base $ep"
    }
}

fun List<Movie>.groupBySeries(): List<Series> {
    return this.groupBy { it.title.cleanTitle() }
        .map { (title, episodes) ->
            Series(
                title = title,
                episodes = episodes.sortedBy { it.title },
                thumbnailUrl = episodes.firstOrNull { it.thumbnailUrl != null }?.thumbnailUrl
            )
        }
}

@Composable
fun App() {
    setSingletonImageLoaderFactory { platformContext ->
        ImageLoader.Builder(platformContext)
            .components { add(KtorNetworkFetcherFactory(client)) }
            .crossfade(true)
            .build()
    }

    var homeLatestCategories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var homeAniCategories by remember { mutableStateOf<List<Category>>(emptyList()) }

    var foreignTvPathStack by remember { mutableStateOf<List<String>>(emptyList()) }
    var explorerItems by remember { mutableStateOf<List<Category>>(emptyList()) }

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedMovie by remember { mutableStateOf<Movie?>(null) }
    var selectedSeries by remember { mutableStateOf<Series?>(null) }
    var currentScreen by remember { mutableStateOf(Screen.HOME) }

    val isMovieFolder = currentScreen == Screen.FOREIGN_TV && explorerItems.any { it.movies.isNotEmpty() }

    LaunchedEffect(currentScreen, foreignTvPathStack) {
        try {
            errorMessage = null
            when (currentScreen) {
                Screen.HOME -> {
                    if (homeLatestCategories.isEmpty()) {
                        isLoading = true
                        homeLatestCategories = client.get("http://192.168.0.2:5000/latestmovies").body()
                        homeAniCategories = client.get("http://192.168.0.2:5000/animations").body()
                    }
                }
                Screen.ANIMATIONS -> {
                    if (homeAniCategories.isEmpty()) {
                        isLoading = true
                        homeAniCategories = client.get("http://192.168.0.2:5000/animations").body()
                    }
                }
                Screen.FOREIGN_TV -> {
                    isLoading = true
                    val pathQuery = if (foreignTvPathStack.isEmpty()) "" else "외국TV/${foreignTvPathStack.joinToString("/")}"
                    val url = if (pathQuery.isEmpty()) "http://192.168.0.2:5000/foreigntv"
                             else "http://192.168.0.2:5000/list?path=${pathQuery.encodeURLParameter()}"

                    val raw: List<Category> = client.get(url).body()
                    explorerItems = if (foreignTvPathStack.isEmpty()) {
                        raw.filter { it.name.contains("미국") || it.name.contains("중국") || it.name.contains("일본") }
                    } else raw
                }
            }
        } catch (e: Exception) {
            errorMessage = "연결 실패: ${e.message}"
        } finally {
            isLoading = false
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFE50914),
            background = Color.Black,
            surface = Color(0xFF121212)
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (selectedMovie != null) {
                VideoPlayerScreen(movie = selectedMovie!!) { selectedMovie = null }
            } else if (selectedSeries != null || isMovieFolder) {
                val seriesData = if (selectedSeries != null) {
                    selectedSeries!!
                } else {
                    val movies = explorerItems.flatMap { it.movies }
                    Series(
                        title = foreignTvPathStack.lastOrNull() ?: "외국 TV",
                        episodes = movies,
                        thumbnailUrl = movies.firstOrNull()?.thumbnailUrl
                    )
                }

                SeriesDetailScreen(
                    series = seriesData,
                    onBack = {
                        if (selectedSeries != null) selectedSeries = null
                        else foreignTvPathStack = foreignTvPathStack.dropLast(1)
                    },
                    onPlayFullScreen = { selectedMovie = it }
                )
            } else {
                Scaffold(
                    topBar = {
                        if (errorMessage == null) {
                            NetflixTopBar(currentScreen, onScreenSelected = {
                                currentScreen = it
                                if (it != Screen.FOREIGN_TV) foreignTvPathStack = emptyList()
                            })
                        }
                    },
                    bottomBar = { NetflixBottomNavigation() },
                    containerColor = Color.Black
                ) { paddingValues ->
                    Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
                        if (isLoading && (currentScreen != Screen.FOREIGN_TV || explorerItems.isEmpty())) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.Red) }
                        } else if (errorMessage != null) {
                            ErrorView(errorMessage!!) {
                                homeLatestCategories = emptyList()
                                currentScreen = Screen.HOME
                            }
                        } else {
                            when (currentScreen) {
                                Screen.HOME -> HomeScreen(homeLatestCategories, homeAniCategories) { selectedSeries = it }
                                Screen.ANIMATIONS -> CategoryListScreen("애니메이션", homeAniCategories) { selectedSeries = it }
                                Screen.FOREIGN_TV -> ForeignTvExplorer(
                                    pathStack = foreignTvPathStack,
                                    items = explorerItems,
                                    onFolderClick = { foreignTvPathStack = foreignTvPathStack + it },
                                    onBackClick = { if (foreignTvPathStack.isNotEmpty()) foreignTvPathStack = foreignTvPathStack.dropLast(1) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NasAppBar(title: String, onBack: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(64.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Black,
                fontSize = 22.sp,
                letterSpacing = (-0.5).sp
            ),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = if (onBack == null) 12.dp else 4.dp)
        )
    }
}

@Composable
fun ForeignTvExplorer(
    pathStack: List<String>,
    items: List<Category>,
    onFolderClick: (String) -> Unit,
    onBackClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        NasAppBar(
            title = if (pathStack.isEmpty()) "외국 TV" else pathStack.last(),
            onBack = if (pathStack.isNotEmpty()) onBackClick else null
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(items) { item ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clickable { onFolderClick(item.name) },
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1F1F1F))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color.White.copy(alpha = 0.1f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.List,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = item.name,
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.weight(1f))
                        Icon(imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Color.DarkGray)
                    }
                }
            }
        }
    }
}

@Composable
fun HomeScreen(latest: List<Category>, ani: List<Category>, onSeriesClick: (Series) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            val all = latest.flatMap { it.movies } + ani.flatMap { it.movies }
            HeroSection(all.firstOrNull()) { movie ->
                all.groupBySeries().find { it.episodes.any { ep -> ep.id == movie.id } }?.let { onSeriesClick(it) }
            }
        }
        item { MovieRow("최신 영화", latest.flatMap { it.movies }.groupBySeries(), onSeriesClick) }
        item { MovieRow("애니메이션", ani.flatMap { it.movies }.groupBySeries(), onSeriesClick) }
        item { Spacer(Modifier.height(100.dp)) }
    }
}

@Composable
fun CategoryListScreen(title: String, categories: List<Category>, onSeriesClick: (Series) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        NasAppBar(title = title)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item { MovieRow(title, categories.flatMap { it.movies }.groupBySeries(), onSeriesClick) }
        }
    }
}

@Composable
fun MovieRow(title: String, seriesList: List<Series>, onSeriesClick: (Series) -> Unit) {
    if (seriesList.isEmpty()) return
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.ExtraBold,
                fontSize = 20.sp,
                letterSpacing = (-0.5).sp
            ),
            color = Color.White,
            modifier = Modifier.padding(start = 16.dp, bottom = 12.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(seriesList) { series ->
                Card(
                    modifier = Modifier
                        .width(120.dp)
                        .height(180.dp)
                        .clickable { onSeriesClick(series) },
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Box(Modifier.fillMaxSize()) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalPlatformContext.current)
                                .data(series.thumbnailUrl?.toSafeUrl())
                                .crossfade(true)
                                .build(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        0.6f to Color.Transparent,
                                        1f to Color.Black.copy(alpha = 0.8f)
                                    )
                                )
                        )
                        Text(
                            text = series.title,
                            color = Color.White,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(8.dp),
                            style = TextStyle(
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                shadow = Shadow(color = Color.Black, blurRadius = 4f)
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SeriesDetailScreen(series: Series, onBack: () -> Unit, onPlayFullScreen: (Movie) -> Unit) {
    var playingMovie by remember(series) { mutableStateOf(series.episodes.firstOrNull()) }
    Column(modifier = Modifier.fillMaxSize().background(Color.Black).navigationBarsPadding()) {
        NasAppBar(title = series.title, onBack = onBack)

        Box(modifier = Modifier.fillMaxWidth().height(210.dp).background(Color.DarkGray)) {
            playingMovie?.let { movie ->
                VideoPlayer(url = movie.videoUrl.toSafeUrl(), modifier = Modifier.fillMaxSize(), onFullscreenClick = { onPlayFullScreen(movie) })
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 100.dp)
        ) {
            item {
                Text(
                    text = series.title,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                    modifier = Modifier.padding(16.dp)
                )
            }
            items(series.episodes) { ep ->
                ListItem(
                    headlineContent = { 
                        Text(
                            text = ep.title.extractEpisode() ?: ep.title.cleanTitle(),
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        ) 
                    },
                    leadingContent = {
                        Box(modifier = Modifier.width(120.dp).height(68.dp).background(Color(0xFF1A1A1A))) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalPlatformContext.current)
                                    .data(ep.thumbnailUrl?.toSafeUrl())
                                    .crossfade(true)
                                    .build(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.align(Alignment.Center).size(28.dp)
                            )
                        }
                    },
                    modifier = Modifier.clickable { playingMovie = ep },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }
    }
}

@Composable
fun VideoPlayerScreen(movie: Movie, onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        VideoPlayer(movie.videoUrl.toSafeUrl(), Modifier.fillMaxSize())
        IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().align(Alignment.TopEnd).padding(16.dp)) {
            Icon(imageVector = Icons.Default.Close, contentDescription = null, tint = Color.White)
        }
    }
}

@Composable
fun HeroSection(movie: Movie?, onPlayClick: (Movie) -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().height(480.dp)) {
        AsyncImage(
            model = movie?.thumbnailUrl?.toSafeUrl(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.4f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.9f)
                        ),
                        startY = 0f
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 24.dp, end = 24.dp, bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = movie?.title?.prettyTitle() ?: "",
                color = Color.White,
                textAlign = TextAlign.Center,
                style = TextStyle(
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-1).sp,
                    shadow = Shadow(color = Color.Black.copy(alpha = 0.8f), blurRadius = 12f)
                )
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = { movie?.let { onPlayClick(it) } },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.fillMaxWidth(0.5f).height(44.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                Spacer(Modifier.width(8.dp))
                Text("재생", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

@Composable
fun NetflixTopBar(currentScreen: Screen, onScreenSelected: (Screen) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "NAS",
            color = Color.Red,
            fontSize = 24.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.clickable { onScreenSelected(Screen.HOME) }
        )
        Spacer(modifier = Modifier.width(32.dp))
        Text(
            text = "애니메이션",
            color = if (currentScreen == Screen.ANIMATIONS) Color.White else Color.LightGray.copy(alpha = 0.7f),
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = if (currentScreen == Screen.ANIMATIONS) FontWeight.Bold else FontWeight.Medium,
                fontSize = 16.sp
            ),
            modifier = Modifier.clickable { onScreenSelected(Screen.ANIMATIONS) }
        )
        Spacer(modifier = Modifier.width(24.dp))
        Text(
            text = "외국 TV",
            color = if (currentScreen == Screen.FOREIGN_TV) Color.White else Color.LightGray.copy(alpha = 0.7f),
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = if (currentScreen == Screen.FOREIGN_TV) FontWeight.Bold else FontWeight.Medium,
                fontSize = 16.sp
            ),
            modifier = Modifier.clickable { onScreenSelected(Screen.FOREIGN_TV) }
        )
    }
}

@Composable
fun NetflixBottomNavigation() {
    NavigationBar(containerColor = Color.Black, contentColor = Color.White) {
        NavigationBarItem(
            selected = true, 
            onClick = {}, 
            icon = { Icon(imageVector = Icons.Default.Home, contentDescription = null) }, 
            label = { Text("홈") }, 
            colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent, selectedTextColor = Color.White, unselectedTextColor = Color.Gray)
        )
        NavigationBarItem(
            selected = false, 
            onClick = {}, 
            icon = { Icon(imageVector = Icons.Default.Search, contentDescription = null) }, 
            label = { Text("검색") }, 
            colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent, selectedTextColor = Color.White, unselectedTextColor = Color.Gray)
        )
    }
}

@Composable
fun ErrorView(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, color = Color.White, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRetry) { Text("재시도") }
        }
    }
}

fun PlaceholderScreen(title: String) {}
