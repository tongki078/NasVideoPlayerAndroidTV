package org.nas.videoplayerandroidtv.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nas.videoplayerandroidtv.domain.model.Series

@Composable
fun MovieCard(series: Series, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    
    // 제목에서 태그 추출 ([자막], [더빙] 등)
    val tags = remember(series.title) {
        val tagRegex = Regex("""[\(\[]\s*(더빙|자막|한글|영어|KOR|DUB|SUB)\s*[\)\]]""", RegexOption.IGNORE_CASE)
        val matches = tagRegex.findAll(series.title)
        
        matches.map { it.groupValues[1].uppercase().trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    // 시청 진행률 계산
    val mainEpisode = series.episodes.firstOrNull()
    val progress = remember(mainEpisode) {
        if (mainEpisode != null && (mainEpisode.duration ?: 0.0) > 0) {
            (mainEpisode.position ?: 0.0) / (mainEpisode.duration ?: 1.0)
        } else 0.0
    }
    
    Box(
        modifier = Modifier
            .width(140.dp)
            .aspectRatio(0.68f)
            .onFocusChanged { isFocused = it.isFocused }
            .clip(RoundedCornerShape(8.dp))
            .border(
                BorderStroke(2.dp, if (isFocused) Color.White else Color.Transparent),
                RoundedCornerShape(8.dp)
            )
            .focusable()
            .clickable { onClick() }
    ) {
        TmdbAsyncImage(
            title = series.title, 
            posterPath = series.posterPath,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // 시청 진행률 게이지 (하단)
        if (progress > 0.01) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.5f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.toFloat().coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(Color.Red)
                )
            }
        }

        // [수정] 자막/더빙 뱃지 표시 (우측 상단 또는 좌측 상단)
        if (tags.isNotEmpty()) {
            Surface(
                color = Color.Red.copy(alpha = 0.85f), // 더 눈에 띄게 빨간색 계열 사용
                shape = RoundedCornerShape(bottomStart = 8.dp),
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Text(
                    text = tags.joinToString(", "),
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}
