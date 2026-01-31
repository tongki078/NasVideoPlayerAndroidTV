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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
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
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val BASE_URL = "http://192.168.0.2:5000"
const val IPHONE_USER_AGENT = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

const val TMDB_API_KEY = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiI3OGNiYWQ0ZjQ3NzcwYjYyYmZkMTcwNTA2NDIwZDQyYyIsIm5iZiI6MTY1MzY3NTU4MC45MTUsInN1YiI6IjYyOTExNjNjMTI0MjVjMDA1MjI0ZGQzNCIsInNjb3BlcyI6WyJhcGlfcmVhZCJdLCJ2ZXJzaW9uIjoxfQ.3YU0WuIx_WDo6nTRKehRtn4N5I4uCgjI1tlpkqfsUhk"
const val TMDB_BASE_URL = "https://api.themoviedb.org/3"
const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w342" 

// Pre-compiled regex for performance
private val REGEX_YEAR = Regex("\\((19|20)\\d{2}\\)|(?<!\\d)(19|20)\\d{2}(?!\\d)")
private val REGEX_BRACKETS = Regex("\\[.*?\\]|\\(.*?\\)")
private val REGEX_EPISODE_E = Regex("(?i)[Ee](\\d+)")
private val REGEX_EPISODE_K = Regex("(\\d+)(?:화|회)")
private val REGEX_EPISODE_NUM = Regex("\\s(\\d+)(?:$|\\.)")
private val REGEX_SEASON_S = Regex("(?i)[Ss](\\d+)")
private val REGEX_SEASON_G = Regex("(\\d+)기")
private val REGEX_NOISE = Regex("(?i)\\.?(?:더빙|자막|무삭제|\\d{3,4}p|WEB-DL|WEBRip|Bluray|HDRip|BDRip|DVDRip|H\\.?26[45]|x26[45]|HEVC|AAC|DTS|AC3|DDP|Dual|Atmos|REPACK|KL|x86|x64|10bit|Multi|REMUX|OVA|OAD|ONA|TV판|극장판|Digital).*")
private val REGEX_DATE_NOISE = Regex("[.\\s_](?:\\d{6}|\\d{8})(?=[.\\s_]|$)")
private val REGEX_CLEAN_EXTRA = Regex("(?i)\\.?[Ee]\\d+|\\d+화|\\d+기|\\d+회|\\d+파트|Season\\s*\\d+|Part\\s*\\d+")
private val REGEX_TRAILING_NUM = Regex("\\s(\\d+)(?!\\d)")
private val REGEX_SYMBOLS = Regex("[._\\-::!?]")
private val REGEX_WHITESPACE = Regex("\\s+")
private val REGEX_KOR_ENG = Regex("([가-힣])([a-zA-Z0-9])")
private val REGEX_ENG_KOR = Regex("([a-zA-Z0-9])([가-힣])")

@Serializable
data class Category(val name: String, val movies: List<Movie> = emptyList())
@Serializable
data class Movie(val id: String, val title: String, val thumbnailUrl: String? = null, val videoUrl: String, val duration: String? = null)
data class Series(val title: String, val episodes: List<Movie>, val thumbnailUrl: String? = null)
@Serializable
data class TmdbSearchResponse(val results: List<TmdbResult>)
@Serializable
data class TmdbResult(val id: Int, @SerialName("poster_path") val posterPath: String? = null, @SerialName("backdrop_path") val backdropPath: String? = null, val name: String? = null, val title: String? = null, @SerialName("media_type") val mediaType: String? = null, val overview: String? = null)
data class TmdbMetadata(val tmdbId: Int? = null, val mediaType: String? = null, val posterUrl: String? = null, val overview: String? = null)
@Serializable
data class TmdbEpisodeResponse(val overview: String? = null, @SerialName("still_path") val stillPath: String? = null)

enum class Screen { HOME, ON_AIR, ANIMATIONS, MOVIES, FOREIGN_TV, SEARCH, LATEST }

val client = HttpClient {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
    install(HttpTimeout) { requestTimeoutMillis = 100000; connectTimeoutMillis = 60000; socketTimeoutMillis = 100000 }
    defaultRequest { header("User-Agent", IPHONE_USER_AGENT) }
}

private val tmdbCache = mutableMapOf<String, TmdbMetadata>()
private val tmdbSemaphore = Semaphore(5)

