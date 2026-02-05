package org.nas.videoplayerandroidtv.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nas.videoplayerandroidtv.domain.model.Series

@Composable
fun MovieRow(
    title: String,
    seriesList: List<Series>,
    onSeriesClick: (Series) -> Unit,
    onReachEnd: () -> Unit = {}
) {
    if (seriesList.isEmpty()) return
    
    val lazyListState = rememberLazyListState()
    
    // 가로 스크롤 끝 감지 (마지막에서 5번째 아이템 노출 시 트리거)
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisibleItemIndex = lazyListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisibleItemIndex >= seriesList.size - 5 && seriesList.size > 0
        }
    }
    
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            onReachEnd()
        }
    }
    
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.ExtraBold,
                fontSize = 22.sp
            ),
            color = Color.White,
            modifier = Modifier.padding(start = 16.dp, bottom = 12.dp)
        )
        LazyRow(
            state = lazyListState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(seriesList, key = { it.title + it.fullPath }) { series ->
                Card(
                    modifier = Modifier
                        .width(130.dp)
                        .height(190.dp)
                        .clickable { onSeriesClick(series) },
                    shape = RoundedCornerShape(4.dp)
                ) {
                    TmdbAsyncImage(
                        title = series.title, 
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
