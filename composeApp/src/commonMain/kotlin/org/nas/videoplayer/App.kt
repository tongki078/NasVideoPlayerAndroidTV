package org.nas.videoplayer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val BASE_URL = "http://192.168.0.2:5000"
const val IPHONE_USER_AGENT = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

// TMDB API 설정 (API Read Access Token 사용)
const val TMDB_API_KEY = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiI3OGNiYWQ0ZjQ3NzcwYjYyYmZkMTcwNTA2NDIwZDQyYyIsIm5iZiI6MTY1MzY3NTU4MC45MTUsInN1YiI6IjYyOTExNjNjMTI0MjVjMDA1MjI0ZGQzNCIsInNjb3BlcyI6WyJhcGlfcmVhZCJdLCJ2ZXJzaW9uIjoxfQ.3YU0WuIx_WDo6nTRKehRtn4N5I4uCgjI1tlpkqfsUhk"
const val TMDB_BASE_URL = "https://api.themoviedb.org/3"
const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w500"

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

@Serializable
data class TmdbSearchResponse(
    val results: List<TmdbResult>
)

@Serializable
data class TmdbResult(
    val id: Int,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("overview") val overview: String? = null
)

data class TmdbMetadata(
    val tmdbId: Int? = null,
    val mediaType: String? = null,
    val posterUrl: String? = null,
    val overview: String? = null
)

@Serializable
data class TmdbEpisodeResponse(
    val overview: String? = null,
    @SerialName("still_path") val stillPath: String? = null
)

enum class Screen { HOME, ON_AIR, ANIMATIONS, MOVIES, FOREIGN_TV, SEARCH, LATEST }

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
    defaultRequest {
        header("User-Agent", IPHONE_USER_AGENT)
    }
}

// TMDB에서 메타데이터(포스터 + 줄거리)를 찾아오는 함수
suspend fun fetchTmdbMetadata(title: String): TmdbMetadata {
    if (TMDB_API_KEY.isBlank() || TMDB_API_KEY == "YOUR_TMDB_API_KEY") return TmdbMetadata()
    
    // 0. 원본 근접 이름
    val rawClean = title.substringBeforeLast('.')
        .replace(Regex("\\[.*?\\]"), "")
        .replace(Regex("\\(.*?\\)"), "")
        .trim()
    if (rawClean.length >= 2) {
        val res = searchTmdb(rawClean, "ko-KR") ?: searchTmdb(rawClean, null)
        if (res != null) return res
    }

    // 1. 순수 제목(연도 제외)
    val baseTitle = title.cleanTitle(keepAfterHyphen = false, includeYear = false)
    if (baseTitle.isEmpty()) return TmdbMetadata()
    
    // 2. 연도 추출
    val yearRegex = Regex("(?<!\\d)(19|20)\\d{2}(?!\\d)")
    val year = yearRegex.find(title)?.value

    // 전략 1: 제목 + 연도
    val queryWithYear = if (year != null) "$baseTitle $year" else baseTitle
    var res = searchTmdb(queryWithYear, "ko-KR") ?: searchTmdb(queryWithYear, null)
    if (res != null) return res

    // 전략 2: 순수 제목만
    res = searchTmdb(baseTitle, "ko-KR") ?: searchTmdb(baseTitle, null)
    if (res != null) return res

    // 전략 3: 키워드 축소
    val words = baseTitle.split(" ").filter { it.isNotBlank() }
    if (words.size >= 2) {
        for (i in (words.size - 1) downTo 1) {
            val shortQuery = words.take(i).joinToString(" ")
            if (shortQuery.length < 2) continue
            res = searchTmdb(shortQuery, "ko-KR") ?: searchTmdb(shortQuery, null)
            if (res != null) return res
        }
    }
    
    return TmdbMetadata()
}

// 특정 에피소드의 상세 정보를 가져오는 함수
suspend fun fetchTmdbEpisodeOverview(tmdbId: Int, season: Int, episode: Int): String? {
    return try {
        val response: TmdbEpisodeResponse = client.get("$TMDB_BASE_URL/tv/$tmdbId/season/$season/episode/$episode") {
            if (TMDB_API_KEY.startsWith("eyJ")) header("Authorization", "Bearer $TMDB_API_KEY")
            else parameter("api_key", TMDB_API_KEY)
            parameter("language", "ko-KR")
        }.body()
        if (!response.overview.isNullOrBlank()) response.overview else fetchTmdbEpisodeOverviewEn(tmdbId, season, episode)
    } catch (e: Exception) {
        null
    }
}

