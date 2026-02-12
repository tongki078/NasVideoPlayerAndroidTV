package org.nas.videoplayerandroidtv.data.repository

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.plugins.*
import io.ktor.http.*
import org.nas.videoplayerandroidtv.data.network.NasApiClient
import org.nas.videoplayerandroidtv.domain.model.*
import org.nas.videoplayerandroidtv.domain.repository.VideoRepository
import org.nas.videoplayerandroidtv.cleanTitle
import kotlinx.coroutines.*

// 시리즈 제목 추출을 위한 정밀 정규식
private val REGEX_GROUP_BY_SERIES = Regex(
    """(?i)[\s._-]*(?:s\d{1,2}e\d{1,3}|season\s*\d{1,2}|s\d{1,2} |ep\d{1,3}|e\d{1,3}|\d{1,3}화|\d{1,3}회|\d+기|part\s*\d|극장판|완결|special|extras|ova|720p|1080p|2160p|4k|h264|h265|x264|x265|bluray|web-dl|aac|mp4|mkv|avi|\([^)]*\)|\[[^\]]*\]).*|(?:\s+\d+\s*$)""", 
    RegexOption.IGNORE_CASE
)
private val REGEX_INDEX_FOLDER = Regex("""(?i)^\s*([0-9A-Z가-힣ㄱ-ㅎ]|0Z|0-Z|가-하|[0-9]-[0-9]|[A-Z]-[A-Z]|[가-힣]-[가-힣])\s*$""")
private val REGEX_YEAR_FOLDER = Regex("""(?i)^\s*(?:\(\d{4}\)|\d{4}|\d{4}\s*년)\s*$""") 

// 시즌/회차/관리용 폴더 감지 강화 (시즌 202501 년 등 복합 패턴 대응)
private val REGEX_SEASON_FOLDER = Regex("""(?i)^\s*(?:Season\s*\d+|시즌\s*\d+(?:\s*년)?|Part\s*\d+|파트\s*\d+|S\d+|\d+기|\d+화|\d+회|특집|Special|Extras|Bonus|미분류|기타|새\s*폴더)\s*$""")

class VideoRepositoryImpl : VideoRepository {
    private val client = NasApiClient.client
    private val baseUrl = NasApiClient.BASE_URL

    private fun isGenericFolder(name: String?): Boolean {
        if (name.isNullOrBlank()) return true
        val n = name.trim()
        return REGEX_INDEX_FOLDER.matches(n) || 
               REGEX_YEAR_FOLDER.matches(n) || 
               REGEX_SEASON_FOLDER.matches(n) ||
               n.contains("Search Results", ignoreCase = true)
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
                parameter("lite", "true") 
                if (category != "전체") parameter("category", category)
            }
            
            val results: List<Category> = response.body()
            
