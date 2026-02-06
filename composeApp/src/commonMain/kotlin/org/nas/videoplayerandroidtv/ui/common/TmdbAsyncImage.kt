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

    val finalImageUrl = remember(metadata, posterPath, isLarge) {
        val path = posterPath ?: metadata?.posterUrl?.substringAfterLast("/")
        if (path != null && path != "null") {
            val size = if (isLarge) TMDB_POSTER_SIZE_LARGE else TMDB_POSTER_SIZE_MEDIUM
            "$TMDB_IMAGE_BASE$size/$path"
        } else null
    }

    if (posterPath == null && metadata == null) {
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

    LaunchedEffect(isSuccess) {
        if (isSuccess) onSuccess()
    }

    // 최상위 컨테이너는 배경색 없이 클리핑만 수행
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Transparent)
    ) {
        if (isSuccess) {
            // [성공] 회색 배경 없이 오직 이미지만 그림 (배경을 Black으로 채워 틈새 방지)
            Image(
                painter = painter,
                contentDescription = title,
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentScale = contentScale
            )
        } else {
            // [로딩/실패] 회색 배경과 텍스트 노출
            // 이 블록은 이미지가 성공하는 즉시 렌더링 트리에서 사라짐
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF222222))
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = title.cleanTitle(false),
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 3
                )
            }
        }
    }
}
