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
import coil3.network.CacheStrategy
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.CachePolicy
import coil3.request.crossfade
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val BASE_URL = "http://192.168.0.2:5000"
const val IPHONE_USER_AGENT = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

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

enum class Screen { HOME, ON_AIR, ANIMATIONS, MOVIES, FOREIGN_TV, SEARCH }

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

// 헬퍼: 현재 화면에 따른 서버 type 값 반환 (서버 base_map과 일치시킴)
fun getServeType(screen: Screen): String {
    return when (screen) {
        Screen.MOVIES -> "movie"
        Screen.ANIMATIONS -> "anim_all"
        Screen.FOREIGN_TV -> "ftv"  // "foreign_tv" -> "ftv"로 수정
        Screen.ON_AIR -> "anim"
        else -> "movie"
    }
}

// 헬퍼: 현재 경로 스택과 파일명을 조합하여 전체 경로 생성
fun getFullPath(pathStack: List<String>, fileName: String): String {
    val stackPath = pathStack.joinToString("/")
    return if (stackPath.isNotEmpty()) {
        if (fileName.contains("/")) fileName else "$stackPath/$fileName"
    } else {
        fileName
    }
}

// URL에서 특정 파라미터 값을 교체하거나 추가하는 헬퍼
fun String.fixTypeParam(targetType: String): String {
    if (!this.startsWith("http")) return this
    return try {
        val url = Url(this)
        val newParams = Parameters.build {
            url.parameters.forEach { s, list ->
                if (s == "type") append(s, targetType)
                else appendAll(s, list)
            }
        }
        URLBuilder(url).apply {
            parameters.clear()
            parameters.appendAll(newParams)
        }.buildString()
    } catch (e: Exception) {
        this
    }
}

// 비디오 재생을 위한 전용 URL 생성 함수
fun createVideoServeUrl(currentScreen: Screen, pathStack: List<String>, movie: Movie): String {
    val type = getServeType(currentScreen)
    // 서버에서 온 URL이 있더라도 type이 잘못되었을 수 있으므로 교정하여 사용
    if (movie.videoUrl.startsWith("http")) {
        return movie.videoUrl.fixTypeParam(type)
    }
    
    val fullPath = getFullPath(pathStack, movie.videoUrl)
    return "$BASE_URL/video_serve?type=$type&path=${fullPath.encodeURLParameter()}"
}

// 썸네일 로딩을 위한 전용 URL 생성 함수
fun createThumbServeUrl(currentScreen: Screen, pathStack: List<String>, movie: Movie): String {
    val thumb = movie.thumbnailUrl ?: return ""
    val type = getServeType(currentScreen)
    
    // 서버에서 온 URL이 있더라도 type이 잘못되었을 수 있으므로 교정하여 사용
    if (thumb.startsWith("http")) {
        return thumb.fixTypeParam(type)
    }
    
    val fullPath = getFullPath(pathStack, movie.videoUrl)
    return "$BASE_URL/thumb_serve?type=$type&id=${thumb.encodeURLParameter()}&path=${fullPath.encodeURLParameter()}"
}

