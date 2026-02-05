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

// 한글 초성 추출 함수 (특수문자 및 괄호 무시 로직 추가)
private fun String.getConsonant(): String {
    val cleaned = this.replace(Regex("""^[\(\[][^\]\)]+[\)\]]\s*"""), "").trim()
    val firstChar = cleaned.firstOrNull() ?: return "#"
    if (firstChar in '가'..'힣') {
        val index = (firstChar - '가') / 588
        val consonants = listOf("ㄱ", "ㄲ", "ㄴ", "ㄷ", "ㄸ", "ㄹ", "ㅁ", "ㅂ", "ㅃ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅉ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ")
        return consonants.getOrElse(index) { "기타" }
    }
    return if (firstChar.isLetterOrDigit()) firstChar.uppercaseChar().toString() else "#"
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

    val themedSections = remember(selectedMode, categoryName) { mutableStateOf<List<Pair<String, List<Series>>>>(emptyList()) }
    var isInitialLoading by remember(selectedMode, categoryName) { mutableStateOf(true) }

    LaunchedEffect(selectedMode, categoryName) {
        isInitialLoading = true
        themedSections.value = emptyList()
        
        try {
            // [획기적 개선] 제목만 있는 경량 데이터이므로 5,000개를 한 번에 가져와서 정렬 문제를 해결합니다.
            val allSeries = withContext(Dispatchers.Default) {
                when {
                    isAirScreen -> if (selectedMode == 0) repository.getAnimationsAir() else repository.getDramasAir()
                    isAniScreen -> {
                        if (selectedMode == 0) repository.getAnimationsRaftel(5000, 0) 
                        else repository.getAnimationsSeries(5000, 0)
                    }
                    else -> emptyList()
                }
            }

            if (allSeries.isEmpty()) {
                isInitialLoading = false
                return@LaunchedEffect
            }

            // 전체 데이터를 초성별로 즉시 그룹화
            val fullGroupedList = withContext(Dispatchers.Default) {
                allSeries.groupBy { it.title.getConsonant() }
                    .map { (consonant, list) -> consonant to list.sortedBy { it.title }.take(100) }
                    .sortedWith(compareBy<Pair<String, List<Series>>> { (key, _) -> 
                        val first = key.firstOrNull() ?: ' '
                        when {
                            first in 'ㄱ'..'ㅎ' -> 1
                            first.isDigit() -> 2
                            first in 'A'..'Z' || first in 'a'..'z' -> 3
                            else -> 4
                        }
                    }.thenBy { it.first })
            }

            // [안정성 유지] 첫 1개 섹션만 즉시 노출하고, 나머지는 0.8초 간격으로 추가하여 DB 크래시 방지
            val currentList = mutableListOf<Pair<String, List<Series>>>()
            if (fullGroupedList.isNotEmpty()) {
                currentList.add(fullGroupedList[0])
                themedSections.value = currentList.toList()
                isInitialLoading = false
            }
            
            if (fullGroupedList.size > 1) {
                fullGroupedList.drop(1).forEach { section ->
                    delay(800) 
                    currentList.add(section)
                    themedSections.value = currentList.toList()
                }
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
                    items(themedSections.value, key = { it.first }) { (title, seriesList) ->
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
