package org.nas.videoplayer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import kotlinx.coroutines.flow.*
import androidx.compose.runtime.Composable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import org.nas.videoplayer.data.*
import org.nas.videoplayer.db.AppDatabase
import com.squareup.sqldelight.db.SqlDriver

const val BASE_URL = "http://192.168.0.2:5000"
const val IPHONE_USER_AGENT = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, lifestyle Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

const val TMDB_API_KEY = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiI3OGNiYWQ0ZjQ3NzcwYjYyYmZkMTcwNTA2NDIwZDQyYyIsIm5iZiI6MTY1MzY3NTU4MC45MTUsInN1YiI6IjYyOTExNjNjMTI0MjVjMDA1MjI0ZGQzNCIsInNjb3BlcyI6WyJhcGlfcmVhZCJdLCJ2ZXJzaW9uIjoxfQ.3YU0WuIx_WDo6nTRKehRtn4N5I4uCgjI1tlpkqfsUhk"
const val TMDB_BASE_URL = "https://api.themoviedb.org/3"
const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w342" 

// --- Regex Pre-compilation ---
private val REGEX_YEAR = Regex("\\((19|20)\\d{2}\\)|(?<!\\d)(19|20)\\d{2}(?!\\d)")
private val REGEX_BRACKETS = Regex("\\[.*?\\]|\\(.*?\\)")
private val REGEX_EPISODE_E = Regex("(?i)[Ee](\\d+)")
private val REGEX_EPISODE_K = Regex("(\\d+)\\s*(?:화|회)")
private val REGEX_EPISODE_NUM = Regex("[.\\s_](\\d+)(?:$|[.\\s_])")
private val REGEX_SEASON_S = Regex("(?i)[Ss](\\d+)")
private val REGEX_SEASON_G = Regex("(\\d+)\\s*기")
private val REGEX_SERIES_CUTOFF = Regex("(?i)[.\\s_](?:S\\d+E\\d+|S\\d+|E\\d+|\\d+\\s*(?:화|회|기)|Season\\s*\\d+|Part\\s*\\d+).*")
private val REGEX_NOISE = Regex("(?i)[.\\s_](?:더빙|자막|무삭제|\\d{3,4}p|WEB-DL|WEBRip|Bluray|HDRip|BDRip|DVDRip|H\\.?26[45]|x26[45]|HEVC|AAC|DTS|AC3|DDP|Dual|Atmos|REPACK|10bit|REMUX|OVA|OAD|ONA|TV판|극장판|FLAC|xvid|DivX|MKV|MP4|AVI|속편|무리무리|1부|2부|파트|완결).*")
private val REGEX_DATE_NOISE = Regex("[.\\s_](\\d{6}|\\d{8})(?=[.\\s_]|$)")
private val REGEX_CLEAN_EXTRA = Regex("(?i)\\.?[Ee]\\d+|\\d+\\s*(?:화|기|회|파트)|Season\\s*\\d+|Part\\s*\\d+")
private val REGEX_TRAILING_NUM = Regex("[.\\s_](\\d+)$")
private val REGEX_SYMBOLS = Regex("[._\\-::!?【】『』「」\"'#@*※]")
private val REGEX_WHITESPACE = Regex("\\s+")
private val REGEX_KOR_ENG = Regex("([가-힣])([a-zA-Z0-9])")
private val REGEX_ENG_KOR = Regex("([a-zA-Z0-9])([가-힣])")

@Serializable
data class Category(val name: String, val movies: List<Movie> = emptyList())
@Serializable
data class Movie(val id: String, val title: String, val thumbnailUrl: String? = null, val videoUrl: String, val duration: String? = null)
data class Series(val title: String, val episodes: List<Movie>, val thumbnailUrl: String? = null, val fullPath: String? = null)
@Serializable
data class TmdbSearchResponse(val results: List<TmdbResult>)
@Serializable
data class TmdbResult(
    val id: Int, 
    @SerialName("poster_path") val posterPath: String? = null, 
    @SerialName("backdrop_path") val backdropPath: String? = null, 
    val name: String? = null, 
    val title: String? = null, 
    @SerialName("media_type") val mediaType: String? = null, 
    val overview: String? = null,
    @SerialName("original_language") val originalLanguage: String? = null,
    @SerialName("origin_country") val originCountry: List<String>? = null,
    @SerialName("genre_ids") val genreIds: List<Int>? = null
)
data class TmdbMetadata(
    val tmdbId: Int? = null, 
    val mediaType: String? = null, 
    val posterUrl: String? = null, 
    val overview: String? = null,
    val genreIds: List<Int> = emptyList()
)

@Serializable
data class TmdbEpisode(
    @SerialName("episode_number") val episodeNumber: Int,
    var overview: String? = null,
    @SerialName("still_path") val stillPath: String? = null,
    val name: String? = null
)

@Serializable
data class TmdbSeasonResponse(val episodes: List<TmdbEpisode>)

@Serializable
data class TmdbCreditsResponse(val cast: List<TmdbCast>)
@Serializable
data class TmdbCast(
    val name: String, 
    @SerialName("roles") val roles: List<TmdbRole>? = null, 
    val character: String? = null, 
    @SerialName("profile_path") val profilePath: String? = null
)
@Serializable
data class TmdbRole(val character: String, @SerialName("episode_count") val episodeCount: Int)

enum class Screen { HOME, ON_AIR, ANIMATIONS, MOVIES, FOREIGN_TV, KOREAN_TV, SEARCH, LATEST }

val client = HttpClient {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
    install(HttpTimeout) { requestTimeoutMillis = 60000; connectTimeoutMillis = 15000; socketTimeoutMillis = 60000 }
    defaultRequest { header("User-Agent", IPHONE_USER_AGENT) }
}

private val tmdbCache = mutableMapOf<String, TmdbMetadata>()

// --- 테마 이름 생성기 (기존 로직 복구) ---
fun getRandomThemeName(originalName: String, index: Int, isMovie: Boolean = false, category: String? = null): String {
    val fixedThemes = listOf(
        if (isMovie) "지금 가장 인기 있는 영화" else "지금 가장 핫한 화제작", 
        if (isMovie) "최근 추가된 흥미로운 영화" else "새로운 정주행의 시작"
    )
    if (index < 2) return fixedThemes[index]

    if (category != null) {
        when {
            category.contains("중국") -> {
                val cnThemes = listOf("대륙의 스케일, 압도적 대서사시", "화려한 무협과 신비로운 판타지", "가슴 절절한 애절한 로맨스", "궁중 암투와 치밀한 전략", "매혹적인 영상미의 시대극")
                return cnThemes[(index - 2) % cnThemes.size]
            }
            category.contains("일본") -> {
                val jpThemes = listOf("특유의 감성, 몽글몽글한 로맨스", "소소하지만 확실한 행복, 일상물", "독특한 소재와 기발한 상상력", "청춘의 열정과 풋풋한 성장기", "깊은 여운을 남기는 미스터리 수사물")
                return jpThemes[(index - 2) % jpThemes.size]
            }
            category.contains("미국") || category.contains("영국") -> {
                val enThemes = listOf("압도적 스케일, 헐리우드 화제작", "치밀한 구성의 고퀄리티 범죄 스릴러", "전 세계를 사로잡은 마성의 시리즈", "상상 그 이상! SF와 판타지의 정점", "강렬한 몰입감의 전문직 드라마")
                return enThemes[(index - 2) % enThemes.size]
            }
            category.contains("다큐") -> {
                val docThemes = listOf("세상을 보는 새로운 시선", "경이로운 자연과 우주의 신비", "지적 호기심을 깨우는 기록들", "우리가 몰랐던 역사의 이면", "사실이 주는 묵직한 감동")
                return docThemes[(index - 2) % docThemes.size]
            }
            category == "라프텔" -> {
                val laThemes = listOf("라프텔이 추천하는 명작 애니", "지금 이 순간, 가장 핫한 애니", "놓치면 후회할 인생 애니메이션", "숨겨진 띵작, 라프텔 셀렉션", "당신의 덕심을 깨울 고퀄리티 애니")
                return laThemes[(index - 2) % laThemes.size]
            }
        }
    }
    
    val alternates = if (isMovie) {
        listOf("시선을 사로잡는 영상미", "주말 밤을 책임질 영화", "시간 순삭! 몰입도 최강", "당신의 취향을 저격할 추천작", "숨겨진 보석 같은 명작")
    } else {
        listOf("한 번 시작하면 멈출 수 없는", "웰메이드 명품 시리즈", "주말 순삭! 정주행 가이드", "놓치면 후회할 인생 드라마", "탄탄한 스토리의 화제작")
    }
    
    val firstChar = originalName.firstOrNull { it.isLetter() }
    val themeIndex = when (firstChar) {
        in '가'..'나' -> 0
        in '다'..'라' -> 1
        in '마'..'바' -> 2
        in '사'..'아' -> 3
        in '자'..'하' -> 4
        else -> 5
    }
    
    return if (category != null) {
        alternates[(index - 2) % alternates.size]
    } else {
        val defaultThemes = listOf("ㄱ/A 시작의 테마", "나/B 새로운 시작", "다/C 강력 추천", "라/D 놓치지 마세요", "마/E 화제의 중심", "기타 테마")
        defaultThemes[themeIndex % defaultThemes.size]
    }
}

