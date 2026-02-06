package org.nas.videoplayerandroidtv.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.nas.videoplayerandroidtv.*

@Composable
fun TmdbAsyncImage(
    title: String, 
    modifier: Modifier = Modifier, 
    contentScale: ContentScale = ContentScale.Crop, 
    typeHint: String? = null, 
    isLarge: Boolean = false,
    isAnimation: Boolean = false,
    posterPath: String? = null // 서버에서 받은 직접 경로 추가
) {
    val cacheKey = remember(title, isAnimation) { if (isAnimation) "ani_$title" else title }
    
    // 1. 서버에서 받은 posterPath를 우선 사용
    // 2. 없으면 tmdbCache 확인
    val metadata = tmdbCache[cacheKey]
    
    val finalImageUrl = remember(metadata, posterPath, isLarge) {
        val path = posterPath ?: metadata?.posterUrl?.substringAfterLast("/")
        if (path != null) {
            val size = if (isLarge) TMDB_POSTER_SIZE_LARGE else TMDB_POSTER_SIZE_MEDIUM
            "$TMDB_IMAGE_BASE$size/$path"
        } else null
    }

    // 서버 경로도 없고 캐시도 없는 경우에만 요청
    if (posterPath == null && metadata == null) {
        LaunchedEffect(cacheKey) {
            fetchTmdbMetadata(title, typeHint, isAnimation = isAnimation)
        }
    }
    
    Box(
        modifier = modifier.background(if (finalImageUrl == null) Color(0xFF1A1A1A) else Color.Transparent)
    ) {
        if (finalImageUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current)
                    .data(finalImageUrl)
                    .crossfade(400)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale
            )
        } else {
            Box(Modifier.fillMaxSize().background(Color(0xFF1A1A1A)), Alignment.Center) {
                Text(
                    text = title.cleanTitle(false),
                    color = Color.DarkGray,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}
