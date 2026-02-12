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
    val title: String? = null,
    val year: String? = null
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
// 닫히지 않은 괄호도 끝까지 제거하도록 보강
private val REGEX_BRACKETS = Regex("""\[.*?(?:\]|$)|\(.*?(?:\)|$)|\{.*?(?:\}|$)|\【.*?(?:\】|$)|\『.*?(?:\』|$)|\「.*?(?:\」|$)""")

// {tmdb 12345} 또는 {tmdb-12345} 형태 모두 대응하도록 정규식 강화
private val REGEX_TMDB_HINT = Regex("""\{tmdb[\s-]*(\d+)\}""")

private val REGEX_JUNK_KEYWORDS = Regex("""(?i)\s*(?:더빙|자막|극장판|더빙|자막|극장판|BD|TV|Web|OAD|OVA|ONA|Full|무삭제|감독판|확장판|최종화|TV판|완결|속편|상|하|1부|2부|파트)\s*""")
private val REGEX_PYEON_SUFFIX = Regex("""(?:편|편)(?=[.\s_]|$)""")

private val REGEX_TECHNICAL_TAGS = Regex("""(?i)[.\s_](?:\d{3,4}p|WEB-DL|WEBRip|Bluray|HDRip|BDRip|DVDRip|H\.?26[45]|x26[45]|HEVC|AAC|DTS|AC3|DDP|Dual|Atmos|REPACK|10bit|REMUX|FLAC|xvid|DivX|MKV|MP4|AVI).*""")
// 대괄호와 소괄호 자체도 제거 대상에 포함
private val REGEX_SPECIAL_CHARS = ("""[\[\]()._\-!?【】『』「」"'#@*※×]""").toRegex()
private val REGEX_SPACES = Regex("""\s+""")

private val REGEX_HANGUL_NUMBER = Regex("""([가-힣])(\d+)(?=[.\s_]|$)""")

// 회차 정보 및 날짜 정보(예: 251005)를 포함한 노이즈 제거용 정규식 강화
private val REGEX_EP_MARKER = Regex("""(?i)(?:^|[.\s_]|(?<=[가-힣]))(?:S\d+E\d+|S\d+|E\d+|\d+\s*(?:화|회|기)|Season\s*\d+|Part\s*\d+).*""")

private val REGEX_INDEX_FOLDER = Regex("""(?i)^\s*([0-9A-Z가-힣ㄱ-ㅎ]|0Z|0-Z|가-하|[0-9]-[0-9]|[A-Z]-[A-Z]|[가-힣]-[가-힣])\s*$""")
private val REGEX_YEAR_FOLDER = Regex("""(?i)^\s*(?:\(\d{4}\)|\d{4}|\d{4}\s*년)\s*$""") 
private val REGEX_SEASON_FOLDER = Regex("""(?i)^\s*(?:Season\s*\d+|시즌\s*\d+(?:\s*년)?|Part\s*\d+|파트\s*\d+|S\d+|\d+기|\d+화|\d+회|특집|Special|Extras|Bonus|미분류|기타|새\s*폴더)\s*$""")

fun isGenericTitle(name: String?): Boolean {
    if (name.isNullOrBlank()) return true
    val n = name.trim().toNfc()
    return REGEX_INDEX_FOLDER.matches(n) || 
           REGEX_YEAR_FOLDER.matches(n) || 
           REGEX_SEASON_FOLDER.matches(n) ||
           n.contains("Search Results", ignoreCase = true) ||
           (n.startsWith("시즌") && n.any { it.isDigit() } && n.contains("년"))
}

