package org.nas.videoplayerandroidtv.ui.detail.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import org.nas.videoplayerandroidtv.data.network.NasApiClient
import org.nas.videoplayerandroidtv.domain.model.Movie
import org.nas.videoplayerandroidtv.ui.common.shimmerBrush
import org.nas.videoplayerandroidtv.util.TitleUtils.prettyTitle

@Composable
fun EpisodeItem(movie: Movie, seriesOverview: String?, seriesPosterPath: String? = null, onPlay: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) { 1.03f } else 1f, label = "EpisodeItemScale")

    // [수정] 회차 썸네일이 없을 때 무작정 포스터를 보여주던 로직을 제거합니다.
    // 이렇게 하면 서버에서 생성한 thumb_serve 이미지가 더 잘 보이게 됩니다.
    val imageUrl = remember(movie.thumbnailUrl) {
        val url = movie.thumbnailUrl ?: ""
        when {
            url.startsWith("http") -> url // 이미 전체 주소면 그대로 사용
            url.isEmpty() -> ""           // 빈 값 처리
            else -> "${NasApiClient.BASE_URL}${if (url.startsWith("/")) "" else "/"}$url" // 서버 내부 경로라면 baseUrl 붙임
        }
    }

    // 재생 시간 결정 로직 (1순위: 시청기록 duration, 2순위: TMDB runtime)
    val durationDisplay = remember(movie.duration, movie.runtime) {
        val dur = movie.duration ?: 0.0
        if (dur > 0) {
            "${(dur / 60).toInt().coerceAtLeast(1)}분"
        } else if (movie.runtime != null && movie.runtime > 0) {
            "${movie.runtime}분"
        } else {
            null
        }
    }

    // 시청 진행률 계산 (하단 게이지용)
    val progress = remember(movie.position, movie.duration) {
        if ((movie.duration ?: 0.0) > 0) {
            (movie.position ?: 0.0) / (movie.duration ?: 1.0)
        } else 0.0
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 8.dp)
            .zIndex(if (isFocused) 10f else 1f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(onClick = onPlay)
            .background(if (isFocused) Color.White.copy(alpha = 0.1f) else Color.Transparent, RoundedCornerShape(8.dp))
            .border(
                width = 2.dp,
                color = if (isFocused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        var isImageLoading by remember { mutableStateOf(true) }
        
        Box(
            modifier = Modifier
                .width(144.dp)
                .height(81.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.DarkGray.copy(alpha = 0.3f))
        ) {
            if (imageUrl.isNotEmpty()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = movie.title ?: "",
                    onState = { state -> isImageLoading = state is AsyncImagePainter.State.Loading },
                    modifier = Modifier.fillMaxSize().background(shimmerBrush(showShimmer = isImageLoading)),
                    contentScale = ContentScale.Crop
                )
            }

            if (progress > 0.01) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .align(Alignment.BottomCenter)
                        .background(Color.Black.copy(alpha = 0.5f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.toFloat().coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(Color.Red)
                    )
                }
            }
        }

        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            val fullTitle = (movie.title ?: "")
            val displayTitle = fullTitle.prettyTitle()
            
            val typeTag = remember(fullTitle) {
                when {
                    fullTitle.contains("더빙") -> " [더빙]"
                    fullTitle.contains("자막") -> " [자막]"
                    else -> ""
                }
            }

            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = displayTitle,
                    color = if (isFocused) Color.White else Color.White.copy(alpha = 0.9f), 
                    fontSize = 14.sp, 
                    fontWeight = FontWeight.Bold, 
                    maxLines = 2, 
                    lineHeight = 20.sp,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (typeTag.isNotEmpty()) {
                    Text(
                        text = typeTag,
                        color = if (typeTag.contains("더빙")) Color.Red else Color(0xFF46D369),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                    )
                }
            }

            Spacer(Modifier.height(5.dp))
            val episodeOverview = movie.overview ?: seriesOverview ?: "줄거리 정보가 없습니다."
            Text(
                text = episodeOverview,
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 12.sp, 
                maxLines = 2, 
                overflow = TextOverflow.Ellipsis, 
                lineHeight = 16.sp
            )
            
            if (durationDisplay != null) {
                Text(
                    text = "($durationDisplay)",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
