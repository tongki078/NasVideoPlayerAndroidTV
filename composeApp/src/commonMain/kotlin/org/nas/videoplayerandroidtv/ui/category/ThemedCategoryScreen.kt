package org.nas.videoplayerandroidtv.ui.category

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import org.nas.videoplayerandroidtv.domain.model.Series
import org.nas.videoplayerandroidtv.domain.model.Movie
import org.nas.videoplayerandroidtv.domain.repository.VideoRepository
import org.nas.videoplayerandroidtv.ui.common.MovieRow
import org.nas.videoplayerandroidtv.*

// TMDB 장르 ID 매핑
private val TMDB_GENRE_MAP = mapOf(
    28 to "액션", 12 to "모험", 16 to "애니메이션", 35 to "코미디", 80 to "범죄",
    99 to "다큐멘터리", 18 to "드라마", 10751 to "가족", 14 to "판타지", 36 to "역사",
    27 to "공포", 10402 to "음악", 9648 to "미스터리", 10749 to "로맨스", 878 to "SF",
    10770 to "TV 영화", 53 to "스릴러", 10752 to "전쟁", 37 to "서부",
    10759 to "액션 & 어드벤처", 10762 to "키즈", 10763 to "뉴스", 10764 to "리얼리티",
    10765 to "SF & 판타지", 10766 to "소프", 10767 to "토크", 10768 to "전쟁 & 정치"
)

/**
 * 포스터 유무에 따른 정렬 (3단계 등급 시스템)
 */
private fun List<Series>.sortByPoster(isAnimation: Boolean): List<Series> {
    return this.sortedWith(
        compareByDescending<Series> { series ->
            val cacheKey = if (isAnimation) "ani_${series.title}" else series.title
            val metadata = tmdbCache[cacheKey]
            when {
                metadata?.posterUrl != null -> 3 // 1순위: 포스터 있음 (무조건 앞)
                metadata == null -> 2           // 2순위: 아직 검색 전 (포스터가 있을 가능성 있음)
                else -> 1                        // 3순위: 포스터 없음 확인됨 (무조건 맨 뒤)
            }
        }.thenByDescending { it.episodes.size } // 에피소드 많은 인기작 차선순위
    )
}

