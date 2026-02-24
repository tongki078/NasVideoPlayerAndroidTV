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
    
    // 추가 태그 추출
    val tags = remember(series.title) {
        val list = mutableListOf<String>()
        if (series.title.contains("[스페셜]")) list.add("스페셜")
        if (series.title.contains("[극장판]")) list.add("극장판")
        if (series.title.contains("[OVA]")) list.add("OVA")
        if (series.title.contains("[더빙]")) list.add("더빙")
        if (series.title.contains("[자막]")) list.add("자막")
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
