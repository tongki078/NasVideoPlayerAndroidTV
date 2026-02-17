package org.nas.videoplayerandroidtv.ui.category

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
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
import org.nas.videoplayerandroidtv.domain.model.HomeSection
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
    cache: MutableMap<String, List<HomeSection>>, // ThemeSection -> HomeSection으로 변경
    lazyListState: LazyListState = rememberLazyListState(),
    onSeriesClick: (Series) -> Unit
) {
    val categoryKey = when (categoryName) {
        "영화" -> "movies"
        "애니메이션" -> "animations_all"
        "방송중" -> "air"
        "외국TV" -> "foreigntv"
        "국내TV" -> "koreantv"
        else -> "movies"
    }

    val modes = when (categoryName) {
        "방송중" -> listOf("라프텔 애니메이션", "드라마")
        "애니메이션" -> listOf("라프텔", "시리즈")
        "영화" -> listOf("제목", "UHD", "최신")
        "외국TV" -> listOf("미국 드라마", "일본 드라마", "중국 드라마", "기타국가 드라마", "다큐")
        "국내TV" -> listOf("드라마", "시트콤", "예능", "교양", "다큐멘터리")
        else -> emptyList()
    }

    val selectedKeyword = modes.getOrNull(selectedMode)
    val cacheKey = "cat_${categoryKey}_kw_${selectedKeyword}"
    
    var themedSections by remember(cacheKey) { 
        mutableStateOf(cache[cacheKey] ?: emptyList()) 
    }
    var isLoading by remember(cacheKey) { mutableStateOf(themedSections.isEmpty()) }

    LaunchedEffect(cacheKey) {
        if (themedSections.isNotEmpty()) return@LaunchedEffect
        
        isLoading = true
        try {
            // [서버 사이드 큐레이션] 모든 분류 로직을 서버로 위임
            val sections = withContext(Dispatchers.IO) {
                repository.getCategorySections(categoryKey, selectedKeyword)
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 48.dp, end = 48.dp, top = 20.dp, bottom = 10.dp), 
                horizontalArrangement = Arrangement.spacedBy(8.dp), 
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
                LazyColumn(
                    modifier = Modifier.fillMaxSize(), 
                    state = lazyListState, 
                    contentPadding = PaddingValues(top = 0.dp, bottom = 60.dp), 
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(themedSections, key = { it.title }) { section ->
                        // 서버에서 받아온 Category 객체들을 Series 객체로 변환하여 MovieRow에 전달
                        val seriesList = section.items.map { cat ->
                            Series(
                                title = cat.name ?: "",
                                episodes = cat.movies ?: emptyList(),
                                fullPath = cat.path,
                                posterPath = cat.posterPath,
                                genreIds = cat.genreIds ?: emptyList(),
                                genreNames = cat.genreNames ?: emptyList(),
                                director = cat.director,
                                actors = cat.actors ?: emptyList(),
                                overview = cat.overview,
                                year = cat.year,
                                rating = cat.rating
                            )
                        }
                        
                        MovieRow(
                            title = section.title, 
                            seriesList = seriesList,
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
        isSelected -> Color.White 
        isFocused -> Color.White.copy(alpha = 0.2f)
        else -> Color.Transparent 
    })
    
    val textColor by animateColorAsState(targetValue = when { 
        isSelected -> Color.Black 
        isFocused -> Color.White 
        else -> Color.Gray 
    })
    
    val borderColor by animateColorAsState(targetValue = when {
        isSelected || isFocused -> Color.Transparent
        else -> Color.Gray.copy(alpha = 0.5f) 
    })

    val scale by animateFloatAsState(if (isFocused) 1.05f else 1.0f)

    Box(
        modifier = Modifier
            .scale(scale)
            .clip(CircleShape)
            .background(backgroundColor)
            .border(
                width = 1.dp, 
                color = borderColor, 
                shape = CircleShape
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text, 
            color = textColor, 
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, 
            fontSize = 10.sp
        )
    }
}