// --- 번역 관련 유틸리티 ---
fun String.containsKorean(): Boolean = any { it.isHangul() }

suspend fun translateToKorean(text: String): String {
    if (text.isBlank() || text.containsKorean()) return text
    return try {
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=ko&dt=t&q=${text.encodeURLParameter()}"
        val response: String = client.get(url).body()
        val jsonArray = Json.parseToJsonElement(response).jsonArray
        val translatedParts = jsonArray[0].jsonArray
        translatedParts.joinToString("") { it.jsonArray[0].toString().replace("\"", "") }
    } catch (e: Exception) { text }
}

suspend fun fetchTmdbMetadata(title: String, typeHint: String? = null): TmdbMetadata {
    if (TMDB_API_KEY.isBlank() || TMDB_API_KEY == "YOUR_TMDB_API_KEY") return TmdbMetadata()
    tmdbCache[title]?.let { return it }

    val year = REGEX_YEAR.find(title)?.value?.replace("(", "")?.replace(")", "")
    val cleanTitle = title.cleanTitle(includeYear = false)
    
    val strategies = mutableListOf<String>()
    
    // 1. 한국어 제목 (한글, 공백, 숫자만 포함)
    val korTitle = cleanTitle.filter { it.isWhitespace() || it.isHangul() || it.isDigit() }.trim().replace(Regex("\\s+"), " ")
    if (korTitle.length >= 2) {
        if (year != null) strategies.add("$korTitle $year")
        strategies.add(korTitle)
        
        // 키워드 축소 (긴 제목 대응)
        val words = korTitle.split(" ").filter { it.isNotBlank() }
        if (words.size > 2) {
            for (i in words.size - 1 downTo 2) {
                strategies.add(words.take(i).joinToString(" "))
            }
        }
    }
    
    // 2. 영어/로마자 제목 (한글 외 문자만 포함)
    val engTitle = cleanTitle.filter { !it.isHangul() }.trim().replace(Regex("\\s+"), " ")
    if (engTitle.length >= 3 && engTitle != korTitle) {
        if (year != null) strategies.add("$engTitle $year")
        strategies.add(engTitle)

        // 영어 제목에 대한 키워드 축소
        val words = engTitle.split(" ").filter { it.isNotBlank() }
        if (words.size > 2) {
            for (i in words.size - 1 downTo 2) {
                strategies.add(words.take(i).joinToString(" "))
            }
        }
    }

    val queryList = strategies.filter { it.isNotBlank() }.distinct()
    var finalMetadata: TmdbMetadata? = null
    var hasNetworkError = false
    
    for (query in queryList) {
        for (lang in listOf("ko-KR", null)) {
            val (res, error) = searchTmdb(query, lang, typeHint)
            if (error != null) { hasNetworkError = true; break }
            if (res != null) { finalMetadata = res; break }
        }
        if (finalMetadata != null || hasNetworkError) break
    }

    var result = finalMetadata ?: TmdbMetadata()
    if (!result.overview.isNullOrBlank() && !result.overview!!.containsKorean()) {
        result = result.copy(overview = translateToKorean(result.overview!!))
    }

    if (!hasNetworkError) tmdbCache[title] = result
    return result
}

suspend fun fetchTmdbSeasonDetails(tmdbId: Int, season: Int): List<TmdbEpisode> {
    return try {
        val responseKo: TmdbSeasonResponse = client.get("$TMDB_BASE_URL/tv/$tmdbId/season/$season") {
            if (TMDB_API_KEY.startsWith("eyJ")) header("Authorization", "Bearer $TMDB_API_KEY")
            else parameter("api_key", TMDB_API_KEY)
            parameter("language", "ko-KR")
        }.body()
        
        var episodes = if (responseKo.episodes.all { it.overview.isNullOrBlank() }) {
            val responseEn: TmdbSeasonResponse = client.get("$TMDB_BASE_URL/tv/$tmdbId/season/$season") {
                if (TMDB_API_KEY.startsWith("eyJ")) header("Authorization", "Bearer $TMDB_API_KEY")
                else parameter("api_key", TMDB_API_KEY)
            }.body()
            responseEn.episodes
        } else responseKo.episodes

        coroutineScope {
            episodes.forEach { ep ->
                if (!ep.overview.isNullOrBlank() && !ep.overview!!.containsKorean()) {
                    launch { ep.overview = translateToKorean(ep.overview!!) }
                }
            }
        }
        episodes
    } catch (e: Exception) { emptyList() }
}

suspend fun fetchTmdbCredits(tmdbId: Int, mediaType: String?): List<TmdbCast> {
    if (tmdbId == 0 || mediaType == null) return emptyList()
    return try {
        val isTv = mediaType == "tv"
        val endpoint = if (isTv) "$TMDB_BASE_URL/tv/$tmdbId/aggregate_credits" else "$TMDB_BASE_URL/movie/$tmdbId/credits"
        val response: TmdbCreditsResponse = client.get(endpoint) {
            if (TMDB_API_KEY.startsWith("eyJ")) header("Authorization", "Bearer $TMDB_API_KEY")
            else parameter("api_key", TMDB_API_KEY)
        }.body()
        response.cast.take(30)
    } catch (e: Exception) { emptyList() }
}

private suspend fun searchTmdb(query: String, language: String?, typeHint: String? = null): Pair<TmdbMetadata?, Exception?> {
    if (query.isBlank()) return null to null
    return try {
        val endpoint = when(typeHint) {
            "movie" -> "movie"
            "tv" -> "tv"
            else -> "multi"
        }
        val response: TmdbSearchResponse = client.get("$TMDB_BASE_URL/search/$endpoint") {
            if (TMDB_API_KEY.startsWith("eyJ")) header("Authorization", "Bearer $TMDB_API_KEY")
            else parameter("api_key", TMDB_API_KEY)
            parameter("query", query)
            if (language != null) parameter("language", language)
            parameter("include_adult", "false")
        }.body()
        
        val results = response.results.filter { it.mediaType != "person" }
        if (results.isEmpty()) return null to null
        
        val result = if (language == "ko-KR") {
            results.find { it.originCountry?.contains("KR") == true } ?: results.firstOrNull { it.posterPath != null } ?: results.firstOrNull()
        } else {
            results.firstOrNull { it.posterPath != null } ?: results.firstOrNull()
        }
        
        val path = result?.posterPath ?: result?.backdropPath
        if (path != null) {
            val mediaType = result?.mediaType ?: typeHint ?: "movie"
            Pair(TmdbMetadata(tmdbId = result?.id, mediaType = mediaType, posterUrl = "$TMDB_IMAGE_BASE$path", overview = result?.overview, genreIds = result?.genreIds ?: emptyList()), null)
        }
        else Pair(null, null)
    } catch (e: Exception) { Pair(null, e) }
}

