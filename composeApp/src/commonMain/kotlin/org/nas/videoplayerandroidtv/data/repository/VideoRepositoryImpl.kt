package org.nas.videoplayerandroidtv.data.repository

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import org.nas.videoplayerandroidtv.data.network.NasApiClient
import org.nas.videoplayerandroidtv.domain.model.Category
import org.nas.videoplayerandroidtv.domain.model.Series
import org.nas.videoplayerandroidtv.domain.repository.VideoRepository
import org.nas.videoplayerandroidtv.cleanTitle
import org.nas.videoplayerandroidtv.extractSeason
import org.nas.videoplayerandroidtv.extractEpisode

class VideoRepositoryImpl : VideoRepository {
    private val client = NasApiClient.client
    private val baseUrl = NasApiClient.BASE_URL

    override suspend fun getCategoryList(path: String): List<Category> = try {
        println("VideoRepository: Requesting category list for path: $path")
        val response: List<Category> = client.get("$baseUrl/list?path=${path.encodeURLParameter()}").body()
        println("VideoRepository: Received ${response.size} categories")
        response
    } catch (e: Exception) {
        println("VideoRepository ERROR (getCategoryList): ${e.message}")
        e.printStackTrace()
        emptyList()
    }

    override suspend fun searchVideos(query: String, category: String): List<Series> = try {
        val url = "$baseUrl/search?q=${query.encodeURLParameter()}" +
                if (category != "전체") "&category=${category.encodeURLParameter()}" else ""
        println("VideoRepository: Searching videos with url: $url")
        val results: List<Category> = client.get(url).body()
        println("VideoRepository: Search results found ${results.size} categories")
        results.flatMap { it.movies }.groupBySeries()
    } catch (e: Exception) {
        println("VideoRepository ERROR (searchVideos): ${e.message}")
        e.printStackTrace()
        emptyList()
    }

    override suspend fun getLatestMovies(): List<Series> = try {
        println("VideoRepository: Requesting latest movies")
        val results: List<Category> = client.get("$baseUrl/latestmovies").body()
        println("VideoRepository: Received ${results.size} latest categories")
        results.flatMap { it.movies }.groupBySeries()
    } catch (e: Exception) {
        println("VideoRepository ERROR (getLatestMovies): ${e.message}")
        e.printStackTrace()
        emptyList()
    }

    override suspend fun getAnimations(): List<Series> = try {
        println("VideoRepository: Requesting animations")
        val results: List<Category> = client.get("$baseUrl/animations").body()
        println("VideoRepository: Received ${results.size} animation categories")
        results.flatMap { it.movies }.groupBySeries()
    } catch (e: Exception) {
        println("VideoRepository ERROR (getAnimations): ${e.message}")
        e.printStackTrace()
        emptyList()
    }

    override suspend fun getDramas(): List<Series> = try {
        val results: List<Category> = client.get("$baseUrl/dramas").body()
        results.flatMap { it.movies }.groupBySeries()
    } catch (e: Exception) {
        println("VideoRepository ERROR (getDramas): ${e.message}")
        emptyList()
    }

    override suspend fun getAnimationsAll(): List<Series> = try {
        val results: List<Category> = client.get("$baseUrl/animations_all").body()
        results.flatMap { it.movies }.groupBySeries()
    } catch (e: Exception) {
        println("VideoRepository ERROR (getAnimationsAll): ${e.message}")
        emptyList()
    }

    private fun List<org.nas.videoplayerandroidtv.domain.model.Movie>.groupBySeries(basePath: String? = null): List<Series> = 
        this.groupBy { it.title.cleanTitle(includeYear = false) }
            .map { (title, eps) -> 
                Series(
                    title = title, 
                    episodes = eps.sortedWith(
                        compareBy<org.nas.videoplayerandroidtv.domain.model.Movie> { it.title.extractSeason() }
                            .thenBy { it.title.extractEpisode()?.filter { it.isDigit() }?.toIntOrNull() ?: 0 }
                    ),
                    fullPath = basePath
                ) 
            }.sortedBy { it.title }
}
