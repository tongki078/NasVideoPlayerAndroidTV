package org.nas.videoplayerandroidtv.ui.player

import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import org.nas.videoplayerandroidtv.R

@OptIn(UnstableApi::class)
@Composable
actual fun VideoPlayer(
    url: String,
    modifier: Modifier,
    initialPosition: Long,
    seekToPosition: Long,
    isPlaying: Boolean,
    onPositionUpdate: ((Long) -> Unit)?,
    onDurationDetermined: ((Long) -> Unit)?,
    onControllerVisibilityChanged: ((Boolean) -> Unit)?,
    onFullscreenClick: (() -> Unit)?,
    onVideoEnded: (() -> Unit)?,
    onSeekFinished: (() -> Unit)?,
    onSubtitleTracksAvailable: ((List<String>) -> Unit)?,
    selectedSubtitleIndex: Int,
    onSubtitleSelected: ((Int) -> Unit)?
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val currentOnVideoEnded by rememberUpdatedState(onVideoEnded)
    val currentOnPositionUpdate by rememberUpdatedState(onPositionUpdate)
    val currentOnDurationDetermined by rememberUpdatedState(onDurationDetermined)
    val currentOnSeekFinished by rememberUpdatedState(onSeekFinished)
    val currentOnControllerVisibilityChanged by rememberUpdatedState(onControllerVisibilityChanged)

    val subtitleTrackGroups = remember { mutableStateListOf<Tracks.Group>() }

    val exoPlayer = remember {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("NasVideoPlayer/1.0 (Android TV; ExoPlayer)")

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(httpDataSourceFactory))
            .build().apply {
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) {
                            currentOnVideoEnded?.invoke()
                        }
                        if (playbackState == Player.STATE_READY) {
                            currentOnDurationDetermined?.invoke(duration)
                        }
                    }
                    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                        if (reason == Player.DISCONTINUITY_REASON_SEEK) currentOnSeekFinished?.invoke()
                    }
                    override fun onTracksChanged(tracks: Tracks) {
                        val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                        subtitleTrackGroups.clear()
                        subtitleTrackGroups.addAll(textGroups)
                        val names = textGroups.mapIndexed { index, group ->
                            val format = group.getTrackFormat(0)
                            format.label ?: format.language ?: "자막 ${index + 1}"
                        }
                        onSubtitleTracksAvailable?.invoke(names)
                    }
                })
            }
    }

    LaunchedEffect(url) {
        if (url.isNotBlank()) {
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse(url))
                .build()
            exoPlayer.setMediaItem(mediaItem, initialPosition)
            exoPlayer.prepare()
        }
    }

    // 자막 트랙 선택 로직 보강
    LaunchedEffect(selectedSubtitleIndex, subtitleTrackGroups.size) {
        val parametersBuilder = exoPlayer.trackSelectionParameters.buildUpon()
        
        when (selectedSubtitleIndex) {
            -1 -> { // 자막 끔
                parametersBuilder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                parametersBuilder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
                // 강제 자막 속성 등도 모두 무시하도록 설정
                parametersBuilder.setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED)
            }
            -2 -> { // 자동 (기본)
                parametersBuilder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                parametersBuilder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
                parametersBuilder.setIgnoredTextSelectionFlags(0)
            }
            else -> { // 특정 자막 선택
                if (selectedSubtitleIndex in subtitleTrackGroups.indices) {
                    parametersBuilder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    parametersBuilder.setOverrideForType(
                        TrackSelectionOverride(
                            subtitleTrackGroups[selectedSubtitleIndex].mediaTrackGroup,
                            0
                        )
                    )
                }
            }
        }
        exoPlayer.trackSelectionParameters = parametersBuilder.build()
    }

    LaunchedEffect(seekToPosition) {
        if (seekToPosition >= 0) {
            exoPlayer.seekTo(seekToPosition)
            exoPlayer.play()
        }
    }

    LaunchedEffect(isPlaying) {
        if (isPlaying) exoPlayer.play() else exoPlayer.pause()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> exoPlayer.pause()
                Lifecycle.Event.ON_RESUME -> if (isPlaying) exoPlayer.play()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.stop()
            exoPlayer.release()
        }
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            if (exoPlayer.isPlaying) currentOnPositionUpdate?.invoke(exoPlayer.currentPosition)
            delay(500)
        }
    }

    Box(modifier = modifier.background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                val view = LayoutInflater.from(ctx).inflate(R.layout.player_view, null) as PlayerView
                view.apply {
                    player = exoPlayer
                    useController = false
                    setOnClickListener { currentOnControllerVisibilityChanged?.invoke(true) }
                }
            },
            update = { view ->
                // UI 레벨에서도 자막 뷰의 가시성을 확실히 제어
                view.subtitleView?.let { 
                    it.visibility = if (selectedSubtitleIndex == -1) View.GONE else View.VISIBLE
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
