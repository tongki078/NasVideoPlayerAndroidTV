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

// TMDB 관련 상수
const val TMDB_API_KEY = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiI3OGNiYWQ0ZjQ3NzcwYjYyYmZkMTcwNTA2NDIwZDQyYyIsIm5iZiI6MTY1MzY3NTU4MC45MTUsInN1YiI6IjYyOTExNjNjMTI0MjVjMDA1MjI0ZGQzNCIsInNjb3BlcyI6WyJhcGlfcmVhZCJdLCJ2ZXJzaW9uIjoxfQ.3YU0WuIx_WDo6nTRKehRtn4N5I4uCgjI1tlpkqfsUhk"
const val TMDB_BASE_URL = "https://api.themoviedb.org/3"
const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/"
const val TMDB_POSTER_SIZE_SMALL = "w185"
const val TMDB_POSTER_SIZE_MEDIUM = "w342"
const val TMDB_POSTER_SIZE_LARGE = "w500"
const val TMDB_BACKDROP_SIZE = "w780"

// Ktor 클라이언트 설정
private val tmdbClient = HttpClient {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
    install(HttpTimeout) { requestTimeoutMillis = 60000; connectTimeoutMillis = 15000; socketTimeoutMillis = 60000 }
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
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    val overview: String? = null,
    @SerialName("origin_country") val originCountry: List<String>? = null,
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

internal val tmdbCache = mutableMapOf<String, TmdbMetadata>()

/**
 * 제목 정제 로직 (강화됨)
 */
fun String.cleanTitle(keepAfterHyphen: Boolean = false, includeYear: Boolean = true, preserveSubtitle: Boolean = false): String {
    var cleaned = this
    
    // 확장자 제거
    if (cleaned.contains(".")) { 
        val ext = cleaned.substringAfterLast('.'); 
        if (ext.length in 2..4) cleaned = cleaned.substringBeforeLast('.') 
    }
    
    // 한글과 영문/숫자 사이 공백 삽입
    cleaned = Regex("""([가-힣])([a-zA-Z0-9])""").replace(cleaned, "$1 $2")
    cleaned = Regex("""([a-zA-Z0-9])([가-힣])""").replace(cleaned, "$1 $2")
    
    // 앞에 붙는 번호나 기호 제거
    cleaned = Regex("""^\d+[.\s_-]+""").replace(cleaned, "")
    cleaned = Regex("""^[a-zA-Z]\d+[.\s_-]+""").replace(cleaned, "")
    cleaned = Regex("""^\[\d+\]\s*""").replace(cleaned, "")
    
    // 년도 추출 및 제거
    val yearMatch = Regex("""\((19|20)\d{2}\)|(?<!\d)(19|20)\d{2}(?!\d)""").find(cleaned)
    val yearStr = yearMatch?.value?.replace("(", "")?.replace(")", "")
    if (yearMatch != null) cleaned = cleaned.replace(yearMatch.value, " ")
    
    // 괄호 내용 제거
    cleaned = Regex("""\[.*?\]|\(.*?\)""").replace(cleaned, " ")
    
    // 시즌/에피소드 정보 제거
    if (!keepAfterHyphen) {
        val epRegex = if (preserveSubtitle) {
            """(?i)[.\s_](?:S\d+E\d+|E\d+|\d+\s*(?:화|회)).*"""
        } else {
            """(?i)[.\s_](?:S\d+E\d+|S\d+|E\d+|\d+\s*(?:화|회|기)|Season\s*\d+|Part\s*\d+).*"""
        }
        cleaned = Regex(epRegex).replace(cleaned, "")
    }
    
    // 릴리즈 그룹, 화질 등 태그 제거
    val tagRegex = """(?i)[.\s_](?:더빙|자막|무삭제|\d{3,4}p|WEB-DL|WEBRip|Bluray|HDRip|BDRip|DVDRip|H\.?26[45]|x26[45]|HEVC|AAC|DTS|AC3|DDP|Dual|Atmos|REPACK|10bit|REMUX|OVA|OAD|ONA|TV판|극장판|FLAC|xvid|DivX|MKV|MP4|AVI|속편|1부|2부|파트|완결|상|하).*"""
    cleaned = Regex(tagRegex).replace(cleaned, " ")
    
    // 특수문자 제거
    cleaned = Regex("""[._\-::!?【】『』「」"'#@*※]""").replace(cleaned, " ")
    
    // 마지막에 남은 숫자 (년도가 아닌 경우만) 처리
    val match = Regex("""\s+(\d+)$""").find(cleaned)
    if (match != null) {
        val num = match.groupValues[1].toInt()
        if (num > 1900 && num < 2100) { cleaned = cleaned.replace(Regex("${match.groupValues[1]}$"), "") }
    }

    cleaned = Regex("""\s+""").replace(cleaned, " ").trim()
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
    val ep = this.extractEpisode(); val base = this.cleanTitle(keepAfterHyphen = true)
    if (ep == null) return base
    return if (base.contains(" - ")) { val split = base.split(" - ", limit = 2); "${split[0]} $ep - ${split[1]}" } else "$base $ep"
}

suspend fun translateToKorean(text: String): String {
    if (text.isBlank() || text.contains(Regex("""[가-힣]"""))) return text
    return try {
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=ko&dt=t&q=${text.encodeURLParameter()}"
        val response: String = tmdbClient.get(url).body()
        val jsonArray = Json.parseToJsonElement(response).jsonArray
        val translatedParts = jsonArray[0].jsonArray
        translatedParts.joinToString("") { part -> part.jsonArray[0].jsonPrimitive.content }
    } catch (e: Exception) { text }
}

/**
 * TMDB 메타데이터 조회 통합 함수 (강화된 검색 로직)
 */
suspend fun fetchTmdbMetadata(title: String, typeHint: String? = null, isAnimation: Boolean = false): TmdbMetadata {
    if (TMDB_API_KEY.isBlank()) return TmdbMetadata()
    val cacheKey = if (isAnimation) "ani_$title" else title
    tmdbCache[cacheKey]?.let { return it }

    val cleanTitle = title.cleanTitle(includeYear = false, preserveSubtitle = true)
    val yearMatch = Regex("""\((19|20)\d{2}\)|(?<!\d)(19|20)\d{2}(?!\d)""").find(title)
    val year = yearMatch?.value?.replace("(", "")?.replace(")", "")

    // 다단계 검색 전략
    val strategies = mutableListOf<SearchStrategy>()
    
    // 1. 한국어 검색 (가장 정확)
    strategies.add(SearchStrategy(cleanTitle, "ko-KR", year, typeHint))
    
    // 2. 애니메이션 특화 검색
    if (isAnimation) {
        strategies.add(SearchStrategy("$cleanTitle 애니메이션", "ko-KR", year, "tv"))
        strategies.add(SearchStrategy(cleanTitle, "ja-JP", year, "tv"))
    }

    // 3. 언어 설정 없이 검색 (글로벌 결과)
    strategies.add(SearchStrategy(cleanTitle, null, year, typeHint))

    // 4. 좀 더 정제된 제목으로 재시도
    val deeperClean = title.cleanTitle(includeYear = false, preserveSubtitle = false)
    if (deeperClean != cleanTitle) {
        strategies.add(SearchStrategy(deeperClean, "ko-KR", year, typeHint))
    }

    var bestResult: TmdbMetadata? = null

    for (strategy in strategies.distinctBy { "${it.query}_${it.lang}_${it.type}" }) {
        val metadata = performSearch(strategy, isAnimation)
        if (metadata != null) {
            // 이미지가 있으면 즉시 반환
            if (metadata.posterUrl != null) {
                tmdbCache[cacheKey] = metadata
                return metadata
            }
            // 이미지는 없지만 ID는 찾은 경우 일단 보관
            if (bestResult == null) bestResult = metadata
        }
    }

    // 이미지를 찾지 못한 경우, ID가 있다면 상세 정보를 통해 이미지 재조회
    if (bestResult?.tmdbId != null && bestResult?.posterUrl == null) {
        val details = fetchTmdbDetails(bestResult!!.tmdbId!!, bestResult!!.mediaType ?: "movie")
        if (details != null) {
            bestResult = bestResult!!.copy(
                posterUrl = details.posterPath?.let { "$TMDB_IMAGE_BASE$TMDB_POSTER_SIZE_MEDIUM$it" },
                backdropUrl = details.backdropPath?.let { "$TMDB_IMAGE_BASE$TMDB_BACKDROP_SIZE$it" },
                overview = details.overview ?: bestResult!!.overview
            )
        }
    }

    // 번역 처리
    if (bestResult != null && !bestResult!!.overview.isNullOrBlank() && !bestResult!!.overview!!.contains(Regex("""[가-힣]"""))) {
        bestResult = bestResult!!.copy(overview = translateToKorean(bestResult!!.overview!!))
    }

    val finalResult = bestResult ?: TmdbMetadata()
    if (finalResult.tmdbId != null) tmdbCache[cacheKey] = finalResult
    return finalResult
}

data class SearchStrategy(val query: String, val lang: String?, val year: String?, val type: String?)

private suspend fun performSearch(strategy: SearchStrategy, isAnimation: Boolean): TmdbMetadata? {
    val typesToTry = if (strategy.type != null && strategy.type != "multi") {
        listOf(strategy.type)
    } else {
        listOf("multi", "movie", "tv")
    }

    for (type in typesToTry) {
        val (metadata, _) = searchTmdbCore(strategy.query, strategy.lang, type, strategy.year, isAnimation)
        if (metadata != null && (metadata.posterUrl != null || metadata.tmdbId != null)) {
            return metadata
        }
    }
    return null
}

private suspend fun searchTmdbCore(query: String, language: String?, endpoint: String, year: String? = null, isAnimation: Boolean = false): Pair<TmdbMetadata?, Exception?> {
    if (query.isBlank()) return null to null
    return try {
        val response: TmdbSearchResponse = tmdbClient.get("$TMDB_BASE_URL/search/$endpoint") {
            if (TMDB_API_KEY.startsWith("eyJ")) header("Authorization", "Bearer $TMDB_API_KEY")
            else parameter("api_key", TMDB_API_KEY)
            parameter("query", query)
            if (language != null) parameter("language", language)
            if (year != null) {
                when (endpoint) {
                    "movie" -> parameter("primary_release_year", year)
                    "tv" -> parameter("first_air_date_year", year)
                    else -> parameter("year", year)
                }
            }
            parameter("include_adult", "false")
        }.body()

        val results = response.results.filter { it.mediaType != "person" }
        if (results.isEmpty()) return null to null

        // 결과 정렬 및 최적 선택
        val bestMatch = results.sortedWith(
            compareByDescending<TmdbResult> { 
                // 애니메이션 가점
                if (isAnimation) it.genreIds?.contains(16) == true else false 
            }.thenByDescending {
                // 제목 일치 가점
                val targetTitle = it.name ?: it.title ?: ""
                targetTitle.equals(query, ignoreCase = true)
            }.thenByDescending { 
                // 이미지 존재 여부
                it.posterPath != null 
            }.thenByDescending { 
                // 인기도
                it.popularity ?: 0.0
            }
        ).firstOrNull()

        if (bestMatch != null) {
            val posterUrl = bestMatch.posterPath?.let { "$TMDB_IMAGE_BASE$TMDB_POSTER_SIZE_MEDIUM$it" }
            val backdropUrl = bestMatch.backdropPath?.let { "$TMDB_IMAGE_BASE$TMDB_BACKDROP_SIZE$it" }
            val mediaType = bestMatch.mediaType ?: (if (endpoint == "multi") (if (bestMatch.title != null) "movie" else "tv") else endpoint)
            
            Pair(TmdbMetadata(
                tmdbId = bestMatch.id, 
                mediaType = mediaType, 
                posterUrl = posterUrl, 
                backdropUrl = backdropUrl,
                overview = bestMatch.overview, 
                genreIds = bestMatch.genreIds ?: emptyList(),
                title = bestMatch.name ?: bestMatch.title
            ), null)
        } else Pair(null, null)
    } catch (e: Exception) {
        Pair(null, e)
    }
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
