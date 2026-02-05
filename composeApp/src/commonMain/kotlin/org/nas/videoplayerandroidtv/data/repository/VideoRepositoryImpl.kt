package org.nas.videoplayerandroidtv.data.repository

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import org.nas.videoplayerandroidtv.data.network.NasApiClient
import org.nas.videoplayerandroidtv.domain.model.*
import org.nas.videoplayerandroidtv.domain.repository.VideoRepository
import org.nas.videoplayerandroidtv.cleanTitle
import kotlinx.coroutines.CancellationException

// 미리 컴파일된 정규식으로 CPU 연산 최적화
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

    override suspend fun searchVideos(query: String, category: String): List<Series> = try {
        val results: List<Category> = client.get("$baseUrl/search?q=${query.encodeURLParameter()}${if (category != "전체") "&category=${category.encodeURLParameter()}" else ""}").body()
        results.flatMap { it.movies ?: emptyList() }.groupBySeries()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        emptyList()
    }

    override suspend fun getLatestMovies(): List<Series> = try {
        val results: List<Category> = client.get("$baseUrl/movies").body()
        // 모든 카테고리의 영화를 먼저 합친 후 그룹화하여 중복 방지
        results.flatMap { it.movies ?: emptyList() }.groupBySeries()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        getAirDataInternal() 
    }

    override suspend fun getAnimations(): List<Series> = try {
        val results: List<Category> = client.get("$baseUrl/animations").body()
        results.flatMap { it.movies ?: emptyList() }.groupBySeries()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        emptyList()
    }

    override suspend fun getAnimationsAll(): List<Series> = try {
        val response = client.get("$baseUrl/animations_all")
        if (response.status == HttpStatusCode.OK) {
            val categories: List<Category> = response.body()
            // animations_all의 경우 각 카테고리가 이미 시리즈 단위일 가능성이 높으므로 제목 정제 후 통합 그룹화
            categories.flatMap { cat -> 
                val catPath = cat.path ?: cat.name
                (cat.movies ?: emptyList()).map { it to catPath } 
            }.groupBySeriesWithPaths()
        } else { emptyList() }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        emptyList()
    }

    override suspend fun getDramas(): List<Series> = try {
        val results: List<Category> = client.get("$baseUrl/dramas").body()
        results.flatMap { it.movies ?: emptyList() }.groupBySeries()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        emptyList()
    }
    
    override suspend fun getAnimationsAir(): List<Series> = 
        getAirDataInternal(filterKeyword = "라프텔")

    override suspend fun getDramasAir(): List<Series> = 
        getAirDataInternal(filterKeyword = "드라마")

    private suspend fun getAirDataInternal(filterKeyword: String? = null): List<Series> {
        return try {
            val response = client.get("$baseUrl/air")
            if (response.status != HttpStatusCode.OK) return emptyList()
            
            val categories: List<Category> = response.body()
            val normalizedKeyword = filterKeyword?.replace(" ", "")?.lowercase()
            
            // 1. 키워드 필터링 후 모든 영화와 해당 폴더 경로를 평탄화(Flatten)
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
            
            // 2. 통합 리스트에 대해 단 한 번의 그룹화 수행 (중복 원천 차단)
            allMoviesWithPaths.groupBySeriesWithPaths()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }
    }

    // 기본 그룹화 함수 (경로 정보가 없는 경우)
    private fun List<Movie>.groupBySeries(): List<Series> = 
        this.map { it to null as String? }.groupBySeriesWithPaths()

    // 경로 정보를 포함한 통합 그룹화 함수 (핵심 로직)
    private fun List<Pair<Movie, String?>>.groupBySeriesWithPaths(): List<Series> = 
        this.groupBy { (movie, _) -> 
            (movie.title ?: "").cleanTitle(false).replace(REGEX_GROUP_BY_SERIES, " ").trim()
        }.map { (title, pairs) -> 
            val episodes = pairs.map { it.first }.sortedBy { it.title ?: "" }
            // 여러 폴더에 흩어져 있어도 하나의 시리즈로 묶고, 가장 빈도가 높거나 첫 번째 경로를 사용
            val representativePath = pairs.firstNotNullOfOrNull { it.second }
            Series(title = title, episodes = episodes, fullPath = representativePath)
        }.sortedByDescending { it.episodes.size }
}