private suspend fun fetchTmdbEpisodeOverviewEn(tmdbId: Int, season: Int, episode: Int): String? {
    return try {
        val response: TmdbEpisodeResponse = client.get("$TMDB_BASE_URL/tv/$tmdbId/season/$season/episode/$episode") {
            if (TMDB_API_KEY.startsWith("eyJ")) header("Authorization", "Bearer $TMDB_API_KEY")
            else parameter("api_key", TMDB_API_KEY)
        }.body()
        response.overview
    } catch (e: Exception) {
        null
    }
}

private suspend fun searchTmdb(query: String, language: String?): TmdbMetadata? {
    if (query.isBlank()) return null
    return try {
        val response: TmdbSearchResponse = client.get("$TMDB_BASE_URL/search/multi") {
            if (TMDB_API_KEY.startsWith("eyJ")) {
                header("Authorization", "Bearer $TMDB_API_KEY")
            } else {
                parameter("api_key", TMDB_API_KEY)
            }
            parameter("query", query)
            if (language != null) parameter("language", language)
            parameter("include_adult", "false")
        }.body()
        
        val result = response.results
            .filter { it.mediaType != "person" }
            .firstOrNull { it.posterPath != null } 
            ?: response.results.firstOrNull { it.backdropPath != null }
            
        val path = result?.posterPath ?: result?.backdropPath
        if (path != null) {
            TmdbMetadata(
                tmdbId = result?.id,
                mediaType = result?.mediaType,
                posterUrl = "$TMDB_IMAGE_BASE$path",
                overview = result?.overview
            )
        } else null
    } catch (e: Exception) {
        null
    }
}

