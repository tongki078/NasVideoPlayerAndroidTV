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

    override suspend fun getCategoryList(path: String, limit: Int, offset: Int): List<Category> = try {
        client.get("$baseUrl/list") {
            parameter("path", path)
            parameter("limit", limit)
            parameter("offset", offset)
        }.body()
    } catch (e: Exception) {
        println("VideoRepository ERROR (getCategoryList): ${e.message}")
        emptyList()
    }

    override suspend fun getCategoryVideoCount(path: String): Int = try {
        val response: List<Category> = client.get("$baseUrl/list?path=${path.encodeURLParameter()}").body()
        response.sumOf { it.movies.size }
    } catch (e: Exception) {
        0
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

    // [최종 해결] 서버의 /air 엔드포인트를 호출하여 전체 데이터를 가져온 뒤 path 필드로 필터링합니다.
    override suspend fun getAnimations(): List<Series> = try {
        val airCategories: List<Category> = client.get("$baseUrl/air").body()
        // path 필드에 "라프텔" 키워드가 포함된 모든 카테고리의 영화를 합칩니다.
        airCategories.filter { it.path?.contains("라프텔") == true }
            .flatMap { it.movies }
            .groupBySeries()
    } catch (e: Exception) {
        println("VideoRepository ERROR (getAnimations): ${e.message}")
        emptyList()
    }

    override suspend fun getDramas(): List<Series> = try {
        val airCategories: List<Category> = client.get("$baseUrl/air").body()
        // path 필드에 "드라마" 키워드가 포함된 모든 카테고리의 영화를 합칩니다.
        airCategories.filter { it.path?.contains("드라마") == true }
            .flatMap { it.movies }
            .groupBySeries()
    } catch (e: Exception) {
        println("VideoRepository ERROR (getDramas): ${e.message}")
        emptyList()
    }

    override suspend fun getAnimationsAll(): List<Series> = try {
        val results: List<Category> = client.get("$baseUrl/animations_all").body()
        results.flatMap { it.movies }.groupBySeries()
    } catch (e: Exception) {
        emptyList()
    }
    
    override suspend fun getAnimationsAir(): List<Series> = getAnimations()
    override suspend fun getDramasAir(): List<Series> = getDramas()
    

    private fun List<org.nas.videoplayerandroidtv.domain.model.Movie>.groupBySeries(basePath: String? = null): List<Series> = 
        this.groupBy { movie -> 
            var t = movie.title.cleanTitle(includeYear = false)
            t = t.replace(Regex("""^\s*[\(\[【](?:더빙|자막|무삭제|완결|스페셜|라프텔|HD)[\)\]】]\s*"""), "")
            t = t.replace(Regex("""\s*\(\d{4}\)\s*"""), "")
            t = t.replace(Regex("""(?i)[.\s_-]+(?:S\d+E\d+|S\d+|E\d+|EP\d+|\d+화|\d+회|시즌\d+|\d+기).*"""), " ")
            t = t.replace(Regex("""(?i)[.\s_-]+(?:\d{3,4}p|WEB-DL|WEBRip|Bluray|HDRip|BDRip|x26[45]|HEVC|AAC|DTS|KL|60fps).*"""), " ")
            t.trim().ifEmpty { movie.title }
        }.map { (title, eps) -> 
            Series(title = title, episodes = eps.sortedBy { it.title }, fullPath = basePath) 
        }.sortedByDescending { it.episodes.size }
}