@Composable
fun ThemedCategoryScreen(
    categoryName: String,
    rootPath: String,
    repository: VideoRepository,
    selectedMode: Int,
    onModeChange: (Int) -> Unit,
    lazyListState: LazyListState = rememberLazyListState(),
    onSeriesClick: (Series) -> Unit
) {
    val isAirScreen = categoryName == "방송중"
    val isAniScreen = categoryName == "애니메이션"
    val isMovieScreen = categoryName == "영화"
    val isForeignTvScreen = categoryName == "외국TV"
    val isKoreanTvScreen = categoryName == "국내TV"

    val modes = when {
        isAirScreen -> listOf("라프텔 애니메이션", "드라마")
        isAniScreen -> listOf("라프텔", "시리즈")
        isMovieScreen -> listOf("최신", "UHD", "제목")
        isForeignTvScreen -> listOf("중국 드라마", "일본 드라마", "미국 드라마", "기타국가 드라마", "다큐")
        isKoreanTvScreen -> listOf("드라마", "시트콤", "교양", "다큐멘터리", "예능")
        else -> emptyList()
    }
    
    var themedSections by remember(selectedMode, categoryName) { mutableStateOf<List<Pair<String, List<Series>>>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var isInitialLoading by remember(selectedMode, categoryName) { mutableStateOf(!isAirScreen) }
    var currentOffset by remember(selectedMode, categoryName) { mutableIntStateOf(0) }
    val pageSize = 12 

    // 일반 카테고리 로딩: 병렬 처리 + 정밀한 선제적 분석
    val loadNextPage: suspend () -> Unit = {
        if (!isLoading) {
            isLoading = true
            try {
                val currentRootPath = when {
                    isAniScreen && selectedMode == 0 -> "애니메이션/라프텔"
                    isAniScreen && selectedMode == 1 -> "애니메이션/시리즈"
                    isMovieScreen && selectedMode == 0 -> "영화/최신"
                    isMovieScreen && selectedMode == 1 -> "영화/UHD"
                    isMovieScreen && selectedMode == 2 -> "영화/제목"
                    isForeignTvScreen && selectedMode == 0 -> "외국TV/중국 드라마"
                    isForeignTvScreen && selectedMode == 1 -> "외국TV/일본 드라마"
                    isForeignTvScreen && selectedMode == 2 -> "외국TV/미국 드라마"
                    isForeignTvScreen && selectedMode == 3 -> "외국TV/기타국가 드라마"
                    isForeignTvScreen && selectedMode == 4 -> "외국TV/다큐"
                    isKoreanTvScreen && selectedMode == 0 -> "국내TV/드라마"
                    isKoreanTvScreen && selectedMode == 1 -> "국내TV/시트콤"
                    isKoreanTvScreen && selectedMode == 2 -> "국내TV/교양"
                    isKoreanTvScreen && selectedMode == 3 -> "국내TV/다큐멘터리"
                    isKoreanTvScreen && selectedMode == 4 -> "국내TV/예능"
                    else -> rootPath
                }

                val themeFolders = repository.getCategoryList(currentRootPath, limit = pageSize, offset = currentOffset)
                
                coroutineScope {
                    themeFolders.forEachIndexed { index, folder ->
                        launch {
                            val folderPath = "$currentRootPath/${folder.name}"
                            val content = repository.getCategoryList(folderPath)
                            val seriesList = content.flatMap { it.movies }.groupBySeries(folderPath)
                            
                            if (seriesList.isNotEmpty()) {
                                val isAni = isAniScreen || isAirScreen || folderPath.contains("애니", ignoreCase = true)
                                
                                // [필수] 화면 노출 전 상위 40개 메타데이터 선제적 조회 (등급 판별을 위함)
                                seriesList.take(40).chunked(8).forEach { batch ->
                                    batch.map { s -> async { fetchTmdbMetadata(s.title, isAnimation = isAni) } }.awaitAll()
                                }
                                
                                val sortedList = seriesList.sortByPoster(isAni)
                                val sectionTitle = getRandomThemeName(folder.name, currentOffset + index, currentRootPath.contains("영화"), categoryName)
                                
                                withContext(Dispatchers.Main) {
                                    if (themedSections.none { it.first == sectionTitle }) {
                                        val newList = (themedSections + (sectionTitle to sortedList)).distinctBy { it.first }
                                        // 포스터가 확인된 섹션을 위로 정렬하여 섹션 단위 품질 보정
                                        themedSections = newList.sortedByDescending { section ->
                                            val firstS = section.second.firstOrNull() ?: return@sortedByDescending 0
                                            val key = if (isAni) "ani_${firstS.title}" else firstS.title
                                            if (tmdbCache[key]?.posterUrl != null) 1 else 0
                                        }
                                        isInitialLoading = false
                                    }
                                }
                            }
                        }
                    }
                }
                currentOffset += pageSize
            } finally {
                isLoading = false
                isInitialLoading = false
            }
        }
    }

    // 방송중 로직
    LaunchedEffect(selectedMode, categoryName) {
        if (!isAirScreen) {
            themedSections = emptyList()
            currentOffset = 0
            isInitialLoading = true
            loadNextPage()
            return@LaunchedEffect
        }

        themedSections = emptyList()
        isLoading = true

        try {
            val isAnimationMode = selectedMode == 0
            val allSeries = (if (isAnimationMode) repository.getAnimations() else repository.getDramas()).shuffled()
            if (allSeries.isEmpty()) return@LaunchedEffect

            val genreGroups = mutableMapOf<String, MutableList<Series>>()
            val noGenreList = mutableListOf<Series>()

            fun updateUI() {
                val finalSections = mutableListOf<Pair<String, List<Series>>>()
                genreGroups.toList().sortedByDescending { it.second.size }.forEach { (genre, list) ->
                    finalSections.add("$genre 시리즈" to list.sortByPoster(isAnimationMode))
                }
                if (noGenreList.isNotEmpty()) {
                    val label = if (isAnimationMode) "전체 애니메이션" else "전체 드라마"
                    finalSections.add(label to noGenreList.sortByPoster(isAnimationMode))
                }
                themedSections = finalSections
            }

            // 캐시 데이터 분류 (0.1초 내 노출)
            val (cachedWithPoster, others) = allSeries.partition { series ->
                val cacheKey = if (isAnimationMode) "ani_${series.title}" else series.title
                tmdbCache[cacheKey]?.posterUrl != null
            }

            cachedWithPoster.forEach { series ->
                val cacheKey = if (isAnimationMode) "ani_${series.title}" else series.title
                val cached = tmdbCache[cacheKey]!!
                val genres = cached.genreIds.mapNotNull { TMDB_GENRE_MAP[it] }
                if (genres.isNotEmpty()) genreGroups.getOrPut(genres.first()) { mutableListOf() }.add(series)
                else noGenreList.add(series)
            }
            
            if (noGenreList.size < 15) noGenreList.addAll(others.take(20))
            updateUI()
            isInitialLoading = false
            yield()

            // 나머지 병렬 분석
            others.drop(20).chunked(12).forEach { batch ->
                coroutineScope {
                    batch.map { async { fetchTmdbMetadata(it.title, isAnimation = isAnimationMode) to it } }.awaitAll()
                        .forEach { (meta, s) ->
                            val genres = meta?.genreIds?.mapNotNull { TMDB_GENRE_MAP[it] } ?: emptyList()
                            if (genres.isNotEmpty()) { noGenreList.remove(s); genreGroups.getOrPut(genres.first()) { mutableListOf() }.add(s) }
                            else if (!noGenreList.contains(s)) noGenreList.add(s)
                        }
                }
                updateUI()
                yield()
            }
        } finally {
            isLoading = false
            isInitialLoading = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0F0F0F))) {
        if (modes.isNotEmpty()) {
            LazyRow(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(modes.size) { index -> CategoryTabItem(text = modes[index], isSelected = selectedMode == index, onClick = { onModeChange(index) }) }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (themedSections.isEmpty() && !isLoading && !isInitialLoading) {
                Box(Modifier.fillMaxSize(), Alignment.Center) { Text("데이터 로딩 중...", color = Color.Gray) }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize(), state = lazyListState, contentPadding = PaddingValues(bottom = 100.dp)) {
                    items(themedSections, key = { it.first }) { (title, seriesList) ->
                        MovieRow(title = title, seriesList = seriesList, onSeriesClick = onSeriesClick)
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryTabItem(text: String, isSelected: Boolean, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val backgroundColor by animateColorAsState(when { isFocused -> Color.White; isSelected -> Color.Red; else -> Color.Gray.copy(alpha = 0.2f) })
    val textColor by animateColorAsState(when { isFocused -> Color.Black; isSelected -> Color.White; else -> Color.Gray })
    Box(modifier = Modifier.clip(RoundedCornerShape(24.dp)).background(backgroundColor).onFocusChanged { isFocused = it.isFocused }.focusable().clickable { onClick() }.padding(horizontal = 20.dp, vertical = 8.dp).scale(if (isFocused) 1.1f else 1.0f), contentAlignment = Alignment.Center) {
        Text(text = text, color = textColor, fontSize = 16.sp, fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium)
    }
}

private fun List<Movie>.groupBySeries(basePath: String? = null): List<Series> = 
    this.groupBy { movie -> 
        var t = movie.title.cleanTitle(includeYear = false)
        t = t.replace(Regex("""^\s*[\(\[【](?:더빙|자막|무삭제|완결)[\)\]】]\s*"""), "")
        t = t.replace(Regex("""(?i)[.\s_-]+(?:S\d+E\d+|S\d+|E\d+|EP\d+|\d+화|\d+회|시즌\d+|\d+기).*"""), "")
        t = t.replace(Regex("""(?i)[.\s_-]+(?:\d{3,4}p|WEB-DL|WEBRip|Bluray|HDRip|BDRip|x26[45]|HEVC|AAC|DTS|KL|60fps).*"""), "")
        t.trim().ifEmpty { movie.title }
    }.map { (title, eps) -> 
        Series(title = title, episodes = eps.sortedBy { it.title }, fullPath = basePath) 
    }.sortedByDescending { it.episodes.size }
