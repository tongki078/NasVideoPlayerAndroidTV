package org.nas.videoplayerandroidtv.ui.common

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
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
    
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.ExtraBold,
                fontSize = 20.sp
            ),
            color = Color.White.copy(alpha = 0.9f),
            modifier = Modifier.padding(start = 52.dp, bottom = 12.dp)
        )
        
        LazyRow(
            state = lazyListState,
            contentPadding = PaddingValues(horizontal = 52.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.height(230.dp), // 높이를 살짝 늘려 확대 공간 확보
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
    
    // 포커스가 없을 때는 반드시 1.0f가 되도록 보장
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.15f else 1.0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium),
        label = "MovieListItemScale"
    )

    Column(
        modifier = Modifier
            .width(220.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .zIndex(if (isFocused) 10f else 1f)
            .focusable()
            .clickable(onClick = onClick)
    ) {
        // 영상 이미지 카드
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(124.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(RoundedCornerShape(4.dp))
                .then(
                    if (isFocused) Modifier.border(2.5.dp, Color.White, RoundedCornerShape(4.dp))
                    else Modifier
                )
        ) {
            TmdbAsyncImage(
                title = series.title, 
                posterPath = series.posterPath,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 하단 정보 영역 (위아래 흔들림 방지를 위해 고정된 공간 사용)
        Box(modifier = Modifier.fillMaxWidth().height(85.dp)) {
            if (isFocused) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp, start = 2.dp, end = 2.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val genre = series.genreIds.take(1).mapNotNull { genreMap[it] }.firstOrNull() ?: "추천"
                        Text(
                            text = genre,
                            color = Color(0xFF46D369),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        
                        if (series.year != null) {
                            Text(series.year, color = Color.White, fontSize = 12.sp)
                        }

                        Box(
                            modifier = Modifier
                                .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(series.rating ?: "15+", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = series.overview ?: "${series.title} - 지금 바로 감상해보세요.",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        maxLines = 2,
                        lineHeight = 15.sp,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
