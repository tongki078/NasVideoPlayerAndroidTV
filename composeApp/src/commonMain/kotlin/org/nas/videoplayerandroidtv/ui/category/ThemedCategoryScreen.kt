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
import kotlinx.coroutines.*
import org.nas.videoplayerandroidtv.domain.model.Series
import org.nas.videoplayerandroidtv.domain.repository.VideoRepository
import org.nas.videoplayerandroidtv.ui.common.MovieRow
import org.nas.videoplayerandroidtv.*

// 테마 분류용 장르 그룹 정의
private object ThemeConfig {
    val ACTION_FANTASY = listOf(16, 10759, 10765, 28, 12, 14)
    val COMEDY_LIFE = listOf(35, 10762)
    val MYSTERY_THRILLER = listOf(9648, 53, 27)
    val DRAMA_ROMANCE = listOf(18, 10749)
    val KIDS_FAMILY = listOf(10762, 10751)
}

// UI용 데이터 클래스 정의 (고유 ID 포함)
private data class ThemeSection(val id: String, val title: String, val seriesList: List<Series>)

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

    val themedSections = remember(selectedMode, categoryName) { mutableStateOf<List<ThemeSection>>(emptyList()) }
    var isInitialLoading by remember(selectedMode, categoryName) { mutableStateOf(true) }

    LaunchedEffect(selectedMode, categoryName) {
        isInitialLoading = true
        themedSections.value = emptyList()
        
        try {
            val allSeries = withContext(Dispatchers.Default) {
                when {
                    isAirScreen -> if (selectedMode == 0) repository.getAnimationsAir() else repository.getDramasAir()
                    isAniScreen -> if (selectedMode == 0) repository.getAnimationsRaftel(5000, 0) else repository.getAnimationsSeries(5000, 0)
                    else -> emptyList()
                }
            }

            if (allSeries.isEmpty()) {
                isInitialLoading = false
                return@LaunchedEffect
            }

            val currentList = mutableListOf<ThemeSection>()

            val actionList = mutableListOf<Series>()
            val comedyList = mutableListOf<Series>()
            val mysteryList = mutableListOf<Series>()
            val dramaList = mutableListOf<Series>()
            val kidsList = mutableListOf<Series>()
            val specialList = mutableListOf<Series>()
            val remainingList = mutableListOf<Series>()

            allSeries.forEach { series ->
                val metadata = tmdbCache[series.title] ?: tmdbCache["ani_${series.title}"]
                val genreIds = metadata?.genreIds ?: emptyList()
                
                when {
                    genreIds.any { it in ThemeConfig.ACTION_FANTASY } -> actionList.add(series)
                    genreIds.any { it in ThemeConfig.COMEDY_LIFE } -> comedyList.add(series)
                    genreIds.any { it in ThemeConfig.MYSTERY_THRILLER } -> mysteryList.add(series)
                    genreIds.any { it in ThemeConfig.DRAMA_ROMANCE } -> dramaList.add(series)
                    genreIds.any { it in ThemeConfig.KIDS_FAMILY } -> kidsList.add(series)
                    series.episodes.size <= 1 -> specialList.add(series)
                    else -> remainingList.add(series)
                }
            }

            if (actionList.isNotEmpty()) currentList.add(ThemeSection("action", "시간 순삭! 액션 & 판타지", actionList.shuffled().take(40)))
            if (comedyList.isNotEmpty()) currentList.add(ThemeSection("comedy", "유쾌한 웃음! 코미디 & 일상", comedyList.shuffled().take(40)))
            if (mysteryList.isNotEmpty()) currentList.add(ThemeSection("mystery", "손에 땀을 쥐는 스릴러 & 미스터리", mysteryList.shuffled().take(40)))
            if (dramaList.isNotEmpty()) currentList.add(ThemeSection("drama", "설레는 로맨스 & 감동 드라마", dramaList.shuffled().take(40)))
            if (kidsList.isNotEmpty()) currentList.add(ThemeSection("kids", "아이와 함께! 키즈 & 가족", kidsList.shuffled().take(40)))
            if (specialList.isNotEmpty()) currentList.add(ThemeSection("special", "부담 없이 즐기는 극장판 & 단편", specialList.shuffled().take(40)))

            val finalRemaining = (remainingList + (allSeries - currentList.flatMap { it.seriesList }.toSet())).distinctBy { it.title }.shuffled()
            val remainingChunks = finalRemaining.chunked(50)

            themedSections.value = currentList.toList()
            isInitialLoading = false

            remainingChunks.forEachIndexed { index, chunk ->
                delay(800)
                // [수정] 고유 ID는 내부적으로만 사용하고, 제목은 통일합니다.
                currentList.add(ThemeSection("remaining_$index", "놓치면 아쉬운 더 많은 작품들", chunk))
                themedSections.value = currentList.toList()
            }

        } catch (e: Exception) {
            if (e is CancellationException) throw e
            isInitialLoading = false
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
            if (!isInitialLoading && themedSections.value.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("영상을 불러올 수 없습니다.", color = Color.Gray)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(), 
                    state = lazyListState, 
                    contentPadding = PaddingValues(bottom = 100.dp)
                ) {
                    // [수정] 고유 ID를 key로 사용합니다.
                    items(themedSections.value, key = { it.id }) { section ->
                        MovieRow(title = section.title, seriesList = section.seriesList, onSeriesClick = onSeriesClick)
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
