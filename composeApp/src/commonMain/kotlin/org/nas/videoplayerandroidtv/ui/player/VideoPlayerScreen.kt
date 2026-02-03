package org.nas.videoplayerandroidtv.ui.player

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.nas.videoplayerandroidtv.VideoPlayer
import org.nas.videoplayerandroidtv.domain.model.Movie

@Composable
fun VideoPlayerScreen(
    movie: Movie,
    playlist: List<Movie> = emptyList(),
    initialPosition: Long = 0L, // 추가
    onPositionUpdate: (Long) -> Unit = {}, // 추가
    onBack: () -> Unit
) {
    var currentMovie by remember(movie) { mutableStateOf(movie) }
    // VideoPlayer 자체 컨트롤러 상태와 동기화
    var isControllerVisible by remember { mutableStateOf(true) }
    
    val nextMovie = remember(currentMovie, playlist) {
        val currentIndex = playlist.indexOfFirst { it.id == currentMovie.id }
        if (currentIndex != -1 && currentIndex < playlist.size - 1) {
            playlist[currentIndex + 1]
        } else null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 1. 실제 비디오 플레이어 호출
        VideoPlayer(
            url = currentMovie.videoUrl,
            modifier = Modifier.fillMaxSize(),
            initialPosition = initialPosition, // 위치 전달
            onPositionUpdate = onPositionUpdate, // 위치 업데이트 콜백
            onControllerVisibilityChanged = { visible ->
                isControllerVisible = visible
            },
            onVideoEnded = {
                nextMovie?.let { currentMovie = it }
            }
        )

        // 2. 커스텀 UI 레이어 (닫기 버튼 및 다음 회차 버튼)
        AnimatedVisibility(
            visible = isControllerVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 상단 좌측: 닫기 버튼
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(16.dp)
                        .align(Alignment.TopStart)
                ) {
                    Icon(
                        Icons.Default.Close, 
                        null, 
                        tint = Color.White, 
                        modifier = Modifier.size(32.dp)
                    )
                }

                // 하단 우측: 다음 회차 보기 버튼
                if (nextMovie != null) {
                    Button(
                        onClick = { 
                            currentMovie = nextMovie 
                            isControllerVisible = true
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = 96.dp, end = 32.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.9f)),
                        shape = RoundedCornerShape(4.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, null, tint = Color.Black)
                        Spacer(Modifier.width(8.dp))
                        Text("다음 회차 보기", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
