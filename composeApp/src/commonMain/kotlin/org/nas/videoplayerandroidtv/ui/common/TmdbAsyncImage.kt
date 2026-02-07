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
import org.nas.videoplayerandroidtv.*

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
    
    // 메타데이터가 아직 로딩 중인지 여부 (캐시에도 없고, 아직 요청 중일 때)
    val isFetchingMetadata = remember(metadata, posterPath) { 
        posterPath == null && metadata == null 
    }

    val finalImageUrl = remember(metadata, posterPath, isLarge) {
        val path = posterPath ?: metadata?.posterUrl?.substringAfterLast("/")
        if (path != null && path != "null" && path.isNotEmpty()) {
            val size = if (isLarge) TMDB_POSTER_SIZE_LARGE else TMDB_POSTER_SIZE_MEDIUM
            "$TMDB_IMAGE_BASE$size/$path"
        } else null
    }

    if (isFetchingMetadata) {
        LaunchedEffect(cacheKey) {
            fetchTmdbMetadata(title, typeHint, isAnimation = isAnimation)
        }
    }

    val painter = rememberAsyncImagePainter(
        model = ImageRequest.Builder(LocalPlatformContext.current)
            .data(finalImageUrl)
            .build()
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
            .background(Color(0xFF1A1A1A)) // 더 어두운 배경으로 로딩 시 이질감 감소
    ) {
        if (isSuccess) {
            Image(
                painter = painter,
                contentDescription = title,
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentScale = contentScale
            )
        } else {
            // 아래 조건이 모두 만족될 때만 텍스트를 노출함:
            // 1. 메타데이터 조회가 끝났는데 이미지 URL이 없을 때 (또는 조회 실패가 확정되었을 때)
            // 2. 이미지를 불러오다가 에러가 났을 때
            // 3. 현재 이미지를 로딩 중이 아닐 때
            val showPlaceholderText = when {
                isError -> true // 이미지 로드 실패 시
                isFetchingMetadata -> false // 메타데이터 조회 중에는 절대 안 보여줌
                isLoadingImage -> false // 이미지 로드 중에는 절대 안 보여줌
                finalImageUrl == null -> true // 조회가 끝났는데 URL이 없으면 보여줌
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
