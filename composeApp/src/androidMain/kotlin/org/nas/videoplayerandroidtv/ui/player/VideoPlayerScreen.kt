package org.nas.videoplayerandroidtv.ui.player.tv

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.zIndex
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
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    
    // Seek 탐색 상태
    var isSeeking by remember { mutableStateOf(false) }
    var seekTime by remember { mutableLongStateOf(0L) }
    var finalSeekPosition by remember { mutableLongStateOf(-1L) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var totalDuration by remember { mutableLongStateOf(0L) }

    val mainBoxFocusRequester = remember { FocusRequester() }
    val nextButtonFocusRequester = remember { FocusRequester() }
    val thumbListState = rememberLazyListState()

    var isNextButtonFocused by remember { mutableStateOf(false) }

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

    // 초기 포커스 요청
    LaunchedEffect(Unit) {
        mainBoxFocusRequester.requestFocus()
    }

    // 컨트롤러 자동 숨김 타이머 (사용자 상호작용 없으면 5초 후 숨김)
    LaunchedEffect(isControllerVisible, lastInteractionTime, isSeeking, isNextButtonFocused) {
        if (isControllerVisible && !isSeeking && !isNextButtonFocused) {
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
            .focusRequester(mainBoxFocusRequester)
            .focusable()
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    lastInteractionTime = System.currentTimeMillis() // 상호작용 시간 갱신
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
                        android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                            if (!isControllerVisible) {
                                isControllerVisible = true
                                true
                            } else if (nextMovie != null) {
                                // 컨트롤러가 켜져있을 때 위를 누르면 다음회차 버튼으로 포커스 이동
                                nextButtonFocusRequester.requestFocus()
                                true
                            } else false
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

        // 넷플릭스 스타일 UI 오버레이
        AnimatedVisibility(
            visible = isControllerVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))) {
                
                // --- [좌측 상단: 다음 회차 버튼] ---
                if (nextMovie != null && !isSeeking) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            onClick = { 
                                currentMovie = nextMovie 
                                isControllerVisible = true
                                mainBoxFocusRequester.requestFocus()
                            },
                            modifier = Modifier
                                .size(50.dp) 
                                .focusRequester(nextButtonFocusRequester)
                                .onFocusChanged { isNextButtonFocused = it.isFocused },
                            shape = CircleShape,
                            color = if (isNextButtonFocused) Color.White else Color.Black.copy(alpha = 0.5f),
                            border = if (isNextButtonFocused) null else BorderStroke(2.dp, Color.White.copy(alpha = 0.7f))
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Next Episode",
                                    tint = if (isNextButtonFocused) Color.Black else Color.White,
                                    modifier = Modifier.size(28.dp) 
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(
                            "다음 회차",
                            color = if (isNextButtonFocused) Color.White else Color.White.copy(alpha = 0.7f),
                            fontSize = 14.sp,
                            fontWeight = if (isNextButtonFocused) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }

                // 넷플릭스 스타일 가로 썸네일 리스트 (탐색 시)
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

                // 하단 탐색 바
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