suspend fun fetchTmdbMetadata(title: String): TmdbMetadata {
    if (TMDB_API_KEY.isBlank() || TMDB_API_KEY == "YOUR_TMDB_API_KEY") return TmdbMetadata()
    tmdbCache[title]?.let { return it }
    val cleanTitle = title.cleanTitle(includeYear = false)
    val year = REGEX_YEAR.find(title)?.value
    val strategies = mutableListOf<String>()
    if (cleanTitle.isNotEmpty()) { strategies.add(cleanTitle); if (year != null) strategies.add("$cleanTitle $year") }
    val rawClean = title.substringBeforeLast('.').let { REGEX_BRACKETS.replace(it, " ") }.let { REGEX_WHITESPACE.replace(it, " ") }.trim()
    if (rawClean.isNotEmpty() && rawClean != cleanTitle) strategies.add(rawClean)
    var finalMetadata: TmdbMetadata? = null
    var hasNetworkError = false
    for (query in strategies.distinct()) {
        val (res, error) = searchTmdb(query, "ko-KR")
        if (error != null) { hasNetworkError = true; break }
        var result = res
        if (result == null) {
            val (resEn, errorEn) = searchTmdb(query, null)
            if (errorEn != null) { hasNetworkError = true; break }
            result = resEn
        }
        if (result != null) { finalMetadata = result; break }
    }
    if (finalMetadata == null && !hasNetworkError && cleanTitle.split(" ").size >= 2) {
        val words = cleanTitle.split(" ").filter { it.isNotBlank() }
        for (i in (words.size - 1) downTo 1) {
            val shortQuery = words.take(i).joinToString(" ")
            if (shortQuery.length < 2) continue
            val (res, _) = searchTmdb(shortQuery, "ko-KR")
            if (res != null) { finalMetadata = res; break }
        }
    }
    val result = finalMetadata ?: TmdbMetadata()
    if (!hasNetworkError) tmdbCache[title] = result
    return result
}

suspend fun fetchTmdbEpisodeOverview(tmdbId: Int, season: Int, episode: Int): String? {
    return try {
        val response: TmdbEpisodeResponse = client.get("$TMDB_BASE_URL/tv/$tmdbId/season/$season/episode/$episode") {
            if (TMDB_API_KEY.startsWith("eyJ")) header("Authorization", "Bearer $TMDB_API_KEY")
            else parameter("api_key", TMDB_API_KEY)
            parameter("language", "ko-KR")
        }.body()
        if (!response.overview.isNullOrBlank()) response.overview else fetchTmdbEpisodeOverviewEn(tmdbId, season, episode)
    } catch (e: Exception) { null }
}

private suspend fun fetchTmdbEpisodeOverviewEn(tmdbId: Int, season: Int, episode: Int): String? {
    return try {
        val response: TmdbEpisodeResponse = client.get("$TMDB_BASE_URL/tv/$tmdbId/season/$season/episode/$episode") {
            if (TMDB_API_KEY.startsWith("eyJ")) header("Authorization", "Bearer $TMDB_API_KEY")
            else parameter("api_key", TMDB_API_KEY)
        }.body()
        response.overview
    } catch (e: Exception) { null }
}

private suspend fun searchTmdb(query: String, language: String?): Pair<TmdbMetadata?, Exception?> {
    if (query.isBlank()) return null to null
    return tmdbSemaphore.withPermit {
        try {
            val response: TmdbSearchResponse = client.get("$TMDB_BASE_URL/search/multi") {
                if (TMDB_API_KEY.startsWith("eyJ")) header("Authorization", "Bearer $TMDB_API_KEY")
                else parameter("api_key", TMDB_API_KEY)
                parameter("query", query); if (language != null) parameter("language", language); parameter("include_adult", "false")
            }.body()
            val result = response.results.filter { it.mediaType != "person" }.firstOrNull { it.posterPath != null } ?: response.results.firstOrNull { it.backdropPath != null }
            val path = result?.posterPath ?: result?.backdropPath
            if (path != null) Pair(TmdbMetadata(tmdbId = result?.id, mediaType = result?.mediaType, posterUrl = "$TMDB_IMAGE_BASE$path", overview = result?.overview), null)
            else Pair(null, null)
        } catch (e: Exception) { Pair(null, e) }
    }
}

