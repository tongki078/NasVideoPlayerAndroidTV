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
import org.nas.videoplayerandroidtv.ui.player.VideoPlayer
import org.nas.videoplayerandroidtv.domain.model.Movie

/**
 * 기본 비디오 플레이어 스크린 (플랫폼 공통)
 */
@Composable
fun BaseVideoPlayerScreen(
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

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
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
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
                ) {
                    Icon(Icons.Default.ArrowBack, null, tint = Color.White)
                }

                if (nextMovie != null) {
                    Button(
                        onClick = { currentMovie = nextMovie },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(32.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text("다음 회차", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
