package org.nas.videoplayerandroidtv.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
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
    onSeriesClick: (Series) -> Unit
) {
    if (seriesList.isEmpty()) return
    
    val lazyListState = rememberLazyListState()
    val standardMargin = 20.dp 
    
    val rowFocusIndices = remember { mutableStateMapOf<String, Int>() }
    val rowKey = remember(title) { "row_$title" }
    
    Column(modifier = Modifier.padding(top = 0.dp)) { // 상단 여백 제거
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp, // 폰트 크기 통일
                letterSpacing = 0.5.sp
            ),
            color = Color(0xFFE0E0E0),
            modifier = Modifier.padding(start = standardMargin, bottom = 4.dp) // 간격 4dp로 축소
        )
        
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
                onClick = { onSeriesClick(series) }
            )
        }
    }
}
