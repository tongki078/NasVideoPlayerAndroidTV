package org.nas.videoplayerandroidtv

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.nas.videoplayerandroidtv.data.TmdbCacheDataSource

// TMDB 관련 상수
const val TMDB_API_KEY = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiI3OGNiYWQ0ZjQ3NzcwYjYyYmZkMTcwNTA2NDIwZDQyYyIsIm5iZiI6MTY1MzY3NTU4MC45MTUsInN1YiI6IjYyOTExNjNjMTI0MjVjMDA1MjI0ZGQzNCIsInNjb3BlcyI6WyJhcGlfcmVhZCJdLCJ2ZXJzaW9uIjoxfQ.3YU0WuIx_WDo6nTRKehRtn4N5I4uCgjI1tlpkqfsUhk"
const val TMDB_BASE_URL = "https://api.themoviedb.org/3"
const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/"
const val TMDB_POSTER_SIZE_SMALL = "w185"
const val TMDB_POSTER_SIZE_MEDIUM = "w342"
const val TMDB_POSTER_SIZE_LARGE = "w500"
const val TMDB_BACKDROP_SIZE = "w780"

private val tmdbClient = HttpClient {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
    install(HttpTimeout) { requestTimeoutMillis = 10000; connectTimeoutMillis = 5000; socketTimeoutMillis = 10000 }
    defaultRequest { 
        header("User-Agent", "Ktor-Client")
        header("Accept", "application/json")
    }
}

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
    @SerialName("genre_ids") val genreIds: List<Int>? = null,
    @SerialName("vote_count") val voteCount: Int? = 0,
    @SerialName("popularity") val popularity: Double? = 0.0
)

data class TmdbMetadata(
    val tmdbId: Int? = null,
    val mediaType: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val overview: String? = null,
    val genreIds: List<Int> = emptyList(),
    val title: String? = null
)

@Serializable
data class TmdbDetailsResponse(
    val id: Int,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val overview: String? = null,
    @SerialName("genres") val genres: List<TmdbGenre>? = null
)

@Serializable
data class TmdbGenre(val id: Int, val name: String)

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

// 전역 캐시 및 중복 요청 방지용 맵
internal val tmdbCache = mutableMapOf<String, TmdbMetadata>()
private val tmdbInFlightRequests = mutableMapOf<String, Deferred<TmdbMetadata>>()

// [추가] DB 캐시 접근을 위한 전역 변수 (App.kt에서 초기화됨)
internal var persistentCache: TmdbCacheDataSource? = null

// 미리 컴파일된 정규식 상수로 성능 최적화
private val REGEX_EXT = Regex("""\.[a-zA-Z0-9]{2,4}$""")
private val REGEX_HANGUL_ALPHA = Regex("""([가-힣])([a-zA-Z0-9])""")
private val REGEX_ALPHA_HANGUL = Regex("""([a-zA-Z0-9])([가-힣])""")
private val REGEX_START_NUM = Regex("""^\d+[.\s_-]+""")
private val REGEX_START_PREFIX = Regex("""^[a-zA-Z]\d+[.\s_-]+""")
private val REGEX_BRACKET_NUM = Regex("""^\[\d+\]\s*""")
private val REGEX_YEAR = Regex("""\((19|20)\d{2}\)|(?<!\d)(19|20)\d{2}(?!\d)""")
private val REGEX_BRACKETS = Regex("""\[.*?\]|\(.*?\)""")
private val REGEX_TAGS = Regex("""(?i)[.\s_](?:더빙|자막|무삭제|\d{3,4}p|WEB-DL|WEBRip|Bluray|HDRip|BDRip|DVDRip|H\.?26[45]|x26[45]|HEVC|AAC|DTS|AC3|DDP|Dual|Atmos|REPACK|10bit|REMUX|OVA|OAD|ONA|TV판|극장판|FLAC|xvid|DivX|MKV|MP4|AVI|속편|1부|2부|파트|완결|상|하).*""")
private val REGEX_SPECIAL_CHARS = Regex("""[._\-::!?【】『』「」"'#@*※]""")
private val REGEX_SPACES = Regex("""\s+""")
private val REGEX_EP_SUFFIX = Regex("""(?i)[.\s_](?:S\d+E\d+|S\d+|E\d+|\d+\s*(?:화|회|기)|Season\s*\d+|Part\s*\d+).*""")
private val REGEX_SUBTITLE_SUFFIX = Regex("""(?i)[.\s_](?:S\d+E\d+|E\d+|\d+\s*(?:화|회)).*""")

