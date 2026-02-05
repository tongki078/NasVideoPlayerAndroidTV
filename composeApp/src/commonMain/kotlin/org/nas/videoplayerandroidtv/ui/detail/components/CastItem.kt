package org.nas.videoplayerandroidtv.ui.detail.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import org.nas.videoplayerandroidtv.TMDB_IMAGE_BASE
import org.nas.videoplayerandroidtv.TMDB_POSTER_SIZE_SMALL
import org.nas.videoplayerandroidtv.TmdbCast
import org.nas.videoplayerandroidtv.ui.common.shimmerBrush

@Composable
fun CastItem(cast: TmdbCast) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) 1.1f else 1f)

    Column(
        modifier = Modifier
            .width(100.dp)
            .padding(end = 16.dp)
            .scale(scale)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable(), 
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        var isLoading by remember { mutableStateOf(true) }
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(shimmerBrush(showShimmer = isLoading))
                .border(
                    width = if (isFocused) 3.dp else 0.dp,
                    color = if (isFocused) Color.White else Color.Transparent,
                    shape = CircleShape
                )
        ) {
            AsyncImage(
                model = "$TMDB_IMAGE_BASE$TMDB_POSTER_SIZE_SMALL${cast.profilePath}",
                contentDescription = cast.name,
                onState = { state -> isLoading = state is AsyncImagePainter.State.Loading },
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = cast.name, 
            color = if (isFocused) Color.Red else Color.White, 
            fontSize = 12.sp, 
            textAlign = TextAlign.Center, 
            maxLines = 2, 
            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal
        )
    }
}
