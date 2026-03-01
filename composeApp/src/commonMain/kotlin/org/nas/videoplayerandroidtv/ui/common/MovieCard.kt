package org.nas.videoplayerandroidtv.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
            
        // 진단 로그 (문제 해결 확인용)
        if (series.title.contains("코난")) {
            println("검색 태그 체크 - 원본 제목: ${series.title}")
            println("검색 태그 체크 - 결과: $list")
        }

        list
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