fun String.cleanTitle(keepAfterHyphen: Boolean = false, includeYear: Boolean = true, preserveSubtitle: Boolean = false): String {
    var cleaned = REGEX_EXT.replace(this, "")
    cleaned = REGEX_HANGUL_ALPHA.replace(cleaned, "$1 $2")
    cleaned = REGEX_ALPHA_HANGUL.replace(cleaned, "$1 $2")
    cleaned = REGEX_START_NUM.replace(cleaned, "")
    cleaned = REGEX_START_PREFIX.replace(cleaned, "")
    cleaned = REGEX_BRACKET_NUM.replace(cleaned, "")
    
    val yearMatch = REGEX_YEAR.find(cleaned)
    val yearStr = yearMatch?.value?.replace("(", "")?.replace(")", "")
    if (yearMatch != null) cleaned = cleaned.replace(yearMatch.value, " ")
    
    cleaned = REGEX_BRACKETS.replace(cleaned, " ")
    if (!keepAfterHyphen) {
        cleaned = if (preserveSubtitle) REGEX_SUBTITLE_SUFFIX.replace(cleaned, "")
        else REGEX_EP_SUFFIX.replace(cleaned, "")
    }
    cleaned = REGEX_TAGS.replace(cleaned, " ")
    cleaned = REGEX_SPECIAL_CHARS.replace(cleaned, " ")
    cleaned = REGEX_SPACES.replace(cleaned, " ").trim()
    
    if (includeYear && yearStr != null) cleaned = "$cleaned ($yearStr)"
    return if (cleaned.isBlank()) this else cleaned
}

fun String.extractEpisode(): String? {
    Regex("""(?i)[Ee](\d+)""").find(this)?.let { return "${it.groupValues[1].toInt()}화" }
    Regex("""(\d+)\s*(?:화|회)""").find(this)?.let { return "${it.groupValues[1].toInt()}화" }
    Regex("""[.\s_](\d+)(?:$|[.\s_])""").find(this)?.let { if (it.groupValues[1].toInt() < 1000) return "${it.groupValues[1].toInt()}화" }
    return null
}

fun String.extractSeason(): Int {
    Regex("""(?i)[Ss](\d+)""").find(this)?.let { return it.groupValues[1].toInt() }
    Regex("""(\d+)\s*기""").find(this)?.let { return it.groupValues[1].toInt() }
    return 1
}

fun String.prettyTitle(): String {
    val ep = this.extractEpisode()
    val base = this.cleanTitle(keepAfterHyphen = true, includeYear = false)
    if (ep == null) return base
    return if (base.contains(" - ")) { 
        val split = base.split(" - ", limit = 2)
        "${split[0]} $ep - ${split[1]}" 
    } else "$base $ep"
}

suspend fun fetchTmdbMetadata(title: String, typeHint: String? = null, isAnimation: Boolean = false): TmdbMetadata {
    if (TMDB_API_KEY.isBlank()) return TmdbMetadata()
    val cacheKey = if (isAnimation) "ani_$title" else title
    
    // 1단계: 메모리 캐시 확인 (0.0001초)
    tmdbCache[cacheKey]?.let { return it }

    // 2단계: 로컬 DB 영구 캐시 확인 (0.01초)
    persistentCache?.getCache(cacheKey)?.let {
        synchronized(tmdbCache) { tmdbCache[cacheKey] = it }
        return it
    }

    // 3단계: 중복 요청 방지하며 네트워크 검색 (1~3초)
    val deferred = synchronized(tmdbInFlightRequests) {
        tmdbInFlightRequests.getOrPut(cacheKey) {
            GlobalScope.async(Dispatchers.Default) {
                val cleanTitle = title.cleanTitle(includeYear = false, preserveSubtitle = true)
                val metadata = performSearchSimple(cleanTitle, typeHint, isAnimation)
                val result = metadata ?: TmdbMetadata()
                
                // 결과 저장
                synchronized(tmdbCache) { tmdbCache[cacheKey] = result }
                persistentCache?.saveCache(cacheKey, result)

                synchronized(tmdbInFlightRequests) { tmdbInFlightRequests.remove(cacheKey) }
                result
            }
        }
    }
    
    return deferred.await()
}

private suspend fun performSearchSimple(query: String, typeHint: String?, isAnimation: Boolean): TmdbMetadata? {
    if (query.isBlank()) return null
    val (metadata, _) = searchTmdbCore(query, "ko-KR", typeHint ?: "multi", null, isAnimation)
    if (metadata != null && metadata.posterUrl != null) return metadata
    if (metadata == null || metadata.posterUrl == null) {
        val (globalMetadata, _) = searchTmdbCore(query, null, typeHint ?: "multi", null, isAnimation)
        if (globalMetadata != null) return globalMetadata
    }
    return metadata
}

