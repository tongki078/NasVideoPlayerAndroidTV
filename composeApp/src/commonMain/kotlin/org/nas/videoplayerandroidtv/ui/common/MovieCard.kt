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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import org.nas.videoplayerandroidtv.domain.model.Series

@Composable
fun MovieCard(series: Series, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    
    // 포커스가 없을 때는 반드시 1.0f가 되도록 명시
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.15f else 1.0f,
        label = "MovieCardScale"
    )

    Box(
        modifier = Modifier
            .width(130.dp)
            .aspectRatio(0.68f)
            .zIndex(if (isFocused) 10f else 1f)
            .onFocusChanged { isFocused = it.isFocused }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (isFocused) Modifier.border(2.5.dp, Color.White, RoundedCornerShape(8.dp))
                else Modifier
            )
            .focusable()
            .clickable { onClick() }
    ) {
        TmdbAsyncImage(
            title = series.title, 
            posterPath = series.posterPath,
            modifier = Modifier.fillMaxSize()
        )
    }
}