@Composable
fun TmdbAsyncImage(title: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop, typeHint: String? = null) {
    var metadata by remember(title) { mutableStateOf(tmdbCache[title]) }
    var isError by remember(title) { mutableStateOf(false) }
    var isLoading by remember(title) { mutableStateOf(metadata == null) }
    LaunchedEffect(title) {
        if (metadata == null) {
            isLoading = true
            metadata = fetchTmdbMetadata(title, typeHint)
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
    Screen.KOREAN_TV -> "ktv"
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
    if (!keepAfterHyphen) { cleaned = REGEX_SERIES_CUTOFF.replace(cleaned, "") }
    if (!keepAfterHyphen && cleaned.contains(" - ")) cleaned = cleaned.substringBefore(" - ")
    cleaned = REGEX_DATE_NOISE.replace(cleaned, " ")
    cleaned = Regex("(?i)\\.?[Ss]\\d+").replace(cleaned, " ")
    cleaned = REGEX_CLEAN_EXTRA.replace(cleaned, " ")
    cleaned = REGEX_NOISE.replace(cleaned, "")
    cleaned = REGEX_SYMBOLS.replace(cleaned, " ")
    val match = REGEX_TRAILING_NUM.find(" $cleaned")
    if (match != null) {
        val num = match.groupValues[1].toInt()
        if (num < 1000) { cleaned = cleaned.replace(Regex("${match.groupValues[1]}$"), "") }
    }
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
    return 1
}

fun String.prettyTitle(): String {
    val ep = this.extractEpisode(); val base = this.cleanTitle(keepAfterHyphen = true)
    if (ep == null) return base
    return if (base.contains(" - ")) { val split = base.split(" - ", limit = 2); "${split[0]} $ep - ${split[1]}" } else "$base $ep"
}

fun List<Movie>.groupBySeries(): List<Series> = this.groupBy { it.title.cleanTitle(keepAfterHyphen = false) }
    .map { (title, episodes) -> 
        val sortedEpisodes = episodes.sortedWith(
            compareBy<Movie> { it.title.extractSeason() }
                .thenBy { it.title.extractEpisode()?.filter { char -> char.isDigit() }?.toIntOrNull() ?: 0 }
                .thenBy { it.title }
        )
        Series(title = title, episodes = sortedEpisodes, thumbnailUrl = null) 
    }
    .sortedBy { it.title }

private var _database: AppDatabase? = null
fun createDatabase(driver: SqlDriver): AppDatabase = AppDatabase(driver)

@Composable
fun rememberAppDatabase(driver: SqlDriver): AppDatabase {
    return remember {
        if (_database == null) {
            _database = createDatabase(driver)
        }
        _database!!
    }
}

// 명시적인 suspend 함수로 분리하여 컴파일러 오류 방지
suspend fun fetchCategoryList(path: String): List<Category> {
    return try {
        val url = "$BASE_URL/list?path=${path.encodeURLParameter()}"
        client.get(url).body<List<Category>>()
    } catch (e: Exception) {
        emptyList()
    }
}

@Composable
fun App(driver: SqlDriver) {
    setSingletonImageLoaderFactory { platformContext ->
        ImageLoader.Builder(platformContext).components { add(KtorNetworkFetcherFactory(client)) }.crossfade(true).build()
    }
    
    val db = rememberAppDatabase(driver)
    val searchHistoryDataSource = remember { SearchHistoryDataSource(db) }
    val watchHistoryDataSource = remember { WatchHistoryDataSource(db) }

    val recentQueriesState = searchHistoryDataSource.getRecentQueries()
        .map { list -> list.map { it.toData() } }
        .collectAsState(initial = emptyList())
    val recentQueries by recentQueriesState
    
    val watchHistoryState = watchHistoryDataSource.getWatchHistory()
        .map { list -> list.map { it.toData() } }
        .collectAsState(initial = emptyList())
    val watchHistory by watchHistoryState

    val scope = rememberCoroutineScope()

    val homeScrollState = rememberLazyListState()
    val onAirScrollState = rememberLazyGridState()
    val animationsScrollState = rememberLazyListState()
    val moviesScrollState = rememberLazyListState()
    val foreignTvScrollState = rememberLazyListState()
    val koreanTvScrollState = rememberLazyListState()

    var homeLatestSeries by remember { mutableStateOf<List<Series>>(emptyList()) }
    var onAirSeries by remember { mutableStateOf<List<Series>>(emptyList()) }
    var onAirDramaSeries by remember { mutableStateOf<List<Series>>(emptyList()) }
    var selectedOnAirTab by rememberSaveable { mutableStateOf("라프텔 애니메이션") }

    var aniItems by remember { mutableStateOf<List<Category>>(emptyList()) }
    var aniCategories by remember { mutableStateOf<List<Pair<String, List<Series>>>>(emptyList()) }
    var selectedAniTab by rememberSaveable { mutableStateOf("라프텔") }
    
    var movieItems by remember { mutableStateOf<List<Category>>(emptyList()) }
    var movieCategories by remember { mutableStateOf<List<Pair<String, List<Series>>>>(emptyList()) }
    var selectedMovieTab by rememberSaveable { mutableStateOf("최신") }
    
    var foreignTvItems by remember { mutableStateOf<List<Category>>(emptyList()) }
    var foreignCategories by remember { mutableStateOf<List<Pair<String, List<Series>>>>(emptyList()) }
    var selectedForeignTab by rememberSaveable { mutableStateOf("다큐") }
    
    var koreanTvItems by remember { mutableStateOf<List<Category>>(emptyList()) }
    var koreanCategories by remember { mutableStateOf<List<Pair<String, List<Series>>>>(emptyList()) }
    var explorerSeries by remember { mutableStateOf<List<Series>>(emptyList()) }

    var foreignTvPathStack by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var koreanTvPathStack by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var moviePathStack by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var aniPathStack by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    
    var lastAniPath by remember { mutableStateOf<String?>(null) }
    var lastMoviePath by remember { mutableStateOf<String?>(null) }
    var lastForeignPath by remember { mutableStateOf<String?>(null) }
    var lastKoreanPath by remember { mutableStateOf<String?>(null) }

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    var selectedMovie by remember { mutableStateOf<Movie?>(null) }
    var moviePlaylist by remember { mutableStateOf<List<Movie>>(emptyList()) }
    var initialPlaybackPosition by remember { mutableStateOf(0L) }
    
    var selectedSeries by remember { mutableStateOf<Series?>(null) }
    var selectedItemScreen by rememberSaveable { mutableStateOf(Screen.HOME) } 
    var currentScreen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchCategory by rememberSaveable { mutableStateOf("전체") }
    var refreshTrigger by remember { mutableStateOf(0) }
    
    var selectedKoreanTab by rememberSaveable { mutableStateOf("") } 

    val currentPathStrings = remember(currentScreen, moviePathStack, aniPathStack, foreignTvPathStack, koreanTvPathStack) {
        when (currentScreen) {
            Screen.MOVIES -> moviePathStack
            Screen.ANIMATIONS -> aniPathStack
            Screen.FOREIGN_TV -> foreignTvPathStack
            Screen.KOREAN_TV -> koreanTvPathStack
            else -> emptyList()
        }
    }
    
    val saveWatchHistory: (Movie, Screen, List<String>) -> Unit = { movie, screen, pathStack ->
        scope.launch(Dispatchers.Default) {
            try {
                watchHistoryDataSource.insertWatchHistory(
                    movie.id, movie.title, movie.videoUrl, movie.thumbnailUrl, currentTimeMillis(), screen.name, Json.encodeToString(pathStack)
                )
            } catch (e: Exception) { }
        }
    }

    val isExplorerSeriesMode by remember {
        derivedStateOf {
            (currentScreen == Screen.FOREIGN_TV && foreignTvItems.any { it.movies.isNotEmpty() && foreignTvPathStack.isNotEmpty() }) || 
            (currentScreen == Screen.KOREAN_TV && koreanTvItems.any { it.movies.isNotEmpty() && koreanTvPathStack.isNotEmpty() }) || 
            (currentScreen == Screen.MOVIES && movieItems.any { it.movies.isNotEmpty() && moviePathStack.isNotEmpty() }) || 
            (currentScreen == Screen.ANIMATIONS && aniItems.any { it.movies.isNotEmpty() && aniPathStack.isNotEmpty() })
        }
    }

    LaunchedEffect(currentScreen, foreignTvPathStack, koreanTvPathStack, moviePathStack, aniPathStack, selectedOnAirTab, selectedAniTab, selectedMovieTab, selectedForeignTab, selectedKoreanTab, refreshTrigger) {
        supervisorScope {
            try {
                errorMessage = null
                when (currentScreen) {
                    Screen.HOME -> {
                        if (homeLatestSeries.isEmpty() || refreshTrigger > 0) {
                            isLoading = true
                            val lData = try { client.get("$BASE_URL/latestmovies").body<List<Category>>() } catch(e: Exception) { emptyList() }
                            val aData = try { client.get("$BASE_URL/animations").body<List<Category>>() } catch(e: Exception) { emptyList() }
                            homeLatestSeries = lData.flatMap { it.movies }.groupBySeries()
                            onAirSeries = aData.flatMap { it.movies }.groupBySeries()
                        }
                    }
                    Screen.ON_AIR -> {
                        if ((selectedOnAirTab == "라프텔 애니메이션" && onAirSeries.isEmpty()) || (selectedOnAirTab == "드라마" && onAirDramaSeries.isEmpty()) || refreshTrigger > 0) {
                            isLoading = true
                            val endpoint = if (selectedOnAirTab == "라프텔 애니메이션") "$BASE_URL/animations" else "$BASE_URL/dramas"
                            val raw: List<Category> = try { client.get(endpoint).body() } catch(e: Exception) { emptyList() }
                            if (selectedOnAirTab == "라프텔 애니메이션") onAirSeries = raw.flatMap { it.movies }.groupBySeries()
                            else onAirDramaSeries = raw.flatMap { it.movies }.groupBySeries()
                        }
                    }
                    Screen.ANIMATIONS -> {
                        if (aniPathStack.isEmpty()) {
                            val targetPath = "애니메이션/$selectedAniTab"
                            if (lastAniPath != targetPath || refreshTrigger > 0) {
                                scope.launch {
                                    isLoading = true
                                    val foldersRaw = fetchCategoryList(targetPath)
                                    val rootRaw = fetchCategoryList("애니메이션")
                                    if (foldersRaw.isNotEmpty()) {
                                        val fetchedData = coroutineScope {
                                            foldersRaw.map { catFolder ->
                                                async {
                                                    val catContent = fetchCategoryList("$targetPath/${catFolder.name}")
                                                    val isSeries = catContent.any { it.movies.isNotEmpty() }
                                                    if (isSeries) {
                                                        "__STANDALONE__" to listOf(Series(title = catFolder.name, episodes = emptyList(), fullPath = "$targetPath/${catFolder.name}"))
                                                    } else {
                                                        val seriesList = catContent.map { Series(title = it.name, episodes = emptyList(), fullPath = "$targetPath/${catFolder.name}/${it.name}") }
                                                        catFolder.name to seriesList
                                                    }
                                                }
                                            }.awaitAll().filter { it.second.isNotEmpty() }
                                        }
                                        val standalone = fetchedData.filter { it.first == "__STANDALONE__" }.flatMap { it.second }
                                        val themes = fetchedData.filter { it.first != "__STANDALONE__" }
                                        val finalCategories = mutableListOf<Pair<String, List<Series>>>()
                                        if (standalone.isNotEmpty()) { finalCategories.add((if (selectedAniTab == "라프텔") "라프텔 추천 명작" else "$selectedAniTab 추천 작품") to standalone) }
                                        val (multi, single) = themes.partition { it.second.size > 10 }
                                        multi.forEachIndexed { index, (name, list) -> finalCategories.add(getRandomThemeName(name, index + (if(standalone.isNotEmpty()) 1 else 0), isMovie = false, category = selectedAniTab) to list) }
                                        if (single.isNotEmpty()) finalCategories.add("기타 ${selectedAniTab} 작품" to single.flatMap { it.second })
                                        aniCategories = finalCategories.groupBy { it.first }.map { (name, groups) -> name to groups.flatMap { it.second }.distinctBy { it.title } }.toList()
                                        aniItems = rootRaw
                                    } else { aniCategories = emptyList(); aniItems = rootRaw }
                                    lastAniPath = targetPath
                                    isLoading = false
                                }
                            }
                        } else {
                            val path = "애니메이션/${aniPathStack.joinToString("/")}"
                            if (lastAniPath != path || refreshTrigger > 0) {
                                scope.launch {
                                    isLoading = true
                                    val raw = fetchCategoryList(path)
                                    aniItems = raw
                                    if (raw.any { it.movies.isNotEmpty() }) { explorerSeries = raw.flatMap { it.movies }.groupBySeries() }
                                    lastAniPath = path
                                    isLoading = false
                                }
                            }
                        }
                    }
                    Screen.MOVIES -> {
                        if (moviePathStack.isEmpty()) {
                            val targetPath = "영화/$selectedMovieTab"
                            if (lastMoviePath != targetPath || refreshTrigger > 0) {
                                scope.launch {
                                    isLoading = true
                                    val foldersRaw = fetchCategoryList(targetPath)
                                    val rootRaw = fetchCategoryList("영화")
                                    if (foldersRaw.isNotEmpty()) {
                                        val fetchedData = coroutineScope {
                                            foldersRaw.map { catFolder ->
                                                async {
                                                    val catContent = fetchCategoryList("$targetPath/${catFolder.name}")
                                                    val isSeries = catContent.any { it.movies.isNotEmpty() }
                                                    if (isSeries) {
                                                        "__STANDALONE__" to listOf(Series(title = catFolder.name, episodes = emptyList(), fullPath = "$targetPath/${catFolder.name}"))
                                                    } else {
                                                        val seriesList = catContent.map { Series(title = it.name, episodes = emptyList(), fullPath = "$targetPath/${catFolder.name}/${it.name}") }
                                                        catFolder.name to seriesList
                                                    }
                                                }
                                            }.awaitAll().filter { it.second.isNotEmpty() }
                                        }
                                        val standalone = fetchedData.filter { it.first == "__STANDALONE__" }.flatMap { it.second }
                                        val themes = fetchedData.filter { it.first != "__STANDALONE__" }
                                        val finalCategories = mutableListOf<Pair<String, List<Series>>>()
                                        if (standalone.isNotEmpty()) { finalCategories.add(("$selectedMovieTab 인기 영화") to standalone) }
                                        val (multi, single) = themes.partition { it.second.size > 10 }
                                        multi.forEachIndexed { index, (name, list) -> finalCategories.add(getRandomThemeName(name, index + (if(standalone.isNotEmpty()) 1 else 0), isMovie = true, category = selectedMovieTab) to list) }
                                        if (single.isNotEmpty()) finalCategories.add("기타 ${selectedMovieTab} 작품" to single.flatMap { it.second })
                                        movieCategories = finalCategories.groupBy { it.first }.map { (name, groups) -> name to groups.flatMap { it.second }.distinctBy { it.title } }.toList()
                                        movieItems = rootRaw.filter { it.name in listOf("제목", "최신", "UHD") }
                                    } else { movieCategories = emptyList(); movieItems = rootRaw.filter { it.name in listOf("제목", "최신", "UHD") } }
                                    lastMoviePath = targetPath
                                    isLoading = false
                                }
                            }
                        } else {
                            val path = "영화/${moviePathStack.joinToString("/")}"
                            if (lastMoviePath != path || refreshTrigger > 0) {
                                scope.launch {
                                    isLoading = true
                                    val raw = fetchCategoryList(path)
                                    movieItems = raw
                                    if (raw.any { it.movies.isNotEmpty() }) { explorerSeries = raw.flatMap { it.movies }.groupBySeries() }
                                    lastMoviePath = path
                                    isLoading = false
                                }
                            }
                        }
                    }
                    Screen.FOREIGN_TV -> {
                        if (foreignTvPathStack.isEmpty()) {
                            val rootFolders = fetchCategoryList("외국TV")
                            val targetPath = "외국TV/$selectedForeignTab"
                            if (lastForeignPath != targetPath || refreshTrigger > 0) {
                                scope.launch {
                                    isLoading = true
                                    val foldersRaw = fetchCategoryList(targetPath)
                                    if (foldersRaw.isNotEmpty()) {
                                        val fetchedData = coroutineScope {
                                            foldersRaw.map { catFolder ->
                                                async {
                                                    val catContent = fetchCategoryList("$targetPath/${catFolder.name}")
                                                    val isSeries = catContent.any { it.movies.isNotEmpty() }
                                                    if (isSeries) {
                                                        "__STANDALONE__" to listOf(Series(title = catFolder.name, episodes = emptyList(), fullPath = "$targetPath/${catFolder.name}"))
                                                    } else {
                                                        val seriesList = catContent.map { Series(title = it.name, episodes = emptyList(), fullPath = "$targetPath/${catFolder.name}/${it.name}") }
                                                        catFolder.name to seriesList
                                                    }
                                                }
                                            }.awaitAll().filter { it.second.isNotEmpty() }
                                        }
                                        val standalone = fetchedData.filter { it.first == "__STANDALONE__" }.flatMap { it.second }
                                        val themes = fetchedData.filter { it.first != "__STANDALONE__" }
                                        val finalCategories = mutableListOf<Pair<String, List<Series>>>()
                                        if (standalone.isNotEmpty()) { finalCategories.add(("$selectedForeignTab 추천 명작") to standalone) }
                                        val (multi, single) = themes.partition { it.second.size > 10 }
                                        multi.forEachIndexed { index, (name, list) -> finalCategories.add(getRandomThemeName(name, index + (if(standalone.isNotEmpty()) 1 else 0), isMovie = false, category = selectedForeignTab) to list) }
                                        if (single.isNotEmpty()) finalCategories.add("기타 ${selectedForeignTab} 작품" to single.flatMap { it.second })
                                        foreignCategories = finalCategories.groupBy { it.first }.map { (name, groups) -> name to groups.flatMap { it.second }.distinctBy { it.title } }.toList()
                                    } else { foreignCategories = emptyList() }
                                    foreignTvItems = rootFolders.filter { it.name in listOf("중국 드라마", "일본 드라마", "미국 드라마", "다큐", "기타 국가 드라마") }
                                    lastForeignPath = targetPath
                                    isLoading = false
                                }
                            }
                        } else {
                            val path = "외국TV/${foreignTvPathStack.joinToString("/")}"
                            if (lastForeignPath != path || refreshTrigger > 0) {
                                scope.launch {
                                    isLoading = true
                                    val raw = fetchCategoryList(path)
                                    foreignTvItems = raw
                                    if (raw.any { it.movies.isNotEmpty() }) { explorerSeries = raw.flatMap { it.movies }.groupBySeries() }
                                    lastForeignPath = path
                                    isLoading = false
                                }
                            }
                        }
                    }
                    Screen.KOREAN_TV -> {
                        if (koreanTvPathStack.isEmpty()) {
                            val rootFolders = fetchCategoryList("국내TV")
                            val filterOptions = rootFolders.filter { it.name != "[업로드]" }
                            if (filterOptions.isNotEmpty() && selectedKoreanTab.isEmpty()) { selectedKoreanTab = filterOptions.first().name }
                            if (selectedKoreanTab.isNotEmpty()) {
                                val targetPath = "국내TV/$selectedKoreanTab"
                                if (lastKoreanPath != targetPath || refreshTrigger > 0) {
                                    scope.launch {
                                        isLoading = true
                                        try {
                                            val foldersRaw = fetchCategoryList(targetPath)
                                            if (foldersRaw.isNotEmpty()) {
                                                val fetchedData = coroutineScope {
                                                    foldersRaw.map { catFolder ->
                                                        async {
                                                            val catContent = fetchCategoryList("$targetPath/${catFolder.name}")
                                                            val isSeries = catContent.any { it.movies.isNotEmpty() }
                                                            if (isSeries) {
                                                                "__STANDALONE__" to listOf(Series(title = catFolder.name, episodes = emptyList(), fullPath = "$targetPath/${catFolder.name}"))
                                                            } else {
                                                                val seriesList = catContent.map { Series(title = it.name, episodes = emptyList(), fullPath = "$targetPath/${catFolder.name}/${it.name}") }
                                                                catFolder.name to seriesList
                                                            }
                                                        }
                                                    }.awaitAll().filter { it.second.isNotEmpty() }
                                                }
                                                val standalone = fetchedData.filter { it.first == "__STANDALONE__" }.flatMap { it.second }
                                                val themes = fetchedData.filter { it.first != "__STANDALONE__" }
                                                val finalCategories = mutableListOf<Pair<String, List<Series>>>()
                                                if (standalone.isNotEmpty()) { finalCategories.add(("$selectedKoreanTab 인기 작품") to standalone) }
                                                val (multi, single) = themes.partition { it.second.size > 10 }
                                                multi.forEachIndexed { index, (name, list) -> finalCategories.add(getRandomThemeName(name, index + (if(standalone.isNotEmpty()) 1 else 0), isMovie = false, category = selectedKoreanTab) to list) }
                                                if (single.isNotEmpty()) finalCategories.add("기타 ${selectedKoreanTab} 작품" to single.flatMap { it.second })
                                                koreanCategories = finalCategories.groupBy { it.first }.map { (name, groups) -> name to groups.flatMap { it.second }.distinctBy { it.title } }.toList()
                                            } else { koreanCategories = emptyList() }
                                        } catch (e: Exception) { errorMessage = "국내 TV 데이터 로딩 중 오류 발생: ${e.message}"; koreanCategories = emptyList() }
                                        finally { koreanTvItems = filterOptions; lastKoreanPath = targetPath; isLoading = false }
                                    }
                                }
                            } else { koreanTvItems = filterOptions; koreanCategories = emptyList() }
                        } else {
                            val path = "국내TV/${koreanTvPathStack.joinToString("/")}"
                            if (lastKoreanPath != path || refreshTrigger > 0) {
                                scope.launch {
                                    isLoading = true
                                    try {
                                        val raw = fetchCategoryList(path)
                                        koreanTvItems = raw
                                        if (raw.any { it.movies.isNotEmpty() }) { explorerSeries = raw.flatMap { it.movies }.groupBySeries() }
                                        else { explorerSeries = emptyList() }
                                        lastKoreanPath = path
                                    } catch (e: Exception) { errorMessage = "데이터 로딩 중 오류 발생: ${e.message}"; koreanTvItems = emptyList(); explorerSeries = emptyList() }
                                    finally { isLoading = false }
                                }
                            }
                        }
                    }
                    else -> {}
                }
            } catch (e: Exception) { errorMessage = "서버 연결에 실패했습니다.\n${e.message}" } finally { isLoading = false }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFE50914), background = Color.Black, surface = Color.Black)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (errorMessage != null) { ErrorScreen(message = errorMessage!!, onRetry = { refreshTrigger++ }) }
            else if (selectedMovie != null) {
                VideoPlayerScreen(
                    movie = selectedMovie!!, playlist = moviePlaylist, initialPosition = initialPlaybackPosition, currentScreen = selectedItemScreen, pathStack = currentPathStrings, 
                    onBack = { selectedMovie = null; moviePlaylist = emptyList(); initialPlaybackPosition = 0L }, 
                    onMovieChange = { newMovie -> selectedMovie = newMovie; initialPlaybackPosition = 0L; saveWatchHistory(newMovie, selectedItemScreen, currentPathStrings) },
                    tabName = selectedOnAirTab
                )
            } else if (selectedSeries != null) {
                val catTitle = when (selectedItemScreen) {
                    Screen.ON_AIR -> selectedOnAirTab
                    Screen.ANIMATIONS -> "애니메이션"
                    Screen.MOVIES, Screen.LATEST -> "영화"
                    Screen.FOREIGN_TV -> "외국 TV"
                    Screen.KOREAN_TV -> "국내 TV"
                    else -> ""
                }
                SeriesDetailScreen(
                    series = selectedSeries!!, categoryTitle = catTitle, currentScreen = selectedItemScreen, pathStack = currentPathStrings, 
                    onBack = { 
                        selectedSeries = null
                        if (selectedItemScreen == Screen.ANIMATIONS && aniPathStack.size >= 1 && (aniPathStack.firstOrNull() == "라프텔" || aniPathStack.firstOrNull() == "시리즈")) { aniPathStack = emptyList() }
                        if (selectedItemScreen == Screen.MOVIES && moviePathStack.size >= 1 && (moviePathStack.firstOrNull() == "최신" || moviePathStack.firstOrNull() == "제목" || moviePathStack.firstOrNull() == "UHD")) { moviePathStack = emptyList() }
                        if (selectedItemScreen == Screen.FOREIGN_TV && foreignTvPathStack.size >= 1 && listOf("중국 드라마", "일본 드라마", "미국 드라마", "다큐", "기타 국가 드라마").any { it == (foreignTvPathStack.firstOrNull() ?: "") }) { foreignTvPathStack = emptyList() }
                        if (selectedItemScreen == Screen.KOREAN_TV && koreanTvPathStack.isNotEmpty()) { koreanTvPathStack = emptyList() }
                    }, 
                    onSaveWatchHistory = saveWatchHistory,
                    onPlayFullScreen = { movie, position -> saveWatchHistory(movie, selectedItemScreen, currentPathStrings); initialPlaybackPosition = position; selectedMovie = movie; moviePlaylist = selectedSeries!!.episodes }
                )
            } else if (isExplorerSeriesMode) {
                val catTitle = when (currentScreen) {
                    Screen.ANIMATIONS -> "애니메이션"
                    Screen.MOVIES -> "영화"
                    Screen.FOREIGN_TV -> "외국 TV"
                    Screen.KOREAN_TV -> "국내 TV"
                    else -> ""
                }
                Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    NasAppBar(title = currentPathStrings.lastOrNull() ?: catTitle, onBack = {
                        when (currentScreen) {
                            Screen.MOVIES -> moviePathStack = moviePathStack.dropLast(1)
                            Screen.ANIMATIONS -> aniPathStack = aniPathStack.dropLast(1)
                            Screen.KOREAN_TV -> koreanTvPathStack = koreanTvPathStack.dropLast(1)
                            else -> foreignTvPathStack = foreignTvPathStack.dropLast(1)
                        }
                    })
                    BoxWithConstraints {
                        val columns = if (maxWidth > 600.dp) GridCells.Fixed(4) else GridCells.Fixed(3)
                        LazyVerticalGrid(columns = columns, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 100.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(explorerSeries) { series -> SearchGridItem(series, currentScreen) { selectedSeries = it; selectedItemScreen = currentScreen } }
                        }
                    }
                }
            } else {
                Scaffold(
                    topBar = { if (currentScreen != Screen.SEARCH) NetflixTopBar(currentScreen, onScreenSelected = { currentScreen = it }) },
                    bottomBar = { 
                        NavigationBar(containerColor = Color.Black, contentColor = Color.White) {
                            NavigationBarItem(selected = currentScreen == Screen.HOME, onClick = { currentScreen = Screen.HOME }, icon = { Icon(Icons.Default.Home, contentDescription = null) }, label = { Text(text = "홈") })
                            NavigationBarItem(selected = currentScreen == Screen.SEARCH, onClick = { currentScreen = Screen.SEARCH }, icon = { Icon(Icons.Default.Search, contentDescription = null) }, label = { Text(text = "검색") })
                        }
                    },
                    containerColor = Color.Black
                ) { pv ->
                    Box(modifier = Modifier.padding(pv).fillMaxSize()) {
                        val isServerDataEmpty by remember {
                            derivedStateOf {
                                when(currentScreen) {
                                    Screen.HOME -> homeLatestSeries.isEmpty() && onAirSeries.isEmpty()
                                    Screen.ON_AIR -> (if(selectedOnAirTab == "라프텔 애니메이션") onAirSeries else onAirDramaSeries).isEmpty()
                                    Screen.FOREIGN_TV -> foreignCategories.isEmpty() && foreignTvItems.isEmpty()
                                    Screen.KOREAN_TV -> koreanCategories.isEmpty() && koreanTvItems.isEmpty()
                                    Screen.MOVIES -> movieCategories.isEmpty() && movieItems.isEmpty()
                                    Screen.ANIMATIONS -> aniCategories.isEmpty() && aniItems.isEmpty()
                                    else -> false
                                }
                            }
                        }
                        val shouldShowFullLoading = isLoading && isServerDataEmpty
                        if (shouldShowFullLoading) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.Red) } }
                        else {
                            when (currentScreen) {
                                Screen.HOME -> HomeScreen(watchHistory, homeLatestSeries, onAirSeries, homeScrollState, onWatchItemClick = { history ->
                                    val movie = Movie(history.id, history.title, history.thumbnailUrl, history.videoUrl)
                                    val screen = Screen.valueOf(history.screenType)
                                    val allLoadedSeries = homeLatestSeries + onAirSeries + onAirDramaSeries + explorerSeries
                                    val foundSeries = allLoadedSeries.find { s -> s.episodes.any { it.id == movie.id } }
                                    selectedMovie = movie
                                    moviePlaylist = foundSeries?.episodes ?: listOf(movie)
                                    selectedItemScreen = screen
                                    try {
                                        val decodedStack = Json.decodeFromString<List<String>>(history.pathStackJson)
                                        when(screen) {
                                            Screen.MOVIES -> moviePathStack = decodedStack
                                            Screen.ANIMATIONS -> aniPathStack = decodedStack
                                            Screen.FOREIGN_TV -> foreignTvPathStack = decodedStack
                                            Screen.KOREAN_TV -> koreanTvPathStack = decodedStack
                                            else -> {}
                                        }
                                    } catch (e: Exception) { }
                                }, onSeriesClick = { s, sc -> selectedSeries = s; selectedItemScreen = sc })
                                Screen.ON_AIR -> CategoryListScreen(selectedFilter = selectedOnAirTab, onFilterSelected = { selectedOnAirTab = it }, seriesList = if (selectedOnAirTab == "라프텔 애니메이션") onAirSeries else onAirDramaSeries, scrollState = onAirScrollState) { s, sc -> selectedSeries = s; selectedItemScreen = sc }
                                Screen.ANIMATIONS -> {
                                    if (aniPathStack.isEmpty()) {
                                        Column(modifier = Modifier.fillMaxSize()) {
                                            var expanded by remember { mutableStateOf(false) }
                                            Box(modifier = Modifier.padding(16.dp)) {
                                                OutlinedButton(onClick = { expanded = true }, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White), border = BorderStroke(1.dp, Color.Gray)) {
                                                    Text(text = selectedAniTab, fontWeight = FontWeight.Bold)
                                                    Spacer(Modifier.width(8.dp))
                                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                                }
                                                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(Color.Black)) {
                                                    listOf("라프텔", "시리즈").forEach { tab ->
                                                        DropdownMenuItem(text = { Text(tab, color = Color.White) }, onClick = { selectedAniTab = tab; expanded = false })
                                                    }
                                                }
                                            }
                                            LazyColumn(state = animationsScrollState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp)) {
                                                items(aniCategories) { pair ->
                                                    val (themeName, seriesList) = pair
                                                    MovieRow(
                                                        title = themeName, screen = Screen.ANIMATIONS, seriesList = seriesList,
                                                        onSeriesClick = { series, sc -> 
                                                            scope.launch {
                                                                isLoading = true
                                                                try {
                                                                    if (series.fullPath != null) {
                                                                        val raw: List<Category> = client.get("$BASE_URL/list?path=${series.fullPath.encodeURLParameter()}").body()
                                                                        aniPathStack = series.fullPath.removePrefix("애니메이션/").split("/")
                                                                        selectedSeries = series.copy(episodes = raw.flatMap { it.movies })
                                                                        selectedItemScreen = sc
                                                                    }
                                                                } catch (e: Exception) { }
                                                                finally { isLoading = false }
                                                            }
                                                        }
                                                    )
                                                }
                                                val otherFolders = aniItems.filter { it.name != "라프텔" && it.name != "시리즈" }
                                                if (otherFolders.isNotEmpty()) {
                                                    item { Text(text = "기타 카테고리", color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp), modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 12.dp)) }
                                                    items(otherFolders) { item ->
                                                        ListItem(
                                                            headlineContent = { Text(text = item.name, color = Color.White) },
                                                            leadingContent = { Icon(imageVector = Icons.AutoMirrored.Filled.List, contentDescription = null, tint = Color.White) },
                                                            trailingContent = { Icon(imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Color.DarkGray) },
                                                            modifier = Modifier.clickable { aniPathStack = aniPathStack + item.name },
                                                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    } else { MovieExplorer(title = "애니메이션", screen = Screen.ANIMATIONS, pathStack = aniPathStack, items = aniItems, scrollState = animationsScrollState, onFolderClick = { aniPathStack = aniPathStack + it }, onBackClick = { if (aniPathStack.isNotEmpty()) aniPathStack = aniPathStack.dropLast(1) }) }
                                }
                                Screen.MOVIES -> {
                                    if (moviePathStack.isEmpty()) {
                                        Column(modifier = Modifier.fillMaxSize()) {
                                            var expanded by remember { mutableStateOf(false) }
                                            Box(modifier = Modifier.padding(16.dp)) {
                                                OutlinedButton(onClick = { expanded = true }, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White), border = BorderStroke(1.dp, Color.Gray)) {
                                                    Text(text = selectedMovieTab, fontWeight = FontWeight.Bold)
                                                    Spacer(Modifier.width(8.dp))
                                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                                }
                                                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(Color.Black)) {
                                                    listOf("최신", "제목", "UHD").forEach { tab ->
                                                        DropdownMenuItem(text = { Text(tab, color = Color.White) }, onClick = { selectedMovieTab = tab; expanded = false })
                                                    }
                                                }
                                            }
                                            LazyColumn(state = moviesScrollState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp)) {
                                                items(movieCategories) { pair ->
                                                    val (themeName, seriesList) = pair
                                                    MovieRow(
                                                        title = themeName, screen = Screen.MOVIES, seriesList = seriesList,
                                                        onSeriesClick = { series, sc -> 
                                                            scope.launch {
                                                                isLoading = true
                                                                try {
                                                                    if (series.fullPath != null) {
                                                                        val raw: List<Category> = client.get("$BASE_URL/list?path=${series.fullPath.encodeURLParameter()}").body()
                                                                        moviePathStack = series.fullPath.removePrefix("영화/").split("/")
                                                                        selectedSeries = series.copy(episodes = raw.flatMap { it.movies })
                                                                        selectedItemScreen = sc
                                                                    }
                                                                } catch (e: Exception) { }
                                                                finally { isLoading = false }
                                                            }
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    } else { MovieExplorer(title = "영화", screen = Screen.MOVIES, pathStack = moviePathStack, items = movieItems, scrollState = moviesScrollState, onFolderClick = { moviePathStack = moviePathStack + it }, onBackClick = { if (moviePathStack.isNotEmpty()) moviePathStack = moviePathStack.dropLast(1) }) }
                                }
                                Screen.FOREIGN_TV -> {
                                    if (foreignTvPathStack.isEmpty()) {
                                        Column(modifier = Modifier.fillMaxSize()) {
                                            var expanded by remember { mutableStateOf(false) }
                                            Box(modifier = Modifier.padding(16.dp)) {
                                                OutlinedButton(onClick = { expanded = true }, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White), border = BorderStroke(1.dp, Color.Gray)) {
                                                    Text(text = selectedForeignTab, fontWeight = FontWeight.Bold)
                                                    Spacer(Modifier.width(8.dp))
                                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                                }
                                                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(Color.Black)) {
                                                    listOf("다큐", "중국 드라마", "일본 드라마", "미국 드라마", "기타 국가 드라마").forEach { tab ->
                                                        DropdownMenuItem(text = { Text(tab, color = Color.White) }, onClick = { selectedForeignTab = tab; expanded = false })
                                                    }
                                                }
                                            }
                                            LazyColumn(state = foreignTvScrollState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp)) {
                                                items(foreignCategories) { pair ->
                                                    val (themeName, seriesList) = pair
                                                    MovieRow(
                                                        title = themeName, screen = Screen.FOREIGN_TV, seriesList = seriesList,
                                                        onSeriesClick = { series, sc -> 
                                                            scope.launch {
                                                                isLoading = true
                                                                try {
                                                                    if (series.fullPath != null) {
                                                                        val raw: List<Category> = client.get("$BASE_URL/list?path=${series.fullPath.encodeURLParameter()}").body()
                                                                        foreignTvPathStack = series.fullPath.removePrefix("외국TV/").split("/")
                                                                        selectedSeries = series.copy(episodes = raw.flatMap { it.movies })
                                                                        selectedItemScreen = sc
                                                                    }
                                                                } catch (e: Exception) { }
                                                                finally { isLoading = false }
                                                            }
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    } else { MovieExplorer(title = "외국 TV", screen = Screen.FOREIGN_TV, pathStack = foreignTvPathStack, items = foreignTvItems, scrollState = foreignTvScrollState, onFolderClick = { foreignTvPathStack = foreignTvPathStack + it }, onBackClick = { if (foreignTvPathStack.isNotEmpty()) foreignTvPathStack = foreignTvPathStack.dropLast(1) }) }
                                }
                                Screen.KOREAN_TV -> {
                                    if (koreanTvPathStack.isEmpty()) {
                                        Column(modifier = Modifier.fillMaxSize()) {
                                            var expanded by remember { mutableStateOf(false) }
                                            Box(modifier = Modifier.padding(16.dp)) {
                                                OutlinedButton(onClick = { expanded = true }, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White), border = BorderStroke(1.dp, Color.Gray)) {
                                                    Text(text = if (selectedKoreanTab.isNotEmpty()) selectedKoreanTab else "카테고리 선택", fontWeight = FontWeight.Bold)
                                                    Spacer(Modifier.width(8.dp))
                                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                                }
                                                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(Color.Black)) {
                                                    koreanTvItems.map { it.name }.forEach { tab ->
                                                        DropdownMenuItem(text = { Text(tab, color = Color.White) }, onClick = { selectedKoreanTab = tab; expanded = false })
                                                    }
                                                }
                                            }
                                            LazyColumn(state = koreanTvScrollState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp)) {
                                                items(koreanCategories) { pair ->
                                                    val (themeName, seriesList) = pair
                                                    MovieRow(
                                                        title = themeName, screen = Screen.KOREAN_TV, seriesList = seriesList,
                                                        onSeriesClick = { series, sc -> 
                                                            scope.launch {
                                                                isLoading = true
                                                                try {
                                                                    if (series.fullPath != null) {
                                                                        val raw: List<Category> = client.get("$BASE_URL/list?path=${series.fullPath.encodeURLParameter()}").body()
                                                                        koreanTvPathStack = series.fullPath.removePrefix("국내TV/").split("/")
                                                                        selectedSeries = series.copy(episodes = raw.flatMap { it.movies })
                                                                        selectedItemScreen = sc
                                                                    }
                                                                } catch (e: Exception) { }
                                                                finally { isLoading = false }
                                                            }
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    } else { MovieExplorer(title = "국내 TV", screen = Screen.KOREAN_TV, pathStack = koreanTvPathStack, items = koreanTvItems, scrollState = koreanTvScrollState, onFolderClick = { koreanTvPathStack = koreanTvPathStack + it }, onBackClick = { if (koreanTvPathStack.isNotEmpty()) koreanTvPathStack = koreanTvPathStack.dropLast(1) }) }
                                }
                                Screen.SEARCH -> SearchScreen(query = searchQuery, onQueryChange = { searchQuery = it }, selectedCategory = searchCategory, onCategoryChange = { searchCategory = it }, recentQueries = recentQueries, onSaveQuery = { q -> scope.launch { searchHistoryDataSource.insertQuery(q, currentTimeMillis()) } }, onDeleteQuery = { q -> scope.launch { searchHistoryDataSource.deleteQuery(q) } }, onClearAll = { scope.launch { searchHistoryDataSource.clearAll() } }, onSeriesClick = { s, sc -> selectedSeries = s; selectedItemScreen = sc })
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
fun CategoryListScreen(selectedFilter: String, onFilterSelected: (String) -> Unit, seriesList: List<Series>, scrollState: LazyGridState = rememberLazyGridState(), onSeriesClick: (Series, Screen) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Box(modifier = Modifier.padding(16.dp)) {
            OutlinedButton(onClick = { expanded = true }, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White), border = BorderStroke(1.dp, Color.Gray)) {
                Text(text = selectedFilter, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(Color.Black)) {
                listOf("라프텔 애니메이션", "드라마").forEach { filter ->
                    DropdownMenuItem(text = { Text(filter, color = Color.White) }, onClick = { onFilterSelected(filter); expanded = false })
                }
            }
        }
        BoxWithConstraints {
            val columns = if (maxWidth > 600.dp) GridCells.Fixed(4) else GridCells.Fixed(3)
            LazyVerticalGrid(state = scrollState, columns = columns, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 100.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(seriesList) { series -> SearchGridItem(series, Screen.ON_AIR) { onSeriesClick(series, Screen.ON_AIR) } }
            }
        }
    }
}

@Composable
fun SearchScreen(query: String, onQueryChange: (String) -> Unit, selectedCategory: String, onCategoryChange: (String) -> Unit, recentQueries: List<SearchHistory>, onSaveQuery: (String) -> Unit, onDeleteQuery: (String) -> Unit, onClearAll: () -> Unit, onSeriesClick: (Series, Screen) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
        OutlinedTextField(
            value = query, onValueChange = onQueryChange, modifier = Modifier.fillMaxWidth().padding(16.dp),
            placeholder = { Text("영화, TV 프로그램, 애니메이션 검색", color = Color.Gray) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
            trailingIcon = { if (query.isNotEmpty()) { IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Default.Close, contentDescription = null, tint = Color.Gray) } } },
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color.Red, unfocusedBorderColor = Color.DarkGray, cursorColor = Color.Red),
            singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) onSaveQuery(query) })
        )
        if (query.isEmpty()) {
            if (recentQueries.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("최근 검색어", color = Color.White, fontWeight = FontWeight.Bold); TextButton(onClick = onClearAll) { Text("모두 지우기", color = Color.Gray) }
                }
                LazyColumn {
                    items(recentQueries) { history ->
                        ListItem(headlineContent = { Text(history.query, color = Color.White) }, leadingContent = { Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.Gray) }, trailingContent = { IconButton(onClick = { onDeleteQuery(history.query) }) { Icon(Icons.Default.Close, contentDescription = null, tint = Color.Gray) } }, modifier = Modifier.clickable { onQueryChange(history.query) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                    }
                }
            }
        } else {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopCenter) { Text("검색 결과가 여기에 표시됩니다.", color = Color.Gray) }
        }
    }
}

@Composable
fun ErrorScreen(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = Color.Red, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(16.dp)); Text(text = "연결 오류", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp)); Text(text = message, color = Color.Gray, fontSize = 14.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp)); Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("다시 시도", color = Color.White) }
        }
    }
}

