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
    
    // 추가 태그 추출 (Regex 기반 개선)
    val tags = remember(series.title) {
        val tagRegex = Regex("\\[(.*?)\\]")
        val matches = tagRegex.findAll(series.title)
        
        val list = matches.map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
            
        list
    }

    // 시청 진행률 (전체 에피소드 중 가장 마지막으로 시청한 것의 평균 혹은 대표값)
    // 여기서는 간단하게 첫 번째 에피소드의 진행률이 있다면 표시 (보통 단일 영화용)
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

        // 뱃지 추가
        if (tags.isNotEmpty()) {
            Surface(
                color = Color.Black.copy(alpha = 0.7f),
                shape = RoundedCornerShape(bottomEnd = 8.dp),
                modifier = Modifier.align(Alignment.TopStart)
            ) {
                Text(
                    text = tags.joinToString(", "),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
        }
    }
}
