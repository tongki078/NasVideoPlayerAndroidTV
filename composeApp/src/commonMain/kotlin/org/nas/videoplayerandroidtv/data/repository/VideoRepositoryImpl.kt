package org.nas.videoplayerandroidtv.data.repository

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.plugins.*
import io.ktor.http.*
import org.nas.videoplayerandroidtv.data.network.NasApiClient
import org.nas.videoplayerandroidtv.domain.model.*
import org.nas.videoplayerandroidtv.domain.repository.VideoRepository
import org.nas.videoplayerandroidtv.util.TitleUtils.cleanTitle
import org.nas.videoplayerandroidtv.util.TitleUtils.isGenericTitle
import org.nas.videoplayerandroidtv.toNfc
import kotlinx.coroutines.*

class VideoRepositoryImpl : VideoRepository {
    private val client = NasApiClient.client
    private val baseUrl = NasApiClient.BASE_URL

    private fun isGenericFolder(name: String?): Boolean {
        if (name.isNullOrBlank()) return true
        val n = name.trim().toNfc()
        return isGenericTitle(n) || n.contains("Search Results", ignoreCase = true)
    }

    // [수정] keyword 파라미터를 추가하여 서버 측 필터링을 지원하도록 변경
    suspend fun fetchCategorySections(categoryKey: String, keyword: String? = null): List<HomeSection> = try {
        val response = client.get("$baseUrl/category_sections") {
            parameter("cat", categoryKey)
            if (keyword != null && keyword != "전체" && keyword != "All") {
                parameter("kw", keyword)
            }
        }.body<List<HomeSection>>()
        
        response.map { section ->
            val filteredItems = section.items.filter { !isGenericFolder(it.name) }.map { cat ->
                val updatedMovies = cat.movies?.map { movie ->
                    if (movie.videoUrl != null && !movie.videoUrl.startsWith("http")) {
                        movie.copy(videoUrl = "$baseUrl${if (movie.videoUrl.startsWith("/")) "" else "/"}${movie.videoUrl}")
                    } else movie
                }
                cat.copy(movies = updatedMovies)
            }
            section.copy(items = filteredItems)
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        emptyList()
    }

    override suspend fun getHomeRecommendations(): List<HomeSection> = try {
        val response = client.get("$baseUrl/home").body<List<HomeSection>>()
        response.map { section ->
            val filteredItems = section.items.filter { !isGenericFolder(it.name) }.map { cat ->
                val updatedMovies = cat.movies?.map { movie ->
                    if (movie.videoUrl != null && !movie.videoUrl.startsWith("http")) {
                        movie.copy(videoUrl = "$baseUrl${if (movie.videoUrl.startsWith("/")) "" else "/"}${movie.videoUrl}")
                    } else movie
                }
                cat.copy(movies = updatedMovies)
            }
            section.copy(items = filteredItems)
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        emptyList()
    }

    override suspend fun getCategoryList(path: String, limit: Int, offset: Int): List<Category> = emptyList()
    override suspend fun getCategoryVideoCount(path: String): Int = 0
    override suspend fun searchVideos(query: String, category: String): List<Series> = emptyList()
    override suspend fun getLatestMovies(limit: Int, offset: Int): List<Series> = emptyList()
    override suspend fun getPopularMovies(limit: Int, offset: Int): List<Series> = emptyList()
    override suspend fun getUhdMovies(limit: Int, offset: Int): List<Series> = emptyList()
    override suspend fun getMoviesByTitle(limit: Int, offset: Int): List<Series> = emptyList()
    override suspend fun getAnimations(): List<Series> = emptyList()
    override suspend fun getAnimationsAll(): List<Series> = emptyList()
    override suspend fun getAnimationsRaftel(limit: Int, offset: Int): List<Series> = emptyList()
    override suspend fun getAnimationsSeries(limit: Int, offset: Int): List<Series> = emptyList()
    override suspend fun getDramas(): List<Series> = emptyList()
    override suspend fun getAnimationsAir(): List<Series> = emptyList()
    override suspend fun getDramasAir(): List<Series> = emptyList()
    override suspend fun getLatestForeignTV(): List<Series> = emptyList()
    override suspend fun getPopularForeignTV(): List<Series> = emptyList()
    override suspend fun getFtvUs(limit: Int, offset: Int): List<Series> = emptyList()
    override suspend fun getFtvCn(limit: Int, offset: Int): List<Series> = emptyList()
    override suspend fun getFtvJp(limit: Int, offset: Int): List<Series> = emptyList()
    override suspend fun getFtvDocu(limit: Int, offset: Int): List<Series> = emptyList()
    override suspend fun getFtvEtc(limit: Int, offset: Int): List<Series> = emptyList()
    override suspend fun getLatestKoreanTV(): List<Series> = emptyList()
    override suspend fun getPopularKoreanTV(): List<Series> = emptyList()
    override suspend fun getKtvDrama(limit: Int, offset: Int): List<Series> = emptyList()
    override suspend fun getKtvSitcom(limit: Int, offset: Int): List<Series> = emptyList()
    override suspend fun getKtvVariety(limit: Int, offset: Int): List<Series> = emptyList()
    override suspend fun getKtvEdu(limit: Int, offset: Int): List<Series> = emptyList()
    override suspend fun getKtvDocu(limit: Int, offset: Int): List<Series> = emptyList()
}
