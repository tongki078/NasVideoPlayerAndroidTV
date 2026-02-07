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
    val isError = painterState is AsyncImagePainter.State.Error
    val isLoading = painterState is AsyncImagePainter.State.Loading
    val isEmpty = painterState is AsyncImagePainter.State.Empty

    LaunchedEffect(isSuccess) {
        if (isSuccess) onSuccess()
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF222222))
    ) {
        if (isSuccess) {
            Image(
                painter = painter,
                contentDescription = title,
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentScale = contentScale
            )
        } else {
            // 포스터 이미지가 아예 없는 경우이거나, 이미지를 가져오려 했으나 실패한 경우에만 텍스트 노출
            // 로딩 중일 때는 텍스트를 노출하지 않아 깜빡임을 방지함
            val shouldShowText = (finalImageUrl == null && !isLoading) || isError
            
            if (shouldShowText) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title.cleanTitle(false),
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        maxLines = 3
                    )
                }
            }
        }
    }
}
