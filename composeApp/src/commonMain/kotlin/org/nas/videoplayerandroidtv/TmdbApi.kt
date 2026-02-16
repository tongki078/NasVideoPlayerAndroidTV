package org.nas.videoplayerandroidtv

import androidx.compose.runtime.mutableStateMapOf
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.compression.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.nas.videoplayerandroidtv.data.TmdbCacheDataSource
import org.nas.videoplayerandroidtv.util.TitleUtils
import org.nas.videoplayerandroidtv.util.TitleUtils.cleanTitle
import org.nas.videoplayerandroidtv.util.TitleUtils.extractYear
import org.nas.videoplayerandroidtv.util.TitleUtils.extractTmdbId

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
    // [최적화] TMDB API 응답 압축 해제 지원
    install(ContentEncoding) {
        gzip()
        deflate()
    }
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
private val tmdbMutex = Mutex()

internal var persistentCache: TmdbCacheDataSource? = null

suspend fun fetchTmdbMetadata(title: String, typeHint: String? = null, isAnimation: Boolean = false): TmdbMetadata {
    if (TMDB_API_KEY.isBlank()) return TmdbMetadata()
    val normalizedTitle = title.toNfc()
    val cacheKey = if (isAnimation) "ani_$normalizedTitle" else normalizedTitle
    tmdbCache[cacheKey]?.let { return it }
    persistentCache?.getCache(cacheKey)?.let {
        tmdbCache[cacheKey] = it
        return it
    }
    val deferred = tmdbMutex.withLock {
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
                tmdbMutex.withLock { tmdbInFlightRequests.remove(cacheKey) }
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
        return TmdbMetadata(tmdbId = tvRes.id, mediaType = "tv", posterUrl = "$TMDB_IMAGE_BASE$TMDB_POSTER_SIZE_MEDIUM${tvRes.posterPath}", backdropUrl = tvRes.backdropPath?.let { "$TMDB_IMAGE_BASE$TMDB_BACKDROP_SIZE$it" }, overview = tvRes.overview, genreIds = tvRes.genres?.map { it.id } ?: emptyList(), title = tvRes.title ?: tvRes.name)
    }
    return null
}

private suspend fun performMultiStepSearch(originalTitle: String, typeHint: String?, isAnimation: Boolean): TmdbMetadata? {
    val year = originalTitle.extractYear()
    val fullCleanQuery = originalTitle.cleanTitle(includeYear = false)
    val tightQuery = fullCleanQuery.replace(" ", "")
    if (tightQuery != fullCleanQuery && tightQuery.length >= 2) {
        searchTmdbCore(tightQuery, "ko-KR", typeHint ?: "multi", year, isAnimation).first?.let { return it }
    }
    searchTmdbCore(fullCleanQuery, "ko-KR", typeHint ?: "multi", year, isAnimation).first?.let { return it }
    val noNumberQuery = fullCleanQuery.replace(Regex("""\s+\d+$"""), "").trim()
    if (noNumberQuery != fullCleanQuery && noNumberQuery.length >= 2) {
        searchTmdbCore(noNumberQuery, "ko-KR", typeHint ?: "multi", year, isAnimation).first?.let { return it }
    }
    searchTmdbCore(fullCleanQuery, "ko-KR", typeHint ?: "multi", null, isAnimation).first?.let { return it }
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
        val bestMatch = results.sortedWith(compareByDescending<TmdbResult> { if (year != null) { (it.releaseDate ?: it.firstAirDate ?: "").take(4) == year } else false }.thenByDescending { if (isAnimation) it.genreIds?.contains(16) == true else true }.thenByDescending { val matchTitle = (it.name ?: it.title ?: "").lowercase().toNfc().replace("×", "x").replace(" ", "").replace(":", "").replace("-", ""); val searchTitle = nfcQuery.lowercase().replace("×", "x").replace(" ", "").replace(":", "").replace("-", ""); matchTitle == searchTitle || matchTitle.contains(searchTitle) || searchTitle.contains(matchTitle) }.thenByDescending { it.posterPath != null }.thenByDescending { it.popularity ?: 0.0 }).firstOrNull()
        if (bestMatch != null && bestMatch.posterPath != null) {
            val posterUrl = "$TMDB_IMAGE_BASE$TMDB_POSTER_SIZE_MEDIUM${bestMatch.posterPath}"
            val backdropUrl = bestMatch.backdropPath?.let { "$TMDB_IMAGE_BASE$TMDB_BACKDROP_SIZE$it" }
            val mediaType = bestMatch.mediaType ?: (if (bestMatch.title != null) "movie" else "tv")
            val resultYear = (bestMatch.releaseDate ?: bestMatch.firstAirDate ?: "").take(4)
            Pair(TmdbMetadata(tmdbId = bestMatch.id, mediaType = mediaType, posterUrl = posterUrl, backdropUrl = backdropUrl, overview = bestMatch.overview, genreIds = bestMatch.genreIds ?: emptyList(), title = bestMatch.name ?: bestMatch.title, year = resultYear), null)
        } else Pair(null, null)
    } catch (e: Exception) { Pair(null, e) }
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
        response.cast
    } catch (e: Exception) { emptyList() }
}
