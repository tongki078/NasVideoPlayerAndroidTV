package org.nas.videoplayer.data.repository

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import org.nas.videoplayer.data.network.NasApiClient
import org.nas.videoplayer.domain.model.Category
import org.nas.videoplayer.domain.model.Series
import org.nas.videoplayer.domain.repository.VideoRepository
import org.nas.videoplayer.cleanTitle
import org.nas.videoplayer.extractSeason
import org.nas.videoplayer.extractEpisode

class VideoRepositoryImpl : VideoRepository {
    private val client = NasApiClient.client
    private val baseUrl = NasApiClient.BASE_URL

    override suspend fun getCategoryList(path: String): List<Category> = try {
        client.get("$baseUrl/list?path=${path.encodeURLParameter()}").body()
    } catch (e: Exception) {
        emptyList()
    }

    override suspend fun searchVideos(query: String, category: String): List<Series> = try {
        val url = "$baseUrl/search?q=${query.encodeURLParameter()}" + 
                if (category != "전체") "&category=${category.encodeURLParameter()}" else ""
        val results: List<Category> = client.get(url).body()
        results.flatMap { it.movies }.groupBySeries()
    } catch (e: Exception) {
        emptyList()
    }

    override suspend fun getLatestMovies(): List<Series> = try {
        val results: List<Category> = client.get("$baseUrl/latestmovies").body()
        results.flatMap { it.movies }.groupBySeries()
    } catch (e: Exception) {
        emptyList()
    }

    override suspend fun getAnimations(): List<Series> = try {
        val results: List<Category> = client.get("$baseUrl/animations").body()
        results.flatMap { it.movies }.groupBySeries()
    } catch (e: Exception) {
        emptyList()
    }

    override suspend fun getDramas(): List<Series> = try {
        val results: List<Category> = client.get("$baseUrl/dramas").body()
        results.flatMap { it.movies }.groupBySeries()
    } catch (e: Exception) {
        emptyList()
    }

    override suspend fun getAnimationsAll(): List<Series> = try {
        val results: List<Category> = client.get("$baseUrl/animations_all").body()
        results.flatMap { it.movies }.groupBySeries()
    } catch (e: Exception) {
        emptyList()
    }

    private fun List<org.nas.videoplayer.domain.model.Movie>.groupBySeries(basePath: String? = null): List<Series> = 
        this.groupBy { it.title.cleanTitle(includeYear = false) }
            .map { (title, eps) -> 
                Series(
                    title = title, 
                    episodes = eps.sortedWith(
                        compareBy<org.nas.videoplayer.domain.model.Movie> { it.title.extractSeason() }
                            .thenBy { it.title.extractEpisode()?.filter { it.isDigit() }?.toIntOrNull() ?: 0 }
                    ),
                    fullPath = basePath
                ) 
            }.sortedBy { it.title }
}
