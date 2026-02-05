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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ktor.http.*
import kotlinx.coroutines.*
import org.nas.videoplayerandroidtv.domain.model.Series
import org.nas.videoplayerandroidtv.domain.repository.VideoRepository
import org.nas.videoplayerandroidtv.ui.common.MovieRow
import org.nas.videoplayerandroidtv.*

private val TMDB_GENRE_MAP = mapOf(
    16 to "애니메이션", 28 to "액션", 12 to "모험", 35 to "코미디", 80 to "범죄",
    99 to "다큐멘터리", 18 to "드라마", 10751 to "가족", 14 to "판타지", 36 to "역사",
    27 to "공포", 10402 to "음악", 9648 to "미스터리", 10749 to "로맨스", 878 to "SF",
    10770 to "TV 영화", 53 to "스릴러", 10752 to "전쟁", 37 to "서부",
    10759 to "액션 & 어드벤처", 10762 to "키즈", 10765 to "SF & 판타지"
)

private fun List<Series>.sortByPoster(isAnimation: Boolean): List<Series> {
    return this.sortedWith(
        compareByDescending<Series> { series ->
            val cacheKey = if (isAnimation) "ani_${series.title}" else series.title
            val metadata = tmdbCache[cacheKey]
            when {
                metadata?.posterUrl != null -> 3
                metadata == null -> 2
                else -> 1
            }
        }.thenByDescending { it.episodes.size }
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
    val isAniScreen = categoryName == "애니메이션"
    val isAirScreen = categoryName == "방송중"
    
    val modes = when {
        isAirScreen -> listOf("라프텔 애니메이션", "드라마")
        isAniScreen -> listOf("라프텔", "시리즈")
        else -> emptyList()
    }

    var themedSections by remember(selectedMode, categoryName) { mutableStateOf<List<Pair<String, List<Series>>>>(emptyList()) }
    var isInitialLoading by remember(selectedMode, categoryName) { mutableStateOf(true) }

    LaunchedEffect(selectedMode, categoryName) {
        isInitialLoading = true
        themedSections = emptyList()
        
        try {
            // [구조적 개선] Repository가 이미 카테고리/모드별로 정제된 데이터를 반환합니다.
            val finalItems = when {
                isAirScreen -> {
                    if (selectedMode == 0) repository.getAnimationsAir()
                    else repository.getDramasAir()
                }
                isAniScreen -> {
                    val allAni = repository.getAnimationsAll()
                    allAni.filter { series ->
                        val path = (series.fullPath ?: "").lowercase()
                        val isRaftel = path.contains("라프텔") || path.contains("raftel")
                        if (selectedMode == 0) isRaftel else !isRaftel
                    }
                }
                else -> emptyList()
            }

            if (finalItems.isEmpty()) {
                isInitialLoading = false
                return@LaunchedEffect
            }

            // 장르별 분류 로직은 동일하게 유지하되, 데이터 안정성 강화
            val genreGroups = mutableMapOf<String, MutableList<Series>>()
            coroutineScope {
                finalItems.chunked(12).forEach { batch ->
                    ensureActive()
                    batch.map { s -> async { fetchTmdbMetadata(s.title, isAnimation = isAniScreen || isAirScreen) to s } }
                        .awaitAll()
                        .forEach { (meta, s) ->
                            val genres = meta?.genreIds?.mapNotNull { TMDB_GENRE_MAP[it] } ?: emptyList()
                            val groupName = genres.firstOrNull() ?: "추천"
                            genreGroups.getOrPut(groupName) { mutableListOf() }.add(s)
                        }
                    
                    themedSections = genreGroups.map { (genre, list) -> 
                        "$genre ${if (isAirScreen && selectedMode == 1) "드라마" else "애니메이션"}" to list.sortByPoster(true) 
                    }.sortedByDescending { it.second.size }
                    
                    isInitialLoading = false
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            isInitialLoading = false
        } finally {
            withContext(NonCancellable) {
                isInitialLoading = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0F0F0F))) {
        if (modes.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp, start = 48.dp, end = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(modes.size) { index ->
                    CategoryTabItem(text = modes[index], isSelected = selectedMode == index, onClick = { onModeChange(index) })
                }
            }
        }

        Box(modifier = Modifier.fillMaxWidth().height(4.dp)) {
            if (isInitialLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Color.Red, trackColor = Color.Transparent)
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (!isInitialLoading && themedSections.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("영상을 불러올 수 없습니다.", color = Color.Gray, textAlign = TextAlign.Center)
                }
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
    val backgroundColor by animateColorAsState(if (isFocused) Color.White else if (isSelected) Color.Red else Color.Gray.copy(alpha = 0.2f))
    val textColor by animateColorAsState(if (isFocused) Color.Black else if (isSelected) Color.White else Color.Gray)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(backgroundColor)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .scale(if (isFocused) 1.1f else 1.0f),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = textColor, fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium, fontSize = 15.sp)
    }
}
