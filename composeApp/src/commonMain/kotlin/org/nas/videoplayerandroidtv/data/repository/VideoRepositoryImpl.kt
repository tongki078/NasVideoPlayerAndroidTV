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

private val REGEX_GROUP_BY_SERIES = Regex("""(?i)[.\s_-]+(?:S\d+E\d+|S\d+|E\d+|EP\d+|\d+화|\d+회|시즌\s*\d+|Season\s*\d+|1기|2기|3기|4기|5기|6기|7기|8기|9기|10기).*""")
private val REGEX_INDEX_FOLDER = Regex("""(?i)^\s*([0-9A-Z가-힣ㄱ-ㅎ]|0Z|0-Z|가-하|[0-9]-[0-9]|[A-Z]-[A-Z]|[가-힣]-[가-힣])\s*$""")
private val REGEX_YEAR_FOLDER = Regex("""(?i)^\s*(\(\d{4}\)|\d{4})\s*$""") 
private val REGEX_SEASON_FOLDER = Regex("""(?i)Season\s*\d+|시즌\s*\d+|Part\s*\d+|파트\s*\d+|\d+기""")

class VideoRepositoryImpl : VideoRepository {
    private val client = NasApiClient.client
    private val baseUrl = NasApiClient.BASE_URL

    override suspend fun getHomeRecommendations(): List<HomeSection> = coroutineScope {
        try {
            val homeDeferred = async { 
                client.get("$baseUrl/home") { parameter("lite", "true") }.body<List<HomeSection>>() 
            }
            val movieDeferred = async { getLatestMovies(20, 0) }
            val dramaDeferred = async { getLatestForeignTV() }

            val homeSections = homeDeferred.await().toMutableList()
            val latestMovies = movieDeferred.await()
            val latestDramas = dramaDeferred.await()

            if (latestMovies.isNotEmpty()) {
                homeSections.add(HomeSection(
                    title = "방금 올라온 최신 영화",
                    items = latestMovies.take(20).map { it.toCategory() }
                ))
            }

            if (latestDramas.isNotEmpty()) {
                homeSections.add(HomeSection(
                    title = "지금 가장 인기 있는 시리즈",
                    items = latestDramas.take(20).map { it.toCategory() }
                ))
            }

            homeSections
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }
    }

    private fun Series.toCategory() = Category(
        name = this.title,
        path = this.fullPath,
        posterPath = this.posterPath,
        genreIds = this.genreIds
    )

    override suspend fun getCategoryList(path: String, limit: Int, offset: Int): List<Category> = try {
        val categories: List<Category> = client.get("$baseUrl/list") {
            parameter("path", path)
            parameter("limit", limit)
            parameter("offset", offset)
            parameter("lite", "true")
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
            val results: List<Category> = client.get("$baseUrl/search") {
                parameter("q", query)
                parameter("lite", "true")
                if (category != "전체") parameter("category", category)
            }.body()
            results.filter { cat -> 
                val name = cat.name ?: ""
                !REGEX_INDEX_FOLDER.matches(name) && !REGEX_YEAR_FOLDER.matches(name)
            }
            .flatMap { it.movies ?: emptyList() }
            .groupBySeries()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }
    }

    override suspend fun getLatestMovies(limit: Int, offset: Int): List<Series> = getGenericData("/movies_latest", 500, offset, "영화")
    override suspend fun getPopularMovies(limit: Int, offset: Int): List<Series> = getLatestMovies(limit, offset)
    override suspend fun getUhdMovies(limit: Int, offset: Int): List<Series> = getGenericData("/movies_uhd", 500, offset, "영화")
    override suspend fun getMoviesByTitle(limit: Int, offset: Int): List<Series> = getGenericData("/movies_title", 500, offset, "영화")

    override suspend fun getAnimations(): List<Series> = withContext(Dispatchers.Default) {
        try {
            val results: List<Category> = client.get("$baseUrl/animations") { parameter("lite", "true") }.body()
            results.flatMap { it.movies ?: emptyList() }.groupBySeries()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }
    }