fun String.cleanTitle(): String {
    var cleaned = this
    if (cleaned.contains(".")) {
        val ext = cleaned.substringAfterLast('.')
        if (ext.length in 2..4) cleaned = cleaned.substringBeforeLast('.')
    }
    val techPattern = "(?i)\\.?(?:\\d{3,4}p|WEB-DL|WEBRip|Bluray|HDRip|BDRip|DVDRip|H\\.?26[45]|x26[45]|HEVC|AAC|DTS|AC3|DDP|Dual|Atmos|REPACK|KL).*"
    cleaned = cleaned.replace(Regex(techPattern), "")
    val dateMatch = Regex("\\.(\\d{2})\\d{4}(\\.|$)").find(cleaned)
    var extractedYear = ""
    if (dateMatch != null) {
        extractedYear = "20" + dateMatch.groupValues[1]
        cleaned = cleaned.replace(dateMatch.value, ".")
    }
    cleaned = cleaned.replace(Regex("(?i)\\.?[Ee]\\d+"), "")
    cleaned = cleaned.replace(".", " ").replace("_", " ")
    cleaned = cleaned.replace(Regex("\\(([^)]+)\\)"), "[$1]")
    cleaned = cleaned.replace(Regex("\\[\\s+"), "[").replace(Regex("\\s+]"), "]")
    val yearMatch = Regex("(19|20)\\d{2}").find(cleaned)
    if (yearMatch != null) {
        val year = yearMatch.value
        val titleBeforeYear = cleaned.take(yearMatch.range.first).trim()
        cleaned = if (titleBeforeYear.endsWith("-")) "$titleBeforeYear $year"
        else if (titleBeforeYear.isNotEmpty()) "$titleBeforeYear - $year"
        else year
    } else if (extractedYear.isNotEmpty()) {
        cleaned = if (cleaned.contains(" - ")) cleaned else "$cleaned - $extractedYear"
    }
    cleaned = cleaned.replace(Regex("\\s+"), " ").replace(Regex("\\s-\\s+"), " - ")
    return cleaned.trim()
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
                    if (onAirCategories.isEmpty()) {
                        isLoading = true
                        onAirCategories = client.get("$BASE_URL/animations").body()
                    }
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
            val movie = selectedMovie
            val series = selectedSeries
            
            if (movie != null) {
                VideoPlayerScreen(
                    movie = movie,
                    currentScreen = currentScreen,
                    pathStack = currentPathStack
                ) { selectedMovie = null }
            } else if (series != null || isExplorerSeriesMode) {
                val categoryTitle = when {
                    currentScreen == Screen.ON_AIR -> "라프텔 애니메이션"
                    currentScreen == Screen.ANIMATIONS -> "애니메이션"
                    currentScreen == Screen.MOVIES -> "영화"
                    currentScreen == Screen.FOREIGN_TV -> "외국 TV"
                    else -> ""
                }

                val seriesData = if (series != null) series
                else {
                    val items = when (currentScreen) {
                        Screen.MOVIES -> movieItems
                        Screen.ANIMATIONS -> aniItems
                        else -> foreignTvItems
                    }
                    val stack = when (currentScreen) {
                        Screen.MOVIES -> moviePathStack
                        Screen.ANIMATIONS -> aniPathStack
                        else -> foreignTvPathStack
                    }
                    val movies = items.flatMap { it.movies }
                    Series(
                        title = stack.lastOrNull() ?: "상세보기",
                        episodes = movies,
                        thumbnailUrl = movies.firstOrNull()?.thumbnailUrl
                    )
                }
                SeriesDetailScreen(
                    series = seriesData,
                    categoryTitle = categoryTitle,
                    currentScreen = currentScreen,
                    pathStack = currentPathStack,
                    onBack = {
                        if (selectedSeries != null) selectedSeries = null
                        else when (currentScreen) {
                            Screen.MOVIES -> moviePathStack = moviePathStack.dropLast(1)
                            Screen.ANIMATIONS -> aniPathStack = aniPathStack.dropLast(1)
                            else -> foreignTvPathStack = foreignTvPathStack.dropLast(1)
                        }
                    },
                    onPlayFullScreen = { selectedMovie = it }
                )
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
                                Screen.HOME -> HomeScreen(homeLatestCategories, onAirCategories) { selectedSeries = it }
                                Screen.ON_AIR -> CategoryListScreen(
                                    title = "방송중", 
                                    rowTitle = "라프텔 애니메이션",
                                    categories = onAirCategories
                                ) { selectedSeries = it }
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

@Composable
fun SearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    selectedCategory: String,
    onCategoryChange: (String) -> Unit,
    onSeriesClick: (Series) -> Unit
) {
    val categories = listOf("전체", "방송중", "애니메이션", "최신영화", "영화", "외국TV")
    val suggestedKeywords = listOf("짱구", "나혼자만 레벨업", "가족 모집", "최신 영화")
    
    var searchResults by remember { mutableStateOf<List<Series>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var isDropdownExpanded by remember { mutableStateOf(false) }

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
            placeholder = { Text("제목, 장르, 시리즈 검색", color = Color.Gray) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = null, tint = Color.White)
                    }
                }
            },
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

        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).wrapContentSize(Alignment.TopStart)) {
            Surface(
                onClick = { isDropdownExpanded = true },
                color = Color(0xFF2B2B2B),
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.dp, Color.DarkGray.copy(alpha = 0.5f))
            ) {
                Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "카테고리: $selectedCategory", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }

            DropdownMenu(
                expanded = isDropdownExpanded,
                onDismissRequest = { isDropdownExpanded = false },
                modifier = Modifier.background(Color(0xFF1F1F1F)).border(1.dp, Color.DarkGray, RoundedCornerShape(4.dp))
            ) {
                categories.forEach { category ->
                    DropdownMenuItem(
                        text = { Text(text = category, color = if (selectedCategory == category) Color.Red else Color.White, fontWeight = if (selectedCategory == category) FontWeight.Bold else FontWeight.Normal, fontSize = 15.sp) },
                        onClick = { onCategoryChange(category); isDropdownExpanded = false }
                    )
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
            if (isSearching) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.Red)
            } else if (query.isEmpty()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("추천 검색어", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Spacer(Modifier.height(12.dp))
                    FlowRow(mainAxisSpacing = 8.dp, crossAxisSpacing = 8.dp) {
                        suggestedKeywords.forEach { keyword ->
                            SuggestionChip(
                                onClick = { onQueryChange(keyword) },
                                label = { Text(keyword, fontSize = 14.sp) },
                                shape = CircleShape,
                                colors = SuggestionChipDefaults.suggestionChipColors(containerColor = Color(0xFF1A1A1A), labelColor = Color.LightGray),
                                border = BorderStroke(1.dp, Color.DarkGray)
                            )
                        }
                    }
                }
            } else if (searchResults.isEmpty()) {
                Text("검색 결과가 없습니다", color = Color.Gray, fontSize = 16.sp, modifier = Modifier.align(Alignment.Center))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 100.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(searchResults) { series ->
                        SearchGridItem(series, onSeriesClick)
                    }
                }
            }
        }
    }
}

