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
    
    // 캐시에서 즉시 초기값 로드 (LaunchedEffect를 기다리지 않음)
    var metadata by remember(cacheKey) { mutableStateOf(tmdbCache[cacheKey]) }
    var isLoading by remember(cacheKey) { mutableStateOf(metadata == null) }
    
    val imageUrl = remember(metadata, isLarge) {
        metadata?.posterUrl?.replace(
            TMDB_POSTER_SIZE_MEDIUM, 
            if (isLarge) TMDB_POSTER_SIZE_LARGE else TMDB_POSTER_SIZE_SMALL
        )
    }

    LaunchedEffect(cacheKey) {
        if (metadata == null) {
            // 다시 한번 캐시 확인 (그 사이 채워졌을 수 있음)
            val cached = tmdbCache[cacheKey]
            if (cached != null) {
                metadata = cached
                isLoading = false
            } else {
                isLoading = true
                val fetched = fetchTmdbMetadata(title, typeHint, isAnimation = isAnimation)
                metadata = fetched
                isLoading = false
            }
        } else {
            isLoading = false
        }
    }
    
    Box(
        modifier = modifier.background(if (metadata?.posterUrl == null && !isLoading) Color(0xFF1A1A1A) else Color.Transparent)
    ) {
        // 이미지가 있는 경우 표시
        if (imageUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current)
                    .data(imageUrl)
                    .crossfade(400) // 부드러운 전환
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                onState = { state ->
                    if (state is coil3.compose.AsyncImagePainter.State.Success) {
                        isLoading = false
                    }
                }
            )
        }
        
        // 로딩 중일 때 Shimmer 표시 (이미지 URL이 있을 때만)
        if (isLoading && imageUrl != null) {
            Box(Modifier.fillMaxSize().background(shimmerBrush(showShimmer = true)))
        }
        
        // 에러 또는 결과 없음 (포스터가 아예 없는 경우)
        if (!isLoading && metadata != null && metadata!!.posterUrl == null) {
            Box(Modifier.fillMaxSize().padding(12.dp), Alignment.Center) {
                Text(
                    text = title.cleanTitle(includeYear = false), 
                    color = Color.Gray, 
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center, 
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
