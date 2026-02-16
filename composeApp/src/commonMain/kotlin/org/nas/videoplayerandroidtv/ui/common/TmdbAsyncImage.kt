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
        posterPath == null && metadata == null 
    }

    val finalImageUrl = remember(metadata, posterPath, isLarge) {
        // 1. 이미 완성된 posterUrl이 있다면 그대로 사용
        if (metadata?.posterUrl != null && metadata.posterUrl.startsWith("http")) {
            return@remember metadata.posterUrl
        }
        
        // 2. posterPath(경로만)가 있는 경우 안전하게 조합
        val path = posterPath ?: metadata?.posterUrl?.substringAfterLast("/")
        if (path != null && path != "null" && path.isNotEmpty()) {
            val size = if (isLarge) TMDB_POSTER_SIZE_LARGE else TMDB_POSTER_SIZE_MEDIUM
            // TMDB_IMAGE_BASE에 슬래시가 포함되어 있으므로 경로 앞의 슬래시 제거 후 조합
            val cleanPath = path.removePrefix("/")
            "$TMDB_IMAGE_BASE$size/$cleanPath"
        } else null
    }

    if (isFetchingMetadata) {
        LaunchedEffect(cacheKey) {
            fetchTmdbMetadata(title, typeHint, isAnimation = isAnimation)
        }
    }

    // [최적화] ImageRequest 설정: 교차 페이드 활성화 및 저사양 기기 메모리 최적화
    val context = LocalPlatformContext.current
    val painter = rememberAsyncImagePainter(
        model = remember(finalImageUrl) {
            ImageRequest.Builder(context)
                .data(finalImageUrl)
                .crossfade(true)
                .size(if (isLarge) Size(500, 750) else Size(342, 513)) // 화면 크기에 맞는 다운샘플링
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
