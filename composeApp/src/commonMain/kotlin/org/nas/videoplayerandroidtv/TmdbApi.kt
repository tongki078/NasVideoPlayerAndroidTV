package org.nas.videoplayerandroidtv

import androidx.compose.runtime.mutableStateMapOf
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
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
    install(HttpTimeout) { requestTimeoutMillis = 15000; connectTimeoutMillis = 10000; socketTimeoutMillis = 15000 }
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
    @SerialName("popularity") val popularity: Double? = 0.0,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null
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
    @SerialName("genres") val genres: List<TmdbGenre>? = null,
    val title: String? = null,
    val name: String? = null
)

@Serializable
data class TmdbGenre(val id: Int, val name: String)

@Serializable
data class TmdbEpisode(
    @SerialName("episode_number") val episodeNumber: Int,
    var overview: String? = null,
    @SerialName("still_path") val stillPath: String? = null,
    val name: String? = null,
    val runtime: Int? = null 
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

internal val tmdbCache = mutableStateMapOf<String, TmdbMetadata>()
private val tmdbInFlightRequests = mutableMapOf<String, Deferred<TmdbMetadata?>>()

internal var persistentCache: TmdbCacheDataSource? = null

private val REGEX_EXT = Regex("""\.[a-zA-Z0-9]{2,4}$""")
private val REGEX_HANGUL_LETTER = Regex("""([가-힣])([a-zA-Z])""")
private val REGEX_LETTER_HANGUL = Regex("""([a-zA-Z])([가-힣])""")

private val REGEX_YEAR = Regex("""\((19|20)\d{2}\)|(?<!\d)(19|20)\d{2}(?!\d)""")
private val REGEX_BRACKETS = Regex("""\[.*?\]|\(.*?\)""")
private val REGEX_TMDB_HINT = Regex("""\{tmdb\s*(\d+)\}""")

private val REGEX_JUNK_KEYWORDS = Regex("""(?i)\s*(?:더빙|자막|극장판|더빙|자막|극장판|BD|TV|Web|OAD|OVA|ONA|Full|무삭제|감독판|확장판|최종화|TV판|완결|속편|상|하|1부|2부|파트)\s*""")
private val REGEX_PYEON_SUFFIX = Regex("""(?:편|편)(?=[.\s_]|$)""")

private val REGEX_TECHNICAL_TAGS = Regex("""(?i)[.\s_](?:\d{3,4}p|WEB-DL|WEBRip|Bluray|HDRip|BDRip|DVDRip|H\.?26[45]|x26[45]|HEVC|AAC|DTS|AC3|DDP|Dual|Atmos|REPACK|10bit|REMUX|FLAC|xvid|DivX|MKV|MP4|AVI).*""")
private val REGEX_SPECIAL_CHARS = Regex("""[._\-!?【】『』「」"'#@*※]""")
private val REGEX_SPACES = Regex("""\s+""")

private val REGEX_HANGUL_NUMBER = Regex("""([가-힣])(\d+)(?=[.\s_]|$)""")

fun String.cleanTitle(keepAfterHyphen: Boolean = false, includeYear: Boolean = true, preserveSubtitle: Boolean = false): String {
    val original = this
    var cleaned = this
    
    // 1. {tmdb ...} 힌트 및 연도 정보를 가장 먼저 제거하여 핵심 제목만 남김
    cleaned = REGEX_TMDB_HINT.replace(cleaned, "")
    
    cleaned = REGEX_EXT.replace(cleaned, "")
    
    val yearMatch = REGEX_YEAR.find(cleaned)
    val yearStr = yearMatch?.value?.replace("(", "")?.replace(")", "")
    cleaned = REGEX_YEAR.replace(cleaned, " ")
    
    cleaned = REGEX_HANGUL_LETTER.replace(cleaned, "$1 $2")
    cleaned = REGEX_LETTER_HANGUL.replace(cleaned, "$1 $2")
    cleaned = REGEX_HANGUL_NUMBER.replace(cleaned, "$1 $2")
    cleaned = REGEX_JUNK_KEYWORDS.replace(cleaned, " ")
    cleaned = REGEX_BRACKETS.replace(cleaned, " ")
    cleaned = REGEX_TECHNICAL_TAGS.replace(cleaned, "")
    
    if (!keepAfterHyphen && cleaned.contains("-")) {
        val parts = cleaned.split("-")
        val afterHyphen = parts.getOrNull(1)?.trim() ?: ""
        if (!(afterHyphen.length <= 2 && afterHyphen.all { it.isDigit() })) {
            cleaned = parts[0]
        }
    }
    
    cleaned = cleaned.replace(":", " ")
    cleaned = REGEX_SPECIAL_CHARS.replace(cleaned, " ")
    cleaned = REGEX_SPACES.replace(cleaned, " ").trim()
    
    if (cleaned.length < 2) {
        val backup = original.replace(REGEX_TMDB_HINT, "").replace(REGEX_EXT, "").trim()
        return if (backup.length >= 2) backup else original
    }
    
    return if (includeYear && yearStr != null) "$cleaned ($yearStr)" else cleaned
}

fun String.extractYear(): String? = REGEX_YEAR.find(this)?.value?.replace("(", "")?.replace(")", "")

fun String.extractTmdbId(): Int? = REGEX_TMDB_HINT.find(this)?.groupValues?.get(1)?.toIntOrNull()

fun String.extractEpisode(): String? {
    Regex("""(?i)[Ee](\d+)""").find(this)?.let { return "${it.groupValues[1].toInt()}화" }
    Regex("""(\d+)\s*(?:화|회|화|회)""").find(this)?.let { return "${it.groupValues[1].toInt()}화" }
    return null
}

fun String.extractSeason(): Int {
    Regex("""(?i)[Ss](\d+)""").find(this)?.let { return it.groupValues[1].toInt() }
    Regex("""(\d+)\s*(?:기|기)""").find(this)?.let { return it.groupValues[1].toInt() }
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
    // 캐시 키 생성 시 title을 미리 정규화(toNfc)하여 비교 일치율을 높임
    val normalizedTitle = title.toNfc()
    val cacheKey = if (isAnimation) "ani_$normalizedTitle" else normalizedTitle
    
    tmdbCache[cacheKey]?.let { return it }
    persistentCache?.getCache(cacheKey)?.let {
        tmdbCache[cacheKey] = it
        return it
    }

    val deferred = synchronized(tmdbInFlightRequests) {
        tmdbInFlightRequests.getOrPut(cacheKey) {
            @OptIn(DelicateCoroutinesApi::class)
            GlobalScope.async(Dispatchers.Default) {
                val result = try {
                    val hintId = normalizedTitle.extractTmdbId()
                    if (hintId != null) {
                        fetchByIdDirectly(hintId)
                    } else {
                        withTimeout(25000) { 
                            performMultiStepSearch(normalizedTitle, typeHint, isAnimation)
                        }
                    }
                } catch (e: Exception) { null }
                
                if (result != null && result.posterUrl != null) {
                    withContext(Dispatchers.Main) { tmdbCache[cacheKey] = result }
                    persistentCache?.saveCache(cacheKey, result)
                }
                synchronized(tmdbInFlightRequests) { tmdbInFlightRequests.remove(cacheKey) }
                result
            }
        }
    }
    
    return deferred.await() ?: TmdbMetadata()
}

private suspend fun fetchByIdDirectly(tmdbId: Int): TmdbMetadata? {
    // 1. 영화 정보 조회 시도
    val movieRes = try {
        tmdbClient.get("$TMDB_BASE_URL/movie/$tmdbId") {
            if (TMDB_API_KEY.startsWith("eyJ")) header("Authorization", "Bearer $TMDB_API_KEY")
            else parameter("api_key", TMDB_API_KEY)
            parameter("language", "ko-KR")
        }.body<TmdbDetailsResponse>()
    } catch (e: Exception) { null }

    if (movieRes != null && movieRes.posterPath != null) {
        return TmdbMetadata(
            tmdbId = movieRes.id,
            mediaType = "movie",
            posterUrl = "$TMDB_IMAGE_BASE$TMDB_POSTER_SIZE_MEDIUM${movieRes.posterPath}",
            backdropUrl = movieRes.backdropPath?.let { "$TMDB_IMAGE_BASE$TMDB_BACKDROP_SIZE$it" },
            overview = movieRes.overview,
            genreIds = movieRes.genres?.map { it.id } ?: emptyList(),
            title = movieRes.title ?: movieRes.name
        )
    }

    // 2. 영화가 없으면 TV 정보 조회 시도
    val tvRes = try {
        tmdbClient.get("$TMDB_BASE_URL/tv/$tmdbId") {
            if (TMDB_API_KEY.startsWith("eyJ")) header("Authorization", "Bearer $TMDB_API_KEY")
            else parameter("api_key", TMDB_API_KEY)
            parameter("language", "ko-KR")
        }.body<TmdbDetailsResponse>()
    } catch (e: Exception) { null }

    if (tvRes != null && tvRes.posterPath != null) {
        return TmdbMetadata(
            tmdbId = tvRes.id,
            mediaType = "tv",
            posterUrl = "$TMDB_IMAGE_BASE$TMDB_POSTER_SIZE_MEDIUM${tvRes.posterPath}",
            backdropUrl = tvRes.backdropPath?.let { "$TMDB_IMAGE_BASE$TMDB_BACKDROP_SIZE$it" },
            overview = tvRes.overview,
            genreIds = tvRes.genres?.map { it.id } ?: emptyList(),
            title = tvRes.title ?: tvRes.name
        )
    }
    
    return null
}

private suspend fun performMultiStepSearch(originalTitle: String, typeHint: String?, isAnimation: Boolean): TmdbMetadata? {
    val year = originalTitle.extractYear()
    val fullCleanQuery = originalTitle.cleanTitle(includeYear = false)
    
    if (year != null) {
        searchTmdbCore(fullCleanQuery, "ko-KR", typeHint ?: "multi", year, isAnimation).first?.let { return it }
    }
    searchTmdbCore(fullCleanQuery, "ko-KR", typeHint ?: "multi", null, isAnimation).first?.let { return it }

    val noNumberQuery = fullCleanQuery.replace(Regex("""\s+\d+$"""), "").trim()
    if (noNumberQuery != fullCleanQuery && noNumberQuery.length >= 2) {
        searchTmdbCore(noNumberQuery, "ko-KR", typeHint ?: "multi", year, isAnimation).first?.let { return it }
        searchTmdbCore(noNumberQuery, "ko-KR", typeHint ?: "multi", null, isAnimation).first?.let { return it }
    }

    return null
}

private suspend fun searchTmdbCore(query: String, language: String?, endpoint: String, year: String? = null, isAnimation: Boolean = false): Pair<TmdbMetadata?, Exception?> {
    if (query.isBlank() || query.length < 1) return null to null
    return try {
        val response: TmdbSearchResponse = tmdbClient.get("$TMDB_BASE_URL/search/$endpoint") {
            if (TMDB_API_KEY.startsWith("eyJ")) header("Authorization", "Bearer $TMDB_API_KEY")
            else parameter("api_key", TMDB_API_KEY)
            parameter("query", query)
            if (language != null) parameter("language", language)
            
            if (year != null && endpoint != "multi") {
                parameter(if (endpoint == "movie") "year" else "first_air_date_year", year)
            }
            parameter("include_adult", "false")
        }.body()
        
        val results = response.results.filter { it.mediaType != "person" }
        if (results.isEmpty()) return null to null
        
        val bestMatch = results.sortedWith(
            compareByDescending<TmdbResult> { 
                if (year != null) {
                    val resYear = (it.releaseDate ?: it.firstAirDate ?: "").take(4)
                    resYear == year
                } else false
            }.thenByDescending { 
                val hasAniGenre = it.genreIds?.contains(16) == true
                if (isAnimation) hasAniGenre else !hasAniGenre
            }.thenByDescending { 
                val matchTitle = (it.name ?: it.title ?: "").replace(" ", "").replace(":", "").replace("-", "")
                val searchTitle = query.replace(" ", "").replace(":", "").replace("-", "")
                matchTitle.equals(searchTitle, ignoreCase = true) || searchTitle.contains(matchTitle) || matchTitle.contains(searchTitle)
            }.thenByDescending { it.posterPath != null }
            .thenByDescending { it.popularity ?: 0.0 }
        ).firstOrNull()
        
        if (bestMatch != null && bestMatch.posterPath != null) {
            val posterUrl = "$TMDB_IMAGE_BASE$TMDB_POSTER_SIZE_MEDIUM${bestMatch.posterPath}"
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