@Composable
fun WatchHistoryRow(watchHistory: List<WatchHistory>, onWatchItemClick: (WatchHistory) -> Unit) {
    if (watchHistory.isEmpty()) return
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Text(text = "시청 중인 콘텐츠", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold, fontSize = 22.sp), color = Color.White, modifier = Modifier.padding(start = 16.dp, bottom = 12.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(watchHistory, key = { it.id }) { history ->
                val typeHint = if (history.screenType == Screen.MOVIES.name || history.screenType == Screen.LATEST.name) "movie" else "tv"
                Card(modifier = Modifier.width(160.dp).height(100.dp).clickable { onWatchItemClick(history) }, shape = RoundedCornerShape(4.dp)) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        TmdbAsyncImage(title = history.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, typeHint = typeHint)
                        Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))))
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(32.dp))
                        Text(text = history.title.cleanTitle(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
fun HomeScreen(watchHistory: List<WatchHistory>, latestSeries: List<Series>, aniSeries: List<Series>, scrollState: LazyListState = rememberLazyListState(), onWatchItemClick: (WatchHistory) -> Unit, onSeriesClick: (Series, Screen) -> Unit) {
    val heroMovie = remember(latestSeries, aniSeries) { latestSeries.firstOrNull()?.episodes?.firstOrNull() ?: aniSeries.firstOrNull()?.episodes?.firstOrNull() }
    val isMainContentLoaded = heroMovie != null || latestSeries.isNotEmpty() || aniSeries.isNotEmpty()
    LazyColumn(state = scrollState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp)) { 
        if (heroMovie != null) { item { HeroSection(heroMovie) { m -> val target = latestSeries.find { it.episodes.any { ep -> ep.id == m.id } }; if (target != null) onSeriesClick(target, Screen.LATEST) else aniSeries.find { it.episodes.any { ep -> ep.id == m.id } }?.let { onSeriesClick(it, Screen.ON_AIR) } } } }
        if (latestSeries.isNotEmpty()) { item { MovieRow("최신 영화", Screen.LATEST, latestSeries, onSeriesClick) } }
        if (aniSeries.isNotEmpty()) { item { MovieRow("라프텔 애니메이션", Screen.ON_AIR, aniSeries, onSeriesClick) } }
        if (isMainContentLoaded && watchHistory.isNotEmpty()) { item { WatchHistoryRow(watchHistory, onWatchItemClick) } }
    }
}

@Composable
fun MovieRow(title: String, screen: Screen, seriesList: List<Series>, onSeriesClick: (Series, Screen) -> Unit) {
    if (seriesList.isEmpty()) return
    val typeHint = if (screen == Screen.MOVIES || screen == Screen.LATEST) "movie" else "tv"
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold, fontSize = 22.sp), color = Color.White, modifier = Modifier.padding(start = 16.dp, bottom = 12.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(seriesList) { series -> Card(modifier = Modifier.width(120.dp).height(180.dp).clickable { onSeriesClick(series, screen) }, shape = RoundedCornerShape(4.dp)) { TmdbAsyncImage(title = series.title, modifier = Modifier.fillMaxSize(), typeHint = typeHint) } }
        }
    }
}

