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
import kotlinx.serialization.json.*

class VideoRepositoryImpl : VideoRepository {
    private val client = NasApiClient.client
    private val baseUrl = NasApiClient.BASE_URL

    private fun isGenericFolder(name: String?): Boolean {
        if (name.isNullOrBlank()) return true
        val n = name.trim().toNfc()
        // [수정] 이름이 숫자로만 되어 있거나 일반적인 제목이 아닌 경우 필터링
        if (n.all { it.isDigit() }) return true
        return isGenericTitle(n) || n.contains("Search Results", ignoreCase = true)
    }

    private fun Movie.fixUrls(): Movie {
        val updatedVideoUrl = if (videoUrl != null && !videoUrl.startsWith("http") && videoUrl.isNotEmpty()) {
            "$baseUrl${if (videoUrl.startsWith("/")) "" else "/"}$videoUrl"
        } else videoUrl
        val updatedThumbUrl = if (thumbnailUrl != null && !thumbnailUrl.startsWith("http") && thumbnailUrl.isNotEmpty()) {
            "$baseUrl${if (thumbnailUrl.startsWith("/")) "" else "/"}$thumbnailUrl"
        } else thumbnailUrl
        return this.copy(videoUrl = updatedVideoUrl, thumbnailUrl = updatedThumbUrl)
    }

    private fun Category.fixUrls(): Category {
        val updatedMovies = movies?.map { it.fixUrls() }
        val updatedSeasons = seasons?.mapValues { entry -> entry.value.map { it.fixUrls() } }
        return this.copy(movies = updatedMovies, seasons = updatedSeasons)
    }

    private fun Category.toSeries() = Series(
        // [수정] 제목이 모호할 경우 경로 정보를 활용
        title = if (!name.isNullOrBlank()) name else (tmdbTitle ?: path?.split("/")?.lastOrNull() ?: "기타"),
        episodes = movies ?: emptyList(),
        seasons = seasons ?: emptyMap(),
        fullPath = path,
        category = category,
        posterPath = posterPath,
        genreIds = genreIds ?: emptyList(),
        genreNames = genreNames ?: emptyList(),
        director = director,
        actors = actors ?: emptyList(),
        overview = overview,
        year = year,
        rating = rating,
        seasonCount = seasonCount,
        tmdbTitle = tmdbTitle
    )

    private suspend fun getCategoryListAsSeries(path: String, keyword: String? = null, limit: Int = 150, offset: Int = 0): List<Series> = try {
        val response = client.get("$baseUrl/list") {
            parameter("path", path)
            if (!keyword.isNullOrBlank()) {
                parameter("keyword", keyword)
            }
            parameter("limit", limit)
            parameter("offset", offset)
        }.body<List<Category>>()
        
        response.filter { !isGenericFolder(it.name) }.map { it.fixUrls().toSeries() }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        println("VideoRepository: getCategoryListAsSeries($path) failed: ${e.message}")
        emptyList()
    }

