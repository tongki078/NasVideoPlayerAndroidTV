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

private val REGEX_GROUP_BY_SERIES = Regex("""(?i)[.\s_-]+(?:S\d+E\d+|S\d+|E\d+|EP\d+|\d+화|\d+회|시즌\s*\d+|Season\s*\d+|\d+기|\(\d+\)).*""")
// 0Z, (2017) 같은 폴더를 제목으로 인식하는 문제를 해결하기 위해 정규식 보강
private val REGEX_INDEX_FOLDER = Regex("""^[0-9A-Z가-힣ㄱ-ㅎ]$|^[0-9]-[0-9]$|^[A-Z]-[A-Z]$|^[가-힣]-[가-힣]$|^0Z$|^0-Z$|^가-하$""")
private val REGEX_YEAR_FOLDER = Regex("""^\(\d{4}\)$|^\d{4}$""") // (YYYY) 또는 YYYY 형식의 연도 폴더
private val REGEX_SEASON_FOLDER = Regex("""(?i)Season\s*\d+|시즌\s*\d+|Part\s*\d+|파트\s*\d+|\d+기""")

class VideoRepositoryImpl : VideoRepository {
    private val client = NasApiClient.client
    private val baseUrl = NasApiClient.BASE_URL

    override suspend fun getHomeRecommendations(): List<HomeSection> = try {
        client.get("$baseUrl/home").body()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        emptyList()
    }

