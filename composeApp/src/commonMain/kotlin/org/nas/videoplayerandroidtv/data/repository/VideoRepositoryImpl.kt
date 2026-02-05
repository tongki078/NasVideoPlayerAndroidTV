package org.nas.videoplayerandroidtv.data.repository

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import org.nas.videoplayerandroidtv.data.network.NasApiClient
import org.nas.videoplayerandroidtv.domain.model.*
import org.nas.videoplayerandroidtv.domain.repository.VideoRepository
import org.nas.videoplayerandroidtv.cleanTitle

class VideoRepositoryImpl : VideoRepository {
    private val client = NasApiClient.client
    private val baseUrl = NasApiClient.BASE_URL

    override suspend fun getCategoryList(path: String, limit: Int, offset: Int): List<Category> = try {
        client.get("$baseUrl/list") {
            parameter("path", path)
            parameter("limit", limit)
            parameter("offset", offset)
        }.body()
    } catch (e: Exception) { emptyList() }

    override suspend fun getCategoryVideoCount(path: String): Int = try {
        val response: List<Category> = client.get("$baseUrl/list?path=${path.encodeURLParameter()}").body()
        response.sumOf { it.movies?.size ?: 0 }
    } catch (e: Exception) { 0 }

    override suspend fun searchVideos(query: String, category: String): List<Series> = try {
        val results: List<Category> = client.get("$baseUrl/search?q=${query.encodeURLParameter()}${if (category != "전체") "&category=${category.encodeURLParameter()}" else ""}").body()
        results.flatMap { it.movies ?: emptyList() }.groupBySeries()
    } catch (e: Exception) { emptyList() }

    override suspend fun getLatestMovies(): List<Series> = try {
        val results: List<Category> = client.get("$baseUrl/movies").body()
        results.flatMap { it.movies ?: emptyList() }.groupBySeries()
    } catch (e: Exception) {
        try { client.get("$baseUrl/air").body<List<Category>>().flatMap { it.movies ?: emptyList() }.groupBySeries() } catch(e: Exception) { emptyList() }
    }

    override suspend fun getAnimations(): List<Series> = try {
        client.get("$baseUrl/animations").body<List<Category>>().flatMap { it.movies ?: emptyList() }.groupBySeries()
    } catch (e: Exception) { emptyList() }

    override suspend fun getAnimationsAll(): List<Series> = try {
        val response = client.get("$baseUrl/animations_all")
        if (response.status == HttpStatusCode.OK) {
            val categories: List<Category> = response.body()
            categories.filter { !it.movies.isNullOrEmpty() }.map { cat ->
                Series(
                    title = (cat.name ?: "Unknown").cleanTitle(false),
                    episodes = (cat.movies ?: emptyList()).sortedBy { it.title },
                    fullPath = cat.path ?: cat.movies?.firstOrNull()?.videoUrl 
                )
            }
        } else { emptyList() }
    } catch (e: Exception) {
        println("VideoRepository CRITICAL ERROR: ${e.message}")
        emptyList()
    }

    override suspend fun getDramas(): List<Series> = try {
        client.get("$baseUrl/dramas").body<List<Category>>().flatMap { it.movies ?: emptyList() }.groupBySeries()
    } catch (e: Exception) { emptyList() }
    
    override suspend fun getAnimationsAir(): List<Series> = try {
        // 방송중(AIR) 카테고리에서 애니메이션 데이터를 가져옵니다.
        client.get("$baseUrl/air").body<List<Category>>()
            .flatMap { it.movies ?: emptyList() }
            .groupBySeries()
    } catch (e: Exception) { 
        getAnimations() 
    }

    override suspend fun getDramasAir(): List<Series> = try {
        // 필요 시 방송중 데이터에서 드라마를 분리할 수 있으나 현재는 공통으로 사용
        client.get("$baseUrl/air").body<List<Category>>()
            .flatMap { it.movies ?: emptyList() }
            .groupBySeries()
    } catch (e: Exception) { 
        getDramas() 
    }

    private fun List<Movie>.groupBySeries(basePath: String? = null): List<Series> = 
        this.groupBy { movie -> 
            (movie.title ?: "").cleanTitle(false).replace(Regex("""(?i)[.\s_-]+(?:S\d+E\d+|S\d+|E\d+|EP\d+|\d+화|\d+회|시즌\d+|\d+기).*"""), " ").trim()
        }.map { (title, eps) -> 
            Series(title = title, episodes = eps.sortedBy { it.title ?: "" }, fullPath = basePath)
        }.sortedByDescending { it.episodes.size }
}
