package org.nas.videoplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Category(
    val name: String,
    val movies: List<Movie>
)

@Serializable
data class Movie(
    val id: String,
    val title: String,
    val thumbnailUrl: String? = null,
    val videoUrl: String
)

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

/**
 * NAS 서버 호환성을 위한 URL 보정 함수
 */
fun String.toSafeUrl(): String {
    if (this.isBlank()) return this
    
    val parts = this.split("?", limit = 2)
    val baseUrl = parts[0].replace(" ", "%20")
    if (parts.size < 2) return baseUrl
    
    val query = parts[1]
        .replace(" ", "%20")
        .replace("/", "%2F")
        .replace("(", "%28")
        .replace(")", "%29")
        .replace("[", "%5B")
        .replace("]", "%5D")
        .replace("#", "%23")
    
    return "$baseUrl?$query"
}

@Composable
fun App() {
    val context = LocalPlatformContext.current

    // Coil 3 전역 설정
    setSingletonImageLoaderFactory { platformContext ->
        ImageLoader.Builder(platformContext)
            .components {
                add(KtorNetworkFetcherFactory(client))
            }
            .crossfade(true)
            .build()
    }

    var myCategories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedMovie by remember { mutableStateOf<Movie?>(null) }

    LaunchedEffect(Unit) {
        try {
            isLoading = true
            val response: List<Category> = client.get("http://192.168.0.2:5000/movies").body()
            myCategories = response
            errorMessage = null
        } catch (e: Exception) {
            errorMessage = "NAS 연결 실패: ${e.message}"
        } finally {
            isLoading = false
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFE50914),
            background = Color.Black,
            surface = Color(0xFF121212),
            onBackground = Color.White,
            onSurface = Color.White
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            if (selectedMovie != null) {
                VideoPlayerScreen(movie = selectedMovie!!) {
                    selectedMovie = null 
                }
            } else {
                Scaffold(
                    bottomBar = { NetflixBottomNavigation() }
                ) { paddingValues ->
                    if (isLoading) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color(0xFFE50914))
                        }
                    } else if (errorMessage != null) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(errorMessage!!, color = Color.White, modifier = Modifier.padding(16.dp))
                                Button(onClick = { 
                                    isLoading = true
                                    errorMessage = null
                                }) { Text("다시 시도") }
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().padding(paddingValues)
                        ) {
                            val heroMovie = myCategories.firstOrNull()?.movies?.firstOrNull()
                            item { 
                                HeroSection(heroMovie) { movie ->
                                    selectedMovie = movie
                                }
                            }
                            
                            if (myCategories.isNotEmpty()) {
                                items(myCategories) { category ->
                                    MovieRow(category.name, category.movies) { movie ->
                                        selectedMovie = movie
                                    }
                                }
                            }
                            item { Spacer(modifier = Modifier.height(20.dp)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VideoPlayerScreen(movie: Movie, onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val safeUrl = movie.videoUrl.toSafeUrl()
        VideoPlayer(
            url = safeUrl,
            modifier = Modifier.fillMaxSize()
        )

        IconButton(
            onClick = onBack,
            modifier = Modifier
                .padding(16.dp)
                .align(Alignment.TopStart)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
        ) {
            Text("←", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun HeroSection(featuredMovie: Movie?, onPlayClick: (Movie) -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().height(450.dp)
    ) {
        val thumbUrl = featuredMovie?.thumbnailUrl?.toSafeUrl()
        if (thumbUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current)
                    .data(thumbUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                placeholder = ColorPainter(Color(0xFF222222)),
                error = ColorPainter(Color(0xFF442222)),
                onError = { state ->
                    println("THUMB_ERROR: Hero | URL: $thumbUrl | Error: ${state.result.throwable}")
                }
            )
        } else {
            Box(modifier = Modifier.fillMaxSize().background(Color.DarkGray))
        }
        
        Box(
            modifier = Modifier.fillMaxSize().background(
                brush = Brush.verticalGradient(colors = listOf(Color.Transparent, Color.Black), startY = 300f)
            )
        )

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = featuredMovie?.title ?: "",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { featuredMovie?.let { onPlayClick(it) } },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Text("▶ 재생", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun MovieRow(title: String, movies: List<Movie>, onMovieClick: (Movie) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(movies) { movie ->
                MoviePosterCard(movie, onMovieClick)
            }
        }
    }
}

@Composable
fun MoviePosterCard(movie: Movie, onMovieClick: (Movie) -> Unit) {
    Column(
        modifier = Modifier.width(130.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Card(
            modifier = Modifier.width(130.dp).height(190.dp).clickable { onMovieClick(movie) },
            shape = RoundedCornerShape(4.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                val thumbUrl = movie.thumbnailUrl?.toSafeUrl()
                if (thumbUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalPlatformContext.current)
                            .data(thumbUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = movie.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        placeholder = ColorPainter(Color(0xFF222222)),
                        error = ColorPainter(Color(0xFF442222)),
                        onError = { state ->
                            println("THUMB_ERROR: Card | URL: $thumbUrl | Error: ${state.result.throwable}")
                        }
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Gray))
                }
                
                Box(
                    modifier = Modifier.fillMaxSize().background(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)),
                            startY = 100f
                        )
                    ),
                    contentAlignment = Alignment.BottomStart
                ) {
                    Text(
                        text = movie.title,
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(8.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = movie.title,
            color = Color.LightGray,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun NetflixBottomNavigation() {
    NavigationBar(containerColor = Color.Black, contentColor = Color.White) {
        NavigationBarItem(
            selected = true, onClick = {}, icon = { Text("🏠", color = Color.White) }, label = { Text("홈") }
        )
        NavigationBarItem(
            selected = false, onClick = {}, icon = { Text("🔍", color = Color.White) }, label = { Text("검색") }
        )
    }
}