@Composable
fun SearchGridItem(series: Series, onSeriesClick: (Series) -> Unit) {
    Card(modifier = Modifier.aspectRatio(2f/3f).clickable { onSeriesClick(series) }, shape = RoundedCornerShape(4.dp)) {
        Box(Modifier.fillMaxSize()) {
            // 검색 결과에서도 올바른 썸네일 URL을 위해 Series의 첫 번째 에피소드 정보를 활용
            val thumbUrl = series.episodes.firstOrNull()?.let { createThumbServeUrl(Screen.HOME, emptyList(), it) }
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current)
                    .data(thumbUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))))
            Text(text = series.title, color = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 12.dp), style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable { onFolderClick(item.name) },
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1F1F1F))
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(6.dp), color = Color.White.copy(alpha = 0.1f), modifier = Modifier.size(40.dp)) {
                            Icon(imageVector = Icons.AutoMirrored.Filled.List, contentDescription = null, tint = Color.White, modifier = Modifier.padding(8.dp))
                        }
                        Spacer(Modifier.width(16.dp))
                        Text(text = item.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.weight(1f))
                        Icon(imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Color.DarkGray)
                    }
                }
            }
        }
    }
}

@Composable
fun FlowRow(mainAxisSpacing: androidx.compose.ui.unit.Dp, crossAxisSpacing: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content = content) { measurables, constraints ->
        val placeholders = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        val rows = mutableListOf<List<androidx.compose.ui.layout.Placeable>>()
        var currentRow = mutableListOf<androidx.compose.ui.layout.Placeable>()
        var currentRowWidth = 0
        placeholders.forEach { placeable ->
            if (currentRowWidth + placeable.width + mainAxisSpacing.roundToPx() > constraints.maxWidth && currentRow.isNotEmpty()) {
                rows.add(currentRow); currentRow = mutableListOf(); currentRowWidth = 0
            }
            currentRow.add(placeable); currentRowWidth += placeable.width + mainAxisSpacing.roundToPx()
        }
        rows.add(currentRow)
        val height = rows.sumOf { it.maxOf { p -> p.height } + crossAxisSpacing.roundToPx() }
        layout(constraints.maxWidth, height) {
            var y = 0
            rows.forEach { row ->
                var x = 0
                row.forEach { placeable -> placeable.placeRelative(x, y); x += placeable.width + mainAxisSpacing.roundToPx() }
                y += row.maxOf { it.height } + crossAxisSpacing.roundToPx()
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
fun HomeScreen(latest: List<Category>, ani: List<Category>, onSeriesClick: (Series) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp)) { 
        item {
            val all = latest.flatMap { it.movies } + ani.flatMap { it.movies }
            HeroSection(all.firstOrNull()) { movie -> all.groupBySeries().find { it.episodes.any { ep -> ep.id == movie.id } }?.let { onSeriesClick(it) } }
        }
        item { MovieRow("최신 영화", Screen.MOVIES, latest.flatMap { it.movies }.groupBySeries(), onSeriesClick) }
        item { MovieRow("라프텔 애니메이션", Screen.ON_AIR, ani.flatMap { it.movies }.groupBySeries(), onSeriesClick) }

        item {
            Spacer(Modifier.height(40.dp))
            Button(
                onClick = {
                    val testMovie = Movie(
                        id = "test_mkv",
                        title = "가난뱅이 신이! 테스트",
                        videoUrl = "$BASE_URL/video_serve?type=anim_all&path=시리즈/가/가난뱅이 신이! (2012)/[Moozzi2] Binbougami ga! - S01E01 (BD 1920x1080 x.264 FLACx2).mkv"
                    )
                    onSeriesClick(Series(testMovie.title, listOf(testMovie), testMovie.thumbnailUrl))
                },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Blue)
            ) {
                Text("🎬 가난뱅이 신이! 테스트 재생", color = Color.White)
            }
        }
    }
}