    override suspend fun getCategorySections(categoryKey: String, keyword: String?): List<HomeSection> = try {
        val response = client.get("$baseUrl/category_sections") {
            parameter("cat", categoryKey)
            if (keyword != null && keyword != "전체" && keyword != "All") {
                parameter("kw", keyword)
            }
        }.body<List<HomeSection>>()
        
        response.map { section ->
            val filteredItems = section.items.filter { !isGenericFolder(it.name) }.map { it.fixUrls() }
            section.copy(items = filteredItems)
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        println("VideoRepository: getCategorySections($categoryKey) failed: ${e.message}")
        emptyList()
    }

    override suspend fun getHomeRecommendations(): List<HomeSection> = try {
        val response = client.get("$baseUrl/home").body<List<HomeSection>>()
        response.map { section ->
            val filteredItems = section.items.filter { !isGenericFolder(it.name) }.map { it.fixUrls() }
            section.copy(items = filteredItems)
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        println("VideoRepository: getHomeRecommendations failed: ${e.message}")
        emptyList()
    }

    override suspend fun getSeriesDetail(path: String): Series? = try {
        val response = client.get("$baseUrl/api/series_detail") {
            parameter("path", path)
        }.body<Category>()
        response.fixUrls().toSeries()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        println("VideoRepository: getSeriesDetail($path) failed: ${e.message}")
        null
    }

    override suspend fun getCategoryList(path: String, limit: Int, offset: Int): List<Category> = try {
        val response = client.get("$baseUrl/list") {
            parameter("path", path)
            parameter("limit", limit)
            parameter("offset", offset)
        }.body<List<Category>>()
        response.map { it.fixUrls() }
    } catch (e: Exception) {
        println("VideoRepository: getCategoryList($path) failed: ${e.message}")
        emptyList()
    }

    override suspend fun getAnimationsAir(): List<Series> = getCategoryListAsSeries("방송중", "라프텔 애니메이션")
    override suspend fun getDramasAir(): List<Series> = getCategoryListAsSeries("방송중", "드라마")
    override suspend fun getAnimationsRaftel(limit: Int, offset: Int): List<Series> = getCategoryListAsSeries("일본 애니메이션", "라프텔", limit, offset)
    override suspend fun getAnimationsSeries(limit: Int, offset: Int): List<Series> = getCategoryListAsSeries("일본 애니메이션", "시리즈", limit, offset)
    override suspend fun getAnimationsAll(): List<Series> = getCategoryListAsSeries("일본 애니메이션", null)
    override suspend fun getMoviesByTitle(limit: Int, offset: Int): List<Series> = getCategoryListAsSeries("영화", "제목", limit, offset)
    override suspend fun getUhdMovies(limit: Int, offset: Int): List<Series> = getCategoryListAsSeries("영화", "UHD", limit, offset)
    override suspend fun getLatestMovies(limit: Int, offset: Int): List<Series> = getCategoryListAsSeries("영화", "최신", limit, offset)
    override suspend fun getPopularMovies(limit: Int, offset: Int): List<Series> = getLatestMovies(limit, offset)
    override suspend fun getFtvUs(limit: Int, offset: Int): List<Series> = getCategoryListAsSeries("외국TV", "미국 드라마", limit, offset)
    override suspend fun getFtvJp(limit: Int, offset: Int): List<Series> = getCategoryListAsSeries("외국TV", "일본 드라마", limit, offset)
    override suspend fun getFtvCn(limit: Int, offset: Int): List<Series> = getCategoryListAsSeries("외국TV", "중국 드라마", limit, offset)
    override suspend fun getFtvEtc(limit: Int, offset: Int): List<Series> = getCategoryListAsSeries("외국TV", "기타국가 드라마", limit, offset)
    override suspend fun getFtvDocu(limit: Int, offset: Int): List<Series> = getCategoryListAsSeries("외국TV", "다큐", limit, offset)
    override suspend fun getKtvDrama(limit: Int, offset: Int): List<Series> = getCategoryListAsSeries("국내TV", "드라마", limit, offset)
    override suspend fun getKtvSitcom(limit: Int, offset: Int): List<Series> = getCategoryListAsSeries("국내TV", "시트콤", limit, offset)
    override suspend fun getKtvVariety(limit: Int, offset: Int): List<Series> = getCategoryListAsSeries("국내TV", "예능", limit, offset)
    override suspend fun getKtvEdu(limit: Int, offset: Int): List<Series> = getCategoryListAsSeries("국내TV", "교양", limit, offset)
    override suspend fun getKtvDocu(limit: Int, offset: Int): List<Series> = getCategoryListAsSeries("국내TV", "다큐멘터리", limit, offset)
    override suspend fun getCategoryVideoCount(path: String): Int = 0
    
    override suspend fun searchVideos(query: String, category: String): List<Series> = try {
        println("VideoRepository: Search start: query=$query")
        val response = client.get("$baseUrl/search") {
            parameter("q", query)
            if (category.isNotEmpty()) {
                parameter("cat", category)
            }
        }
        
        val jsonString = response.bodyAsText()
        println("VideoRepository: Raw search response: $jsonString")
        
        val categories = Json.decodeFromString<List<Category>>(jsonString)
        categories.map { it.fixUrls().toSeries() }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        println("VideoRepository: Search failed: ${e.message}")
        emptyList()
    }

    override suspend fun getAnimations(): List<Series> = getAnimationsAll()
    override suspend fun getDramas(): List<Series> = emptyList()
    override suspend fun getLatestForeignTV(): List<Series> = getCategoryListAsSeries("외국TV", null)
    override suspend fun getPopularForeignTV(): List<Series> = getLatestForeignTV()
    override suspend fun getLatestKoreanTV(): List<Series> = getCategoryListAsSeries("국내TV", null)
    override suspend fun getPopularKoreanTV(): List<Series> = getLatestKoreanTV()

    override suspend fun getSubtitleInfo(videoUrl: String): SubtitleInfo = try {
        val url = Url(videoUrl)
        val type = url.parameters["type"]
        val path = url.parameters["path"]
        
        client.get("$baseUrl/api/subtitle_info") {
            parameter("type", type)
            parameter("path", path)
        }.body<SubtitleInfo>()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        println("VideoRepository: getSubtitleInfo failed: ${e.message}")
        SubtitleInfo()
    }

    override suspend fun updateProgress(episodeId: String, position: Double, duration: Double): Boolean = try {
        client.post("$baseUrl/api/update_progress") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("episode_id", episodeId)
                put("position", position)
                put("duration", duration)
            })
        }.status == HttpStatusCode.OK
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        println("VideoRepository: updateProgress failed: ${e.message}")
        false
    }
}
