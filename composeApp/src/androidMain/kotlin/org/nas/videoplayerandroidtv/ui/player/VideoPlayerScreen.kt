package org.nas.videoplayerandroidtv.ui.player

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
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import org.nas.videoplayerandroidtv.ui.player.VideoPlayer
import org.nas.videoplayerandroidtv.domain.model.Movie
import org.nas.videoplayerandroidtv.data.network.NasApiClient

/**
 * Android TV 전용 넷플릭스 스타일 플레이어 스크린
 * 팅김 방지를 위해 모든 방향키 이벤트를 앱 내에서 완전히 소비함
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
    val skipOpeningFocusRequester = remember { FocusRequester() }
    val thumbListState = rememberLazyListState()

    var isNextButtonFocused by remember { mutableStateOf(false) }
    var isSkipOpeningFocused by remember { mutableStateOf(false) }

    val nextMovie = remember(currentMovie, playlist) {
        val currentIndex = playlist.indexOfFirst { it.id == currentMovie.id }
        if (currentIndex != -1 && currentIndex < playlist.size - 1) {
            playlist[currentIndex + 1]
        } else null
    }

    // --- [정교한 오프닝 구간 설정] ---
    val introStart = currentMovie.introStart ?: 5000L
    val introEnd = currentMovie.introEnd ?: 90000L
    val isDuringOpening = currentPosition in introStart..introEnd

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

    // 컨트롤러 자동 숨김 타이머 (버튼에 포커스가 없을 때만 작동)
    LaunchedEffect(isControllerVisible, lastInteractionTime, isSeeking, isNextButtonFocused, isSkipOpeningFocused) {
        if (isControllerVisible && !isSeeking && !isNextButtonFocused && !isSkipOpeningFocused) {
            delay(5000)
            isControllerVisible = false
        }
    }

    // 탐색 시 썸네일 리스트 중앙 정렬 유지
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
                val keyCode = keyEvent.nativeKeyEvent.keyCode
                
                // --- [팅김 방지 핵심 로직] ---
                // 리모컨 신호가 앱 밖(시스템 검색 UI 등)으로 나가지 않도록 차단
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP || 
                    keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                    
                    if (keyEvent.type == KeyEventType.KeyDown) {
                        lastInteractionTime = System.currentTimeMillis()
                        if (!isControllerVisible) {
                            isControllerVisible = true
                        } else if (!isSeeking) {
                            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP && nextMovie != null) {
                                nextButtonFocusRequester.requestFocus()
                            } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN && isDuringOpening) {
                                skipOpeningFocusRequester.requestFocus()
                            }
                        }
                    }
                    return@onKeyEvent true // KeyDown, KeyUp 모두 true 반환하여 시스템 UI 트리거 완전 차단
                }

                if (keyEvent.type == KeyEventType.KeyDown) {
                    lastInteractionTime = System.currentTimeMillis()
                    when (keyCode) {
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
                onPositionUpdate(pos)
                if (!isSeeking) seekTime = pos
            },
            onDurationDetermined = { dur -> totalDuration = dur },
            onControllerVisibilityChanged = { visible -> isControllerVisible = visible },
            onVideoEnded = { nextMovie?.let { currentMovie = it } }
        )

        // UI 오버레이
        Box(modifier = Modifier.fillMaxSize()) {
            
            // --- [좌측 상단: 다음 회차 버튼] ---
            AnimatedVisibility(
                visible = isControllerVisible && nextMovie != null && !isSeeking,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopStart)
            ) {
                Column(
                    modifier = Modifier.padding(48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        onClick = { 
                            nextMovie?.let { 
                                currentMovie = it
                                isControllerVisible = true
                                mainBoxFocusRequester.requestFocus() 
                            }
                        },
                        modifier = Modifier
                            .size(50.dp) 
                            .focusRequester(nextButtonFocusRequester)
                            .onFocusChanged { isNextButtonFocused = it.isFocused }
                            .focusable()
                            .onKeyEvent { 
                                // 버튼 내부에서도 방향키 시스템 유출 차단
                                val k = it.nativeKeyEvent.keyCode
                                k == android.view.KeyEvent.KEYCODE_DPAD_UP || k == android.view.KeyEvent.KEYCODE_DPAD_DOWN 
                            },
                        shape = CircleShape,
                        color = if (isNextButtonFocused) Color.White else Color.Black.copy(alpha = 0.5f),
                        border = if (isNextButtonFocused) null else BorderStroke(2.dp, Color.White.copy(alpha = 0.7f))
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.PlayArrow, "Next", tint = if (isNextButtonFocused) Color.Black else Color.White, modifier = Modifier.size(28.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("다음 회차", color = if (isNextButtonFocused) Color.White else Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                }
            }

            // --- [우측 하단: 오프닝 건너뛰기 버튼] ---
            AnimatedVisibility(
                visible = isDuringOpening && !isSeeking,
                enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomEnd)
            ) {
                Surface(
                    onClick = { 
                        finalSeekPosition = introEnd // 정확한 오프닝 종료 지점으로 점프
                        mainBoxFocusRequester.requestFocus()
                    },
                    modifier = Modifier
                        .padding(bottom = 120.dp, end = 48.dp)
                        .focusRequester(skipOpeningFocusRequester)
                        .onFocusChanged { isSkipOpeningFocused = it.isFocused }
                        .focusable()
                        .onKeyEvent { 
                            val k = it.nativeKeyEvent.keyCode
                            k == android.view.KeyEvent.KEYCODE_DPAD_UP || k == android.view.KeyEvent.KEYCODE_DPAD_DOWN 
                        },
                    shape = RoundedCornerShape(4.dp),
                    color = if (isSkipOpeningFocused) Color.White else Color.Black.copy(alpha = 0.6f),
                    border = if (isSkipOpeningFocused) null else BorderStroke(1.dp, Color.White.copy(alpha = 0.6f))
                ) {
                    Row(modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PlayArrow, "Skip", tint = if (isSkipOpeningFocused) Color.Black else Color.White, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("오프닝 건너뛰기", color = if (isSkipOpeningFocused) Color.Black else Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // --- [하단 영역: 시크바 및 썸네일 탐색창] ---
            AnimatedVisibility(
                visible = isControllerVisible || isSeeking,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.4f)),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 넷플릭스 스타일 가로 썸네일 리스트 (isSeeking일 때만 표시)
                    if (isSeeking && totalDuration > 0) {
                        LazyRow(
                            state = thumbListState,
                            contentPadding = PaddingValues(horizontal = 100.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            userScrollEnabled = false,
                            modifier = Modifier.fillMaxWidth().height(120.dp).padding(bottom = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            items(seekThumbnails) { timestamp ->
                                val isCenter = (timestamp == seekTime)
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
                                        .border(width = if (isCenter) 3.dp else 1.dp, color = if (isCenter) Color.White else Color.Gray.copy(alpha = 0.5f), shape = RoundedCornerShape(8.dp))
                                        .background(Color.DarkGray)
                                ) {
                                    AsyncImage(model = thumbUrl, contentDescription = "Seek", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                }
                            }
                        }
                    }

                    Column(modifier = Modifier.padding(bottom = 48.dp, start = 60.dp, end = 60.dp)) {
                        val progress = if (totalDuration > 0) seekTime.toFloat() / totalDuration else 0f
                        Box(modifier = Modifier.fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.3f))) {
                            Box(modifier = Modifier.fillMaxWidth(progress).fillMaxHeight().background(Color.Red))
                        }
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(formatTime(if (isSeeking) seekTime else currentPosition), color = Color.White, fontSize = 14.sp)
                            Text(formatTime(totalDuration), color = Color.White, fontSize = 14.sp)
                        }
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
    return if (h > 0) "${h}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    else "${m}:${s.toString().padStart(2, '0')}"
}
