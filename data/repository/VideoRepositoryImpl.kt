package org.nas.videoplayerandroidtv.data.repository

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.plugins.*
import io.ktor.http.*
import org.nas.videoplayerandroidtv.data.network.NasApiClient
import org.nas.videoplayerandroidtv.domain.model.*
import org.nas.videoplayerandroidtv.domain.repository.VideoRepository
import org.nas.videoplayerandroidtv.cleanTitle
import kotlinx.coroutines.*

private val REGEX_GROUP_BY_SERIES = Regex("""(?i)[.\s_-]+(?:S\d+E\d+|S\d+|E\d+|EP\d+|\d+화|\d+회|시즌\d+|\d+기|\(\d+\)).*""")

class VideoRepositoryImpl : VideoRepository {
    private val client = NasApiClient.client
    private val baseUrl = NasApiClient.BASE_URL

    override suspend fun getHomeRecommendations(): List<HomeSection> = try {
        client.get("$baseUrl/home").body()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        emptyList()
    }

    override suspend fun getCategoryList(path: String, limit: Int, offset: Int): List<Category> = try {
        val categories: List<Category> = client.get("$baseUrl/list") {
            parameter("path", path)
            parameter("limit", limit)
            parameter("offset", offset)
        }.body()
        categories.distinctBy { it.name ?: it.path }
            .map { it.copy(movies = it.movies?.distinctBy { m -> m.id }) }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        emptyList()
    }

    override suspend fun getCategoryVideoCount(path: String): Int = try {
        val response: List<Category> = client.get("$baseUrl/list") {
            parameter("path", path)
        }.body()
        response.distinctBy { it.name ?: it.path }
            .sumOf { it.movies?.distinctBy { m -> m.id }?.size ?: 0 }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        0
    }

    override suspend fun searchVideos(query: String, category: String): List<Series> = withContext(Dispatchers.Default) {
        try {
            val results: List<Category> = client.get("$baseUrl/search") {
                parameter("q", query)
                if (category != "전체") parameter("category", category)
            }.body()
            results.flatMap { it.movies ?: emptyList() }.groupBySeries()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }
    }

    override suspend fun getLatestMovies(limit: Int, offset: Int): List<Series> = getMoviesFlat("최신", limit, offset)
    override suspend fun getPopularMovies(limit: Int, offset: Int): List<Series> = getLatestMovies(limit, offset)
    override suspend fun getUhdMovies(limit: Int, offset: Int): List<Series> = getMoviesFlat("UHD", limit, offset)
    override suspend fun getMoviesByTitle(limit: Int, offset: Int): List<Series> = getMoviesFlat("제목", limit, offset)

