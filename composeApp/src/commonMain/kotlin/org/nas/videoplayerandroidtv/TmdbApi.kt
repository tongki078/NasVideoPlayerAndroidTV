package org.nas.videoplayerandroidtv

import androidx.compose.runtime.mutableStateMapOf
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

internal val tmdbCache = mutableStateMapOf<String, TmdbMetadata>()
private val tmdbInFlightRequests = mutableMapOf<String, Deferred<TmdbMetadata?>>()

internal var persistentCache: TmdbCacheDataSource? = null

private val REGEX_EXT = Regex("""\.[a-zA-Z0-9]{2,4}$""")
private val REGEX_HANGUL_ALPHA = Regex("""([가-힣])([a-zA-Z0-9])""")
private val REGEX_ALPHA_HANGUL = Regex("""([a-zA-Z0-9])([가-힣])""")
private val REGEX_YEAR = Regex("""\((19|20)\d{2}\)|(?<!\d)(19|20)\d{2}(?!\d)""")
private val REGEX_BRACKETS = Regex("""\[.*?\]|\(.*?\)""")

// 불필요한 수식어 (NFC + NFD 대응)
private val REGEX_JUNK_KEYWORDS = Regex("""(?i)\s*(?:더빙|자막|극장판|더빙|자막|극장판|BD|TV|Web|OAD|OVA|ONA|Full|무삭제|감독판|확장판|최종화|TV판|완결|속편|상|하|1부|2부|파트)\s*""")

// '편' 접미사 (NFC + NFD 대응)
private val REGEX_PYEON_SUFFIX = Regex("""(?:편|편)(?=[.\s_]|$)""")

private val REGEX_TECHNICAL_TAGS = Regex("""(?i)[.\s_](?:\d{3,4}p|WEB-DL|WEBRip|Bluray|HDRip|BDRip|DVDRip|H\.?26[45]|x26[45]|HEVC|AAC|DTS|AC3|DDP|Dual|Atmos|REPACK|10bit|REMUX|FLAC|xvid|DivX|MKV|MP4|AVI).*""")
private val REGEX_SPECIAL_CHARS = Regex("""[._\-!?【】『』「」"'#@*※]""")
private val REGEX_SPACES = Regex("""\s+""")
private val REGEX_EP_MARKER = Regex("""(?i)[.\s_](?:S\d+E\d+|S\d+|E\d+|\d+\s*(?:화|회|기)|Season\s*\d+|Part\s*\d+)""")

fun String.cleanTitle(keepAfterHyphen: Boolean = false, includeYear: Boolean = true, preserveSubtitle: Boolean = false): String {
    val original = this
    var cleaned = REGEX_EXT.replace(this, "")
    cleaned = REGEX_HANGUL_ALPHA.replace(cleaned, "$1 $2")
    cleaned = REGEX_ALPHA_HANGUL.replace(cleaned, "$1 $2")
    
    val yearMatch = REGEX_YEAR.find(cleaned)
    val yearStr = yearMatch?.value?.replace("(", "")?.replace(")", "")
    cleaned = REGEX_YEAR.replace(cleaned, " ")
    
    // 수식어 제거
    cleaned = REGEX_JUNK_KEYWORDS.replace(cleaned, " ")
    cleaned = REGEX_EP_MARKER.replace(cleaned, " ")
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
        return original.replace(REGEX_EXT, "").trim()
    }
    
    return if (includeYear && yearStr != null) "$cleaned ($yearStr)" else cleaned
}

fun String.extractYear(): String? = REGEX_YEAR.find(this)?.value?.replace("(", "")?.replace(")", "")

