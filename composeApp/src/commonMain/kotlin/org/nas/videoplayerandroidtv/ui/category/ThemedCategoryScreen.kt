package org.nas.videoplayerandroidtv.ui.category

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
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
import org.nas.videoplayerandroidtv.domain.repository.VideoRepository
import org.nas.videoplayerandroidtv.ui.common.MovieRow
import org.nas.videoplayerandroidtv.*

@Composable
fun ThemedCategoryScreen(
    categoryName: String,
    rootPath: String,
    repository: VideoRepository,
    selectedMode: Int,
    onModeChange: (Int) -> Unit,
    cache: MutableMap<String, List<ThemeSection>>, 
    lazyListState: LazyListState = rememberLazyListState(),
    onSeriesClick: (Series) -> Unit
) {
    val isMovieScreen = categoryName == "영화"
    val isAniScreen = categoryName == "애니메이션"
    val isAirScreen = categoryName == "방송중"
    val isForeignTVScreen = categoryName == "외국TV"
    val isKoreanTVScreen = categoryName == "국내TV"

    val modes = when {
        isAirScreen -> listOf("라프텔 애니메이션", "드라마")
        isAniScreen -> listOf("라프텔", "시리즈")
        isMovieScreen -> listOf("제목", "UHD", "최신")
        isForeignTVScreen -> listOf("미국 드라마", "일본 드라마", "중국 드라마", "기타국가 드라마", "다큐")
        isKoreanTVScreen -> listOf("드라마", "시트콤", "예능", "교양", "다큐멘터리")
        else -> emptyList()
    }

    val cacheKey = "category_${categoryName}_mode_${selectedMode}"
    
    var themedSections by remember(cacheKey) { 
        mutableStateOf(cache[cacheKey] ?: emptyList()) 
    }
    var isLoading by remember(cacheKey) { mutableStateOf(themedSections.isEmpty()) }

    LaunchedEffect(cacheKey) {
        if (themedSections.isNotEmpty()) return@LaunchedEffect
        
        isLoading = true
        try {
            // 1. 네트워크 로드 (IO 스레드)
            val result = withContext(Dispatchers.IO) {
                val limit = 300 // 충분히 많은 양을 가져옴
                when {
                    isMovieScreen -> when (selectedMode) {
                        0 -> repository.getMoviesByTitle(limit, 0)
                        1 -> repository.getUhdMovies(limit, 0)
                        else -> repository.getLatestMovies(limit, 0)
                    }
                    isAniScreen -> if (selectedMode == 0) repository.getAnimationsRaftel(limit, 0) else repository.getAnimationsSeries(limit, 0)
                    isAirScreen -> if (selectedMode == 0) repository.getAnimationsAir() else repository.getDramasAir()
                    isForeignTVScreen -> when (selectedMode) {
                        0 -> repository.getFtvUs(limit, 0)   // 미국 드라마
                        1 -> repository.getFtvJp(limit, 0)   // 일본 드라마
                        2 -> repository.getFtvCn(limit, 0)   // 중국 드라마
                        3 -> repository.getFtvEtc(limit, 0)  // 기타국가 드라마
                        4 -> repository.getFtvDocu(limit, 0) // 다큐
                        else -> emptyList()
                    }
                    isKoreanTVScreen -> when (selectedMode) {
                        0 -> repository.getKtvDrama(limit, 0)
                        1 -> repository.getKtvSitcom(limit, 0)
                        2 -> repository.getKtvVariety(limit, 0)
                        3 -> repository.getKtvEdu(limit, 0)
                        4 -> repository.getKtvDocu(limit, 0)
                        else -> emptyList()
                    }
                    else -> emptyList()
                }
            }

            // 2. 데이터 분류 연산 (Default 스레드)
            val sections = withContext(Dispatchers.Default) {
                // [수정] 모든 세부 카테고리 탭에서는 '테마 분류' 대신 '전체 목록'으로 표시
                // 사용자가 폴더를 선택해서 들어온 것이므로 분류 없이 전체를 보여주는 것이 정확함
                if (result.isNotEmpty()) {
                    listOf(ThemeSection("all_list", "전체 목록", result))
                } else {
                    emptyList()
                }
            }

            themedSections = sections
            cache[cacheKey] = sections
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isLoading = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (modes.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp, start = 48.dp, end = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(modes.size) { index ->
                    CategoryTabItem(text = modes[index], isSelected = selectedMode == index, onClick = { onModeChange(index) })
                }
            }
        }

        if (isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp), color = Color.White.copy(alpha = 0.5f), trackColor = Color.Transparent)

        Box(modifier = Modifier.fillMaxSize()) {
            if (!isLoading && themedSections.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("영상이 없습니다.", color = Color.Gray)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize(), state = lazyListState, contentPadding = PaddingValues(top = 0.dp, bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(themedSections, key = { it.id }) { section ->
                        MovieRow(
                            title = section.title, 
                            seriesList = section.seriesList, 
                            repository = repository,
                            onSeriesClick = onSeriesClick
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryTabItem(text: String, isSelected: Boolean, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    
    val backgroundColor by animateColorAsState(targetValue = when { 
        isFocused -> Color.White 
        isSelected -> Color.White.copy(alpha = 0.15f)
        else -> Color.Transparent 
    })
    val textColor by animateColorAsState(targetValue = when { 
        isFocused -> Color.Black 
        isSelected -> Color.White 
        else -> Color.Gray 
    })
    val scale by animateFloatAsState(if (isFocused) 1.1f else 1.0f)

    Box(
        modifier = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(10.dp))
            .background(backgroundColor)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 8.dp), 
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = textColor, fontWeight = if (isFocused || isSelected) FontWeight.Bold else FontWeight.Medium, fontSize = 15.sp)
    }
}
