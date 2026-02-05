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
import androidx.compose.ui.text.style.TextOverflow
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
    isAnimation: Boolean = false
) {
    val cacheKey = remember(title, isAnimation) { if (isAnimation) "ani_$title" else title }
    
    // [초고속 최적화] LaunchedEffect를 통한 개별 요청을 제거하고 캐시 데이터를 즉시 참조
    val metadata = tmdbCache[cacheKey]
    
    val imageUrl = remember(metadata, isLarge) {
        metadata?.posterUrl?.replace(
            TMDB_POSTER_SIZE_MEDIUM, 
            if (isLarge) TMDB_POSTER_SIZE_LARGE else TMDB_POSTER_SIZE_SMALL
        )
    }

    // 데이터가 없는 경우에만 백그라운드에서 조용히 요청 (HomeScreen의 프리페칭 보조)
    if (metadata == null) {
        LaunchedEffect(cacheKey) {
            fetchTmdbMetadata(title, typeHint, isAnimation = isAnimation)
        }
    }
    
    Box(
        modifier = modifier.background(if (metadata?.posterUrl == null && metadata != null) Color(0xFF1A1A1A) else Color.Transparent)
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current)
                    .data(imageUrl)
                    .crossfade(400)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale
            )
        } else {
            // 로딩 중이거나 데이터가 없는 경우의 플레이스홀더
            Box(Modifier.fillMaxSize().background(Color(0xFF1A1A1A)), Alignment.Center) {
                if (metadata != null) {
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
}
