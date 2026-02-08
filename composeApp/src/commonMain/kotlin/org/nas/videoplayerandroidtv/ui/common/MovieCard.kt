package org.nas.videoplayerandroidtv.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import org.nas.videoplayerandroidtv.domain.model.Series

@Composable
fun MovieCard(series: Series, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    
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
    }
}