@Composable
fun TmdbAsyncImage(title: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    var metadata by remember(title) { mutableStateOf(tmdbCache[title]) }
    var isError by remember(title) { mutableStateOf(false) }
    var isLoading by remember(title) { mutableStateOf(metadata == null) }
    LaunchedEffect(title) {
        if (metadata == null) {
            isLoading = true
            metadata = fetchTmdbMetadata(title)
            isError = metadata?.posterUrl == null
            isLoading = false
        } else { isError = metadata?.posterUrl == null; isLoading = false }
    }
    Box(modifier = modifier.background(Color(0xFF1A1A1A))) {
        if (metadata?.posterUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current).data(metadata!!.posterUrl).crossfade(200).build(),
                contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = contentScale, onError = { isError = true }
            )
        }
        if (isError && !isLoading) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp).background(Color.Black.copy(alpha = 0.3f)), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = Color.Red.copy(alpha = 0.5f), modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(8.dp))
                Text(text = title.cleanTitle(), color = Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 5, overflow = TextOverflow.Ellipsis)
            }
        } else if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = Color.DarkGray)
            }
        }
    }
}

fun getServeType(screen: Screen, tabName: String = ""): String = when (screen) {
    Screen.LATEST -> "latest"
    Screen.MOVIES -> "movie"
    Screen.ANIMATIONS -> "anim_all"
    Screen.FOREIGN_TV -> "ftv" 
    Screen.ON_AIR -> if (tabName == "드라마") "drama" else "anim"
    else -> "movie"
}

fun getFullPath(pathStack: List<String>, fileName: String): String {
    val stackPath = pathStack.joinToString("/")
    return if (stackPath.isNotEmpty()) { if (fileName.contains("/")) fileName else "$stackPath/$fileName" } else fileName
}

fun createVideoServeUrl(currentScreen: Screen, pathStack: List<String>, movie: Movie, tabName: String = ""): String {
    if (movie.videoUrl.startsWith("http")) return movie.videoUrl
    return URLBuilder("$BASE_URL/video_serve").apply {
        parameters["type"] = getServeType(currentScreen, tabName)
        parameters["path"] = getFullPath(pathStack, movie.videoUrl)
    }.buildString()
}

fun String.cleanTitle(keepAfterHyphen: Boolean = false, includeYear: Boolean = true): String {
    var cleaned = this
    if (cleaned.contains(".")) { val ext = cleaned.substringAfterLast('.'); if (ext.length in 2..4) cleaned = cleaned.substringBeforeLast('.') }
    cleaned = REGEX_KOR_ENG.replace(cleaned, "$1 $2")
    cleaned = REGEX_ENG_KOR.replace(cleaned, "$1 $2")
    val yearMatch = REGEX_YEAR.find(cleaned)
    val yearStr = yearMatch?.value?.replace("(", "")?.replace(")", "")
    if (yearMatch != null) cleaned = cleaned.replace(yearMatch.value, " ")
    cleaned = REGEX_BRACKETS.replace(cleaned, " ")
    if (!keepAfterHyphen && cleaned.contains(" - ")) cleaned = cleaned.substringBefore(" - ")
    cleaned = REGEX_DATE_NOISE.replace(cleaned, " ")
    cleaned = REGEX_CLEAN_EXTRA.replace(cleaned, " ")
    cleaned = REGEX_NOISE.replace(cleaned, "")
    val match = REGEX_TRAILING_NUM.find(cleaned)
    if (match != null && match.groupValues[1].toInt() < 1000) cleaned = cleaned.replace(REGEX_TRAILING_NUM, " ")
    cleaned = REGEX_SYMBOLS.replace(cleaned, " ")
    cleaned = REGEX_WHITESPACE.replace(cleaned, " ").trim()
    if (includeYear && yearStr != null) cleaned = "$cleaned ($yearStr)"
    return cleaned
}

fun String.extractEpisode(): String? {
    REGEX_EPISODE_E.find(this)?.let { return "${it.groupValues[1].toInt()}화" }
    REGEX_EPISODE_K.find(this)?.let { return "${it.groupValues[1].toInt()}화" }
    REGEX_EPISODE_NUM.find(this)?.let { if (it.groupValues[1].toInt() < 1000) return "${it.groupValues[1].toInt()}화" }
    return null
}

fun String.extractSeason(): Int {
    REGEX_SEASON_S.find(this)?.let { return it.groupValues[1].toInt() }
    REGEX_SEASON_G.find(this)?.let { return it.groupValues[1].toInt() }
    val upper = this.uppercase()
    if (upper.contains("세일러 문") || upper.contains("SAILOR MOON")) {
        if (upper.contains(" R ")) return 2; if (upper.contains(" S ")) return 3; if (upper.contains("SUPERS")) return 4; if (upper.contains("STARS")) return 5
    }
    return 1
}

fun String.prettyTitle(): String {
    val ep = this.extractEpisode(); val base = this.cleanTitle(keepAfterHyphen = true)
    if (ep == null) return base
    return if (base.contains(" - ")) { val split = base.split(" - ", limit = 2); "${split[0]} $ep - ${split[1]}" } else "$base $ep"
}