private suspend fun searchTmdbCore(query: String, language: String?, endpoint: String, year: String? = null, isAnimation: Boolean = false): Pair<TmdbMetadata?, Exception?> {
    if (query.isBlank()) return null to null
    return try {
        val response: TmdbSearchResponse = tmdbClient.get("$TMDB_BASE_URL/search/$endpoint") {
            if (TMDB_API_KEY.startsWith("eyJ")) header("Authorization", "Bearer $TMDB_API_KEY")
            else parameter("api_key", TMDB_API_KEY)
            parameter("query", query)
            if (language != null) parameter("language", language)
            parameter("include_adult", "false")
        }.body()
        val results = response.results.filter { it.mediaType != "person" }
        if (results.isEmpty()) return null to null
        val bestMatch = results.sortedWith(
            compareByDescending<TmdbResult> { if (isAnimation) it.genreIds?.contains(16) == true else false }
                .thenByDescending { (it.name ?: it.title ?: "").equals(query, ignoreCase = true) }
                .thenByDescending { it.posterPath != null }
                .thenByDescending { it.popularity ?: 0.0 }
        ).firstOrNull()
        if (bestMatch != null) {
            val posterUrl = bestMatch.posterPath?.let { "$TMDB_IMAGE_BASE$TMDB_POSTER_SIZE_MEDIUM$it" }
            val backdropUrl = bestMatch.backdropPath?.let { "$TMDB_IMAGE_BASE$TMDB_BACKDROP_SIZE$it" }
            val mediaType = bestMatch.mediaType ?: (if (bestMatch.title != null) "movie" else "tv")
            Pair(TmdbMetadata(tmdbId = bestMatch.id, mediaType = mediaType, posterUrl = posterUrl, backdropUrl = backdropUrl, overview = bestMatch.overview, genreIds = bestMatch.genreIds ?: emptyList(), title = bestMatch.name ?: bestMatch.title), null)
        } else Pair(null, null)
    } catch (e: Exception) { Pair(null, e) }
}

suspend fun fetchTmdbDetails(tmdbId: Int, mediaType: String): TmdbDetailsResponse? {
    return try {
        tmdbClient.get("$TMDB_BASE_URL/$mediaType/$tmdbId") {
            if (TMDB_API_KEY.startsWith("eyJ")) header("Authorization", "Bearer $TMDB_API_KEY")
            else parameter("api_key", TMDB_API_KEY)
            parameter("language", "ko-KR")
        }.body()
    } catch (e: Exception) { null }
}

suspend fun fetchTmdbSeasonDetails(tmdbId: Int, season: Int): List<TmdbEpisode> {
    return try {
        val response: TmdbSeasonResponse = tmdbClient.get("$TMDB_BASE_URL/tv/$tmdbId/season/$season") {
            if (TMDB_API_KEY.startsWith("eyJ")) header("Authorization", "Bearer $TMDB_API_KEY")
            else parameter("api_key", TMDB_API_KEY)
            parameter("language", "ko-KR")
        }.body()
        response.episodes
    } catch (e: Exception) { emptyList() }
}

suspend fun fetchTmdbEpisodeDetails(tmdbId: Int, season: Int, episodeNum: Int): TmdbEpisode? {
    return try {
        tmdbClient.get("$TMDB_BASE_URL/tv/$tmdbId/season/$season/episode/$episodeNum") {
            if (TMDB_API_KEY.startsWith("eyJ")) header("Authorization", "Bearer $TMDB_API_KEY")
            else parameter("api_key", TMDB_API_KEY)
            parameter("language", "ko-KR")
        }.body()
    } catch (e: Exception) { null }
}

suspend fun fetchTmdbCredits(tmdbId: Int, mediaType: String?): List<TmdbCast> {
    if (tmdbId == 0 || mediaType == null) return emptyList()
    return try {
        val endpoint = if (mediaType == "tv") "$TMDB_BASE_URL/tv/$tmdbId/aggregate_credits" else "$TMDB_BASE_URL/movie/$tmdbId/credits"
        val response: TmdbCreditsResponse = tmdbClient.get(endpoint) {
            if (TMDB_API_KEY.startsWith("eyJ")) header("Authorization", "Bearer $TMDB_API_KEY")
            else parameter("api_key", TMDB_API_KEY)
            parameter("language", "ko-KR")
        }.body()
        response.cast.take(30)
    } catch (e: Exception) { emptyList() }
}

fun getRandomThemeName(originalName: String, index: Int, isMovie: Boolean = false, category: String? = null): String {
    val alternates = if (isMovie) listOf("시선을 사로잡는 영화", "주말 밤 책임질 명작", "시간 순삭 몰입도 최강") else listOf("한 번 시작하면 멈출 수 없는", "웰메이드 명품 시리즈", "놓치면 후회할 인생 드라마")
    return alternates[index % alternates.size]
}
