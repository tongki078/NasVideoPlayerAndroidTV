package org.nas.videoplayerandroidtv.ui.detail.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nas.videoplayerandroidtv.*
import org.nas.videoplayerandroidtv.domain.model.Series
import org.nas.videoplayerandroidtv.ui.common.TmdbAsyncImage
import org.nas.videoplayerandroidtv.ui.player.VideoPlayer
import androidx.compose.ui.focus.onFocusChanged

@Composable
fun SeriesDetailHeader(
    series: Series, 
    metadata: TmdbMetadata?, 
    credits: List<TmdbCast>,
    previewUrl: String? = null,
    initialPosition: Long = 0L,
    onPositionUpdate: (Long) -> Unit = {},
    onPlayClick: () -> Unit,
    onFullscreenClick: () -> Unit = {}
) {
    var isPlayButtonFocused by remember { mutableStateOf(false) }
    var isPlayerFocused by remember { mutableStateOf(false) }

    Column {
        // 상단 플레이어 영역
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(350.dp)
                .onFocusChanged { isPlayerFocused = it.isFocused }
                .focusable()
                .clickable { onFullscreenClick() }
                .border(
                    width = if (isPlayerFocused) 4.dp else 0.dp,
                    color = if (isPlayerFocused) Color.White else Color.Transparent
                )
        ) {
            if (previewUrl != null) {
                VideoPlayer(
                    url = previewUrl,
                    modifier = Modifier.fillMaxSize(),
                    initialPosition = initialPosition,
                    onPositionUpdate = onPositionUpdate,
                    onFullscreenClick = onFullscreenClick
                )
            } else {
                TmdbAsyncImage(
                    title = series.title, 
                    modifier = Modifier.fillMaxSize(), 
                    contentScale = ContentScale.Crop, 
                    isLarge = true
                )
            }
            
            if (isPlayerFocused) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.2f)), 
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow, 
                        contentDescription = null, 
                        modifier = Modifier.size(80.dp), 
                        tint = Color.White
                    )
                    Text(
                        text = "전체화면으로 보기", 
                        color = Color.White, 
                        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp), 
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        
        Spacer(Modifier.height(16.dp))

        // 정보 영역
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = series.title.cleanTitle(), 
                    color = Color.White, 
                    fontSize = 28.sp, 
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onPlayClick,
                    modifier = Modifier
                        .width(150.dp)
                        .height(48.dp)
                        .onFocusChanged { isPlayButtonFocused = it.isFocused }
                        .border(
                            width = if (isPlayButtonFocused) 3.dp else 0.dp,
                            color = if (isPlayButtonFocused) Color.Yellow else Color.Transparent,
                            shape = RoundedCornerShape(4.dp)
                        ),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isPlayButtonFocused) Color.White else Color.Red
                    ),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow, 
                        contentDescription = null, 
                        tint = if (isPlayButtonFocused) Color.Black else Color.White
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "재생", 
                        color = if (isPlayButtonFocused) Color.Black else Color.White, 
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = metadata?.overview ?: "상세 정보를 불러오는 중입니다...", 
                    color = Color.LightGray, 
                    fontSize = 15.sp, 
                    lineHeight = 22.sp, 
                    maxLines = 4, 
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (credits.isNotEmpty()) {
            Text(
                text = "출연진", 
                color = Color.White, 
                fontWeight = FontWeight.Bold, 
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), 
                fontSize = 18.sp
            )
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp)) {
                items(credits) { cast ->
                    CastItem(cast)
                }
            }
        }
    }
}