fun List<Movie>.groupBySeries(): List<Series> = this.groupBy { it.title.cleanTitle(keepAfterHyphen = false) }
    .map { (title, episodes) -> Series(title = title, episodes = episodes.sortedBy { it.title }, thumbnailUrl = null) }

@Composable
fun App() {
    setSingletonImageLoaderFactory { platformContext ->
        ImageLoader.Builder(platformContext).components { add(KtorNetworkFetcherFactory(client)) }.crossfade(true).build()
    }
    var homeLatestSeries by remember { mutableStateOf<List<Series>>(emptyList()) }
    var onAirSeries by remember { mutableStateOf<List<Series>>(emptyList()) }
    var onAirDramaSeries by remember { mutableStateOf<List<Series>>(emptyList()) }
    var selectedOnAirTab by rememberSaveable { mutableStateOf("라프텔 애니메이션") }

    var aniItems by remember { mutableStateOf<List<Category>>(emptyList()) }
    var movieItems by remember { mutableStateOf<List<Category>>(emptyList()) }
    var foreignTvItems by remember { mutableStateOf<List<Category>>(emptyList()) }
    var explorerSeries by remember { mutableStateOf<List<Series>>(emptyList()) }

    var foreignTvPathStack by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var moviePathStack by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var aniPathStack by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    
    var lastAniPath by remember { mutableStateOf<String?>(null) }
    var lastMoviePath by remember { mutableStateOf<String?>(null) }
    var lastForeignPath by remember { mutableStateOf<String?>(null) }

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedMovie by remember { mutableStateOf<Movie?>(null) }
    var selectedSeries by remember { mutableStateOf<Series?>(null) }
    var selectedItemScreen by rememberSaveable { mutableStateOf(Screen.HOME) } 
    var currentScreen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchCategory by rememberSaveable { mutableStateOf("전체") }

    val currentPathStack = remember(currentScreen, moviePathStack, aniPathStack, foreignTvPathStack) {
        when (currentScreen) { Screen.MOVIES -> moviePathStack; Screen.ANIMATIONS -> aniPathStack; Screen.FOREIGN_TV -> foreignTvPathStack; else -> emptyList() }
    }
    val isExplorerSeriesMode by remember {
        derivedStateOf {
            (currentScreen == Screen.FOREIGN_TV && foreignTvItems.any { it.movies.isNotEmpty() }) || 
            (currentScreen == Screen.MOVIES && movieItems.any { it.movies.isNotEmpty() }) || 
            (currentScreen == Screen.ANIMATIONS && aniItems.any { it.movies.isNotEmpty() })
        }
    }

    LaunchedEffect(currentScreen, foreignTvPathStack, moviePathStack, aniPathStack, selectedOnAirTab) {
        try {
            errorMessage = null
            when (currentScreen) {
                Screen.HOME -> {
                    if (homeLatestSeries.isEmpty()) {
                        isLoading = true
                        val lRes = async { client.get("$BASE_URL/latestmovies").body<List<Category>>() }
                        val aRes = async { client.get("$BASE_URL/animations").body<List<Category>>() }
                        val lData = lRes.await(); val aData = aRes.await()
                        withContext(Dispatchers.Default) {
                            homeLatestSeries = lData.flatMap { it.movies }.groupBySeries()
                            onAirSeries = aData.flatMap { it.movies }.groupBySeries()
                        }
                    }
                }
                Screen.ON_AIR -> {
                    if (selectedOnAirTab == "라프텔 애니메이션" && onAirSeries.isEmpty()) {
                        isLoading = true
                        val raw: List<Category> = client.get("$BASE_URL/animations").body()
                        withContext(Dispatchers.Default) { onAirSeries = raw.flatMap { it.movies }.groupBySeries() }
                    } else if (selectedOnAirTab == "드라마" && onAirDramaSeries.isEmpty()) {
                        isLoading = true
                        val raw: List<Category> = client.get("$BASE_URL/dramas").body()
                        withContext(Dispatchers.Default) { onAirDramaSeries = raw.flatMap { it.movies }.groupBySeries() }
                    }
                }
                Screen.ANIMATIONS -> {
                    val path = if (aniPathStack.isEmpty()) "애니메이션" else "애니메이션/${aniPathStack.joinToString("/")}"
                    if (lastAniPath != path) {
                        isLoading = true
                        val raw: List<Category> = client.get("$BASE_URL/list?path=${path.encodeURLParameter()}").body()
                        aniItems = raw
                        if (raw.any { it.movies.isNotEmpty() }) {
                            withContext(Dispatchers.Default) { explorerSeries = raw.flatMap { it.movies }.groupBySeries() }
                        }
                        lastAniPath = path
                    }
                }
                Screen.MOVIES -> {
                    val path = if (moviePathStack.isEmpty()) "영화" else "영화/${moviePathStack.joinToString("/")}"
                    if (lastMoviePath != path) {
                        isLoading = true
                        val raw: List<Category> = client.get("$BASE_URL/list?path=${path.encodeURLParameter()}").body()
                        movieItems = raw
                        if (raw.any { it.movies.isNotEmpty() }) {
                            withContext(Dispatchers.Default) { explorerSeries = raw.flatMap { it.movies }.groupBySeries() }
                        }
                        lastMoviePath = path
                    }
                }
                Screen.FOREIGN_TV -> {
                    val path = if (foreignTvPathStack.isEmpty()) "외국TV" else "외국TV/${foreignTvPathStack.joinToString("/")}"
                    if (lastForeignPath != path) {
                        isLoading = true
                        val raw: List<Category> = client.get("$BASE_URL/list?path=${path.encodeURLParameter()}").body()
                        foreignTvItems = raw
                        if (raw.any { it.movies.isNotEmpty() }) {
                            withContext(Dispatchers.Default) { explorerSeries = raw.flatMap { it.movies }.groupBySeries() }
                        }
                        lastForeignPath = path
                    }
                }
                else -> {}
            }
        } catch (e: Exception) { errorMessage = "연결 실패: ${e.message}" } finally { isLoading = false }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFE50914), background = Color.Black, surface = Color(0xFF121212))) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (selectedMovie != null) {
                VideoPlayerScreen(movie = selectedMovie!!, currentScreen = selectedItemScreen, pathStack = currentPathStack, onBack = { selectedMovie = null }, tabName = selectedOnAirTab)
            } else if (selectedSeries != null) {
                val catTitle = when (selectedItemScreen) { Screen.ON_AIR -> selectedOnAirTab; Screen.ANIMATIONS -> "애니메이션"; Screen.MOVIES, Screen.LATEST -> "영화"; Screen.FOREIGN_TV -> "외국 TV"; else -> "" }
                SeriesDetailScreen(series = selectedSeries!!, categoryTitle = catTitle, currentScreen = selectedItemScreen, pathStack = currentPathStack, onBack = { selectedSeries = null }, onPlayFullScreen = { selectedMovie = it })
            } else if (isExplorerSeriesMode) {
                val items = when (currentScreen) { Screen.MOVIES -> movieItems; Screen.ANIMATIONS -> aniItems; else -> foreignTvItems }
                val allSeries = remember(items) { items.flatMap { it.movies }.groupBySeries() }
                val catTitle = when (currentScreen) { Screen.ANIMATIONS -> "애니메이션"; Screen.MOVIES -> "영화"; Screen.FOREIGN_TV -> "외국 TV"; else -> "" }
                Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    NasAppBar(title = currentPathStack.lastOrNull() ?: catTitle, onBack = {
                        when (currentScreen) { Screen.MOVIES -> moviePathStack = moviePathStack.dropLast(1); Screen.ANIMATIONS -> aniPathStack = aniPathStack.dropLast(1); else -> foreignTvPathStack = foreignTvPathStack.dropLast(1) }
                    })
                    BoxWithConstraints {
                        val columns = if (maxWidth > 600.dp) GridCells.Fixed(4) else GridCells.Fixed(3)
                        LazyVerticalGrid(columns = columns, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 100.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(allSeries) { series -> SearchGridItem(series) { selectedSeries = it; selectedItemScreen = currentScreen } }
                        }
                    }
                }
            } else {
                Scaffold(
                    topBar = { if (errorMessage == null && currentScreen != Screen.SEARCH) NetflixTopBar(currentScreen, onScreenSelected = { currentScreen = it }) },
                    bottomBar = { NetflixBottomNavigation(currentScreen = currentScreen, onScreenSelected = { currentScreen = it }) },
                    containerColor = Color.Black
                ) { pv ->
                    Box(modifier = Modifier.padding(pv).fillMaxSize()) {
                        val isDataEmpty by remember { derivedStateOf { when(currentScreen) { Screen.HOME -> homeLatestSeries.isEmpty() && onAirSeries.isEmpty(); Screen.ON_AIR -> (if(selectedOnAirTab == "라프텔 애니메이션") onAirSeries else onAirDramaSeries).isEmpty(); Screen.FOREIGN_TV -> foreignTvItems.isEmpty(); Screen.MOVIES -> movieItems.isEmpty(); Screen.ANIMATIONS -> aniItems.isEmpty(); else -> false } } }
                        if (isLoading && isDataEmpty) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.Red) } }
                        else if (errorMessage != null) { ErrorView(errorMessage!!) { currentScreen = Screen.HOME } }
                        else {
                            when (currentScreen) {
                                Screen.HOME -> HomeScreen(homeLatestSeries, onAirSeries) { s, sc -> selectedSeries = s; selectedItemScreen = sc }
                                Screen.ON_AIR -> CategoryListScreen(
                                    selectedFilter = selectedOnAirTab,
                                    onFilterSelected = { selectedOnAirTab = it },
                                    seriesList = if (selectedOnAirTab == "라프텔 애니메이션") onAirSeries else onAirDramaSeries
                                ) { s, sc -> selectedSeries = s; selectedItemScreen = sc }
                                Screen.ANIMATIONS -> MovieExplorer(title = "애니메이션", pathStack = aniPathStack, items = aniItems, onFolderClick = { aniPathStack = aniPathStack + it }, onBackClick = { if (aniPathStack.isNotEmpty()) aniPathStack = aniPathStack.dropLast(1) })
                                Screen.MOVIES -> MovieExplorer(title = "영화", pathStack = moviePathStack, items = movieItems, onFolderClick = { moviePathStack = moviePathStack + it }, onBackClick = { if (moviePathStack.isNotEmpty()) moviePathStack = moviePathStack.dropLast(1) })
                                Screen.FOREIGN_TV -> MovieExplorer(title = "외국 TV", pathStack = foreignTvPathStack, items = foreignTvItems, onFolderClick = { foreignTvPathStack = foreignTvPathStack + it }, onBackClick = { if (foreignTvPathStack.isNotEmpty()) foreignTvPathStack = foreignTvPathStack.dropLast(1) })
                                Screen.SEARCH -> SearchScreen(query = searchQuery, onQueryChange = { searchQuery = it }, selectedCategory = searchCategory, onCategoryChange = { searchCategory = it }, onSeriesClick = { s, sc -> selectedSeries = s; selectedItemScreen = sc })
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
fun CategoryListScreen(selectedFilter: String, onFilterSelected: (String) -> Unit, seriesList: List<Series>, onSeriesClick: (Series, Screen) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf("라프텔 애니메이션", "드라마")
    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Row(modifier = Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.padding(start = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { expanded = true }) {
                    Text(text = selectedFilter, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black, fontSize = 24.sp), color = Color.White)
                    Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(Color(0xFF2B2B2B))) {
                    options.forEach { option ->
                        DropdownMenuItem(text = { Text(option, color = Color.White, fontWeight = if(option == selectedFilter) FontWeight.Bold else FontWeight.Normal) }, onClick = { onFilterSelected(option); expanded = false })
                    }
                }
            }
        }
        BoxWithConstraints {
            val columns = if (maxWidth > 600.dp) GridCells.Fixed(4) else GridCells.Fixed(3)
            if (seriesList.isEmpty()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("콘텐츠가 없습니다.", color = Color.Gray) } }
            else {
                LazyVerticalGrid(columns = columns, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 100.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(seriesList) { series -> SearchGridItem(series) { onSeriesClick(series, Screen.ON_AIR) } }
                }
            }
        }
    }
}

