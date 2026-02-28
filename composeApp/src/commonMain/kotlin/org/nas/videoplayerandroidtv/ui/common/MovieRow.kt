package org.nas.videoplayerandroidtv.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nas.videoplayerandroidtv.domain.model.Series
import org.nas.videoplayerandroidtv.domain.repository.VideoRepository
import org.nas.videoplayerandroidtv.ui.home.NetflixPivotItem
import org.nas.videoplayerandroidtv.ui.home.NetflixTvPivotRow

@Composable
fun MovieRow(
    title: String,
    seriesList: List<Series>,
    repository: VideoRepository,
    initials: List<String>? = emptyList(), // [추가] 초성 필터 리스트
    onSeriesClick: (Series) -> Unit,
    onInitialClick: ((String) -> Unit)? = null // [추가] 초성 클릭 이벤트
) {
    if (seriesList.isEmpty()) return
    
    val lazyListState = rememberLazyListState()
    val standardMargin = 20.dp 
    
    val rowFocusIndices = remember { mutableStateMapOf<String, Int>() }
    val rowKey = remember(title) { "row_$title" }
    
    Column(modifier = Modifier.padding(top = 0.dp)) { 
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = standardMargin, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp, 
                    letterSpacing = 0.5.sp
                ),
                color = Color(0xFFE0E0E0)
            )

            // [추가] 초성 필터 바 표시
            if (!initials.isNullOrEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(initials) { char ->
                        InitialFilterItem(char) { onInitialClick?.invoke(char) }
                    }
                }
            }
        }
        
        NetflixTvPivotRow(
            state = lazyListState,
            items = seriesList,
            marginValue = standardMargin,
            rowKey = rowKey,
            rowFocusIndices = rowFocusIndices,
            keySelector = { it.title + (it.fullPath ?: "") }
        ) { series, index, rowState, focusRequester, marginPx, focusedIndex ->
            NetflixPivotItem(
                title = series.title,
                posterPath = series.posterPath,
                initialVideoUrl = series.episodes.firstOrNull()?.videoUrl,
                categoryPath = series.fullPath,
                repository = repository,
                index = index,
                focusedIndex = focusedIndex,
                state = rowState,
                marginPx = marginPx,
                focusRequester = focusRequester,
                overview = series.overview,
                year = series.year,
                rating = series.rating,
                onClick = { onSeriesClick(series) }
            )
        }
    }
}

@Composable
private fun InitialFilterItem(text: String, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val backgroundColor = if (isFocused) Color.White else Color.DarkGray.copy(alpha = 0.5f)
    val textColor = if (isFocused) Color.Black else Color.LightGray

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(backgroundColor)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = textColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
