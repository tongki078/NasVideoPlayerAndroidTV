package org.nas.videoplayerandroidtv.data.repository

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.plugins.*
import io.ktor.http.*
import org.nas.videoplayerandroidtv.data.network.NasApiClient
import org.nas.videoplayerandroidtv.domain.model.*
import org.nas.videoplayerandroidtv.domain.repository.VideoRepository
import org.nas.videoplayerandroidtv.util.TitleUtils
import org.nas.videoplayerandroidtv.util.TitleUtils.cleanTitle
import org.nas.videoplayerandroidtv.util.TitleUtils.isGenericTitle
import org.nas.videoplayerandroidtv.util.TitleUtils.REGEX_GROUP_BY_SERIES
import org.nas.videoplayerandroidtv.util.TitleUtils.REGEX_INDEX_FOLDER
import org.nas.videoplayerandroidtv.util.TitleUtils.REGEX_YEAR_FOLDER
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

    override suspend fun getCategoryList(path: String, limit: Int, offset: Int): List<Category> = try {
        val categories: List<Category> = client.get("$baseUrl/list") {
            parameter("path", path)
            parameter("limit", limit)
            parameter("offset", offset)
        }.body()
        
        categories.distinctBy { it.name ?: it.path }.map { cat ->
            val updatedMovies = cat.movies?.map { movie ->
                if (movie.videoUrl != null && !movie.videoUrl.startsWith("http")) {
                    movie.copy(videoUrl = "$baseUrl${if (movie.videoUrl.startsWith("/")) "" else "/"}${movie.videoUrl}")
                } else movie
            }
            cat.copy(movies = updatedMovies)
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        emptyList()
    }

    override suspend fun getCategoryVideoCount(path: String): Int = try {
        val response: List<Category> = client.get("$baseUrl/list") {
            parameter("path", path)
            parameter("lite", "true")
        }.body()
        response.sumOf { it.movies?.size ?: 0 }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        0
    }

    override suspend fun searchVideos(query: String, category: String): List<Series> = withContext(Dispatchers.Default) {
        try {
            val response = client.get("$baseUrl/search") {
                parameter("q", query)
                // 상세 페이지에서의 수집을 위해 lite=true를 제거하여 실제 에피소드 정보를 가져옴
                if (category != "전체") parameter("category", category)
            }
            val results: List<Category> = response.body()
            results.groupCategoriesToSeries("")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }
    }

    override suspend fun getLatestMovies(limit: Int, offset: Int): List<Series> = getGenericData("/movies_latest", 600, offset, "영화")
    override suspend fun getPopularMovies(limit: Int, offset: Int): List<Series> = getLatestMovies(limit, offset)
    override suspend fun getUhdMovies(limit: Int, offset: Int): List<Series> = getGenericData("/movies_uhd", 600, offset, "영화")
    override suspend fun getMoviesByTitle(limit: Int, offset: Int): List<Series> = getGenericData("/movies_title", 600, offset, "영화")

    override suspend fun getAnimations(): List<Series> = withContext(Dispatchers.Default) {
        try {
            val results: List<Category> = client.get("$baseUrl/air_animations") {
                parameter("lite", "true")
            }.body()
            results.filter { cat ->
                val path = cat.path ?: ""
                val name = cat.name ?: ""
                !path.contains("OTT") && !name.contains("OTT")
            }.groupCategoriesToSeries("방송중")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }
    }

    override suspend fun getAnimationsAll(): List<Series> = getGroupedData("/animations_all", 600, 0, "애니메이션")
    override suspend fun getAnimationsRaftel(limit: Int, offset: Int): List<Series> = getGroupedData("/anim_raftel", 600, offset, "애니메이션", "라프텔")
    override suspend fun getAnimationsSeries(limit: Int, offset: Int): List<Series> = getGroupedData("/anim_series", 600, offset, "애니메이션", "시리즈")

    override suspend fun getDramas(): List<Series> = withContext(Dispatchers.Default) {
        try {
            val results: List<Category> = client.get("$baseUrl/air_dramas") {
                parameter("lite", "true")
            }.body()
            results.filter { cat ->
                val path = cat.path ?: ""
                val name = cat.name ?: ""
                !path.contains("OTT") && !name.contains("OTT")
            }.groupCategoriesToSeries("방송중")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }
    }
    
    override suspend fun getAnimationsAir(): List<Series> = getAirDataInternal("라프텔")
    override suspend fun getDramasAir(): List<Series> = getAirDataInternal("드라마")
    
    override suspend fun getLatestForeignTV(): List<Series> = getGroupedData("/foreigntv", 600, 0, "외국TV")
    override suspend fun getPopularForeignTV(): List<Series> = getLatestForeignTV()

    override suspend fun getFtvUs(limit: Int, offset: Int): List<Series> = getGroupedData("/ftv_us", 600, offset, "외국TV", "미국")
    override suspend fun getFtvCn(limit: Int, offset: Int): List<Series> = getGroupedData("/ftv_cn", 600, offset, "외국TV", "중국")
    override suspend fun getFtvJp(limit: Int, offset: Int): List<Series> = getGroupedData("/ftv_jp", 600, offset, "외국TV", "일본")
    override suspend fun getFtvDocu(limit: Int, offset: Int): List<Series> = getGroupedData("/ftv_docu", 600, offset, "외국TV", "다큐")
    override suspend fun getFtvEtc(limit: Int, offset: Int): List<Series> = getGroupedData("/ftv_etc", 600, offset, "외국TV", "기타")

    override suspend fun getLatestKoreanTV(): List<Series> = getGroupedData("/koreantv", 600, 0, "국내TV")
    override suspend fun getPopularKoreanTV(): List<Series> = getLatestKoreanTV()

    override suspend fun getKtvDrama(limit: Int, offset: Int): List<Series> = getGroupedData("/ktv_drama", 600, offset, "국내TV", "드라마")
    override suspend fun getKtvSitcom(limit: Int, offset: Int): List<Series> = getGroupedData("/ktv_sitcom", 600, offset, "국내TV", "시트콤")
    override suspend fun getKtvVariety(limit: Int, offset: Int): List<Series> = getGroupedData("/ktv_variety", 600, offset, "국내TV", "예능")
    override suspend fun getKtvEdu(limit: Int, offset: Int): List<Series> = getGroupedData("/ktv_edu", 600, offset, "국내TV", "교양")
    override suspend fun getKtvDocu(limit: Int, offset: Int): List<Series> = getGroupedData("/ktv_docu", 600, offset, "국내TV", "다큐멘터리")

    private suspend fun getGroupedData(endpoint: String, limit: Int, offset: Int, prefix: String, filterKeyword: String? = null): List<Series> = withContext(Dispatchers.Default) {
        try {
            val response = client.get("$baseUrl$endpoint") {
                parameter("limit", limit)
                parameter("offset", offset)
                parameter("lite", "true")
            }
            if (response.status == HttpStatusCode.OK) {
                val categories: List<Category> = response.body()
                val filtered = if (filterKeyword != null) {
                    val target = filterKeyword.replace(" ", "").lowercase().toNfc()
                    categories.filter { cat ->
                        val p = cat.path?.replace(" ", "")?.lowercase()?.toNfc() ?: ""
                        val n = cat.name?.replace(" ", "")?.lowercase()?.toNfc() ?: ""
                        p.contains(target) || n.contains(target) ||
                        (target == "미국" && (p.contains("미드") || p.contains("us"))) ||
                        (target == "중국" && (p.contains("중드") || p.contains("cn"))) ||
                        (target == "일본" && (p.contains("일드") || p.contains("jp")))
                    }
                } else categories
                filtered.groupCategoriesToSeries(prefix, filterKeyword)
            } else emptyList()
        } catch (e: Exception) { emptyList() }
    }

    private suspend fun getGenericData(endpoint: String, limit: Int, offset: Int, prefix: String): List<Series> = withContext(Dispatchers.Default) {
        try {
            val response = client.get("$baseUrl$endpoint") {
                parameter("limit", limit)
                parameter("offset", offset)
                parameter("lite", "true")
            }
            if (response.status == HttpStatusCode.OK) {
                val categories: List<Category> = response.body()
                categories.filter { cat -> !isGenericFolder(cat.name) }.map { cat ->
                    val episodes = cat.movies?.map { movie ->
                        if (movie.videoUrl != null && !movie.videoUrl.startsWith("http")) {
                            movie.copy(videoUrl = "$baseUrl${if (movie.videoUrl.startsWith("/")) "" else "/"}${movie.videoUrl}")
                        } else movie
                    } ?: emptyList()
                    val rawPath = cat.path ?: ""
                    val fullPath = if (rawPath.isNotEmpty()) {
                        if (rawPath.startsWith(prefix)) rawPath else "$prefix/$rawPath"
                    } else null
                    Series(title = cat.name ?: "Unknown", episodes = episodes, fullPath = fullPath, genreIds = cat.genreIds ?: emptyList(), posterPath = cat.posterPath, overview = cat.overview, year = cat.year, rating = cat.rating)
                }
            } else emptyList()
        } catch (e: Exception) { emptyList() }
    }

    private suspend fun getAirDataInternal(filterKeyword: String? = null): List<Series> = withContext(Dispatchers.Default) {
        try {
            val endpoint = if (filterKeyword == "라프텔") "/air_animations" else if (filterKeyword == "드라마") "/air_dramas" else "/air"
            val response = client.get("$baseUrl$endpoint") { parameter("lite", "true") }
            if (response.status != HttpStatusCode.OK) return@withContext emptyList()
            val categories: List<Category> = response.body()
            categories.filter { cat ->
                val p = cat.path ?: ""; val n = cat.name ?: ""
                if (p.contains("OTT") || n.contains("OTT") || isGenericFolder(n)) return@filter false
                if (filterKeyword != null) p.contains(filterKeyword) || n.contains(filterKeyword) else true
            }.groupCategoriesToSeries("방송중", filterKeyword)
        } catch (e: Exception) { emptyList() }
    }

    private fun List<Category>.groupCategoriesToSeries(basePathPrefix: String = "", filterKeyword: String? = null): List<Series> {
        val showGroups = mutableMapOf<String, MutableList<Category>>()
        val skipFolders = mutableSetOf("미국 드라마", "중국 드라마", "일본 드라마", "기타국가 드라마", "다큐", "드라마", "시트콤", "예능", "교양", "다큐멘터리", "미드", "중드", "일드", "외드", "Foreign TV", "ForeignTV", "Korean TV", "KoreanTV", "Documentary", "라프텔", "시리즈", "애니메이션", "국내TV", "외국TV", "영화", "방송중", "라프텔 애니메이션", "OTT 애니메이션", "volume1", "video", "GDS3", "GDRIVE", "NAS", "share").map { it.lowercase().toNfc() }.toMutableSet()
        filterKeyword?.let { skipFolders.add(it.lowercase().toNfc()) }
        basePathPrefix.split("/").forEach { skipFolders.add(it.lowercase().toNfc()) }
        for (cat in this) {
            val rawPath = cat.path ?: continue
            val parts = rawPath.split("/").filter { it.isNotBlank() }
            if (parts.isEmpty()) continue
            var showTitle = ""; var showPath = ""
            for (i in parts.lastIndex downTo 0) {
                val part = parts[i].trim().toNfc()
                val partLower = part.lowercase()
                if (partLower in skipFolders || REGEX_INDEX_FOLDER.matches(part) || REGEX_YEAR_FOLDER.matches(part) || isGenericTitle(part)) continue
                showTitle = part; showPath = parts.subList(0, i + 1).joinToString("/"); break
            }
            if (showTitle.isEmpty()) {
                val catName = cat.name ?: "Unknown"
                if (catName.lowercase().toNfc() in skipFolders || isGenericFolder(catName)) continue
                showTitle = catName; showPath = rawPath
            }
            val groupKey = showTitle.cleanTitle(false).replace(REGEX_GROUP_BY_SERIES, "").trim()
            if (groupKey.isNotEmpty()) showGroups.getOrPut(groupKey) { mutableListOf() }.add(cat.copy(path = showPath))
        }
        return showGroups.map { (title, cats) ->
            val allEpisodes = cats.flatMap { it.movies ?: emptyList() }.map { movie ->
                if (movie.videoUrl != null && !movie.videoUrl.startsWith("http")) movie.copy(videoUrl = "$baseUrl${if (movie.videoUrl.startsWith("/")) "" else "/"}${movie.videoUrl}") else movie
            }.distinctBy { it.videoUrl ?: it.id }.sortedBy { it.title ?: "" }
            val bestCat = cats.maxByOrNull { (if (it.posterPath != null) 2 else 0) + (if (!it.genreIds.isNullOrEmpty()) 1 else 0) } ?: cats.first()
            val fullPath = if (basePathPrefix.isNotEmpty()) { if (bestCat.path?.startsWith(basePathPrefix) == true) bestCat.path else "$basePathPrefix/${bestCat.path}" } else bestCat.path
            Series(title = title, episodes = allEpisodes, fullPath = fullPath, genreIds = bestCat.genreIds ?: emptyList(), posterPath = bestCat.posterPath, overview = bestCat.overview, year = bestCat.year, rating = bestCat.rating)
        }.filter { it.title.isNotEmpty() && !isGenericFolder(it.title) }.sortedBy { it.title }
    }
}