@Composable
fun HomeScreen(latestSeries: List<Series>, aniSeries: List<Series>, onSeriesClick: (Series, Screen) -> Unit) {
    val heroMovie = remember(latestSeries, aniSeries) { latestSeries.firstOrNull()?.episodes?.firstOrNull() ?: aniSeries.firstOrNull()?.episodes?.firstOrNull() }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp)) { 
        item { HeroSection(heroMovie) { m -> val target = latestSeries.find { it.episodes.any { ep -> ep.id == m.id } }; if (target != null) onSeriesClick(target, Screen.LATEST) else aniSeries.find { it.episodes.any { ep -> ep.id == m.id } }?.let { onSeriesClick(it, Screen.ON_AIR) } } }
        item { MovieRow("최신 영화", Screen.LATEST, latestSeries, onSeriesClick) }
        item { MovieRow("라프텔 애니메이션", Screen.ON_AIR, aniSeries, onSeriesClick) }
    }
}

@Composable
fun MovieRow(title: String, screen: Screen, seriesList: List<Series>, onSeriesClick: (Series, Screen) -> Unit) {
    if (seriesList.isEmpty()) return
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold, fontSize = 22.sp), color = Color.White, modifier = Modifier.padding(start = 16.dp, bottom = 12.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(seriesList) { series -> Card(modifier = Modifier.width(120.dp).height(180.dp).clickable { onSeriesClick(series, screen) }, shape = RoundedCornerShape(4.dp)) { TmdbAsyncImage(title = series.title, modifier = Modifier.fillMaxSize()) } }
        }
    }
}

