package org.nas.videoplayerandroidtv.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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
    onSeriesClick: (Series) -> Unit
) {
    if (seriesList.isEmpty()) return
    
    val lazyListState = rememberLazyListState()
    
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.ExtraBold,
                fontSize = 22.sp
            ),
            color = Color.White,
            modifier = Modifier.padding(start = 48.dp, bottom = 12.dp)
        )
        
        LazyRow(
            state = lazyListState,
            contentPadding = PaddingValues(start = 48.dp, end = 100.dp), // 오류 수정: start/end 개별 지정
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(seriesList, key = { it.title + it.fullPath }) { series ->
                MovieCard(series = series, onClick = { onSeriesClick(series) })
            }
        }
    }
}

@Composable
private fun MovieCard(series: Series, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .width(130.dp)
            .height(190.dp)
            .clickable { onClick() }
            .focusable(),
        shape = RoundedCornerShape(4.dp),
        colors = CardDefaults.cardColors(containerColor = Color.DarkGray)
    ) {
        TmdbAsyncImage(
            title = series.title, 
            modifier = Modifier.fillMaxSize()
        )
    }
}
