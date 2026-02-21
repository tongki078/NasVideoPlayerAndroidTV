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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import org.nas.videoplayerandroidtv.ui.player.VideoPlayer
import org.nas.videoplayerandroidtv.domain.model.Movie
import org.nas.videoplayerandroidtv.util.TitleUtils.prettyTitle

@Composable
fun BaseVideoPlayerScreen(
    movie: Movie,
    playlist: List<Movie> = emptyList(),
    initialPosition: Long = 0L,
    onPositionUpdate: (Long) -> Unit = {},
    onBack: () -> Unit
) {
    var currentMovie by remember(movie) { mutableStateOf(movie) }
    // 테스트를 위해 초기값을 true로 하고, 자동으로 사라지는 시간을 넉넉히(20초) 잡습니다.
    var isControllerVisible by remember { mutableStateOf(true) }
    
    val nextMovie = remember(currentMovie, playlist) {
        val currentIndex = playlist.indexOfFirst { it.id == currentMovie.id }
        if (currentIndex != -1 && currentIndex < playlist.size - 1) {
            playlist[currentIndex + 1]
        } else null
    }

    LaunchedEffect(isControllerVisible) {
        if (isControllerVisible) {
            delay(20000) // 20초 동안 유지 (테스트용)
            // isControllerVisible = false // 일단 자동으로 사라지지 않게 주석 처리하여 확인
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // 비디오 플레이어 레이어 (zIndex 0)
        Box(modifier = Modifier.fillMaxSize().zIndex(0f)) {
            VideoPlayer(
                url = currentMovie.videoUrl ?: "",
                modifier = Modifier.fillMaxSize(),
                initialPosition = initialPosition,
                onPositionUpdate = onPositionUpdate,
                onControllerVisibilityChanged = { visible ->
                    isControllerVisible = visible
                },
                onVideoEnded = {
                    // nextMovie?.let { currentMovie = it }
                }
            )
        }

        // 컨트롤러 UI 레이어 (zIndex 1) - AnimatedVisibility 대신 단순 if 사용
        if (isControllerVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(1f)
                    .background(Color.Black.copy(alpha = 0.3f)) // 화면이 살짝 어두워지는지 확인용
            ) {
                // 뒤로가기 버튼
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.TopStart).padding(24.dp)
                ) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(36.dp))
                }

                // 우측 상단 제목 및 회차 정보
                val displayTitle = remember(currentMovie.title) {
                    val cleaned = currentMovie.title?.prettyTitle()
                    if (cleaned.isNullOrBlank()) currentMovie.title ?: "제목 없음" else cleaned
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 40.dp, end = 40.dp)
                        .widthIn(max = 600.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    Surface(
                        color = Color.Red.copy(alpha = 0.8f), // 눈에 잘 띄도록 임시로 빨간색 배경 사용
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = displayTitle,
                            color = Color.White,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                            style = LocalTextStyle.current.copy(
                                shadow = Shadow(Color.Black, Offset(4f, 4f), 8f)
                            )
                        )
                    }
                }

                if (nextMovie != null) {
                    Button(
                        onClick = { currentMovie = nextMovie },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.2f))
                    ) {
                        Text("다음 회차", color = Color.White)
                    }
                }
            }
        }
    }
}