@Composable
fun SearchGridItem(series: Series, onSeriesClick: (Series) -> Unit) {
    Card(modifier = Modifier.aspectRatio(0.67f).clickable { onSeriesClick(series) }, shape = RoundedCornerShape(4.dp)) {
        TmdbAsyncImage(title = series.title, modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun MovieExplorer(title: String, pathStack: List<String>, items: List<Category>, onFolderClick: (String) -> Unit, onBackClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        NasAppBar(title = if (pathStack.isEmpty()) title else pathStack.last(), onBack = if (pathStack.isNotEmpty()) onBackClick else null)
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp)) {
            items(items) { item ->
                ListItem(headlineContent = { Text(text = item.name, color = Color.White) }, leadingContent = { Icon(imageVector = Icons.AutoMirrored.Filled.List, contentDescription = null, tint = Color.White) }, trailingContent = { Icon(imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Color.DarkGray) }, modifier = Modifier.clickable { onFolderClick(item.name) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
            }
        }
    }
}

@Composable
fun NasAppBar(title: String, onBack: (() -> Unit)? = null) {
    Row(modifier = Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White) }
        Text(text = title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black, fontSize = 24.sp), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = if (onBack == null) 12.dp else 4.dp))
    }
}

@Composable
fun SeriesDetailScreen(series: Series, categoryTitle: String = "", currentScreen: Screen, pathStack: List<String>, onBack: () -> Unit, onPlayFullScreen: (Movie) -> Unit) {
    val scope = rememberCoroutineScope()
    var playingMovie by remember(series) { mutableStateOf(series.episodes.firstOrNull()) }
    var metadata by remember(series) { mutableStateOf<TmdbMetadata?>(null) }
    var currentEpisodeOverview by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(series) { metadata = fetchTmdbMetadata(series.title) }
    LaunchedEffect(playingMovie, metadata) {
        val tmdbId = metadata?.tmdbId
        if (tmdbId != null && metadata?.mediaType == "tv" && playingMovie != null) {
            val epNum = Regex("\\d+").find(playingMovie!!.title.extractEpisode() ?: "")?.value?.toIntOrNull()
            currentEpisodeOverview = if (epNum != null) fetchTmdbEpisodeOverview(tmdbId, playingMovie!!.title.extractSeason(), epNum) else null
        } else { currentEpisodeOverview = null }
    }
    DisposableEffect(Unit) { onDispose { scope.launch { try { client.get("$BASE_URL/stop_all") } catch (e: Exception) {} } } }
    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        NasAppBar(title = categoryTitle, onBack = onBack)
        Box(modifier = Modifier.fillMaxWidth().height(210.dp).background(Color.DarkGray)) { playingMovie?.let { movie -> VideoPlayer(url = createVideoServeUrl(currentScreen, pathStack, movie, tabName = categoryTitle), modifier = Modifier.fillMaxSize(), onFullscreenClick = { onPlayFullScreen(movie) }) } }
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(text = series.title, color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold, fontSize = 24.sp))
            val displayOverview = currentEpisodeOverview ?: metadata?.overview
            if (!displayOverview.isNullOrBlank()) { Spacer(Modifier.height(8.dp)); Text(text = displayOverview, color = Color.LightGray, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp), maxLines = 4, overflow = TextOverflow.Ellipsis) }
        }
        HorizontalDivider(color = Color.DarkGray, thickness = 1.dp)
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp)) {
            items(series.episodes) { ep -> ListItem(headlineContent = { Text(text = ep.title.extractEpisode() ?: ep.title.prettyTitle(), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }, leadingContent = { Box(modifier = Modifier.width(120.dp).height(68.dp).background(Color(0xFF1A1A1A))) { TmdbAsyncImage(title = ep.title, modifier = Modifier.fillMaxSize()); Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.align(Alignment.Center).size(28.dp)) } }, modifier = Modifier.clickable { playingMovie = ep }, colors = ListItemDefaults.colors(containerColor = Color.Transparent)) }
        }
    }
}