@Composable
fun TmdbAsyncImage(
    title: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    var posterUrl by remember(title) { mutableStateOf<String?>(null) }
    var isError by remember(title) { mutableStateOf(false) }
    
    LaunchedEffect(title) {
        val meta = fetchTmdbMetadata(title)
        posterUrl = meta.posterUrl
        if (posterUrl == null) isError = true
    }

    Box(modifier = modifier.background(Color(0xFF1A1A1A))) {
        if (posterUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current)
                    .data(posterUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                onError = { isError = true }
            )
        }
        
        if (isError || (posterUrl == null)) {
            Column(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.DarkGray,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = title.cleanTitle(),
                    color = Color.DarkGray,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

fun getServeType(screen: Screen): String {
    return when (screen) {
        Screen.LATEST -> "latest"
        Screen.MOVIES -> "movie"
        Screen.ANIMATIONS -> "anim_all"
        Screen.FOREIGN_TV -> "ftv" 
        Screen.ON_AIR -> "anim"
        else -> "movie"
    }
}

fun getFullPath(pathStack: List<String>, fileName: String): String {
    val stackPath = pathStack.joinToString("/")
    return if (stackPath.isNotEmpty()) {
        if (fileName.contains("/")) fileName else "$stackPath/$fileName"
    } else {
        fileName
    }
}

fun createVideoServeUrl(currentScreen: Screen, pathStack: List<String>, movie: Movie): String {
    if (movie.videoUrl.startsWith("http")) return movie.videoUrl
    val type = getServeType(currentScreen)
    val fullPath = getFullPath(pathStack, movie.videoUrl)
    return URLBuilder("$BASE_URL/video_serve").apply {
        parameters["type"] = type
        parameters["path"] = fullPath
    }.buildString()
}

fun String.cleanTitle(keepAfterHyphen: Boolean = false, includeYear: Boolean = true): String {
    var cleaned = this

    if (cleaned.contains(".")) {
        val ext = cleaned.substringAfterLast('.')
        if (ext.length in 2..4) cleaned = cleaned.substringBeforeLast('.')
    }
    
    cleaned = cleaned.replace(Regex("([가-힣])([a-zA-Z0-9])"), "$1 $2")
    cleaned = cleaned.replace(Regex("([a-zA-Z0-9])([가-힣])"), "$1 $2")

    val yearRegex = Regex("\\((19|20)\\d{2}\\)|(?<!\\d)(19|20)\\d{2}(?!\\d)")
    val yearMatch = yearRegex.find(cleaned)
    val yearStr = yearMatch?.value?.replace("(", "")?.replace(")", "")
    
    if (yearMatch != null) {
        cleaned = cleaned.replace(yearMatch.value, " ")
    }

    cleaned = cleaned.replace(Regex("\\[.*?\\]"), " ").replace(Regex("\\(.*?\\)"), " ")

    if (!keepAfterHyphen && cleaned.contains(" - ")) {
        cleaned = cleaned.substringBefore(" - ")
    }

    cleaned = cleaned.replace(Regex("[.\\s_](?:\\d{6}|\\d{8})(?=[.\\s_]|$)"), " ")

    cleaned = cleaned.replace(Regex("(?i)\\.?[Ee]\\d+"), " ")
    cleaned = cleaned.replace(Regex("\\d+화|\\d+기|\\d+회|\\d+파트|Season\\s*\\d+|Part\\s*\\d+"), " ")
    
    val noisePattern = "(?i)\\.?(?:더빙|자막|무삭제|\\d{3,4}p|WEB-DL|WEBRip|Bluray|HDRip|BDRip|DVDRip|H\\.?26[45]|x26[45]|HEVC|AAC|DTS|AC3|DDP|Dual|Atmos|REPACK|KL|x86|x64|10bit|Multi|REMUX|OVA|OAD|ONA|TV판|극장판|Digital).*"
    cleaned = cleaned.replace(Regex(noisePattern), "")

    val trailingNumRegex = Regex("\\s(\\d+)(?!\\d)")
    val match = trailingNumRegex.find(cleaned)
    if (match != null) {
        val num = match.groupValues[1].toInt()
        if (num < 1000) { 
            cleaned = cleaned.replace(trailingNumRegex, " ")
        }
    }

    cleaned = cleaned.replace(Regex("[._\\-::!?]"), " ")
    cleaned = cleaned.replace(Regex("\\s+"), " ").trim()
    
    if (includeYear && yearStr != null) {
        cleaned = "$cleaned ($yearStr)"
    }
    
    return cleaned
}

fun String.extractEpisode(): String? {
    val eMatch = Regex("(?i)[Ee](\\d+)").find(this)
    if (eMatch != null) return "${eMatch.groupValues[1].toInt()}화"
    
    val hMatch = Regex("(\\d+)화").find(this)
    if (hMatch != null) return hMatch.groupValues[0]

    val rMatch = Regex("(\\d+)회").find(this)
    if (rMatch != null) return rMatch.groupValues[0]

    val numMatch = Regex("\\s(\\d+)(?:$|\\.)").find(this)
    if (numMatch != null) {
        val num = numMatch.groupValues[1].toInt()
        if (num < 1000) return "${num}화"
    }
    
    return null
}

fun String.extractSeason(): Int {
    val upper = this.uppercase()
    val sMatch = Regex("(?i)[Ss](\\d+)").find(this)
    if (sMatch != null) return sMatch.groupValues[1].toInt()

    val gMatch = Regex("(\\d+)기").find(this)
    if (gMatch != null) return gMatch.groupValues[1].toInt()

    if (upper.contains("세일러 문") || upper.contains("SAILOR MOON")) {
        if (Regex("[\\s._]R[\\s._]").containsMatchIn(upper) || upper.endsWith(" R")) return 2
        if (Regex("[\\s._]S[\\s._]").containsMatchIn(upper) || upper.endsWith(" S")) return 3
        if (upper.contains("SUPERS")) return 4
        if (upper.contains("STARS")) return 5
    }
    return 1
}

fun String.prettyTitle(): String {
    val ep = this.extractEpisode()
    val base = this.cleanTitle(keepAfterHyphen = true)
    if (ep == null) return base
    return if (base.contains(" - ")) {
        val split = base.split(" - ", limit = 2)
        "${split[0]} $ep - ${split[1]}"
    } else {
        "$base $ep"
    }
}

fun List<Movie>.groupBySeries(): List<Series> {
    return this.groupBy { it.title.cleanTitle(keepAfterHyphen = false) }
        .map { (title, episodes) ->
            Series(
                title = title,
                episodes = episodes.sortedBy { it.title },
                thumbnailUrl = null
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
    var onAirCategories by remember { mutableStateOf<List<Category>>(emptyList()) }
    
    var foreignTvPathStack by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var moviePathStack by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var aniPathStack by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    
    var foreignTvItems by remember { mutableStateOf<List<Category>>(emptyList()) }
    var movieItems by remember { mutableStateOf<List<Category>>(emptyList()) }
    var aniItems by remember { mutableStateOf<List<Category>>(emptyList()) }

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedMovie by remember { mutableStateOf<Movie?>(null) }
    var selectedSeries by remember { mutableStateOf<Series?>(null) }
    var selectedItemScreen by rememberSaveable { mutableStateOf(Screen.HOME) } 
    var currentScreen by rememberSaveable { mutableStateOf(Screen.HOME) }

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchCategory by rememberSaveable { mutableStateOf("전체") }

    val currentPathStack = when (currentScreen) {
        Screen.MOVIES -> moviePathStack
        Screen.ANIMATIONS -> aniPathStack
        Screen.FOREIGN_TV -> foreignTvPathStack
        else -> emptyList()
    }

    val isExplorerSeriesMode = (currentScreen == Screen.FOREIGN_TV && foreignTvItems.any { it.movies.isNotEmpty() }) ||
                               (currentScreen == Screen.MOVIES && movieItems.any { it.movies.isNotEmpty() }) ||
                               (currentScreen == Screen.ANIMATIONS && aniItems.any { it.movies.isNotEmpty() })

    LaunchedEffect(currentScreen, foreignTvPathStack, moviePathStack, aniPathStack) {
        try {
            errorMessage = null
            when (currentScreen) {
                Screen.HOME -> {
                    if (homeLatestCategories.isEmpty()) {
                        isLoading = true
                        homeLatestCategories = client.get("$BASE_URL/latestmovies").body()
                        onAirCategories = client.get("$BASE_URL/animations").body()
                    }
                }
                Screen.ON_AIR -> {
                    isLoading = true
                    onAirCategories = client.get("$BASE_URL/animations").body()
                }
                Screen.ANIMATIONS -> {
                    isLoading = true
                    val pathQuery = if (aniPathStack.isEmpty()) "" else "애니메이션/${aniPathStack.joinToString("/")}"
                    val url = if (pathQuery.isEmpty()) "$BASE_URL/animations_all"
                             else "$BASE_URL/list?path=${pathQuery.encodeURLParameter()}"
                    aniItems = client.get(url).body()
                }
                Screen.MOVIES -> {
                    isLoading = true
                    val pathQuery = if (moviePathStack.isEmpty()) "" else "영화/${moviePathStack.joinToString("/")}"
                    val url = if (pathQuery.isEmpty()) "$BASE_URL/movies"
                             else "$BASE_URL/list?path=${pathQuery.encodeURLParameter()}"
                    movieItems = client.get(url).body()
                }
                Screen.FOREIGN_TV -> {
                    isLoading = true
                    val pathQuery = if (foreignTvPathStack.isEmpty()) "" else "외국TV/${foreignTvPathStack.joinToString("/")}"
                    val url = if (pathQuery.isEmpty()) "$BASE_URL/foreigntv"
                             else "$BASE_URL/list?path=${pathQuery.encodeURLParameter()}"
                    foreignTvItems = client.get(url).body()
                }
                else -> {}
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
                VideoPlayerScreen(
                    movie = selectedMovie!!,
                    currentScreen = selectedItemScreen,
                    pathStack = currentPathStack,
                    onBack = { selectedMovie = null }
                )
            } else if (selectedSeries != null) {
                val categoryTitle = when (selectedItemScreen) {
                    Screen.ON_AIR -> "라프텔 애니메이션"
                    Screen.ANIMATIONS -> "애니메이션"
                    Screen.MOVIES, Screen.LATEST -> "영화"
                    Screen.FOREIGN_TV -> "외국 TV"
                    else -> ""
                }
                SeriesDetailScreen(
                    series = selectedSeries!!,
                    categoryTitle = categoryTitle,
                    currentScreen = selectedItemScreen,
                    pathStack = currentPathStack,
                    onBack = { selectedSeries = null },
                    onPlayFullScreen = { selectedMovie = it }
                )
            } else if (isExplorerSeriesMode) {
                val items = when (currentScreen) {
                    Screen.MOVIES -> movieItems
                    Screen.ANIMATIONS -> aniItems
                    else -> foreignTvItems
                }
                val allSeries = items.flatMap { it.movies }.groupBySeries()
                val categoryTitle = when (currentScreen) {
                    Screen.ANIMATIONS -> "애니메이션"
                    Screen.MOVIES -> "영화"
                    Screen.FOREIGN_TV -> "외국 TV"
                    else -> ""
                }
                
                Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    NasAppBar(
                        title = currentPathStack.lastOrNull() ?: categoryTitle,
                        onBack = {
                            when (currentScreen) {
                                Screen.MOVIES -> moviePathStack = moviePathStack.dropLast(1)
                                Screen.ANIMATIONS -> aniPathStack = aniPathStack.dropLast(1)
                                else -> foreignTvPathStack = foreignTvPathStack.dropLast(1)
                            }
                        }
                    )
                    BoxWithConstraints {
                        val isTablet = maxWidth > 600.dp
                        LazyVerticalGrid(
                            columns = if (isTablet) GridCells.Fixed(4) else GridCells.Fixed(3),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 100.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(allSeries) { series ->
                                SearchGridItem(series) {
                                    selectedSeries = it
                                    selectedItemScreen = currentScreen
                                }
                            }
                        }
                    }
                }
            } else {
                Scaffold(
                    topBar = {
                        if (errorMessage == null && currentScreen != Screen.SEARCH) {
                            NetflixTopBar(currentScreen, onScreenSelected = { currentScreen = it })
                        }
                    },
                    bottomBar = { 
                        NetflixBottomNavigation(
                            currentScreen = currentScreen,
                            onScreenSelected = { currentScreen = it }
                        ) 
                    },
                    containerColor = Color.Black
                ) { paddingValues ->
                    Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
                        val isDataEmpty = when(currentScreen) {
                            Screen.FOREIGN_TV -> foreignTvItems.isEmpty()
                            Screen.MOVIES -> movieItems.isEmpty()
                            Screen.ANIMATIONS -> aniItems.isEmpty()
                            else -> false
                        }
                        if (isLoading && isDataEmpty) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.Red) }
                        } else if (errorMessage != null) {
                            ErrorView(errorMessage!!) { currentScreen = Screen.HOME }
                        } else {
                            when (currentScreen) {
                                Screen.HOME -> HomeScreen(homeLatestCategories, onAirCategories) { series, screen -> 
                                    selectedSeries = series
                                    selectedItemScreen = screen
                                }
                                Screen.ON_AIR -> CategoryListScreen(
                                    title = "방송중",
                                    categories = onAirCategories
                                ) { series, screen ->
                                    selectedSeries = series
                                    selectedItemScreen = screen
                                }
                                Screen.ANIMATIONS -> MovieExplorer(
                                    title = "애니메이션",
                                    pathStack = aniPathStack,
                                    items = aniItems,
                                    onFolderClick = { aniPathStack = aniPathStack + it },
                                    onBackClick = { if (aniPathStack.isNotEmpty()) aniPathStack = aniPathStack.dropLast(1) }
                                )
                                Screen.MOVIES -> MovieExplorer(
                                    title = "영화",
                                    pathStack = moviePathStack,
                                    items = movieItems,
                                    onFolderClick = { moviePathStack = moviePathStack + it },
                                    onBackClick = { if (moviePathStack.isNotEmpty()) moviePathStack = moviePathStack.dropLast(1) }
                                )
                                Screen.FOREIGN_TV -> MovieExplorer(
                                    title = "외국 TV",
                                    pathStack = foreignTvPathStack,
                                    items = foreignTvItems,
                                    onFolderClick = { foreignTvPathStack = foreignTvPathStack + it },
                                    onBackClick = { if (foreignTvPathStack.isNotEmpty()) foreignTvPathStack = foreignTvPathStack.dropLast(1) }
                                )
                                Screen.SEARCH -> SearchScreen(
                                    query = searchQuery,
                                    onQueryChange = { searchQuery = it },
                                    selectedCategory = searchCategory,
                                    onCategoryChange = { searchCategory = it },
                                    onSeriesClick = { series, screen ->
                                        selectedSeries = series
                                        selectedItemScreen = screen
                                    }
                                )
                                else -> {}
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    selectedCategory: String,
    onCategoryChange: (String) -> Unit,
    onSeriesClick: (Series, Screen) -> Unit
) {
    val categories = listOf("전체", "방송중", "애니메이션", "영화", "외국TV")
    var searchResults by remember { mutableStateOf<List<Series>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    LaunchedEffect(query, selectedCategory) {
        if (query.isBlank()) {
            searchResults = emptyList()
            isSearching = false
            return@LaunchedEffect
        }
        delay(300)
        isSearching = true
        try {
            val response: List<Category> = client.get("$BASE_URL/search") {
                parameter("q", query)
                parameter("category", selectedCategory)
            }.body()
            searchResults = response.flatMap { it.movies }.groupBySeries()
        } catch (e: Exception) {
            println("검색 오류: ${e.message}")
        } finally {
            isSearching = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            placeholder = { Text("검색", color = Color.Gray) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF333333),
                unfocusedContainerColor = Color(0xFF333333),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = Color.Red,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            ),
            shape = RoundedCornerShape(8.dp),
            singleLine = true
        )

        Box(modifier = Modifier.fillMaxSize()) {
            if (isSearching) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.Red)
            } else {
                val screen = when (selectedCategory) {
                    "방송중" -> Screen.ON_AIR
                    "애니메이션" -> Screen.ANIMATIONS
                    "영화" -> Screen.MOVIES
                    "외국TV" -> Screen.FOREIGN_TV
                    else -> Screen.HOME
                }
                BoxWithConstraints {
                    val isTablet = maxWidth > 600.dp
                    LazyVerticalGrid(
                        columns = if (isTablet) GridCells.Fixed(4) else GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 100.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(searchResults) { series ->
                            SearchGridItem(series) { onSeriesClick(series, screen) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SearchGridItem(series: Series, onSeriesClick: (Series) -> Unit) {
    Card(modifier = Modifier.aspectRatio(0.67f).clickable { onSeriesClick(series) }, shape = RoundedCornerShape(4.dp)) {
        Box(Modifier.fillMaxSize()) {
            TmdbAsyncImage(
                title = series.title,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun MovieExplorer(
    title: String,
    pathStack: List<String>,
    items: List<Category>,
    onFolderClick: (String) -> Unit,
    onBackClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        NasAppBar(
            title = if (pathStack.isEmpty()) title else pathStack.last(),
            onBack = if (pathStack.isNotEmpty()) onBackClick else null
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(), 
            contentPadding = PaddingValues(start = 0.dp, top = 8.dp, end = 0.dp, bottom = 100.dp) 
        ) {
            items(items) { item ->
                ListItem(
                    headlineContent = { Text(text = item.name, color = Color.White) },
                    leadingContent = { Icon(imageVector = Icons.AutoMirrored.Filled.List, contentDescription = null, tint = Color.White) },
                    trailingContent = { Icon(imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Color.DarkGray) },
                    modifier = Modifier.clickable { onFolderClick(item.name) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }
    }
}

@Composable
fun NasAppBar(title: String, onBack: (() -> Unit)? = null) {
    Row(modifier = Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) { IconButton(onClick = onBack) { Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White) } }
        Text(text = title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black, fontSize = 24.sp), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = if (onBack == null) 12.dp else 4.dp))
    }
}

@Composable
fun HomeScreen(latest: List<Category>, ani: List<Category>, onSeriesClick: (Series, Screen) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp)) { 
        item {
            val latestMovies = latest.flatMap { it.movies }
            val aniMovies = ani.flatMap { it.movies }
            val all = latestMovies + aniMovies
            val heroMovie = all.firstOrNull()
            HeroSection(heroMovie) { movie -> 
                val isLatest = latestMovies.any { it.id == movie.id }
                val screen = if (isLatest) Screen.LATEST else Screen.ON_AIR
                all.groupBySeries().find { it.episodes.any { ep -> ep.id == movie.id } }?.let { onSeriesClick(it, screen) } 
            }
        }
        item { MovieRow("최신 영화", Screen.LATEST, latest.flatMap { it.movies }.groupBySeries(), onSeriesClick) }
        item { MovieRow("라프텔 애니메이션", Screen.ON_AIR, ani.flatMap { it.movies }.groupBySeries(), onSeriesClick) }
    }
}

@Composable
fun CategoryListScreen(
    title: String, 
    categories: List<Category>, 
    onSeriesClick: (Series, Screen) -> Unit
) {
    val allSeries = categories.flatMap { it.movies }.groupBySeries()
    
    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        NasAppBar(title = title)
        BoxWithConstraints {
            val isTablet = maxWidth > 600.dp
            LazyVerticalGrid(
                columns = if (isTablet) GridCells.Fixed(4) else GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 100.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(allSeries) { series ->
                    SearchGridItem(series) { onSeriesClick(series, Screen.ON_AIR) }
                }
            }
        }
    }
}

@Composable
fun MovieRow(title: String, screen: Screen, seriesList: List<Series>, onSeriesClick: (Series, Screen) -> Unit) {
    if (seriesList.isEmpty()) return
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold, fontSize = 22.sp), color = Color.White, modifier = Modifier.padding(start = 16.dp, bottom = 12.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(seriesList) { series ->
                Card(modifier = Modifier.width(120.dp).height(180.dp).clickable { onSeriesClick(series, screen) }, shape = RoundedCornerShape(4.dp)) {
                    Box(Modifier.fillMaxSize()) {
                        TmdbAsyncImage(
                            title = series.title,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SeriesDetailScreen(
    series: Series, 
    categoryTitle: String = "",
    currentScreen: Screen,
    pathStack: List<String>,
    onBack: () -> Unit, 
    onPlayFullScreen: (Movie) -> Unit
) {
    val scope = rememberCoroutineScope()
    var playingMovie by remember(series) { mutableStateOf(series.episodes.firstOrNull()) }
    var metadata by remember(series) { mutableStateOf<TmdbMetadata?>(null) }
    var currentEpisodeOverview by remember { mutableStateOf<String?>(null) }

    // 시리즈 전체 메타데이터 로드
    LaunchedEffect(series) {
        metadata = fetchTmdbMetadata(series.title)
    }

    // 에피소드 선택 시 해당 에피소드 줄거리 가져오기 시도
    LaunchedEffect(playingMovie, metadata) {
        val tmdbId = metadata?.tmdbId
        val mediaType = metadata?.mediaType
        if (tmdbId != null && mediaType == "tv" && playingMovie != null) {
            val fileName = playingMovie!!.title
            val epNumMatch = Regex("\\d+").find(fileName.extractEpisode() ?: "")
            val epNum = epNumMatch?.value?.toIntOrNull()
            val seasonNum = fileName.extractSeason()

            if (epNum != null) {
                currentEpisodeOverview = fetchTmdbEpisodeOverview(tmdbId, seasonNum, epNum)
            } else {
                currentEpisodeOverview = null
            }
        } else {
            currentEpisodeOverview = null
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            scope.launch { try { client.get("$BASE_URL/stop_all") } catch (e: Exception) {} }
        }
    }
    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        NasAppBar(title = categoryTitle, onBack = onBack)

        // 1. 영상 플레이어 영역
        Box(modifier = Modifier.fillMaxWidth().height(210.dp).background(Color.DarkGray)) {
            playingMovie?.let { movie -> 
                val finalUrl = createVideoServeUrl(currentScreen, pathStack, movie)
                VideoPlayer(url = finalUrl, modifier = Modifier.fillMaxSize(), onFullscreenClick = { onPlayFullScreen(movie) }) 
            }
        }

        // 2. 영상 하단 고정 정보 영역 (제목 및 줄거리)
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = series.title,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
            )

            // 줄거리 표시 (에피소드 줄거리 -> 시리즈 줄거리 순서)
            val displayOverview = currentEpisodeOverview ?: metadata?.overview
            if (!displayOverview.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = displayOverview,
                    color = Color.LightGray,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            } else if (metadata == null) {
                // 로딩 중 플레이스홀더
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(60.dp).background(Color.DarkGray.copy(alpha = 0.3f), RoundedCornerShape(4.dp)))
            }
        }

        Divider(color = Color.DarkGray, thickness = 1.dp)

        // 3. 에피소드 리스트
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp)) {
            items(series.episodes) { ep ->
                ListItem(
                    headlineContent = { Text(text = ep.title.extractEpisode() ?: ep.title.prettyTitle(), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = {
                        Box(modifier = Modifier.width(120.dp).height(68.dp).background(Color(0xFF1A1A1A))) {
                            TmdbAsyncImage(
                                title = ep.title,
                                modifier = Modifier.fillMaxSize()
                            )
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.align(Alignment.Center).size(28.dp))
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
fun VideoPlayerScreen(movie: Movie, currentScreen: Screen, pathStack: List<String>, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        onDispose {
            scope.launch { try { client.get("$BASE_URL/stop_all") } catch (e: Exception) {} }
        }
    }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val finalUrl = createVideoServeUrl(currentScreen, pathStack, movie)
        VideoPlayer(finalUrl, Modifier.fillMaxSize())
        IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().align(Alignment.TopEnd).padding(16.dp)) {
            Icon(imageVector = Icons.Default.Close, contentDescription = null, tint = Color.White)
        }
    }
}

@Composable
fun HeroSection(movie: Movie?, onPlayClick: (Movie) -> Unit) {
    if (movie == null) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(550.dp)
            .clickable { onPlayClick(movie) }
            .background(Color.Black)
    ) {
        Card(
            modifier = Modifier
                .width(300.dp)
                .height(450.dp)
                .align(Alignment.TopCenter)
                .padding(top = 20.dp),
            shape = RoundedCornerShape(8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
        ) {
            TmdbAsyncImage(
                title = movie.title,
                modifier = Modifier.fillMaxSize()
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.3f),
                            Color.Black
                        ),
                        startY = 300f
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = movie.title.cleanTitle(),
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    shadow = Shadow(color = Color.Black, blurRadius = 8f)
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { onPlayClick(movie) },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.width(120.dp).height(45.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                Spacer(Modifier.width(8.dp))
                Text("재생", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun NetflixTopBar(currentScreen: Screen, onScreenSelected: (Screen) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text = "N", color = Color.Red, fontSize = 32.sp, fontWeight = FontWeight.Black, modifier = Modifier.clickable { onScreenSelected(Screen.HOME) })
        Spacer(modifier = Modifier.width(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf("방송중" to Screen.ON_AIR, "애니" to Screen.ANIMATIONS, "영화" to Screen.MOVIES, "외국 TV" to Screen.FOREIGN_TV).forEach { (label, screen) ->
                Text(
                    text = label, 
                    color = if (currentScreen == screen) Color.White else Color.Gray,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.clickable { onScreenSelected(screen) }
                )
            }
        }
    }
}

@Composable
fun NetflixBottomNavigation(currentScreen: Screen, onScreenSelected: (Screen) -> Unit) {
    NavigationBar(containerColor = Color.Black, contentColor = Color.White) {
        NavigationBarItem(
            selected = currentScreen == Screen.HOME, 
            onClick = { onScreenSelected(Screen.HOME) }, 
            icon = { Icon(Icons.Default.Home, contentDescription = null) }, 
            label = { Text(text = "홈") }
        )
        NavigationBarItem(
            selected = currentScreen == Screen.SEARCH, 
            onClick = { onScreenSelected(Screen.SEARCH) }, 
            icon = { Icon(Icons.Default.Search, contentDescription = null) }, 
            label = { Text(text = "검색") }
        )
    }
}

@Composable
fun ErrorView(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, color = Color.White, textAlign = TextAlign.Center); Spacer(modifier = Modifier.height(16.dp)); Button(onClick = onRetry) { Text("재시도") }
        }
    }
}
