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
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.onFocusChanged

@Composable
fun VideoPlayerScreen(
    movie: Movie,
    playlist: List<Movie> = emptyList(),
    initialPosition: Long = 0L,
    onPositionUpdate: (Long) -> Unit = {},
    onBack: () -> Unit
) {
    var currentMovie by remember(movie) { mutableStateOf(movie) }
    var isControllerVisible by remember { mutableStateOf(true) }
    
    val nextMovie = remember(currentMovie, playlist) {
        val currentIndex = playlist.indexOfFirst { it.id == currentMovie.id }
        if (currentIndex != -1 && currentIndex < playlist.size - 1) {
            playlist[currentIndex + 1]
        } else null
    }

    // 컨트롤러 자동 숨김 타이머
    LaunchedEffect(isControllerVisible) {
        if (isControllerVisible) {
            delay(5000)
            isControllerVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onKeyEvent { keyEvent ->
                // 리모컨 버튼 입력 처리
                if (keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        // 확인 버튼 (Center DPAD / Enter)
                        android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                        android.view.KeyEvent.KEYCODE_ENTER -> {
                            isControllerVisible = !isControllerVisible
                            true
                        }
                        // 뒤로가기 버튼
                        android.view.KeyEvent.KEYCODE_BACK -> {
                            if (isControllerVisible) {
                                isControllerVisible = false
                                true
                            } else {
                                onBack()
                                true
                            }
                        }
                        // 방향키 입력 시 컨트롤러 표시
                        android.view.KeyEvent.KEYCODE_DPAD_UP,
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            isControllerVisible = true
                            false // 시스템이 기본 포커스 이동을 처리하도록 false 반환
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        VideoPlayer(
            url = currentMovie.videoUrl,
            modifier = Modifier.fillMaxSize(),
            initialPosition = initialPosition,
            onPositionUpdate = onPositionUpdate,
            onControllerVisibilityChanged = { visible ->
                isControllerVisible = visible
            },
            onVideoEnded = {
                nextMovie?.let { currentMovie = it }
            }
        )

        AnimatedVisibility(
            visible = isControllerVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 상단 좌측: 닫기 버튼 (TV 포커스 가능하도록 개선)
                var isCloseFocused by remember { mutableStateOf(false) }
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(16.dp)
                        .align(Alignment.TopStart)
                        .onFocusChanged { isCloseFocused = it.isFocused }
                ) {
                    Icon(
                        Icons.Default.Close, 
                        null, 
                        tint = if (isCloseFocused) Color.Red else Color.White, 
                        modifier = Modifier.size(32.dp)
                    )
                }

                // 하단 우측: 다음 회차 보기 버튼 (TV 포커스 가능하도록 개선)
                if (nextMovie != null) {
                    var isNextFocused by remember { mutableStateOf(false) }
                    Button(
                        onClick = { 
                            currentMovie = nextMovie 
                            isControllerVisible = true
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = 96.dp, end = 32.dp)
                            .onFocusChanged { isNextFocused = it.isFocused },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isNextFocused) Color.Red else Color.White.copy(alpha = 0.9f)
                        ),
                        shape = RoundedCornerShape(4.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, null, tint = if (isNextFocused) Color.White else Color.Black)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "다음 회차 보기", 
                            color = if (isNextFocused) Color.White else Color.Black, 
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