@Composable
fun VideoPlayerScreen(movie: Movie, currentScreen: Screen, pathStack: List<String>, onBack: () -> Unit, tabName: String = "") {
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { scope.launch { try { client.get("$BASE_URL/stop_all") } catch (e: Exception) {} } } }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        VideoPlayer(createVideoServeUrl(currentScreen, pathStack, movie, tabName = tabName), Modifier.fillMaxSize())
        IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().align(Alignment.TopEnd).padding(16.dp)) { Icon(imageVector = Icons.Default.Close, contentDescription = null, tint = Color.White) }
    }
}

@Composable
fun HeroSection(movie: Movie?, onPlayClick: (Movie) -> Unit) {
    if (movie == null) return
    Box(modifier = Modifier.fillMaxWidth().height(550.dp).clickable { onPlayClick(movie) }.background(Color.Black)) {
        TmdbAsyncImage(title = movie.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(colors = listOf(Color.Black.copy(alpha = 0.4f), Color.Transparent, Color.Black.copy(alpha = 0.6f), Color.Black))))
        Card(modifier = Modifier.width(280.dp).height(400.dp).align(Alignment.TopCenter).padding(top = 40.dp), shape = RoundedCornerShape(8.dp), elevation = CardDefaults.cardElevation(defaultElevation = 16.dp), border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.2f))) { TmdbAsyncImage(title = movie.title, modifier = Modifier.fillMaxSize()) }
        Column(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = movie.title.cleanTitle(), color = Color.White, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, shadow = Shadow(color = Color.Black, blurRadius = 8f)), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(20.dp))
            Button(onClick = { onPlayClick(movie) }, colors = ButtonDefaults.buttonColors(containerColor = Color.White), shape = RoundedCornerShape(4.dp), modifier = Modifier.width(120.dp).height(45.dp)) { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black); Spacer(Modifier.width(8.dp)); Text("재생", color = Color.Black, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
fun SearchScreen(query: String, onQueryChange: (String) -> Unit, selectedCategory: String, onCategoryChange: (String) -> Unit, onSeriesClick: (Series, Screen) -> Unit) {
    var searchResults by remember { mutableStateOf<List<Series>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    LaunchedEffect(query, selectedCategory) {
        if (query.isBlank()) { searchResults = emptyList(); isSearching = false; return@LaunchedEffect }
        delay(300); isSearching = true
        try {
            val response: List<Category> = client.get("$BASE_URL/search") { parameter("q", query); parameter("category", selectedCategory) }.body()
            searchResults = response.flatMap { it.movies }.groupBySeries()
        } catch (e: Exception) { println("검색 오류: ${e.message}") } finally { isSearching = false }
    }
    Column(modifier = Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
        TextField(value = query, onValueChange = onQueryChange, modifier = Modifier.fillMaxWidth().padding(16.dp), placeholder = { Text("검색", color = Color.Gray) }, leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) }, colors = TextFieldDefaults.colors(focusedContainerColor = Color(0xFF333333), unfocusedContainerColor = Color(0xFF333333), focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Color.Red, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent), shape = RoundedCornerShape(8.dp), singleLine = true)
        Box(modifier = Modifier.fillMaxSize()) {
            if (isSearching) { CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.Red) }
            else {
                val screen = when (selectedCategory) { "방송중" -> Screen.ON_AIR; "애니메이션" -> Screen.ANIMATIONS; "영화" -> Screen.MOVIES; "외국TV" -> Screen.FOREIGN_TV; else -> Screen.HOME }
                BoxWithConstraints {
                    val columns = if (maxWidth > 600.dp) GridCells.Fixed(4) else GridCells.Fixed(3)
                    LazyVerticalGrid(columns = columns, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 100.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(searchResults) { series -> SearchGridItem(series) { onSeriesClick(series, screen) } }
                    }
                }
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
                Text(text = label, color = if (currentScreen == screen) Color.White else Color.Gray, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.clickable { onScreenSelected(screen) } )
            }
        }
    }
}

@Composable
fun NetflixBottomNavigation(currentScreen: Screen, onScreenSelected: (Screen) -> Unit) {
    NavigationBar(containerColor = Color.Black, contentColor = Color.White) {
        NavigationBarItem(selected = currentScreen == Screen.HOME, onClick = { onScreenSelected(Screen.HOME) }, icon = { Icon(Icons.Default.Home, contentDescription = null) }, label = { Text(text = "홈") })
        NavigationBarItem(selected = currentScreen == Screen.SEARCH, onClick = { onScreenSelected(Screen.SEARCH) }, icon = { Icon(Icons.Default.Search, contentDescription = null) }, label = { Text(text = "검색") })
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