    // 상세페이지 조회용: 모든 항목을 가져오며 URL을 보정함
    override suspend fun getCategoryList(path: String, limit: Int, offset: Int): List<Category> = try {
        val categories: List<Category> = client.get("$baseUrl/list") {
            parameter("path", path)
            parameter("limit", limit)
            parameter("offset", offset)
        }.body()
        
        categories.distinctBy { it.name ?: it.path }.map { cat ->
            // 중요: 에피소드 재생을 위해 videoUrl을 완전한 주소로 보정
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
                if (category != "전체") parameter("category", category)
            }.body()
            results.filter { cat -> !REGEX_INDEX_FOLDER.matches(cat.name ?: "") }
                .flatMap { it.movies ?: emptyList() }
                .groupBySeries()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }
    }

    override suspend fun getLatestMovies(limit: Int, offset: Int): List<Series> = getGenericData("/movies_latest", limit, offset, "영화")
    override suspend fun getPopularMovies(limit: Int, offset: Int): List<Series> = getLatestMovies(limit, offset)
    override suspend fun getUhdMovies(limit: Int, offset: Int): List<Series> = getGenericData("/movies_uhd", limit, offset, "영화")
    override suspend fun getMoviesByTitle(limit: Int, offset: Int): List<Series> = getGenericData("/movies_title", limit, offset, "영화")

    override suspend fun getAnimations(): List<Series> = withContext(Dispatchers.Default) {
        try {
            val results: List<Category> = client.get("$baseUrl/animations").body()
            results.flatMap { it.movies ?: emptyList() }.groupBySeries()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }
    }

    override suspend fun getAnimationsAll(): List<Series> = withContext(Dispatchers.Default) {
        try {
            val response = client.get("$baseUrl/animations_all") {
                parameter("lite", "true")
                timeout { requestTimeoutMillis = 30000 }
            }
            if (response.status == HttpStatusCode.OK) {
                val categories: List<Category> = response.body()
                // 애니메이션 탭이므로 "애니메이션" 프리픽스 추가
                categories.filter { cat -> !REGEX_INDEX_FOLDER.matches(cat.name ?: "") }.groupCategoriesToSeries("애니메이션")
            } else { emptyList() }
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun getAnimationsRaftel(limit: Int, offset: Int): List<Series> = withContext(Dispatchers.Default) {
        try {
            val categories: List<Category> = client.get("$baseUrl/anim_raftel") {
                parameter("limit", limit)
                parameter("offset", offset)
                parameter("lite", "true")
            }.body()
            categories.filter { cat -> !REGEX_INDEX_FOLDER.matches(cat.name ?: "") }.groupCategoriesToSeries("애니메이션")
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun getAnimationsSeries(limit: Int, offset: Int): List<Series> = withContext(Dispatchers.Default) {
        try {
            val categories: List<Category> = client.get("$baseUrl/anim_series") {
                parameter("limit", limit)
                parameter("offset", offset)
                parameter("lite", "true")
            }.body()
            categories.filter { cat -> !REGEX_INDEX_FOLDER.matches(cat.name ?: "") }.groupCategoriesToSeries("애니메이션")
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun getDramas(): List<Series> = withContext(Dispatchers.Default) {
        try {
            val results: List<Category> = client.get("$baseUrl/dramas").body()
            results.flatMap { it.movies ?: emptyList() }.groupBySeries()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }
    }
    
    override suspend fun getAnimationsAir(): List<Series> = getAirDataInternal("라프텔")
    override suspend fun getDramasAir(): List<Series> = getAirDataInternal("드라마")
    
    override suspend fun getLatestForeignTV(): List<Series> = getGroupedData("/foreigntv", 1000, 0, "외국TV")
    override suspend fun getPopularForeignTV(): List<Series> = getLatestForeignTV()

    override suspend fun getFtvUs(limit: Int, offset: Int): List<Series> = getGroupedData("/ftv_us", 1000, 0, "외국TV", "미국 드라마")
    override suspend fun getFtvCn(limit: Int, offset: Int): List<Series> = getGroupedData("/ftv_cn", 1000, 0, "외국TV", "중국 드라마")
    override suspend fun getFtvJp(limit: Int, offset: Int): List<Series> = getGroupedData("/ftv_jp", 1000, 0, "외국TV", "일본 드라마")
    override suspend fun getFtvDocu(limit: Int, offset: Int): List<Series> = getGroupedData("/ftv_docu", 1000, 0, "외국TV", "다큐")
    override suspend fun getFtvEtc(limit: Int, offset: Int): List<Series> = getGroupedData("/ftv_etc", 1000, 0, "외국TV", "기타국가 드라마")

    override suspend fun getLatestKoreanTV(): List<Series> = getGroupedData("/koreantv", 1000, 0, "국내TV")
    override suspend fun getPopularKoreanTV(): List<Series> = getLatestKoreanTV()

    override suspend fun getKtvDrama(limit: Int, offset: Int): List<Series> = getGroupedData("/ktv_drama", 1000, 0, "국내TV", "드라마")
    override suspend fun getKtvSitcom(limit: Int, offset: Int): List<Series> = getGroupedData("/ktv_sitcom", 1000, 0, "국내TV", "시트콤")
    override suspend fun getKtvVariety(limit: Int, offset: Int): List<Series> = getGroupedData("/ktv_variety", 1000, 0, "국내TV", "예능")
    override suspend fun getKtvEdu(limit: Int, offset: Int): List<Series> = getGroupedData("/ktv_edu", 1000, 0, "국내TV", "교양")
    override suspend fun getKtvDocu(limit: Int, offset: Int): List<Series> = getGroupedData("/ktv_docu", 1000, 0, "국내TV", "다큐멘터리")

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
                    val catName = cat.name ?: ""
                    if (REGEX_INDEX_FOLDER.matches(catName)) return@filter false
                    
                    if (filterKeyword != null) {
                        val target = filterKeyword.replace(" ", "").lowercase()
                        val pathNormalized = cat.path?.replace(" ", "")?.lowercase() ?: ""
                        pathNormalized.startsWith(target) || catName.replace(" ", "").lowercase().startsWith(target)
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
                categories.filter { cat -> !REGEX_INDEX_FOLDER.matches(cat.name ?: "") }.map { cat ->
                    Series(
                        title = cat.name ?: "Unknown",
                        episodes = emptyList(),
                        // prefix 추가 (예: "영화/제목")
                        fullPath = if (cat.path != null) "$prefix/${cat.path}" else null,
                        genreIds = cat.genreIds ?: emptyList(),
                        posterPath = cat.posterPath
                    )
                }
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun getAirDataInternal(filterKeyword: String? = null): List<Series> = withContext(Dispatchers.Default) {
        try {
            val response = client.get("$baseUrl/air") {
                parameter("lite", "true")
            }
            if (response.status != HttpStatusCode.OK) return@withContext emptyList()
            
            val categories: List<Category> = response.body()
            val normalizedKeyword = filterKeyword?.replace(" ", "")?.lowercase()
            
            val filtered = categories.filter { cat -> 
                if (REGEX_INDEX_FOLDER.matches(cat.name ?: "")) return@filter false
                if (normalizedKeyword == null) true
                else {
                    val name = (cat.name ?: "").replace(" ", "").lowercase()
                    val path = (cat.path ?: "").replace(" ", "").lowercase()
                    name.contains(normalizedKeyword) || path.contains(normalizedKeyword)
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
        } catch (e: Exception) {
            if (e is CancellationException) throw e
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
                "라프텔", "시리즈"
            )
            
            var found = false
            val currentPathParts = mutableListOf<String>()
            for (part in parts) {
                currentPathParts.add(part)
                if (!found) {
                    // 연도 폴더, 인덱스 폴더, 시즌 폴더 등 메타데이터성 폴더를 모두 건너뛴다.
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
            
            val finalTitle = showTitle.cleanTitle(false).replace(REGEX_GROUP_BY_SERIES, "").trim()
            val groupKey = if (finalTitle.isBlank()) showTitle else finalTitle
            
            showGroups.getOrPut(groupKey) { mutableListOf() }.add(cat.copy(path = showPath))
        }
        
        return showGroups.map { (title, cats) ->
            val bestCat = cats.find { it.posterPath != null } ?: cats.first()
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