@Composable
fun SearchGridItem(series: Series, screen: Screen, onSeriesClick: (Series) -> Unit) {
    val typeHint = if (screen == Screen.MOVIES) "movie" else "tv"
    Card(modifier = Modifier.aspectRatio(0.67f).clickable { onSeriesClick(series) }, shape = RoundedCornerShape(4.dp)) { TmdbAsyncImage(title = series.title, modifier = Modifier.fillMaxSize(), typeHint = typeHint) }
}

@Composable
fun MovieExplorer(title: String, screen: Screen, pathStack: List<String>, items: List<Category>, scrollState: LazyListState = rememberLazyListState(), onFolderClick: (String) -> Unit, onBackClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        NasAppBar(title = if (pathStack.isEmpty()) title else pathStack.last(), onBack = if (pathStack.isNotEmpty()) onBackClick else null)
        LazyColumn(state = scrollState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp)) {
            items(items) { item ->
                ListItem(headlineContent = { Text(text = item.name, color = Color.White) }, leadingContent = { Icon(imageVector = Icons.AutoMirrored.Filled.List, contentDescription = null, tint = Color.White) }, trailingContent = { Icon(imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Color.DarkGray) }, modifier = Modifier.clickable { onFolderClick(item.name) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent) )
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
fun SeriesDetailScreen(series: Series, categoryTitle: String = "", currentScreen: Screen, pathStack: List<String>, onBack: () -> Unit, onSaveWatchHistory: (Movie, Screen, List<String>) -> Unit, onPlayFullScreen: (Movie, Long) -> Unit) {
    val scope = rememberCoroutineScope()
    var episodes by remember(series) { mutableStateOf(series.episodes) }
    var playingMovie by remember(series, episodes) { mutableStateOf(episodes.firstOrNull()) }
    var metadata by remember(series) { mutableStateOf<TmdbMetadata?>(null) }
    var credits by remember(series) { mutableStateOf<List<TmdbCast>>(emptyList()) }
    var seasonEpisodes by remember(series) { mutableStateOf<List<TmdbEpisode>>(emptyList()) }
    var currentPosition by remember { mutableStateOf(0L) }
    val typeHint = if (currentScreen == Screen.MOVIES || currentScreen == Screen.LATEST) "movie" else "tv"
    LaunchedEffect(playingMovie) { playingMovie?.let { onSaveWatchHistory(it, currentScreen, pathStack) } }
    LaunchedEffect(series) { 
        if (episodes.isEmpty() && series.fullPath != null) {
            try {
                val raw: List<Category> = client.get("$BASE_URL/list?path=${series.fullPath.encodeURLParameter()}").body()
                episodes = raw.flatMap { it.movies }
            } catch (e: Exception) {}
        }
        val meta = fetchTmdbMetadata(series.title, typeHint)
        metadata = meta
        if (meta.tmdbId != null) {
            credits = fetchTmdbCredits(meta.tmdbId, meta.mediaType)
            val seasonNum = episodes.firstOrNull()?.title?.extractSeason() ?: 1
            seasonEpisodes = fetchTmdbSeasonDetails(meta.tmdbId, seasonNum)
        }
    }
    DisposableEffect(Unit) { onDispose { scope.launch { try { client.get("$BASE_URL/stop_all") } catch (e: Exception) {} } } }
    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        NasAppBar(title = categoryTitle, onBack = onBack)
        Box(modifier = Modifier.fillMaxWidth().height(210.dp).background(Color.DarkGray)) { 
            playingMovie?.let { movie -> 
                VideoPlayer(url = createVideoServeUrl(currentScreen, pathStack, movie, tabName = categoryTitle), modifier = Modifier.fillMaxSize(), initialPosition = 0L, onPositionUpdate = { currentPosition = it }, onFullscreenClick = { onPlayFullScreen(movie, currentPosition) }, onVideoEnded = {
                    val currentIndex = episodes.indexOfFirst { it.id == movie.id }
                    if (currentIndex != -1 && currentIndex < episodes.size - 1) { playingMovie = episodes[currentIndex + 1] }
                })
            }
        }
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp)) {
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(text = series.title, color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold, fontSize = 24.sp))
                    if (!metadata?.overview.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(text = metadata!!.overview!!, color = Color.LightGray, style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp), maxLines = 5, overflow = TextOverflow.Ellipsis) 
                    }
                }
            }
            if (credits.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        Text(text = "성우 및 출연진", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(credits) { cast ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(85.dp)) {
                                    AsyncImage(model = ImageRequest.Builder(LocalPlatformContext.current).data("$TMDB_IMAGE_BASE${cast.profilePath}").crossfade(true).build(), contentDescription = null, modifier = Modifier.size(75.dp).clip(CircleShape).background(Color.DarkGray), contentScale = ContentScale.Crop)
                                    Spacer(Modifier.height(6.dp))
                                    Text(text = cast.name, color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                    val charName = cast.roles?.firstOrNull()?.character ?: cast.character ?: ""
                                    if (charName.isNotEmpty()) { Text(text = charName, color = Color.Gray, fontSize = 9.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                }
                            }
                        }
                    }
                }
            }
            item { Text(text = "에피소드", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)) }
            items(episodes) { ep ->
                val isPlaying = playingMovie?.id == ep.id
                val epNum = ep.title.extractEpisode()?.filter { it.isDigit() }?.toIntOrNull()
                val epMetadata = seasonEpisodes.find { it.episodeNumber == epNum }
                Column(modifier = Modifier.fillMaxWidth().clickable { playingMovie = ep }.background(if (isPlaying) Color.White.copy(alpha = 0.1f) else Color.Transparent).padding(vertical = 12.dp)) {
                    ListItem(
                        headlineContent = { Text(text = ep.title.extractEpisode() ?: ep.title.prettyTitle(), color = if (isPlaying) Color.Red else Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) }, 
                        supportingContent = { if (!epMetadata?.name.isNullOrBlank()) { Text(text = epMetadata!!.name!!, color = Color.LightGray, fontSize = 13.sp) } },
                        leadingContent = { 
                            Box(modifier = Modifier.width(130.dp).height(74.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF1A1A1A))) { 
                                val stillUrl = epMetadata?.stillPath?.let { "$TMDB_IMAGE_BASE$it" }
                                if (stillUrl != null) { AsyncImage(model = ImageRequest.Builder(LocalPlatformContext.current).data(stillUrl).crossfade(true).build(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } 
                                else { TmdbAsyncImage(title = ep.title, modifier = Modifier.fillMaxSize(), typeHint = "tv") }
                                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))
                                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(32.dp)) 
                            } 
                        }, colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    if (!epMetadata?.overview.isNullOrBlank()) { Text(text = epMetadata!!.overview!!, color = Color.Gray, fontSize = 13.sp, style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), maxLines = 3, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

@Composable
fun VideoPlayerScreen(movie: Movie, playlist: List<Movie>, initialPosition: Long = 0L, currentScreen: Screen, pathStack: List<String>, onBack: () -> Unit, onMovieChange: (Movie) -> Unit, tabName: String = "") {
    val scope = rememberCoroutineScope()
    var showControls by remember(movie) { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { scope.launch { try { client.get("$BASE_URL/stop_all") } catch (e: Exception) {} } } }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        VideoPlayer(url = createVideoServeUrl(currentScreen, pathStack, movie, tabName = tabName), modifier = Modifier.fillMaxSize(), initialPosition = initialPosition, onControllerVisibilityChanged = { showControls = it }, onVideoEnded = {
            val currentIndex = playlist.indexOfFirst { it.id == movie.id }
            if (currentIndex != -1 && currentIndex < playlist.size - 1) { onMovieChange(playlist[currentIndex + 1]) }
        })
        AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut()) {
            Row(modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) { Text(text = movie.title.prettyTitle(), color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                IconButton(onClick = onBack) { Icon(imageVector = Icons.Default.Close, contentDescription = null, tint = Color.White) }
            }
        }
        val currentIndex = playlist.indexOfFirst { it.id == movie.id }
        if (currentIndex != -1 && currentIndex < playlist.size - 1) {
            AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 100.dp, end = 32.dp)) {
                Surface(onClick = { onMovieChange(playlist[currentIndex + 1]) }, shape = RoundedCornerShape(8.dp), color = Color.Black.copy(alpha = 0.6f), border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f))) {
                    Row(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp)); Text("다음 에피소드", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun HeroSection(movie: Movie?, onPlayClick: (Movie) -> Unit) {
    if (movie == null) return
    Box(modifier = Modifier.fillMaxWidth().height(550.dp).clickable { onPlayClick(movie) }.background(Color.Black)) {
        TmdbAsyncImage(title = movie.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, typeHint = "movie")
        Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(colors = listOf(Color.Black.copy(alpha = 0.4f), Color.Transparent, Color.Black.copy(alpha = 0.6f), Color.Black))))
        Card(modifier = Modifier.width(280.dp).height(400.dp).align(Alignment.TopCenter).padding(top = 40.dp), shape = RoundedCornerShape(8.dp), elevation = CardDefaults.cardElevation(defaultElevation = 16.dp), border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.2f))) { TmdbAsyncImage(title = movie.title, modifier = Modifier.fillMaxSize(), typeHint = "movie") }
        Column(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = movie.title.cleanTitle(), color = Color.White, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, shadow = Shadow(color = Color.Black, blurRadius = 8f)), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(20.dp)); Button(onClick = { onPlayClick(movie) }, colors = ButtonDefaults.buttonColors(containerColor = Color.White), shape = RoundedCornerShape(4.dp), modifier = Modifier.width(120.dp).height(45.dp)) { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black); Spacer(Modifier.width(8.dp)); Text("재생", color = Color.Black, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
fun NetflixTopBar(currentScreen: Screen, onScreenSelected: (Screen) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text = "N", color = Color.Red, fontSize = 32.sp, fontWeight = FontWeight.Black, modifier = Modifier.clickable { onScreenSelected(Screen.HOME) })
        Spacer(modifier = Modifier.width(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf("방송중" to Screen.ON_AIR, "애니" to Screen.ANIMATIONS, "영화" to Screen.MOVIES, "외국 TV" to Screen.FOREIGN_TV, "국내 TV" to Screen.KOREAN_TV).forEach { (label, screen) ->
                Text(text = label, color = if (currentScreen == screen) Color.White else Color.Gray, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.clickable { onScreenSelected(screen) } )
            }
        }
    }
}

fun Char.isHangul(): Boolean = this in '\uAC00'..'\uD7A3' || this in '\u1100'..'\u11FF' || this in '\u3130'..'\u318F' || this in '\uA960'..'\uA97F' || this in '\uD7B0'..'\uD7FF'