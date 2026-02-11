package org.nas.videoplayerandroidtv.ui.player.tv

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import org.nas.videoplayerandroidtv.ui.player.VideoPlayer
import org.nas.videoplayerandroidtv.domain.model.Movie
import org.nas.videoplayerandroidtv.data.network.NasApiClient

/**
 * Android TV 전용 넷플릭스 스타일 플레이어 스크린
 */
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
    
    // Seek 탐색 상태
    var isSeeking by remember { mutableStateOf(false) }
    var seekTime by remember { mutableLongStateOf(0L) }
    var finalSeekPosition by remember { mutableLongStateOf(-1L) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var totalDuration by remember { mutableLongStateOf(0L) }

    val focusRequester = remember { FocusRequester() }
    val thumbListState = rememberLazyListState()

    val nextMovie = remember(currentMovie, playlist) {
        val currentIndex = playlist.indexOfFirst { it.id == currentMovie.id }
        if (currentIndex != -1 && currentIndex < playlist.size - 1) {
            playlist[currentIndex + 1]
        } else null
    }

    val seekThumbnails = remember(seekTime, totalDuration) {
        if (totalDuration <= 0) emptyList<Long>()
        else {
            val interval = 30000L 
            (-3..3).map { i ->
                (seekTime + (i * interval)).coerceIn(0L, totalDuration)
            }.distinct()
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(isControllerVisible, isSeeking) {
        if (isControllerVisible && !isSeeking) {
            delay(5000)
            isControllerVisible = false
        }
    }

    LaunchedEffect(seekThumbnails) {
        if (isSeeking && seekThumbnails.isNotEmpty()) {
            val centerIndex = seekThumbnails.size / 2
            thumbListState.animateScrollToItem(centerIndex)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                        android.view.KeyEvent.KEYCODE_ENTER -> {
                            if (isSeeking) {
                                finalSeekPosition = seekTime
                                isSeeking = false
                            } else {
                                isControllerVisible = !isControllerVisible
                            }
                            true
                        }
                        android.view.KeyEvent.KEYCODE_BACK -> {
                            if (isSeeking) {
                                isSeeking = false
                                true
                            } else if (isControllerVisible) {
                                isControllerVisible = false
                                true
                            } else {
                                onBack()
                                true
                            }
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (!isSeeking) {
                                seekTime = currentPosition
                                isSeeking = true
                            }
                            isControllerVisible = true
                            seekTime = (seekTime - 10000).coerceAtLeast(0L)
                            true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (!isSeeking) {
                                seekTime = currentPosition
                                isSeeking = true
                            }
                            isControllerVisible = true
                            seekTime = (seekTime + 10000).coerceAtMost(totalDuration)
                            true
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
            seekToPosition = finalSeekPosition,
            onPositionUpdate = { pos ->
                currentPosition = pos
                if (!isSeeking) seekTime = pos
            },
            onDurationDetermined = { dur -> totalDuration = dur },
            onControllerVisibilityChanged = { visible -> isControllerVisible = visible },
            onVideoEnded = { nextMovie?.let { currentMovie = it } }
        )

        AnimatedVisibility(
            visible = isControllerVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))) {
                
                if (isSeeking && totalDuration > 0) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 128.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LazyRow(
                            state = thumbListState,
                            contentPadding = PaddingValues(horizontal = 100.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            userScrollEnabled = false,
                            modifier = Modifier.fillMaxWidth().height(120.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            items(seekThumbnails) { timestamp ->
                                val isCenter = timestamp == seekTime
                                val baseUrl = NasApiClient.BASE_URL
                                val timeSec = timestamp / 1000
                                val thumbUrl = remember(currentMovie.id, timestamp) {
                                    val originalThumb = currentMovie.thumbnailUrl ?: ""
                                    if (originalThumb.contains("?")) "$baseUrl$originalThumb&t=$timeSec"
                                    else "$baseUrl$originalThumb?t=$timeSec"
                                }

                                Box(
                                    modifier = Modifier
                                        .width(if (isCenter) 200.dp else 160.dp)
                                        .fillMaxHeight(if (isCenter) 1f else 0.8f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(
                                            width = if (isCenter) 3.dp else 1.dp,
                                            color = if (isCenter) Color.White else Color.Gray.copy(alpha = 0.5f),
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .background(Color.DarkGray)
                                ) {
                                    AsyncImage(
                                        model = thumbUrl,
                                        contentDescription = "Seek Thumb",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 48.dp)
                        .padding(horizontal = 60.dp)
                ) {
                    val progress = if (totalDuration > 0) seekTime.toFloat() / totalDuration else 0f
                    
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color.White.copy(alpha = 0.3f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress)
                                .fillMaxHeight()
                                .background(Color.Red)
                        )
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatTime(if (isSeeking) seekTime else currentPosition), color = Color.White, fontSize = 14.sp)
                        Text(formatTime(totalDuration), color = Color.White, fontSize = 14.sp)
                    }
                }

                if (nextMovie != null && !isSeeking) {
                    var isNextFocused by remember { mutableStateOf(false) }
                    Button(
                        onClick = { 
                            currentMovie = nextMovie 
                            isControllerVisible = true
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = 96.dp)
                            .padding(end = 32.dp)
                            .onFocusChanged { isNextFocused = it.isFocused },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isNextFocused) Color.Red else Color.White.copy(alpha = 0.9f)
                        ),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, null, tint = if (isNextFocused) Color.White else Color.Black)
                        Spacer(Modifier.width(8.dp))
                        Text("다음 회차 보기", color = if (isNextFocused) Color.White else Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        "${h}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    } else {
        "${m}:${s.toString().padStart(2, '0')}"
    }
}