fun String.cleanTitle(keepAfterHyphen: Boolean = false, includeYear: Boolean = true, preserveSubtitle: Boolean = false): String {
    val normalized = this.toNfc()
    var cleaned = normalized
    cleaned = cleaned.replace("×", "x").replace("✕", "x")
    cleaned = REGEX_TMDB_HINT.replace(cleaned, "")
    cleaned = REGEX_EXT.replace(cleaned, "")
    
    // 에피소드 및 날짜 노이즈 제거
    cleaned = REGEX_EP_MARKER.replace(cleaned, "") // 서버 규칙과 동일하게 적용 (복합 제목 유지)
    
    // 연도 추출 및 제거
    val yearMatch = REGEX_YEAR.find(cleaned)
    val yearStr = yearMatch?.value?.replace("(", "")?.replace(")", "")
    cleaned = REGEX_YEAR.replace(cleaned, " ")
    
    // 괄호 내용 제거 (닫히지 않은 괄호 포함)
    cleaned = REGEX_BRACKETS.replace(cleaned, " ")
    
    // [수정] 한글/영문 사이 공백 삽입 로직 제거 (Z반 -> Z반 보존)
    // cleaned = REGEX_HANGUL_LETTER.replace(cleaned, "$1 $2")
    // cleaned = REGEX_LETTER_HANGUL.replace(cleaned, "$1 $2")

    cleaned = REGEX_HANGUL_NUMBER.replace(cleaned, "$1 $2")
    cleaned = cleaned.replace("(자막)", "").replace("(더빙)", "").replace("[자막]", "").replace("[더빙]", "")
    cleaned = REGEX_JUNK_KEYWORDS.replace(cleaned, " ")
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
        val backup = normalized.replace(REGEX_TMDB_HINT, "").replace(REGEX_EXT, "").trim()
        return if (backup.length >= 2) backup else normalized
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
    Regex("""(?i)Season\s*(\d+)""").find(this)?.let { return it.groupValues[1].toInt() }
    Regex("""(?i)Part\s*(\d+)""").find(this)?.let { return it.groupValues[1].toInt() }
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
    val movieRes = try {
        tmdbClient.get("$TMDB_BASE_URL/movie/$tmdbId") {
            if (TMDB_API_KEY.startsWith("eyJ")) header("Authorization", "Bearer $TMDB_API_KEY")
            else parameter("api_key", TMDB_API_KEY)
            parameter("language", "ko-KR")
        }.body<TmdbDetailsResponse>()
    } catch (e: Exception) { null }
    if (movieRes != null && movieRes.posterPath != null) {
        return TmdbMetadata(tmdbId = movieRes.id, mediaType = "movie", posterUrl = "$TMDB_IMAGE_BASE$TMDB_POSTER_SIZE_MEDIUM${movieRes.posterPath}", backdropUrl = movieRes.backdropPath?.let { "$TMDB_IMAGE_BASE$TMDB_BACKDROP_SIZE$it" }, overview = movieRes.overview, genreIds = movieRes.genres?.map { it.id } ?: emptyList(), title = movieRes.title ?: movieRes.name)
    }
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
    
    val tightQuery = fullCleanQuery.replace(" ", "")

    // 1. 공백 제거 쿼리 + 연도 조합
    if (tightQuery != fullCleanQuery && tightQuery.length >= 2) {
        searchTmdbCore(tightQuery, "ko-KR", typeHint ?: "multi", year, isAnimation).first?.let { return it }
    }

    // 2. 기본 검색 (연도 포함)
    searchTmdbCore(fullCleanQuery, "ko-KR", typeHint ?: "multi", year, isAnimation).first?.let { return it }

    // 3. 핵심 제목 (숫자 제거) 검색
    val noNumberQuery = fullCleanQuery.replace(Regex("""\s+\d+$"""), "").trim()
    if (noNumberQuery != fullCleanQuery && noNumberQuery.length >= 2) {
        searchTmdbCore(noNumberQuery, "ko-KR", typeHint ?: "multi", year, isAnimation).first?.let { return it }
    }

    // 4. 연도 없이 검색
    searchTmdbCore(fullCleanQuery, "ko-KR", typeHint ?: "multi", null, isAnimation).first?.let { return it }
    
    // 5. 공백 제거 쿼리만 (마지막 수단)
    if (tightQuery.length >= 2) {
        searchTmdbCore(tightQuery, "ko-KR", typeHint ?: "multi", null, isAnimation).first?.let { return it }
    }

    return null
}

private suspend fun searchTmdbCore(query: String, language: String?, endpoint: String, year: String? = null, isAnimation: Boolean = false): Pair<TmdbMetadata?, Exception?> {
    if (query.isBlank() || query.length < 1) return null to null
    val nfcQuery = query.toNfc()
    return try {
        val response: TmdbSearchResponse = tmdbClient.get("$TMDB_BASE_URL/search/$endpoint") {
            if (TMDB_API_KEY.startsWith("eyJ")) header("Authorization", "Bearer $TMDB_API_KEY")
            else parameter("api_key", TMDB_API_KEY)
            parameter("query", nfcQuery)
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
                if (isAnimation) hasAniGenre else true
            }.thenByDescending { 
                val matchTitle = (it.name ?: it.title ?: "").lowercase().toNfc().replace("×", "x").replace(" ", "").replace(":", "").replace("-", "")
                val searchTitle = nfcQuery.lowercase().replace("×", "x").replace(" ", "").replace(":", "").replace("-", "")
                matchTitle.equals(searchTitle, ignoreCase = true) || matchTitle.contains(searchTitle) || searchTitle.contains(matchTitle)
            }.thenByDescending { it.posterPath != null }
            .thenByDescending { it.popularity ?: 0.0 }
        ).firstOrNull()
        
        if (bestMatch != null && bestMatch.posterPath != null) {
            val posterUrl = "$TMDB_IMAGE_BASE$TMDB_POSTER_SIZE_MEDIUM${bestMatch.posterPath}"
            val backdropUrl = bestMatch.backdropPath?.let { "$TMDB_IMAGE_BASE$TMDB_BACKDROP_SIZE$it" }
            val mediaType = bestMatch.mediaType ?: (if (bestMatch.title != null) "movie" else "tv")
            val resultYear = (bestMatch.releaseDate ?: bestMatch.firstAirDate ?: "").take(4)
            Pair(TmdbMetadata(tmdbId = bestMatch.id, mediaType = mediaType, posterUrl = posterUrl, backdropUrl = backdropUrl, overview = bestMatch.overview, genreIds = bestMatch.genreIds ?: emptyList(), title = bestMatch.name ?: bestMatch.title, year = resultYear), null)
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
        val response: TmdbEpisode = tmdbClient.get("$TMDB_BASE_URL/tv/$tmdbId/season/$season/episode/$episodeNum") {
            if (TMDB_API_KEY.startsWith("eyJ")) header("Authorization", "Bearer $TMDB_API_KEY")
            else parameter("api_key", TMDB_API_KEY)
            parameter("language", "ko-KR")
        }.body()
        response
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
