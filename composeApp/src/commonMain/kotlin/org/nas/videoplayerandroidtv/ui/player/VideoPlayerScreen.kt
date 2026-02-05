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
                if (keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                        android.view.KeyEvent.KEYCODE_ENTER -> {
                            isControllerVisible = !isControllerVisible
                            true
                        }
                        android.view.KeyEvent.KEYCODE_BACK -> {
                            if (isControllerVisible) {
                                isControllerVisible = false
                                true
                            } else {
                                onBack()
                                true
                            }
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_UP,
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            isControllerVisible = true
                            false 
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        VideoPlayer(
            url = currentMovie.videoUrl ?: "",
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
