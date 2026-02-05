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

private val REGEX_GROUP_BY_SERIES = Regex("""(?i)[.\s_-]+(?:S\d+E\d+|S\d+|E\d+|EP\d+|\d+화|\d+회|시즌\d+|\d+기).*""")

class VideoRepositoryImpl : VideoRepository {
    private val client = NasApiClient.client
    private val baseUrl = NasApiClient.BASE_URL

    override suspend fun getCategoryList(path: String, limit: Int, offset: Int): List<Category> = try {
        client.get("$baseUrl/list") {
            parameter("path", path)
            parameter("limit", limit)
            parameter("offset", offset)
        }.body()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        emptyList()
    }

    override suspend fun getCategoryVideoCount(path: String): Int = try {
        val response: List<Category> = client.get("$baseUrl/list?path=${path.encodeURLParameter()}").body()
        response.sumOf { it.movies?.size ?: 0 }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        0
    }

    override suspend fun searchVideos(query: String, category: String): List<Series> = withContext(Dispatchers.Default) {
        try {
            val results: List<Category> = client.get("$baseUrl/search?q=${query.encodeURLParameter()}${if (category != "전체") "&category=${category.encodeURLParameter()}" else ""}").body()
            results.flatMap { it.movies ?: emptyList() }.groupBySeries()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }
    }

    override suspend fun getLatestMovies(): List<Series> = withContext(Dispatchers.Default) {
        try {
            val results: List<Category> = client.get("$baseUrl/movies").body()
            results.flatMap { it.movies ?: emptyList() }.groupBySeries()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            getAirDataInternal() 
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
            val categories: List<Category> = client.get("$baseUrl/anim_raftel").body()
            categories.groupCategoriesToSeries("애니메이션")
        } catch (e: Exception) {
            emptyList() 
        }
    }

    override suspend fun getAnimationsSeries(limit: Int, offset: Int): List<Series> = withContext(Dispatchers.Default) {
        try {
            val categories: List<Category> = client.get("$baseUrl/anim_series").body()
            categories.groupCategoriesToSeries("애니메이션")
        } catch (e: Exception) {
            emptyList() 
        }
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
    
    override suspend fun getAnimationsAir(): List<Series> = 
        getAirDataInternal(filterKeyword = "라프텔")

    override suspend fun getDramasAir(): List<Series> = 
        getAirDataInternal(filterKeyword = "드라마")

    private suspend fun getAirDataInternal(filterKeyword: String? = null): List<Series> = withContext(Dispatchers.Default) {
        try {
            val response = client.get("$baseUrl/air")
            if (response.status != HttpStatusCode.OK) return@withContext emptyList()
            
            val categories: List<Category> = response.body()
            val normalizedKeyword = filterKeyword?.replace(" ", "")?.lowercase()
            
            val allMoviesWithPaths = categories
                .filter { cat -> 
                    if (normalizedKeyword == null) true
                    else {
                        val name = (cat.name ?: "").replace(" ", "").lowercase()
                        val path = (cat.path ?: "").replace(" ", "").lowercase()
                        name.contains(normalizedKeyword) || path.contains(normalizedKeyword)
                    }
                }
                .flatMap { cat ->
                    val catPath = cat.path ?: cat.name
                    (cat.movies ?: emptyList()).map { it to catPath }
                }
            
            allMoviesWithPaths.groupBySeriesWithPaths()
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
                fullPath = fullPath
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
