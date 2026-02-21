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
import org.nas.videoplayerandroidtv.domain.model.SubtitleTrack
import org.nas.videoplayerandroidtv.data.network.NasApiClient
import java.net.URLEncoder

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
    onSubtitleSelected: ((Int) -> Unit)?,
    externalSubtitles: List<SubtitleTrack>
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
                // [추가] 기본적으로 한국어 자막을 선호하도록 설정
                val params = trackSelectionParameters.buildUpon()
                    .setPreferredTextLanguage("ko")
                    .setSelectUndeterminedTextLanguage(true)
                    .build()
                trackSelectionParameters = params

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) currentOnVideoEnded?.invoke()
                        if (playbackState == Player.STATE_READY) currentOnDurationDetermined?.invoke(duration)
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
                        
                        if (selectedSubtitleIndex == -1) {
                            val builder = trackSelectionParameters.buildUpon()
                            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            trackSelectionParameters = builder.build()
                        }
                    }
                })
            }
    }

    // URL 및 외부 자막 로드 로직 (재생 위치 유지)
    LaunchedEffect(url, externalSubtitles) {
        if (url.isNotBlank()) {
            // 현재 재생 중인 위치를 캡처 (자막이 중간에 추가되었을 때 끊김 방지)
            val currentPos = if (exoPlayer.playbackState != Player.STATE_IDLE) {
                exoPlayer.currentPosition
            } else {
                initialPosition
            }

            val mediaItemBuilder = MediaItem.Builder()
                .setUri(Uri.parse(url))
            
            // 외부 자막 설정 (URL 인코딩 적용)
            val subtitleConfigs = externalSubtitles.map { track ->
                val encodedPath = URLEncoder.encode(track.path, "UTF-8")
                val subUrl = if (track.path.startsWith("http")) track.path 
                             else "${NasApiClient.BASE_URL}/api/subtitle_extract?sub_path=$encodedPath"
                
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUrl))
                    .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                    .setLanguage("ko")
                    .setLabel(track.name)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED)
                    .build()
            }
            mediaItemBuilder.setSubtitleConfigurations(subtitleConfigs)
            
            exoPlayer.setMediaItem(mediaItemBuilder.build(), currentPos)
            exoPlayer.prepare()
        }
    }

    LaunchedEffect(selectedSubtitleIndex, subtitleTrackGroups.size) {
        val builder = exoPlayer.trackSelectionParameters.buildUpon()
        when (selectedSubtitleIndex) {
            -1 -> {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                builder.setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED)
            }
            -2 -> {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
                builder.setPreferredTextLanguage("ko") // 자동 모드에서도 한국어 선호
            }
            else -> {
                if (selectedSubtitleIndex in subtitleTrackGroups.indices) {
                    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    builder.setOverrideForType(
                        TrackSelectionOverride(subtitleTrackGroups[selectedSubtitleIndex].mediaTrackGroup, 0)
                    )
                }
            }
        }
        exoPlayer.trackSelectionParameters = builder.build()
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
                val subView = view.subtitleView
                if (subView != null) {
                    if (selectedSubtitleIndex == -1) {
                        subView.visibility = View.GONE
                        subView.setCues(null)
                    } else {
                        subView.visibility = View.VISIBLE
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