            // 검색 결과를 기존의 groupCategoriesToSeries 로직을 통해 처리합니다.
            // 이렇게 하면 국내TV, 외국TV 등이 제목별로 그룹화되고 경로 정보(fullPath)도 올바르게 생성됩니다.
            results.filter { !isGenericFolder(it.name) }.groupCategoriesToSeries("")

        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }
    }

    private fun List<Movie>.groupBySeriesInternal(
        basePath: String? = null,
        posterPath: String? = null,
        genreIds: List<Int>? = emptyList()
    ): List<Series> {
        if (this.isEmpty()) return emptyList()
        return this.groupBy { movie ->
            (movie.title ?: "").cleanTitle(true).replace(REGEX_GROUP_BY_SERIES, "").trim()
        }.filterKeys { it.isNotBlank() }.map { (title, episodes) ->
            val sortedEps = episodes.distinctBy { it.id }.sortedBy { it.title ?: "" }
            Series(
                title = title,
                episodes = sortedEps,
                fullPath = basePath,
                posterPath = posterPath,
                genreIds = genreIds ?: emptyList()
            )
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

    override suspend fun getAnimationsAll(): List<Series> = withContext(Dispatchers.Default) {
        try {
            val response = client.get("$baseUrl/animations_all") {
                parameter("lite", "true")
                parameter("limit", 600)
            }
            if (response.status == HttpStatusCode.OK) {
                val categories: List<Category> = response.body()
                categories.filter { cat -> !isGenericFolder(cat.name) }.groupCategoriesToSeries("애니메이션")
            } else { emptyList() }
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun getAnimationsRaftel(limit: Int, offset: Int): List<Series> = withContext(Dispatchers.Default) {
        try {
            val categories: List<Category> = client.get("$baseUrl/anim_raftel") {
                parameter("limit", 600)
                parameter("offset", offset)
                parameter("lite", "true")
            }.body()
            categories.filter { cat -> !isGenericFolder(cat.name) }.groupCategoriesToSeries("애니메이션")
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun getAnimationsSeries(limit: Int, offset: Int): List<Series> = withContext(Dispatchers.Default) {
        try {
            val categories: List<Category> = client.get("$baseUrl/anim_series") {
                parameter("limit", 600)
                parameter("offset", offset)
                parameter("lite", "true")
            }.body()
            categories.filter { cat -> !isGenericFolder(cat.name) }.groupCategoriesToSeries("애니메이션")
        } catch (e: Exception) { emptyList() }
    }

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

    override suspend fun getFtvUs(limit: Int, offset: Int): List<Series> = getGroupedData("/ftv_us", 600, offset, "외국TV", "미국 드라마")
    override suspend fun getFtvCn(limit: Int, offset: Int): List<Series> = getGroupedData("/ftv_cn", 600, offset, "외국TV", "중국 드라마")
    override suspend fun getFtvJp(limit: Int, offset: Int): List<Series> = getGroupedData("/ftv_jp", 600, offset, "외국TV", "일본 드라마")
    override suspend fun getFtvDocu(limit: Int, offset: Int): List<Series> = getGroupedData("/ftv_docu", 600, offset, "외국TV", "다큐")
    override suspend fun getFtvEtc(limit: Int, offset: Int): List<Series> = getGroupedData("/ftv_etc", 600, offset, "외국TV", "기타국가 드라마")

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
                val filtered = categories.filter { cat ->
                    if (isGenericFolder(cat.name)) return@filter false
                    
                    if (filterKeyword != null) {
                        val target = filterKeyword.replace(" ", "").lowercase()
                        val pathNormalized = cat.path?.replace(" ", "")?.lowercase() ?: ""
                        pathNormalized.contains(target) || (cat.name?.replace(" ", "")?.lowercase()?.contains(target) == true)
                    } else true
                }
                filtered.groupCategoriesToSeries(prefix)
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
                    Series(
                        title = cat.name ?: "Unknown",
                        episodes = emptyList(),
                        fullPath = if (cat.path != null) "$prefix/${cat.path}" else null,
                        genreIds = cat.genreIds ?: emptyList(),
                        posterPath = cat.posterPath,
                        overview = cat.overview,
                        year = cat.year,
                        rating = cat.rating
                    )
                }
            } else emptyList()
        } catch (e: Exception) { emptyList() }
    }

    private suspend fun getAirDataInternal(filterKeyword: String? = null): List<Series> = withContext(Dispatchers.Default) {
        try {
            val endpoint = if (filterKeyword == "라프텔") "/air_animations" else if (filterKeyword == "드라마") "/air_dramas" else "/air"
            
            val response = client.get("$baseUrl$endpoint") {
                parameter("lite", "true")
            }
            
            if (response.status != HttpStatusCode.OK) return@withContext emptyList()
            
            val categories: List<Category> = response.body()
            
            val filteredCategories = categories.filter { cat ->
                val path = cat.path ?: ""
                val name = cat.name ?: ""
                if (path.contains("OTT") || name.contains("OTT")) return@filter false
                if (isGenericFolder(name)) return@filter false
                
                if (filterKeyword != null) {
                    path.contains(filterKeyword) || name.contains(filterKeyword)
                } else true
            }
            
            filteredCategories.groupCategoriesToSeries("방송중")
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun List<Category>.groupCategoriesToSeries(basePathPrefix: String = ""): List<Series> {
        val showGroups = mutableMapOf<String, MutableList<Category>>()
        
        for (cat in this) {
            val path = cat.path ?: continue
            val parts = path.split("/")
            
            var showTitle = ""
            var showPath = ""
            
            val skipFolders = listOf(
                "미국 드라마", "중국 드라마", "일본 드라마", "기타국가 드라마", "다큐", "드라마", "시트콤", "예능", "교양", "다큐멘터리",
                "라프텔", "시리즈", "애니메이션", "국내TV", "외국TV", "영화", "방송중", "라프텔 애니메이션", "OTT 애니메이션",
                "volume1", "video", "GDS3", "GDRIVE", "VIDEO", "NAS", "share"
            )
            
            var found = false
            val currentPathParts = mutableListOf<String>()
            
            for (part in parts) {
                currentPathParts.add(part)
                if (!found) {
                    if (part in skipFolders || isGenericFolder(part)) {
                        continue
                    }
                    showTitle = part
                    showPath = currentPathParts.joinToString("/")
                    found = true
                }
            }
            
            if (showTitle.isEmpty() || isGenericFolder(showTitle)) {
                val catName = cat.name ?: ""
                if (!isGenericFolder(catName)) {
                    showTitle = catName
                } else {
                    val firstMovie = cat.movies?.firstOrNull()?.title
                    if (firstMovie != null) {
                        showTitle = firstMovie.cleanTitle(false).replace(REGEX_GROUP_BY_SERIES, "").trim()
                    }
                }
            }

            if (showTitle.isEmpty()) showTitle = "Unknown"
            if (showPath.isEmpty()) showPath = path
            
            val groupKey = showTitle.cleanTitle(false).replace(REGEX_GROUP_BY_SERIES, "").trim()
            if (groupKey.isNotEmpty() && !isGenericFolder(groupKey)) {
                showGroups.getOrPut(groupKey) { mutableListOf() }.add(cat.copy(path = showPath))
            }
        }
        
        return showGroups.map { (title, cats) ->
            val allEpisodes = cats.flatMap { it.movies ?: emptyList() }.map { movie ->
                if (movie.videoUrl != null && !movie.videoUrl.startsWith("http")) {
                    movie.copy(videoUrl = "$baseUrl${if (movie.videoUrl.startsWith("/")) "" else "/"}${movie.videoUrl}")
                } else movie
            }.distinctBy { it.id }.sortedBy { it.title ?: "" }

            val bestCat = cats.maxByOrNull { 
                (if (it.posterPath != null) 2 else 0) + (if (!it.genreIds.isNullOrEmpty()) 1 else 0)
            } ?: cats.first()

            val fullPath = if (basePathPrefix.isNotEmpty()) {
                if (bestCat.path?.startsWith(basePathPrefix) == true) bestCat.path 
                else "$basePathPrefix/${bestCat.path}"
            } else bestCat.path

            Series(
                title = title, 
                episodes = allEpisodes,
                fullPath = fullPath,
                genreIds = bestCat.genreIds ?: emptyList(),
                posterPath = bestCat.posterPath,
                overview = bestCat.overview,
                year = bestCat.year,
                rating = bestCat.rating
            )
        }
        .filter { it.title.length >= 2 && !isGenericFolder(it.title) }
        .sortedBy { it.title }
    }
}
