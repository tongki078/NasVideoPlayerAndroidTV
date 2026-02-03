package org.nas.videoplayer.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.nas.videoplayer.*

@Composable
fun TmdbAsyncImage(
    title: String, 
    modifier: Modifier = Modifier, 
    contentScale: ContentScale = ContentScale.Crop, 
    typeHint: String? = null, 
    isLarge: Boolean = false
) {
    var metadata by remember(title) { mutableStateOf(tmdbCache[title]) }
    var isError by remember(title) { mutableStateOf(false) }
    var isLoading by remember(title) { mutableStateOf(metadata == null) }
    
    val imageUrl = metadata?.posterUrl?.replace(
        TMDB_POSTER_SIZE_MEDIUM, 
        if (isLarge) TMDB_POSTER_SIZE_LARGE else TMDB_POSTER_SIZE_SMALL
    )

    LaunchedEffect(title) {
        if (metadata == null) {
            isLoading = true
            metadata = fetchTmdbMetadata(title, typeHint)
            isError = metadata?.posterUrl == null
            isLoading = false
        } else { 
            isError = metadata?.posterUrl == null
            isLoading = false 
        }
    }
    
    Box(modifier = modifier.background(Color(0xFF1A1A1A))) {
        if (imageUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current)
                    .data(imageUrl)
                    .crossfade(200)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                onError = { isError = true }
            )
        }
        if (isError && !isLoading) {
            Box(Modifier.fillMaxSize().padding(8.dp), Alignment.Center) {
                Text(
                    text = title.cleanTitle(includeYear = false), 
                    color = Color.Gray, 
                    fontSize = 10.sp, 
                    textAlign = TextAlign.Center, 
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(20.dp),
                color = Color.Red,
                strokeWidth = 2.dp
            )
        }
    }
}