fun String.extractEpisode(): String? {
    Regex("""(?i)[Ee](\d+)""").find(this)?.let { return "${it.groupValues[1].toInt()}화" }
    Regex("""(\d+)\s*(?:화|회)""").find(this)?.let { return "${it.groupValues[1].toInt()}화" }
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
                    withTimeout(25000) { 
                        performMultiStepSearch(title, typeHint, isAnimation)
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

private suspend fun performMultiStepSearch(originalTitle: String, typeHint: String?, isAnimation: Boolean): TmdbMetadata? {
    val year = originalTitle.extractYear()
    val fullCleanQuery = originalTitle.cleanTitle(includeYear = false)
    
    // 1. 기본 정제된 제목으로 검색
    searchTmdbCore(fullCleanQuery, "ko-KR", typeHint ?: "multi", year, isAnimation).first?.let { return it }
    searchTmdbCore(fullCleanQuery, "ko-KR", typeHint ?: "multi", null, isAnimation).first?.let { return it }

    // 2. '편' 접미사 제거 후 검색 (귀멸의 칼날 사례)
    if (fullCleanQuery.contains("편") || fullCleanQuery.contains("편")) {
        val noPyeonQuery = REGEX_PYEON_SUFFIX.replace(fullCleanQuery, "").trim()
        if (noPyeonQuery != fullCleanQuery) {
            searchTmdbCore(noPyeonQuery, "ko-KR", typeHint ?: "multi", year, isAnimation).first?.let { return it }
            searchTmdbCore(noPyeonQuery, "ko-KR", typeHint ?: "multi", null, isAnimation).first?.let { return it }
        }
    }

    // 3. 단어 단위 유연한 검색 (앞의 단어들 조합)
    val words = fullCleanQuery.split(" ").filter { it.length >= 2 }
    if (words.size >= 2) {
        // 앞의 두 단어 (예: "귀멸의 칼날")
        val shortQuery = "${words[0]} ${words[1]}"
        searchTmdbCore(shortQuery, "ko-KR", typeHint ?: "multi", year, isAnimation).first?.let { return it }
        
        if (words.size >= 3) {
            // 앞의 세 단어 (예: "귀멸의 칼날 나타구모산")
            val midQuery = "${words[0]} ${words[1]} ${words[2]}"
            searchTmdbCore(midQuery, "ko-KR", typeHint ?: "multi", year, isAnimation).first?.let { return it }
        }
    }

    // 4. 최후의 수단: 연도 없이 원본 텍스트 일부로 시도
    val rawQuery = originalTitle.replace(REGEX_EXT, "").take(25)
    searchTmdbCore(rawQuery, "ko-KR", if (isAnimation) "tv" else "multi", null, isAnimation).first?.let { return it }

    return null
}

private suspend fun searchTmdbCore(query: String, language: String?, endpoint: String, year: String? = null, isAnimation: Boolean = false): Pair<TmdbMetadata?, Exception?> {
    if (query.isBlank() || query.length < 2) return null to null
    return try {
        val response: TmdbSearchResponse = tmdbClient.get("$TMDB_BASE_URL/search/$endpoint") {
            if (TMDB_API_KEY.startsWith("eyJ")) header("Authorization", "Bearer $TMDB_API_KEY")
            else parameter("api_key", TMDB_API_KEY)
            parameter("query", query)
            if (language != null) parameter("language", language)
            if (year != null) parameter(if (endpoint == "movie") "year" else "first_air_date_year", year)
            parameter("include_adult", "false")
        }.body()
        
        val results = response.results.filter { it.mediaType != "person" }
        if (results.isEmpty()) return null to null
        
        val queryNumbers = Regex("""\d+""").findAll(query).map { it.value }.toList()
        
        val bestMatch = results.sortedWith(
            compareByDescending<TmdbResult> { 
                val hasAniGenre = it.genreIds?.contains(16) == true
                if (isAnimation) hasAniGenre else !hasAniGenre
            }.thenByDescending { 
                val resultTitle = it.name ?: it.title ?: ""
                queryNumbers.all { num -> resultTitle.contains(num) }
            }.thenByDescending { 
                val matchTitle = (it.name ?: it.title ?: "").replace(" ", "")
                val searchTitle = query.replace(" ", "")
                matchTitle.equals(searchTitle, ignoreCase = true) || searchTitle.contains(matchTitle)
            }.thenByDescending { it.posterPath != null }
            .thenByDescending { it.popularity ?: 0.0 }
        ).firstOrNull()
        
        if (bestMatch != null && bestMatch.posterPath != null) {
            if (isAnimation && bestMatch.genreIds?.contains(16) != true && results.any { it.genreIds?.contains(16) == true }) {
                return null to null
            }

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
        val response: TmdbSeasonResponse = tmdbClient.get("$TMDB_BASE_URL/tv/$tmdbId/season/$season") {
            if (TMDB_API_KEY.startsWith("eyJ")) header("Authorization", "Bearer $TMDB_API_KEY")
            else parameter("api_key", TMDB_API_KEY)
            parameter("language", "ko-KR")
        }.body()
        response.episodes.find { it.episodeNumber == episodeNum }
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
