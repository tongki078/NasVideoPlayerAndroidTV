package org.nas.videoplayerandroidtv.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nas.videoplayerandroidtv.domain.model.Series

private val genreMap = mapOf(
    28 to "액션", 12 to "모험", 16 to "애니", 35 to "코미디", 80 to "범죄",
    99 to "다큐", 18 to "드라마", 10751 to "가족", 14 to "판타지", 36 to "역사",
    27 to "공포", 10402 to "음악", 9648 to "미스터리", 10749 to "로맨스", 878 to "SF",
    10770 to "TV영화", 53 to "스릴러", 10752 to "전쟁", 37 to "서부",
    10759 to "액션&어드벤처", 10762 to "키즈", 10763 to "뉴스", 10764 to "리얼리티",
    10765 to "SF&판타지", 10766 to "소프", 10767 to "토크", 10768 to "전쟁&정치"
)

@Composable
fun MovieRow(
    title: String,
    seriesList: List<Series>,
    onSeriesClick: (Series) -> Unit
) {
    if (seriesList.isEmpty()) return
    
    val lazyListState = rememberLazyListState()
    
    Column(modifier = Modifier.padding(top = 16.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.ExtraBold,
                fontSize = 22.sp
            ),
            color = Color.White.copy(alpha = 0.9f),
            modifier = Modifier.padding(start = 52.dp, bottom = 12.dp)
        )
        
        LazyRow(
            state = lazyListState,
            contentPadding = PaddingValues(start = 52.dp, end = 52.dp, top = 10.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            items(seriesList, key = { it.title + (it.fullPath ?: "") }) { series ->
                MovieListItem(series = series, onClick = { onSeriesClick(series) })
            }
        }
    }
}

@Composable
private fun MovieListItem(series: Series, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier
            .width(140.dp) // 세로형 카드 너비로 고정
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f) // 세로형 포스터 비율
                .clip(RoundedCornerShape(8.dp))
                .border(
                    BorderStroke(2.dp, if (isFocused) Color.White else Color.Transparent),
                    RoundedCornerShape(8.dp)
                )
        ) {
            TmdbAsyncImage(
                title = series.title, 
                posterPath = series.posterPath,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        val infoAlpha by animateFloatAsState(if (isFocused) 1f else 0f)
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp) // 세로형에 맞춰 정보 영역 높이 조정
                .graphicsLayer { alpha = infoAlpha }
        ) {
            Text(
                text = series.title,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val genre = series.genreIds.firstNotNullOfOrNull { genreMap[it] } ?: "추천"
            Text(
                text = genre,
                color = Color(0xFF46D369),
                fontSize = 10.sp,
                maxLines = 1
            )
        }
    }
}
