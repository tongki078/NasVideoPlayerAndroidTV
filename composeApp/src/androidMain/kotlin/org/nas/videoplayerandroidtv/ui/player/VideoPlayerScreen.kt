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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.delay
import org.nas.videoplayerandroidtv.domain.model.Movie
import org.nas.videoplayerandroidtv.data.network.NasApiClient
import org.nas.videoplayerandroidtv.cleanTitle
import org.nas.videoplayerandroidtv.extractEpisode

/**
 * 직접 정의한 Pause 아이콘
 */
val MyPauseIcon: ImageVector
    get() = ImageVector.Builder(
        name = "Pause",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).path(fill = androidx.compose.ui.graphics.SolidColor(Color.White)) {
        moveTo(6f, 19f)
        horizontalLineToRelative(4f)
        verticalLineTo(5f)
        horizontalLineTo(6f)
        verticalLineToRelative(14f)
        close()
        moveTo(14f, 5f)
        verticalLineToRelative(14f)
        horizontalLineToRelative(4f)
        verticalLineTo(5f)
        horizontalLineToRelative(-4f)
        close()
    }.build()

@Composable
fun VideoPlayerScreen(
    movie: Movie,
    playlist: List<Movie> = emptyList(),
    initialPosition: Long = 0L,
    onPositionUpdate: (Long) -> Unit = {},
    onBack: () -> Unit
) {
    var currentMovie by remember(movie) { mutableStateOf(movie) }
    
    var isSeeking by remember(currentMovie.id) { mutableStateOf(false) }
    var userPaused by remember { mutableStateOf(false) }
    var seekTime by remember(currentMovie.id) { mutableLongStateOf(0L) }
    var finalSeekPosition by remember(currentMovie.id) { mutableLongStateOf(-1L) }
    var currentPosition by remember(currentMovie.id) { mutableLongStateOf(0L) }
    var totalDuration by remember(currentMovie.id) { mutableLongStateOf(0L) }
    
    val startPosition = remember(currentMovie.id) { 
        if (currentMovie.id == movie.id) initialPosition else 0L 
    }
    
    var isControllerVisible by remember { mutableStateOf(true) }
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

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

    val introStart = currentMovie.introStart ?: 0L
    val introEnd = currentMovie.introEnd ?: 90000L
    val isDuringOpening = currentPosition in introStart..introEnd

    val seekThumbnails = remember(seekTime, totalDuration) {
        if (totalDuration <= 0) emptyList()
        else {
            val interval = 30000L 
            (-3..3).map { i ->
                (seekTime + (i * interval)).coerceIn(0L, totalDuration)
            }.distinct()
        }
    }

    LaunchedEffect(Unit) {
        mainBoxFocusRequester.requestFocus()
    }

    LaunchedEffect(currentMovie.id, isDuringOpening) {
        delay(1000)
        if (isDuringOpening && currentPosition < 5000L) {
            isControllerVisible = true
            lastInteractionTime = System.currentTimeMillis()
        }
    }

    LaunchedEffect(isControllerVisible, lastInteractionTime, isSeeking, isNextButtonFocused, isSkipOpeningFocused, userPaused) {
        if (isControllerVisible && !isSeeking && !isNextButtonFocused && !isSkipOpeningFocused && !userPaused) {
            delay(3000)
            isControllerVisible = false
        }
    }

    LaunchedEffect(seekThumbnails) {
        if (isSeeking && seekThumbnails.isNotEmpty()) {
            val centerIndex = seekThumbnails.size / 2
            thumbListState.scrollToItem(centerIndex)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(mainBoxFocusRequester)
            .focusable()
            .onKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onKeyEvent false
                
                lastInteractionTime = System.currentTimeMillis()
                val keyCode = keyEvent.nativeKeyEvent.keyCode
                
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                        if (!isControllerVisible) {
                            isControllerVisible = true
                            true
                        } else if (nextMovie != null) {
                            try { nextButtonFocusRequester.requestFocus() } catch(_: Exception) {}
                            true
                        } else false
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (!isControllerVisible) {
                            isControllerVisible = true
                            true
                        } else if (isDuringOpening) {
                            try { skipOpeningFocusRequester.requestFocus() } catch(_: Exception) {}
                            true
                        } else false
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                        isControllerVisible = true
                        if (!isSeeking) { seekTime = currentPosition; isSeeking = true }
                        seekTime = (seekTime - 10000).coerceAtLeast(0L)
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        isControllerVisible = true
                        if (!isSeeking) { seekTime = currentPosition; isSeeking = true }
                        seekTime = (seekTime + 10000).coerceAtMost(totalDuration)
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER -> {
                        if (isSeeking) {
                            finalSeekPosition = seekTime
                            isSeeking = false
                            lastInteractionTime = System.currentTimeMillis()
                            true
                        } else if (isNextButtonFocused || isSkipOpeningFocused) {
                            false
                        } else {
                            userPaused = !userPaused
                            isControllerVisible = true
                            lastInteractionTime = System.currentTimeMillis()
                            true
                        }
                    }
                    android.view.KeyEvent.KEYCODE_BACK -> {
                        if (isSeeking) { isSeeking = false; true }
                        else if (isControllerVisible) { isControllerVisible = false; true }
                        else { onBack(); true }
                    }
                    else -> false
                }
            }
    ) {
        VideoPlayer(
            url = currentMovie.videoUrl ?: "",
            modifier = Modifier.fillMaxSize(),
            initialPosition = startPosition,
            seekToPosition = finalSeekPosition,
            isPlaying = !isSeeking && !userPaused,
            onPositionUpdate = { pos ->
                currentPosition = pos
                onPositionUpdate(pos)
                if (!isSeeking) seekTime = pos
            },
            onDurationDetermined = { dur -> totalDuration = dur },
            onControllerVisibilityChanged = { visible -> isControllerVisible = visible },
            onVideoEnded = { 
                nextMovie?.let { 
                    currentMovie = it 
                    isControllerVisible = true
                    userPaused = false
                } 
            },
            onSeekFinished = {
                finalSeekPosition = -1L
            }
        )

        AnimatedVisibility(
            visible = isSeeking,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f))
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            
            // --- [우측 상단 제목 및 회차 정보] ---
            AnimatedVisibility(
                visible = isControllerVisible && !isSeeking,
                enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
                modifier = Modifier.align(Alignment.TopEnd).zIndex(2f)
            ) {
                Column(
                    modifier = Modifier.padding(top = 32.dp, end = 48.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    val titleText = currentMovie.title ?: ""
                    val cleanBase = titleText.cleanTitle(keepAfterHyphen = true, includeYear = false)
                    val episode = titleText.extractEpisode()

                    // 제목 (더 작게 수정: 20.sp)
                    Text(
                        text = cleanBase,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.End,
                        style = LocalTextStyle.current.copy(
                            shadow = Shadow(Color.Black.copy(alpha = 0.8f), androidx.compose.ui.geometry.Offset(2f, 2f), 4f)
                        ),
                        modifier = Modifier.widthIn(max = 400.dp)
                    )
                    
                    // 회차 정보 (더 작게 수정: 16.sp)
                    if (episode != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = episode,
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Normal,
                            textAlign = TextAlign.End,
                            style = LocalTextStyle.current.copy(
                                shadow = Shadow(Color.Black.copy(alpha = 0.8f), androidx.compose.ui.geometry.Offset(2f, 2f), 4f)
                            )
                        )
                    }
                }
            }

            // --- [플레이어 버튼 레이어] ---
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(48.dp)
            ) {
                // 1. 다음 회차 보기 (좌측 상단)
                AnimatedVisibility(
                    visible = isControllerVisible && nextMovie != null && !isSeeking,
                    enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    NetflixIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowForward,
                        isFocused = isNextButtonFocused,
                        onClick = { 
                            if (nextMovie != null) {
                                currentMovie = nextMovie
                                isControllerVisible = true
                                userPaused = false
                                try { mainBoxFocusRequester.requestFocus() } catch(_: Exception) {}
                            }
                        },
                        modifier = Modifier
                            .focusRequester(nextButtonFocusRequester)
                            .onFocusChanged { isNextButtonFocused = it.isFocused }
                    )
                }

                // 2. 오프닝 건너뛰기 (좌측 하단)
                AnimatedVisibility(
                    visible = isControllerVisible && isDuringOpening && !isSeeking,
                    enter = fadeIn() + slideInHorizontally(initialOffsetX = { -it }),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 80.dp)
                ) {
                    NetflixPlayerButton(
                        text = "오프닝 건너뛰기",
                        icon = Icons.AutoMirrored.Filled.ArrowForward,
                        isFocused = isSkipOpeningFocused,
                        compact = true,
                        onClick = { 
                            finalSeekPosition = introEnd
                            try { mainBoxFocusRequester.requestFocus() } catch(_: Exception) {}
                        },
                        modifier = Modifier
                            .focusRequester(skipOpeningFocusRequester)
                            .onFocusChanged { isSkipOpeningFocused = it.isFocused }
                    )
                }
            }

            // 하단 시크바 및 썸네일 탐색
            AnimatedVisibility(
                visible = isControllerVisible || isSeeking,
                enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().then(
                        if (isSeeking) Modifier else Modifier.background(Color.Black.copy(alpha = 0.4f))
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (isSeeking && totalDuration > 0) {
                        val context = LocalContext.current
                        LazyRow(
                            state = thumbListState,
                            contentPadding = PaddingValues(horizontal = 100.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            userScrollEnabled = false,
                            modifier = Modifier.fillMaxWidth().height(140.dp).padding(bottom = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            items(seekThumbnails, key = { it }) { timestamp ->
                                val isCenter = (timestamp == seekTime)
                                val baseUrl = NasApiClient.BASE_URL
                                val timeSec = timestamp / 1000
                                val thumbRequest = remember<ImageRequest>(currentMovie.id, timestamp) {
                                    val originalThumb = currentMovie.thumbnailUrl ?: ""
                                    val finalUrl = if (originalThumb.contains("?")) "$baseUrl$originalThumb&t=$timeSec"
                                                  else "$baseUrl$originalThumb?t=$timeSec"
                                    ImageRequest.Builder(context).data(finalUrl).crossfade(true).build()
                                }
                                Box(
                                    modifier = Modifier
                                        .width(if (isCenter) 220.dp else 160.dp)
                                        .fillMaxHeight(if (isCenter) 1f else 0.7f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(width = if (isCenter) 3.dp else 1.dp, color = if (isCenter) Color.White else Color.Gray.copy(alpha = 0.5f), shape = RoundedCornerShape(8.dp))
                                        .background(Color.DarkGray)
                                ) {
                                    AsyncImage(model = thumbRequest, contentDescription = "Seek", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 48.dp, start = 48.dp, end = 60.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (userPaused) Icons.Default.PlayArrow else MyPauseIcon,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(64.dp)
                        )
                        
                        Spacer(Modifier.width(24.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            val progress = if (totalDuration > 0) (if(isSeeking) seekTime else currentPosition).toFloat() / totalDuration else 0f
                            Box(modifier = Modifier.fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.3f))) {
                                Box(modifier = Modifier.fillMaxWidth(progress).fillMaxHeight().background(Color.Red))
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(formatTime(if (isSeeking) seekTime else currentPosition), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Text(formatTime(totalDuration), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NetflixIconButton(
    icon: ImageVector,
    isFocused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(44.dp)
            .focusable(),
        shape = CircleShape,
        color = if (isFocused) Color.White else Color.Black.copy(alpha = 0.6f),
        border = if (isFocused) null else BorderStroke(1.dp, Color.White.copy(alpha = 0.8f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isFocused) Color.Black else Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
fun NetflixPlayerButton(
    text: String,
    icon: ImageVector,
    isFocused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .height(if (compact) 40.dp else 48.dp)
            .widthIn(min = if (compact) 100.dp else 160.dp)
            .focusable(),
        shape = RoundedCornerShape(4.dp),
        color = if (isFocused) Color.White else Color.Black.copy(alpha = 0.6f),
        border = if (isFocused) null else BorderStroke(1.dp, Color.White.copy(alpha = 0.8f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (compact) 16.dp else 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isFocused) Color.Black else Color.White,
                modifier = Modifier.size(if (compact) 20.dp else 24.dp)
            )
            Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
            Text(
                text = text,
                color = if (isFocused) Color.Black else Color.White,
                fontSize = if (compact) 14.sp else 16.sp,
                fontWeight = FontWeight.Bold
            )
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
