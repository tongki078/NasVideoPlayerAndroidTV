package org.nas.videoplayer

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

private val tmdbClient = HttpClient {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
    install(HttpTimeout) { requestTimeoutMillis = 60000; connectTimeoutMillis = 15000; socketTimeoutMillis = 60000 }
    defaultRequest { header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, lifestyle Gecko) Version/17.0 Mobile/15E148 Safari/604.1") }
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
    @SerialName("origin_country") val originCountry: List<String>? = null,
    @SerialName("genre_ids") val genreIds: List<Int>? = null,
    @SerialName("vote_count") val voteCount: Int? = 0
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

internal val tmdbCache = mutableMapOf<String, TmdbMetadata>()

fun String.cleanTitle(keepAfterHyphen: Boolean = false, includeYear: Boolean = true, preserveSubtitle: Boolean = false): String {
    var cleaned = this
    if (cleaned.contains(".")) { val ext = cleaned.substringAfterLast('.'); if (ext.length in 2..4) cleaned = cleaned.substringBeforeLast('.') }
    
    cleaned = Regex("""([가-힣])([a-zA-Z0-9])""").replace(cleaned, "$1 $2")
    cleaned = Regex("""([a-zA-Z0-9])([가-힣])""").replace(cleaned, "$1 $2")
    
    cleaned = Regex("""^\d+[.\s_-]+""").replace(cleaned, "")
    cleaned = Regex("""^[a-zA-Z]\d+[.\s_-]+""").replace(cleaned, "")
    cleaned = Regex("""^\[\d+\]\s*""").replace(cleaned, "")
    
    val yearMatch = Regex("""\((19|20)\d{2}\)|(?<!\d)(19|20)\d{2}(?!\d)""").find(cleaned)
    val yearStr = yearMatch?.value?.replace("(", "")?.replace(")", "")
    if (yearMatch != null) cleaned = cleaned.replace(yearMatch.value, " ")
    
    cleaned = Regex("""\[.*?\]|\(.*?\)""").replace(cleaned, " ")
    
    if (!keepAfterHyphen) {
        if (preserveSubtitle) {
            cleaned = Regex("""(?i)[.\s_](?:S\d+E\d+|E\d+|\d+\s*(?:화|회)).*""").replace(cleaned, "")
        } else {
            cleaned = Regex("""(?i)[.\s_](?:S\d+E\d+|S\d+|E\d+|\d+\s*(?:화|회|기)|Season\s*\d+|Part\s*\d+).*""").replace(cleaned, "")
        }
    }
    
    val tagRegex = if (preserveSubtitle) {
        """(?i)[.\s_](?:더빙|자막|무삭제|\d{3,4}p|WEB-DL|WEBRip|Bluray|HDRip|BDRip|DVDRip|H\.?26[45]|x26[45]|HEVC|AAC|DTS|AC3|DDP|Dual|Atmos|REPACK|10bit|REMUX|FLAC|xvid|DivX|MKV|MP4|AVI|속편|무리무리|1부|2부|파트|완결).*"""
    } else {
        """(?i)[.\s_](?:더빙|자막|무삭제|\d{3,4}p|WEB-DL|WEBRip|Bluray|HDRip|BDRip|DVDRip|H\.?26[45]|x26[45]|HEVC|AAC|DTS|AC3|DDP|Dual|Atmos|REPACK|10bit|REMUX|OVA|OAD|ONA|TV판|극장판|FLAC|xvid|DivX|MKV|MP4|AVI|속편|무리무리|1부|2부|파트|완결).*"""
    }
    cleaned = Regex(tagRegex).replace(cleaned, " ")
    
    cleaned = Regex("""[._\-::!?【】『』「」"'#@*※]""").replace(cleaned, " ")
    
    val match = Regex("""[.\s_](\d+)$""").find(" $cleaned")
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

suspend fun fetchTmdbMetadata(title: String, typeHint: String? = null, isAnimation: Boolean = false): TmdbMetadata {
    if (TMDB_API_KEY.isBlank()) return TmdbMetadata()
    val cacheKey = if (isAnimation) "ani_$title" else title
    tmdbCache[cacheKey]?.let { return it }

    val cleanTitleForSearch = title.cleanTitle(includeYear = false, preserveSubtitle = true)
    val yearMatch = Regex("""\((19|20)\d{2}\)|(?<!\d)(19|20)\d{2}(?!\d)""").find(title)
    val year = yearMatch?.value?.replace("(", "")?.replace(")", "")

    val searchStrategies = mutableListOf<Triple<String, String?, String?>>()
    
    // 애니메이션인 경우 제목 뒤에 "애니메이션"을 붙여 검색 결과의 정확도를 높임
    if (isAnimation) {
        searchStrategies.add(Triple("$cleanTitleForSearch 애니메이션", "ko-KR", year))
    }
    
    searchStrategies.add(Triple(cleanTitleForSearch, "ko-KR", year))
    searchStrategies.add(Triple(cleanTitleForSearch, null, year))
    
    val baseTitle = title.cleanTitle(includeYear = false, preserveSubtitle = false)
    if (baseTitle != cleanTitleForSearch) {
        searchStrategies.add(Triple(baseTitle, "ko-KR", year))
    }

    var finalMetadata: TmdbMetadata? = null
    var hasNetworkError = false

    // 애니메이션은 주로 TV 섹션에 많으므로 힌트가 없으면 tv로 시도
    val effectiveTypeHint = if (isAnimation && (typeHint == null || typeHint == "multi")) "tv" else typeHint

    for ((q, lang, y) in searchStrategies.distinct()) {
        val (res, error) = searchTmdb(q, lang, effectiveTypeHint, y, isAnimation)
        if (error != null) { hasNetworkError = true; break }
        
        if (res != null && (res.posterUrl != null || res.tmdbId != null)) {
            // 애니메이션 검색일 경우, 애니메이션 장르(16)가 포함된 결과를 찾을 때까지 계속 탐색 가능하도록 로직 보완
            if (isAnimation && !res.genreIds.contains(16)) {
                if (finalMetadata == null) finalMetadata = res
                continue 
            }
            finalMetadata = res
            if (res.posterUrl != null) break
        }
    }

    var result = finalMetadata ?: TmdbMetadata()

    if (result.posterUrl == null && result.tmdbId != null && result.mediaType != null) {
        val credits = fetchTmdbCredits(result.tmdbId!!, result.mediaType)
        val firstCastProfilePath = credits.firstOrNull { !it.profilePath.isNullOrBlank() }?.profilePath
        if (firstCastProfilePath != null) {
            result = result.copy(posterUrl = "$TMDB_IMAGE_BASE$TMDB_POSTER_SIZE_MEDIUM$firstCastProfilePath")
        }
    }

    if (!result.overview.isNullOrBlank() && !result.overview!!.contains(Regex("""[가-힣]"""))) {
        result = result.copy(overview = translateToKorean(result.overview!!))
    }

    if (!hasNetworkError) tmdbCache[cacheKey] = result
    return result
}

private suspend fun searchTmdb(query: String, language: String?, typeHint: String? = null, year: String? = null, isAnimation: Boolean = false): Pair<TmdbMetadata?, Exception?> {
    if (query.isBlank()) return null to null
    return try {
        val endpoint = if (typeHint != null) typeHint else "multi"
        val response: TmdbSearchResponse = tmdbClient.get("$TMDB_BASE_URL/search/$endpoint") {
            if (TMDB_API_KEY.startsWith("eyJ")) header("Authorization", "Bearer $TMDB_API_KEY")
            else parameter("api_key", TMDB_API_KEY)
            parameter("query", query)
            if (language != null) parameter("language", language)
            if (year != null) {
                if (endpoint == "movie") parameter("primary_release_year", year)
                else if (endpoint == "tv") parameter("first_air_date_year", year)
                else parameter("year", year)
            }
            parameter("include_adult", "false")
        }.body()

        val results = response.results.filter { it.mediaType != "person" }
        if (results.isEmpty()) return null to null

        val result = results.sortedWith(
            compareByDescending<TmdbResult> { 
                // 1. 애니메이션 검색 시 장르 ID 16(애니메이션) 최우선
                if (isAnimation) it.genreIds?.contains(16) == true else true 
            }.thenByDescending { 
                // 2. 제목 일치 여부
                it.name?.equals(query, ignoreCase = true) == true || it.title?.equals(query, ignoreCase = true) == true 
            }.thenByDescending { 
                it.posterPath != null 
            }.thenByDescending { 
                // 3. 일본 원산지 우선 (애니메이션인 경우)
                if (isAnimation) it.originCountry?.contains("JP") == true else false 
            }.thenByDescending { 
                it.voteCount ?: 0 
            }
        ).firstOrNull()

        if (result != null) {
            val path = result.posterPath ?: result.backdropPath
            val posterUrl = path?.let { "$TMDB_IMAGE_BASE$TMDB_POSTER_SIZE_MEDIUM$it" }
            val mediaType = result.mediaType ?: typeHint ?: (if (isAnimation) "tv" else "movie")
            Pair(TmdbMetadata(tmdbId = result.id, mediaType = mediaType, posterUrl = posterUrl, overview = result.overview, genreIds = result.genreIds ?: emptyList()), null)
        } else Pair(null, null)
    } catch (e: Exception) { Pair(null, e) }
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
        val episode: TmdbEpisode = tmdbClient.get("$TMDB_BASE_URL/tv/$tmdbId/season/$season/episode/$episodeNum") {
            if (TMDB_API_KEY.startsWith("eyJ")) header("Authorization", "Bearer $TMDB_API_KEY")
            else parameter("api_key", TMDB_API_KEY)
            parameter("language", "ko-KR")
        }.body()
        episode
    } catch (e: Exception) { null }
}

suspend fun fetchTmdbCredits(tmdbId: Int, mediaType: String?): List<TmdbCast> {
    if (tmdbId == 0 || mediaType == null) return emptyList()
    return try {
        val isTv = mediaType == "tv"
        val endpoint = if (isTv) "$TMDB_BASE_URL/tv/$tmdbId/aggregate_credits" else "$TMDB_BASE_URL/movie/$tmdbId/credits"
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
