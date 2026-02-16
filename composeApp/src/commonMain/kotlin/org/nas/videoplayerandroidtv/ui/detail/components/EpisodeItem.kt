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
import org.nas.videoplayerandroidtv.domain.model.Movie
import org.nas.videoplayerandroidtv.ui.common.shimmerBrush
import org.nas.videoplayerandroidtv.util.TitleUtils.prettyTitle

@Composable
fun EpisodeItem(movie: Movie, seriesOverview: String?, onPlay: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) 1.03f else 1f, label = "EpisodeItemScale")

    val imageUrl = movie.thumbnailUrl ?: ""

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
            .padding(12.dp), 
        verticalAlignment = Alignment.CenterVertically
    ) {
        var isImageLoading by remember { mutableStateOf(true) }
        
        // 썸네일 영역: 이미지가 없어도 기본 배경색을 주어 영역 확보
        Box(
            modifier = Modifier
                .width(160.dp)
                .height(90.dp)
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
        }

        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = (movie.title ?: "").prettyTitle(),
                color = if (isFocused) Color.White else Color.White.copy(alpha = 0.9f), 
                fontSize = 16.sp, 
                fontWeight = FontWeight.Bold, 
                maxLines = 1, 
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            val episodeOverview = movie.overview ?: seriesOverview ?: "줄거리 정보가 없습니다."
            Text(
                text = episodeOverview,
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 13.sp, 
                maxLines = 2, 
                overflow = TextOverflow.Ellipsis, 
                lineHeight = 18.sp
            )
        }
    }
}
