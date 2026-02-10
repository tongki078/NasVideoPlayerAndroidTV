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
    repository: VideoRepository, // 미리보기를 위해 리포지토리 추가
    onSeriesClick: (Series) -> Unit
) {
    if (seriesList.isEmpty()) return
    
    val lazyListState = rememberLazyListState()
    val standardMargin = 20.dp 
    
    // 행별 포커스 상태 관리
    val rowFocusIndices = remember { mutableStateMapOf<String, Int>() }
    val rowKey = remember(title) { "row_$title" }
    
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                letterSpacing = 0.5.sp
            ),
            color = Color(0xFFE0E0E0),
            modifier = Modifier.padding(start = standardMargin, bottom = 20.dp)
        )
        
        // 홈 화면과 동일한 피벗 로우 사용
        NetflixTvPivotRow(
            state = lazyListState,
            items = seriesList,
            marginValue = standardMargin,
            rowKey = rowKey,
            rowFocusIndices = rowFocusIndices,
            keySelector = { it.title + (it.fullPath ?: "") }
        ) { series, index, rowState, focusRequester, marginPx, focusedIndex ->
            // 홈 화면과 동일한 피벗 아이템 사용
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
