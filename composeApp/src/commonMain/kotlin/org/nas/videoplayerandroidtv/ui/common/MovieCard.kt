package org.nas.videoplayerandroidtv.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.nas.videoplayerandroidtv.domain.model.Series

@Composable
fun MovieCard(series: Series, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) 1.1f else 1f, label = "scale")

    Box(
        modifier = Modifier
            .width(130.dp)
            .aspectRatio(0.68f)
            .scale(scale)
            .onFocusChanged { isFocused = it.isFocused }
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (isFocused) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp))
                else Modifier
            )
            .focusable()
            .clickable { onClick() } // 전달받은 onClick(onSeriesClick)을 정확히 실행
    ) {
        TmdbAsyncImage(
            title = series.title, 
            posterPath = series.posterPath,
            modifier = Modifier.fillMaxSize()
        )
    }
}