    private suspend fun getMoviesFlat(route: String, limit: Int, offset: Int): List<Series> = withContext(Dispatchers.Default) {
        try {
            val response: List<Category> = client.get("$baseUrl/movies") {
                parameter("route", route)
                parameter("limit", limit)
                parameter("offset", offset)
                parameter("lite", "true")
            }.body()
            
            response
                .distinctBy { it.name ?: it.path }
                .map { cat ->
                    Series(
                        title = cat.name ?: "Unknown",
                        episodes = emptyList(),
                        fullPath = "영화/${cat.path}",
                        genreIds = cat.genreIds ?: emptyList(),
                        posterPath = cat.posterPath
                    )
                }
                .distinctBy { it.title }
                .sortedBy { it.title }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getAnimations(): List<Series> = withContext(Dispatchers.Default) {
        try {
            val results: List<Category> = client.get("$baseUrl/animations").body()
            results.flatMap { it.movies ?: emptyList() }.groupBySeries()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }
    }

    override suspend fun getAnimationsAll(): List<Series> = withContext(Dispatchers.Default) {
        try {
            val response = client.get("$baseUrl/animations_all") {
                parameter("lite", "true")
                timeout { requestTimeoutMillis = 30000 }
            }
            if (response.status == HttpStatusCode.OK) {
                val categories: List<Category> = response.body()
                categories.groupCategoriesToSeries()
            } else { emptyList() }
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun getAnimationsRaftel(limit: Int, offset: Int): List<Series> = withContext(Dispatchers.Default) {
        try {
            val categories: List<Category> = client.get("$baseUrl/anim_raftel") {
                parameter("limit", limit)
                parameter("offset", offset)
                parameter("lite", "true")
            }.body()
            categories.groupCategoriesToSeries("애니메이션")
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun getAnimationsSeries(limit: Int, offset: Int): List<Series> = withContext(Dispatchers.Default) {
        try {
            val categories: List<Category> = client.get("$baseUrl/anim_series") {
                parameter("limit", limit)
                parameter("offset", offset)
                parameter("lite", "true")
            }.body()
            categories.groupCategoriesToSeries("애니메이션")
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun getDramas(): List<Series> = withContext(Dispatchers.Default) {
        try {
            val results: List<Category> = client.get("$baseUrl/dramas").body()
            results.flatMap { it.movies ?: emptyList() }.groupBySeries()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }
    }
    
    override suspend fun getAnimationsAir(): List<Series> = getAirDataInternal("라프텔")
    override suspend fun getDramasAir(): List<Series> = getAirDataInternal("드라마")
    
    override suspend fun getLatestForeignTV(): List<Series> = getAirDataInternal("외국TV")
    override suspend fun getPopularForeignTV(): List<Series> = getLatestForeignTV()

    // 외국 TV 세부 카테고리 구현
    override suspend fun getFtvUs(limit: Int, offset: Int): List<Series> = getGenericData("/ftv_us", limit, offset)
    override suspend fun getFtvCn(limit: Int, offset: Int): List<Series> = getGenericData("/ftv_cn", limit, offset)
    override suspend fun getFtvJp(limit: Int, offset: Int): List<Series> = getGenericData("/ftv_jp", limit, offset)
    override suspend fun getFtvDocu(limit: Int, offset: Int): List<Series> = getGenericData("/ftv_docu", limit, offset)
    override suspend fun getFtvEtc(limit: Int, offset: Int): List<Series> = getGenericData("/ftv_etc", limit, offset)

    override suspend fun getLatestKoreanTV(): List<Series> = getAirDataInternal("국내TV")
    override suspend fun getPopularKoreanTV(): List<Series> = getLatestKoreanTV()

    // 국내 TV 세부 카테고리 구현
    override suspend fun getKtvDrama(limit: Int, offset: Int): List<Series> = getGenericData("/ktv_drama", limit, offset)
    override suspend fun getKtvSitcom(limit: Int, offset: Int): List<Series> = getGenericData("/ktv_sitcom", limit, offset)
    override suspend fun getKtvVariety(limit: Int, offset: Int): List<Series> = getGenericData("/ktv_variety", limit, offset)
    override suspend fun getKtvEdu(limit: Int, offset: Int): List<Series> = getGenericData("/ktv_edu", limit, offset)
    override suspend fun getKtvDocu(limit: Int, offset: Int): List<Series> = getGenericData("/ktv_docu", limit, offset)

    private suspend fun getGenericData(endpoint: String, limit: Int, offset: Int): List<Series> = withContext(Dispatchers.Default) {
        try {
            val categories: List<Category> = client.get("$baseUrl$endpoint") {
                parameter("limit", limit)
                parameter("offset", offset)
                parameter("lite", "true")
            }.body()
            categories.map { cat ->
                Series(
                    title = cat.name ?: "Unknown",
                    episodes = emptyList(),
                    fullPath = cat.path,
                    genreIds = cat.genreIds ?: emptyList(),
                    posterPath = cat.posterPath
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun getAirDataInternal(filterKeyword: String? = null): List<Series> = withContext(Dispatchers.Default) {
        try {
            val response = client.get("$baseUrl/air") {
                parameter("lite", "true")
            }
            if (response.status != HttpStatusCode.OK) return@withContext emptyList()
            
            val categories: List<Category> = response.body()
            val normalizedKeyword = filterKeyword?.replace(" ", "")?.lowercase()
            
            val filtered = categories.filter { cat -> 
                if (normalizedKeyword == null) true
                else {
                    val name = (cat.name ?: "").replace(" ", "").lowercase()
                    val path = (cat.path ?: "").replace(" ", "").lowercase()
                    name.contains(normalizedKeyword) || path.contains(normalizedKeyword)
                }
            }
            
            filtered.map { cat ->
                Series(
                    title = cat.name ?: "Unknown",
                    episodes = emptyList(),
                    fullPath = cat.path,
                    genreIds = cat.genreIds ?: emptyList(),
                    posterPath = cat.posterPath
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }
    }

    private fun List<Category>.groupCategoriesToSeries(basePathPrefix: String = ""): List<Series> {
        return this.groupBy { cat ->
            (cat.name ?: "Unknown").cleanTitle(false).replace(REGEX_GROUP_BY_SERIES, " ").trim()
        }.map { (title, cats) ->
            val firstCat = cats.first()
            val catPath = firstCat.path ?: firstCat.name ?: ""
            val fullPath = if (basePathPrefix.isNotEmpty()) "$basePathPrefix/$catPath" else catPath
            Series(
                title = title, 
                episodes = emptyList(), 
                fullPath = fullPath,
                genreIds = firstCat.genreIds ?: emptyList(),
                posterPath = firstCat.posterPath
            )
        }.sortedBy { it.title }
    }

    private fun List<Movie>.groupBySeries(basePath: String? = null): List<Series> = 
        this.map { it to basePath }.groupBySeriesWithPaths()

    private fun List<Pair<Movie, String?>>.groupBySeriesWithPaths(): List<Series> = 
        this.groupBy { (movie, _) -> 
            (movie.title ?: "").cleanTitle(false).replace(REGEX_GROUP_BY_SERIES, " ").trim()
        }.map { (title, pairs) -> 
            val episodes = pairs.map { it.first }.distinctBy { it.id }.sortedBy { it.title ?: "" }
            val representativePath = pairs.firstNotNullOfOrNull { it.second }
            Series(title = title, episodes = episodes, fullPath = representativePath)
        }.sortedByDescending { it.episodes.size }
}
