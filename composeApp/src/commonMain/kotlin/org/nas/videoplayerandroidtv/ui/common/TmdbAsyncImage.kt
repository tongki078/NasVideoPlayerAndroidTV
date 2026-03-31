package org.nas.videoplayerandroidtv.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.CachePolicy
import coil3.size.Size
import org.nas.videoplayerandroidtv.*
import org.nas.videoplayerandroidtv.util.TitleUtils.cleanTitle

@Composable
fun TmdbAsyncImage(
    title: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    typeHint: String? = null,
    isLarge: Boolean = false,
    isAnimation: Boolean = false,
    posterPath: String? = null,
    onSuccess: () -> Unit = {}
) {
    val cacheKey = remember(title, isAnimation) { if (isAnimation) "ani_$title" else title }
    val metadata = tmdbCache[cacheKey]
    
    val isFetchingMetadata = remember(metadata, posterPath) { 
        (posterPath == null || posterPath == "null" || posterPath.isEmpty()) && metadata == null 
    }

    val finalImageUrl = remember(metadata, posterPath, isLarge) {
        if (posterPath?.startsWith("http") == true) return@remember posterPath

        if (metadata?.posterUrl != null && metadata.posterUrl.startsWith("http")) {
            return@remember metadata.posterUrl
        }
        
        val path = if (posterPath != null && posterPath != "null" && posterPath.isNotEmpty()) {
            posterPath
        } else {
            metadata?.posterUrl?.substringAfterLast("/")
        }

        if (path != null && path != "null" && path.isNotEmpty()) {
            // [최적화] 히어로 섹션은 고해상도(w1280), 일반 목록은 중간 해상도(w500)
            val size = if (isLarge) "w1280" else "w500"
            val cleanPath = path.removePrefix("/")
            "$TMDB_IMAGE_BASE$size/$cleanPath"
        } else null
    }

    if (isFetchingMetadata) {
        LaunchedEffect(cacheKey) {
            fetchTmdbMetadata(title, typeHint, isAnimation = isAnimation)
        }
    }

    val context = LocalPlatformContext.current
    val painter = rememberAsyncImagePainter(
        model = remember(finalImageUrl, isLarge) {
            ImageRequest.Builder(context)
                .data(finalImageUrl)
                .crossfade(true)
                // 🔴 캐시 설정만 명확히 하여 로딩 속도 개선 (Priority 제거하여 에러 해결)
                .diskCachePolicy(CachePolicy.ENABLED)
                .memoryCachePolicy(CachePolicy.ENABLED)
                // TV 환경에 최적화된 다운샘플링 사이즈
                .size(if (isLarge) Size(1280, 720) else Size(500, 750))
                .build()
        }
    )

    val painterState by painter.state.collectAsState()
    val isSuccess = painterState is AsyncImagePainter.State.Success
    val isError = painterState is AsyncImagePainter.State.Error
    val isLoadingImage = painterState is AsyncImagePainter.State.Loading

    LaunchedEffect(isSuccess) {
        if (isSuccess) onSuccess()
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1A1A1A)) 
    ) {
        if (isSuccess) {
            Image(
                painter = painter,
                contentDescription = title,
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentScale = contentScale
            )
        } else {
            val showPlaceholderText = when {
                isError -> true
                isFetchingMetadata -> false
                isLoadingImage -> false
                finalImageUrl == null -> true
                else -> false
            }

            if (showPlaceholderText) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title.cleanTitle(false),
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 3
                    )
                }
            }
        }
    }
}
