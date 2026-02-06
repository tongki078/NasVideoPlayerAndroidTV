package org.nas.videoplayerandroidtv.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nas.videoplayerandroidtv.domain.model.Series

@Composable
fun MovieCard(series: Series, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) 1.1f else 1f)

    Card(
        modifier = Modifier
            .width(130.dp)
            .height(190.dp)
            .clickable { onClick() }
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .scale(scale),
        shape = RoundedCornerShape(8.dp), // 둥근 모서리만 남김
        colors = CardDefaults.cardColors(containerColor = Color.DarkGray),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isFocused) 8.dp else 2.dp)
    ) {
        TmdbAsyncImage(
            title = series.title, 
            posterPath = series.posterPath,
            modifier = Modifier.fillMaxSize()
        )
    }
}