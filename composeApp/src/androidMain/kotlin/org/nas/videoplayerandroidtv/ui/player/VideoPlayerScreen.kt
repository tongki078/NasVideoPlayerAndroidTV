package org.nas.videoplayerandroidtv.ui.player

import android.util.Log
import android.view.LayoutInflater
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.nas.videoplayerandroidtv.domain.model.Movie
import org.nas.videoplayerandroidtv.data.network.NasApiClient
import org.nas.videoplayerandroidtv.domain.repository.VideoRepository
import org.nas.videoplayerandroidtv.util.TitleUtils.cleanTitle
import org.nas.videoplayerandroidtv.util.TitleUtils.extractEpisode
import org.nas.videoplayerandroidtv.util.TitleUtils.extractSeason

/**
 * Pause 아이콘
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
    repository: VideoRepository,
    onPositionUpdate: (Long, Long) -> Unit = { _, _ -> },
    onNextMovie: (Movie) -> Unit,
    onBack: () -> Unit
) {
    // 🔴 무조건 실행되는 로그 추가
    Log.d("VideoPlayerDebug", "Movie Data: title=${movie.title}, season=${movie.season_number}, ep=${movie.episode_number}")

    val scope = rememberCoroutineScope()

    // key를 movie.id로 지정하여 movie가 바뀌면 상태를 초기화하도록 수정
    var isSeeking by remember(movie.id) { mutableStateOf(false) }
    var userPaused by remember(movie.id) { mutableStateOf(false) }
    var seekTime by remember(movie.id) { mutableLongStateOf(0L) }
    var finalSeekPosition by remember(movie.id) { mutableLongStateOf(-1L) }
    var currentPosition by remember(movie.id) { mutableLongStateOf(0L) }
    var totalDuration by remember(movie.id) { mutableLongStateOf(0L) }

    val startPosition = remember(movie.id) {
        val serverPos = ((movie.position ?: 0.0) * 1000).toLong()
        val durationMs = ((movie.duration ?: 0.0) * 1000).toLong()

        // [버그 수정] 다 본 영상(95% 이상 시청)을 다시 클릭했을 때
        // 자동으로 다음 화로 넘어가는 현상을 방지하기 위해, 다 본 영상은 처음부터 재생함.
        if (durationMs > 0 && serverPos > durationMs * 0.95) {
            0L
        } else if (serverPos > 0) {
            serverPos
        } else {
            initialPosition
        }
    }

    var isControllerVisible by remember { mutableStateOf(true) }
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    var subtitleTracks by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedSubtitleIndex by remember { mutableStateOf(-2) }
    var isSubtitlePanelOpen by remember { mutableStateOf(false) }

    val mainBoxFocusRequester = remember { FocusRequester() }
    val backButtonFocusRequester = remember { FocusRequester() }
    val replayButtonFocusRequester = remember { FocusRequester() }
    val nextButtonFocusRequester = remember { FocusRequester() }
    val skipOpeningFocusRequester = remember { FocusRequester() }
    val subtitleButtonFocusRequester = remember { FocusRequester() }

    val thumbListState = rememberLazyListState()

    var isBackButtonFocused by remember { mutableStateOf(false) }
    var isReplayButtonFocused by remember { mutableStateOf(false) }
    var isNextButtonFocused by remember { mutableStateOf(false) }
    var isSkipOpeningFocused by remember { mutableStateOf(false) }
    var isSubtitleButtonFocused by remember { mutableStateOf(false) }

    val nextMovie = remember(movie, playlist) {
        val currentIndex = playlist.indexOfFirst { it.id == movie.id }
        if (currentIndex != -1 && currentIndex < playlist.size - 1) {
            playlist[currentIndex + 1]
        } else null
    }

    val introEnd = movie.introEnd ?: 90000L
    val isDuringOpening = currentPosition in (movie.introStart ?: 0L)..introEnd

    val allThumbnails = remember(totalDuration) {
        if (totalDuration <= 0) emptyList()
        else (0L..totalDuration step 10000L).toList()
    }

    LaunchedEffect(Unit) {
        mainBoxFocusRequester.requestFocus()
    }

    LaunchedEffect(currentPosition, totalDuration, movie.id) {
        if (currentPosition > 0 && totalDuration > 0 && !isSeeking) {
            onPositionUpdate(currentPosition, totalDuration)
            if (currentPosition % 10000 < 1000 || currentPosition > totalDuration - 5000) {
                scope.launch {
                    repository.updateProgress(
                        movie.id ?: "",
                        currentPosition / 1000.0,
                        totalDuration / 1000.0
                    )
                }
            }
        }
    }

    LaunchedEffect(isControllerVisible, lastInteractionTime, isSeeking, isNextButtonFocused, isBackButtonFocused, isReplayButtonFocused, isSkipOpeningFocused, isSubtitleButtonFocused, userPaused, isSubtitlePanelOpen) {
        if (isControllerVisible && !isSeeking && !isNextButtonFocused && !isBackButtonFocused && !isReplayButtonFocused && !isSkipOpeningFocused && !isSubtitleButtonFocused && !userPaused && !isSubtitlePanelOpen) {
            delay(5000)
            isControllerVisible = false
        }
    }

    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val horizontalPadding = (screenWidth - 280.dp) / 2

    LaunchedEffect(seekTime, isSeeking) {
        if (isSeeking && allThumbnails.isNotEmpty()) {
            val targetIndex = (seekTime / 10000L).toInt().coerceIn(allThumbnails.indices)
            thumbListState.animateScrollToItem(targetIndex, 0)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(mainBoxFocusRequester)
            .focusable()
            .onKeyEvent { keyEvent ->
                if (isSubtitlePanelOpen) return@onKeyEvent false
                if (keyEvent.type != KeyEventType.KeyDown) return@onKeyEvent false

                lastInteractionTime = System.currentTimeMillis()
                val keyCode = keyEvent.nativeKeyEvent.keyCode

                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                        if (!isControllerVisible) { isControllerVisible = true; true }
                        else if (isSubtitleButtonFocused || isSkipOpeningFocused) {
                            backButtonFocusRequester.requestFocus()
                            true
                        } else if (!isBackButtonFocused && !isReplayButtonFocused && !isNextButtonFocused) {
                            backButtonFocusRequester.requestFocus()
                            true
                        } else false
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (!isControllerVisible) { isControllerVisible = true; true }
                        else if (isBackButtonFocused || isReplayButtonFocused || isNextButtonFocused) {
                            mainBoxFocusRequester.requestFocus()
                            true
                        } else {
                            if (isDuringOpening) {
                                try { skipOpeningFocusRequester.requestFocus() } catch(_:Exception) { subtitleButtonFocusRequester.requestFocus() }
                            } else {
                                try { subtitleButtonFocusRequester.requestFocus() } catch(_:Exception) { mainBoxFocusRequester.requestFocus() }
                            }
                            true
                        }
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (isBackButtonFocused || isReplayButtonFocused || isNextButtonFocused) {
                            false
                        } else if (isSubtitleButtonFocused && isDuringOpening) {
                            try { skipOpeningFocusRequester.requestFocus() } catch(_:Exception) {}
                            true
                        }
                        else {
                            isControllerVisible = true
                            if (!isSeeking) { seekTime = currentPosition; isSeeking = true }
                            seekTime = (seekTime - 10000).coerceAtLeast(0L)
                            true
                        }
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (isBackButtonFocused || isReplayButtonFocused || isNextButtonFocused) {
                            false
                        } else if (isSkipOpeningFocused) {
                            try { subtitleButtonFocusRequester.requestFocus() } catch(_:Exception) {}
                            true
                        }
                        else {
                            isControllerVisible = true
                            if (!isSeeking) { seekTime = currentPosition; isSeeking = true }
                            seekTime = (seekTime + 10000).coerceAtMost(totalDuration)
                            true
                        }
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER -> {
                        if (isSeeking) {
                            finalSeekPosition = seekTime
                            isSeeking = false
                            true
                        } else if (isSkipOpeningFocused) {
                            finalSeekPosition = introEnd
                            isControllerVisible = false
                            mainBoxFocusRequester.requestFocus()
                            true
                        } else if (isBackButtonFocused || isReplayButtonFocused || isNextButtonFocused || isSubtitleButtonFocused) {
                            false
                        } else {
                            userPaused = !userPaused
                            isControllerVisible = true
                            true
                        }
                    }
                    android.view.KeyEvent.KEYCODE_BACK -> {
                        if (isSeeking) { isSeeking = false; seekTime = currentPosition; true }
                        else if (isControllerVisible) { isControllerVisible = false; true }
                        else { onBack(); true }
                    }
                    else -> false
                }
            }
    ) {
        VideoPlayer(
            url = movie.videoUrl ?: "",
            modifier = Modifier.fillMaxSize(),
            initialPosition = startPosition,
            seekToPosition = finalSeekPosition,
            isPlaying = !isSeeking && !userPaused && !isSubtitlePanelOpen,
            onPositionUpdate = { pos -> currentPosition = pos; if (!isSeeking) seekTime = pos },
            onDurationDetermined = { dur -> totalDuration = dur },
            onControllerVisibilityChanged = { visible -> isControllerVisible = visible },
            onVideoEnded = { nextMovie?.let { onNextMovie(it) } },
            onSeekFinished = { finalSeekPosition = -1L },
            onSubtitleTracksAvailable = { subtitleTracks = it },
            selectedSubtitleIndex = selectedSubtitleIndex,
            onSubtitleSelected = { }
        )

        AnimatedVisibility(visible = isSeeking, enter = fadeIn(), exit = fadeOut()) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)))
        }

        Box(modifier = Modifier.fillMaxSize()) {
            key(movie.id) {
                AnimatedVisibility(
                    visible = isControllerVisible && !isSeeking,
                    enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopEnd).zIndex(2f)
                ) {
                    Column(
                        modifier = Modifier.padding(top = 32.dp, end = 48.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(
                            text = movie.title ?: "",
                            color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                            style = LocalTextStyle.current.copy(shadow = Shadow(Color.Black, androidx.compose.ui.geometry.Offset(2f, 2f), 4f))
                        )

                        val season = movie.season_number ?: 0
                        val episode = movie.episode_number ?: 0

                        val infoLabel = buildString {
                            if (season > 0) append("시즌 $season ")
                            if (episode > 0) append(": ${episode}화")
                        }

                        if (infoLabel.isNotBlank()) {
                            Text(
                                text = infoLabel,
                                color = Color.White.copy(alpha = 0.8f), fontSize = 18.sp,
                                style = LocalTextStyle.current.copy(shadow = Shadow(Color.Black, androidx.compose.ui.geometry.Offset(2f, 2f), 4f))
                            )
                        }
                    }
                }
            }

            Box(modifier = Modifier.fillMaxSize().padding(48.dp)) {
                AnimatedVisibility(
                    visible = isControllerVisible && !isSeeking,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        NetflixIconButton(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            isFocused = isBackButtonFocused,
                            onClick = { onBack() },
                            modifier = Modifier.focusRequester(backButtonFocusRequester).onFocusChanged { isBackButtonFocused = it.isFocused }
                        )

                        NetflixIconButton(
                            icon = Icons.Default.Refresh,
                            isFocused = isReplayButtonFocused,
                            onClick = { finalSeekPosition = 0L; mainBoxFocusRequester.requestFocus() },
                            modifier = Modifier.focusRequester(replayButtonFocusRequester).onFocusChanged { isReplayButtonFocused = it.isFocused }
                        )

                        if (nextMovie != null) {
                            NetflixIconButton(
                                icon = Icons.AutoMirrored.Filled.ArrowForward,
                                isFocused = isNextButtonFocused,
                                onClick = { nextMovie.let { onNextMovie(it) }; mainBoxFocusRequester.requestFocus() },
                                modifier = Modifier.focusRequester(nextButtonFocusRequester).onFocusChanged { isNextButtonFocused = it.isFocused }
                            )
                        }
                    }
                }

                // 오프닝 건너뛰기 버튼
                if (isDuringOpening) {
                    AnimatedVisibility(
                        visible = isControllerVisible && !isSeeking,
                        enter = fadeIn() + slideInHorizontally(),
                        exit = fadeOut() + slideOutHorizontally(),
                        modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 96.dp).zIndex(5f)
                    ) {
                        NetflixPlayerButton(
                            text = "오프닝 건너뛰기",
                            icon = Icons.AutoMirrored.Filled.ArrowForward,
                            isFocused = isSkipOpeningFocused,
                            onClick = {
                                finalSeekPosition = introEnd
                                isControllerVisible = false
                                mainBoxFocusRequester.requestFocus()
                            },
                            modifier = Modifier.focusRequester(skipOpeningFocusRequester).onFocusChanged { isSkipOpeningFocused = it.isFocused }
                        )
                    }
                }

                AnimatedVisibility(visible = isControllerVisible && !isSeeking, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 80.dp)) {
                    NetflixIconButton(Icons.Default.Settings, isSubtitleButtonFocused, onClick = { isSubtitlePanelOpen = true },
                        modifier = Modifier.focusRequester(subtitleButtonFocusRequester).onFocusChanged { isSubtitleButtonFocused = it.isFocused })
                }
            }

            AnimatedVisibility(visible = (isControllerVisible || isSeeking), enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                Column(modifier = Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (isSeeking && totalDuration > 0) {
                        Box(modifier = Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                            LazyRow(state = thumbListState, contentPadding = PaddingValues(horizontal = horizontalPadding), horizontalArrangement = Arrangement.spacedBy(20.dp), userScrollEnabled = false, modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                                itemsIndexed(allThumbnails) { _, timestamp ->
                                    Box(modifier = Modifier.width(280.dp).height(160.dp).clip(RoundedCornerShape(8.dp)).background(Color.DarkGray).border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(8.dp))) {
                                        AsyncImage(model = ImageRequest.Builder(LocalContext.current).data("${movie.videoUrl?.replace("video_serve", "thumb_serve")}&id=${movie.id}&t=${timestamp/1000}").crossfade(true).build(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    }
                                }
                            }
                            Box(modifier = Modifier.width(292.dp).height(172.dp).border(4.dp, Color.White, RoundedCornerShape(10.dp)).zIndex(1f))
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                    }
                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 48.dp, start = 48.dp, end = 60.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (userPaused) Icons.Default.PlayArrow else MyPauseIcon, null, tint = Color.White, modifier = Modifier.size(56.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            val progress = if (totalDuration > 0) (if(isSeeking) seekTime else currentPosition).toFloat() / totalDuration else 0f
                            Box(modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f))) {
                                Box(modifier = Modifier.fillMaxWidth(progress).fillMaxHeight().background(Color.Red))
                            }
                            Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(formatTime(if (isSeeking) seekTime else currentPosition), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                Text(formatTime(totalDuration), color = Color.White.copy(alpha = 0.6f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        if (isSubtitlePanelOpen) {
            Dialog(
                onDismissRequest = { isSubtitlePanelOpen = false; mainBoxFocusRequester.requestFocus() },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).clickable {
                        isSubtitlePanelOpen = false
                        mainBoxFocusRequester.requestFocus()
                    })

                    Surface(
                        modifier = Modifier.fillMaxHeight().width(450.dp).align(Alignment.CenterEnd),
                        color = Color.Black,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
                    ) {
                        Column(modifier = Modifier.padding(48.dp).fillMaxSize()) {
                            Text("자막", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 32.dp))

                            val options = listOf("자막 끔") + subtitleTracks
                            LazyColumn(modifier = Modifier.weight(1f)) {
                                itemsIndexed(options) { index, title ->
                                    val trackIdx = index - 1
                                    val isSelected = selectedSubtitleIndex == trackIdx
                                    var isItemFocused by remember { mutableStateOf(false) }
                                    val itemFocusRequester = remember { FocusRequester() }

                                    if (index == 0) {
                                        LaunchedEffect(Unit) {
                                            delay(150)
                                            try { itemFocusRequester.requestFocus() } catch(_:Exception) {}
                                        }
                                    }

                                    Surface(
                                        onClick = {
                                            selectedSubtitleIndex = trackIdx
                                            isSubtitlePanelOpen = false
                                            mainBoxFocusRequester.requestFocus()
                                        },
                                        color = if (isItemFocused) Color.White.copy(alpha = 0.2f) else Color.Transparent,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .focusRequester(itemFocusRequester)
                                            .onFocusChanged { isItemFocused = it.isFocused }
                                            .focusable()
                                    ) {
                                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text(text = title, color = if (isSelected) Color.Red else Color.White, fontSize = 22.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            if (isSelected) { Icon(Icons.Default.Check, null, tint = Color.Red, modifier = Modifier.size(32.dp)) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NetflixIconButton(icon: ImageVector, isFocused: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, modifier = modifier.size(56.dp).focusable(), shape = CircleShape, color = if (isFocused) Color.White else Color.Black.copy(alpha = 0.5f), border = if (isFocused) null else BorderStroke(2.dp, Color.White.copy(alpha = 0.8f))) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = if (isFocused) Color.Black else Color.White, modifier = Modifier.size(32.dp)) }
    }
}

@Composable
fun NetflixPlayerButton(text: String, icon: ImageVector, isFocused: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    Surface(onClick = onClick, modifier = modifier.height(if (compact) 48.dp else 56.dp).widthIn(min = 160.dp).focusable(), shape = RoundedCornerShape(8.dp), color = if (isFocused) Color.White else Color.Black.copy(alpha = 0.5f), border = if (isFocused) null else BorderStroke(2.dp, Color.White.copy(alpha = 0.8f))) {
        Row(modifier = Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(icon, null, tint = if (isFocused) Color.Black else Color.White, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Text(text, color = if (isFocused) Color.Black else Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
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