@Composable
fun CategoryListScreen(
    title: String, 
    rowTitle: String,
    categories: List<Category>, 
    onSeriesClick: (Series) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        NasAppBar(title = title)
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp)) { 
            item { 
                MovieRow(
                    title = rowTitle, 
                    screen = Screen.ON_AIR,
                    seriesList = categories.flatMap { it.movies }.groupBySeries(), 
                    onSeriesClick = onSeriesClick
                ) 
            } 
        }
    }
}

@Composable
fun MovieRow(title: String, screen: Screen, seriesList: List<Series>, onSeriesClick: (Series) -> Unit) {
    if (seriesList.isEmpty()) return
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold, fontSize = 22.sp), color = Color.White, modifier = Modifier.padding(start = 16.dp, bottom = 12.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(seriesList) { series ->
                Card(modifier = Modifier.width(120.dp).height(180.dp).clickable { onSeriesClick(series) }, shape = RoundedCornerShape(4.dp)) {
                    Box(Modifier.fillMaxSize()) {
                        // MovieRow에서도 올바른 썸네일 URL 생성을 위해 에피소드 정보 활용
                        val thumbUrl = series.episodes.firstOrNull()?.let { createThumbServeUrl(screen, emptyList(), it) }
                        AsyncImage(
                            model = ImageRequest.Builder(LocalPlatformContext.current)
                                .data(thumbUrl)
                                .crossfade(true)
                                .build(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))))
                        Text(text = series.title, color = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 20.dp), style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, shadow = Shadow(color = Color.Black, blurRadius = 4f)), maxLines = 2, overflow = TextOverflow.Ellipsis)
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
    var playingMovie by remember(series) { mutableStateOf(series.episodes.firstOrNull()) }
    Column(modifier = Modifier.fillMaxSize().background(Color.Black).navigationBarsPadding()) {
        NasAppBar(title = categoryTitle, onBack = onBack)
        Box(modifier = Modifier.fillMaxWidth().height(210.dp).background(Color.DarkGray)) {
            playingMovie?.let { movie -> 
                val finalUrl = createVideoServeUrl(currentScreen, pathStack, movie)
                VideoPlayer(url = finalUrl, modifier = Modifier.fillMaxSize(), onFullscreenClick = { onPlayFullScreen(movie) }) 
            }
        }
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp)) {
            item { Text(text = series.title, color = Color.White, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold, fontSize = 26.sp), modifier = Modifier.padding(16.dp)) }
            items(series.episodes) { ep ->
                ListItem(
                    headlineContent = { Text(text = ep.title.extractEpisode() ?: ep.title.cleanTitle(), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = {
                        Box(modifier = Modifier.width(120.dp).height(68.dp).background(Color(0xFF1A1A1A))) {
                            val thumbUrl = createThumbServeUrl(currentScreen, pathStack, ep)
                            AsyncImage(
                                model = ImageRequest.Builder(LocalPlatformContext.current)
                                    .data(thumbUrl)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
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
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1A1A1A))
                .clickable { onPlayClick(movie) }
        ) {
            val thumbUrl = createThumbServeUrl(Screen.HOME, emptyList(), movie)
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current)
                    .data(thumbUrl)
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
                            1f to Color.Black.copy(alpha = 0.9f)
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp, start = 16.dp, end = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = movie.title.prettyTitle(),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    style = TextStyle(
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        shadow = Shadow(color = Color.Black, blurRadius = 8f)
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { onPlayClick(movie) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                    shape = RoundedCornerShape(4.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 0.dp),
                    modifier = Modifier.height(44.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("재생", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

@Composable
fun NetflixTopBar(currentScreen: Screen, onScreenSelected: (Screen) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "N", 
            color = Color.Red, 
            fontSize = 32.sp, 
            fontWeight = FontWeight.Black, 
            modifier = Modifier.clickable { onScreenSelected(Screen.HOME) }
        )
        Spacer(modifier = Modifier.width(20.dp))
        Text(text = "방송중", color = if (currentScreen == Screen.ON_AIR) Color.White else Color.LightGray.copy(alpha = 0.7f), style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (currentScreen == Screen.ON_AIR) FontWeight.Bold else FontWeight.Medium, fontSize = 17.sp), modifier = Modifier.clickable { onScreenSelected(Screen.ON_AIR) }); Spacer(modifier = Modifier.width(16.dp))
        Text(text = "애니메이션", color = if (currentScreen == Screen.ANIMATIONS) Color.White else Color.LightGray.copy(alpha = 0.7f), style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (currentScreen == Screen.ANIMATIONS) FontWeight.Bold else FontWeight.Medium, fontSize = 17.sp), modifier = Modifier.clickable { onScreenSelected(Screen.ANIMATIONS) }); Spacer(modifier = Modifier.width(16.dp))
        Text(text = "영화", color = if (currentScreen == Screen.MOVIES) Color.White else Color.LightGray.copy(alpha = 0.7f), style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (currentScreen == Screen.MOVIES) FontWeight.Bold else FontWeight.Medium, fontSize = 17.sp), modifier = Modifier.clickable { onScreenSelected(Screen.MOVIES) }); Spacer(modifier = Modifier.width(16.dp))
        Text(text = "외국 TV", color = if (currentScreen == Screen.FOREIGN_TV) Color.White else Color.LightGray.copy(alpha = 0.7f), style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (currentScreen == Screen.FOREIGN_TV) FontWeight.Bold else FontWeight.Medium, fontSize = 17.sp), modifier = Modifier.clickable { onScreenSelected(Screen.FOREIGN_TV) })
    }
}

@Composable
fun NetflixBottomNavigation(currentScreen: Screen, onScreenSelected: (Screen) -> Unit) {
    NavigationBar(
        containerColor = Color.Black, 
        contentColor = Color.White, 
        modifier = Modifier.height(72.dp),
        windowInsets = WindowInsets(0, 0, 0, 0)
    ) {
        NavigationBarItem(
            selected = currentScreen == Screen.HOME, 
            onClick = { onScreenSelected(Screen.HOME) }, 
            icon = { 
                Icon(
                    imageVector = Icons.Default.Home, 
                    contentDescription = null, 
                    modifier = Modifier.size(24.dp).offset(y = 2.dp)
                ) 
            }, 
            label = { 
                Text(
                    text = "홈", 
                    fontSize = 12.sp, 
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.offset(y = (-6).dp)
                ) 
            }, 
            colors = NavigationBarItemDefaults.colors(
                indicatorColor = Color.Transparent, 
                selectedIconColor = Color.White, 
                unselectedIconColor = Color.Gray, 
                selectedTextColor = Color.White, 
                unselectedTextColor = Color.Gray
            )
        )
        NavigationBarItem(
            selected = currentScreen == Screen.SEARCH, 
            onClick = { onScreenSelected(Screen.SEARCH) }, 
            icon = { 
                Icon(
                    imageVector = Icons.Default.Search, 
                    contentDescription = null, 
                    modifier = Modifier.size(24.dp).offset(y = 2.dp)
                ) 
            }, 
            label = { 
                Text(
                    text = "검색", 
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.offset(y = (-6).dp)
                ) 
            }, 
            colors = NavigationBarItemDefaults.colors(
                indicatorColor = Color.Transparent, 
                selectedIconColor = Color.White, 
                unselectedIconColor = Color.Gray, 
                selectedTextColor = Color.White, 
                unselectedTextColor = Color.Gray
            )
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

fun PlaceholderScreen(title: String) {}