    override suspend fun getAnimationsAll(): List<Series> = withContext(Dispatchers.Default) {
        try {
            val response = client.get("$baseUrl/animations_all") { parameter("lite", "true") }
            if (response.status == HttpStatusCode.OK) {
                val categories: List<Category> = response.body()
                categories.filter { cat -> 
                    val name = cat.name ?: ""
                    !REGEX_INDEX_FOLDER.matches(name) && !REGEX_YEAR_FOLDER.matches(name)
                }.groupCategoriesToSeries("애니메이션")
            } else { emptyList() }
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun getAnimationsRaftel(limit: Int, offset: Int): List<Series> = withContext(Dispatchers.Default) {
        try {
            val categories: List<Category> = client.get("$baseUrl/anim_raftel") {
                parameter("limit", 500)
                parameter("offset", offset)
                parameter("lite", "true")
            }.body()
            categories.filter { cat -> 
                val name = cat.name ?: ""
                !REGEX_INDEX_FOLDER.matches(name) && !REGEX_YEAR_FOLDER.matches(name)
            }.groupCategoriesToSeries("애니메이션")
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun getAnimationsSeries(limit: Int, offset: Int): List<Series> = withContext(Dispatchers.Default) {
        try {
            val categories: List<Category> = client.get("$baseUrl/anim_series") {
                parameter("limit", 500)
                parameter("offset", offset)
                parameter("lite", "true")
            }.body()
            categories.filter { cat -> 
                val name = cat.name ?: ""
                !REGEX_INDEX_FOLDER.matches(name) && !REGEX_YEAR_FOLDER.matches(name)
            }.groupCategoriesToSeries("애니메이션")
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun getDramas(): List<Series> = withContext(Dispatchers.Default) {
        try {
            val results: List<Category> = client.get("$baseUrl/dramas") { parameter("lite", "true") }.body()
            results.flatMap { it.movies ?: emptyList() }.groupBySeries()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }
    }
    
    override suspend fun getAnimationsAir(): List<Series> = getAirDataInternal("라프텔")
    override suspend fun getDramasAir(): List<Series> = getAirDataInternal("드라마")
    
    override suspend fun getLatestForeignTV(): List<Series> = getGroupedData("/foreigntv", 500, 0, "외국TV")
    override suspend fun getPopularForeignTV(): List<Series> = getLatestForeignTV()

    override suspend fun getFtvUs(limit: Int, offset: Int): List<Series> = getGroupedData("/ftv_us", 500, 0, "외국TV", "미국 드라마")
    override suspend fun getFtvCn(limit: Int, offset: Int): List<Series> = getGroupedData("/ftv_cn", 500, 0, "외국TV", "중국 드라마")
    override suspend fun getFtvJp(limit: Int, offset: Int): List<Series> = getGroupedData("/ftv_jp", 500, 0, "외국TV", "일본 드라마")
    override suspend fun getFtvDocu(limit: Int, offset: Int): List<Series> = getGroupedData("/ftv_docu", 500, 0, "외국TV", "다큐")
    override suspend fun getFtvEtc(limit: Int, offset: Int): List<Series> = getGroupedData("/ftv_etc", 500, 0, "외국TV", "기타국가 드라마")

    override suspend fun getLatestKoreanTV(): List<Series> = getGroupedData("/koreantv", 500, 0, "국내TV")
    override suspend fun getPopularKoreanTV(): List<Series> = getLatestKoreanTV()

    override suspend fun getKtvDrama(limit: Int, offset: Int): List<Series> = getGroupedData("/ktv_drama", 500, 0, "국내TV", "드라마")
    override suspend fun getKtvSitcom(limit: Int, offset: Int): List<Series> = getGroupedData("/ktv_sitcom", 500, 0, "국내TV", "시트콤")
    override suspend fun getKtvVariety(limit: Int, offset: Int): List<Series> = getGroupedData("/ktv_variety", 500, 0, "국내TV", "예능")
    override suspend fun getKtvEdu(limit: Int, offset: Int): List<Series> = getGroupedData("/ktv_edu", 500, 0, "국내TV", "교양")
    override suspend fun getKtvDocu(limit: Int, offset: Int): List<Series> = getGroupedData("/ktv_docu", 500, 0, "국내TV", "다큐멘터리")

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
                    val name = cat.name ?: ""
                    if (REGEX_INDEX_FOLDER.matches(name) || REGEX_YEAR_FOLDER.matches(name)) return@filter false
                    
                    if (filterKeyword != null) {
                        val target = filterKeyword.replace(" ", "").lowercase()
                        val pathNormalized = cat.path?.replace(" ", "")?.lowercase() ?: ""
                        pathNormalized.startsWith(target) || name.replace(" ", "").lowercase().startsWith(target)
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
                categories.filter { cat -> 
                    val name = cat.name ?: ""
                    !REGEX_INDEX_FOLDER.matches(name) && !REGEX_YEAR_FOLDER.matches(name)
                }.map { cat ->
                    Series(
                        title = cat.name ?: "Unknown",
                        episodes = emptyList(),
                        fullPath = if (cat.path != null) "$prefix/${cat.path}" else null,
                        genreIds = cat.genreIds ?: emptyList(),
                        posterPath = cat.posterPath
                    )
                }
            } else emptyList()
        } catch (e: Exception) { emptyList() }
    }

    private suspend fun getAirDataInternal(filterKeyword: String? = null): List<Series> = withContext(Dispatchers.Default) {
        try {
            val response = client.get("$baseUrl/air") { parameter("lite", "true") }
            if (response.status != HttpStatusCode.OK) return@withContext emptyList()
            
            val categories: List<Category> = response.body()
            val normalizedKeyword = filterKeyword?.replace(" ", "")?.lowercase()
            
            val filtered = categories.filter { cat -> 
                val name = cat.name ?: ""
                if (REGEX_INDEX_FOLDER.matches(name) || REGEX_YEAR_FOLDER.matches(name)) return@filter false
                if (normalizedKeyword == null) true
                else {
                    val n = name.replace(" ", "").lowercase()
                    val p = (cat.path ?: "").replace(" ", "").lowercase()
                    n.contains(normalizedKeyword) || p.contains(normalizedKeyword)
                }
            }
            
            filtered.map { cat ->
                Series(
                    title = cat.name ?: "Unknown",
                    episodes = emptyList(),
                    fullPath = if (cat.path != null) "방송중/${cat.path}" else null,
                    genreIds = cat.genreIds ?: emptyList(),
                    posterPath = cat.posterPath
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun List<Category>.groupCategoriesToSeries(basePathPrefix: String = ""): List<Series> {
        val showGroups = mutableMapOf<String, MutableList<Category>>()
        val titleCache = mutableMapOf<String, String>() // 정제된 제목 캐싱
        
        for (cat in this) {
            val path = cat.path ?: continue
            val parts = path.split("/")
            
            var showTitle = ""
            var showPath = ""
            
            val skipFolders = listOf(
                "미국 드라마", "중국 드라마", "일본 드라마", "기타국가 드라마", "다큐", "드라마", "시트콤", "예능", "교양", "다큐멘터리",
                "라프텔", "시리즈", "애니메이션", "국내TV", "외국TV", "영화", "방송중"
            )
            
            var found = false
            val currentPathParts = mutableListOf<String>()
            for (part in parts) {
                currentPathParts.add(part)
                if (!found) {
                    if (part in skipFolders || REGEX_INDEX_FOLDER.matches(part) || REGEX_YEAR_FOLDER.matches(part) || REGEX_SEASON_FOLDER.containsMatchIn(part)) {
                        continue
                    }
                    showTitle = part
                    showPath = currentPathParts.joinToString("/")
                    found = true
                }
            }
            
            if (showTitle.isEmpty()) {
                showTitle = cat.name ?: "Unknown"
                showPath = path
            }
            
            // 제목 정제 비용 최적화
            val groupKey = titleCache.getOrPut(showTitle) {
                val cleaned = showTitle.cleanTitle(false).replace(REGEX_GROUP_BY_SERIES, "").trim()
                if (cleaned.isBlank()) showTitle else cleaned
            }
            
            showGroups.getOrPut(groupKey) { mutableListOf() }.add(cat.copy(path = showPath))
        }
        
        return showGroups.map { (title, cats) ->
            // 메타데이터가 가장 풍부한 카테고리 선택
            val bestCat = cats.maxByOrNull { 
                (if (it.posterPath != null) 2 else 0) + (if (!it.genreIds.isNullOrEmpty()) 1 else 0)
            } ?: cats.first()

            val fullPath = if (basePathPrefix.isNotEmpty()) "$basePathPrefix/${bestCat.path}" else bestCat.path
            Series(
                title = title, 
                episodes = emptyList(), 
                fullPath = fullPath,
                genreIds = bestCat.genreIds ?: emptyList(),
                posterPath = bestCat.posterPath
            )
        }
        .filter { !REGEX_INDEX_FOLDER.matches(it.title) && !REGEX_YEAR_FOLDER.matches(it.title) }
        .sortedBy { it.title }
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
