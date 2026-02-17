package org.nas.videoplayerandroidtv.ui.player

import android.util.Log
import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
    onSeekFinished: (() -> Unit)?
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnVideoEnded by rememberUpdatedState(onVideoEnded)
    val currentOnPositionUpdate by rememberUpdatedState(onPositionUpdate)
    val currentOnDurationDetermined by rememberUpdatedState(onDurationDetermined)
    val currentOnSeekFinished by rememberUpdatedState(onSeekFinished)
    val currentOnControllerVisibilityChanged by rememberUpdatedState(onControllerVisibilityChanged)

    val exoPlayer = remember {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(httpDataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
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
                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int
                    ) {
                        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                            currentOnSeekFinished?.invoke()
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e("VideoPlayer", "ExoPlayer Error: ${error.message}", error)
                    }
                })
            }
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
            if (exoPlayer.isPlaying) {
                currentOnPositionUpdate?.invoke(exoPlayer.currentPosition)
            }
            delay(500)
        }
    }

    LaunchedEffect(url) {
        if (url.isBlank()) return@LaunchedEffect
        Log.d("VideoPlayer", "Playing URL: $url")
        
        val mediaItemBuilder = MediaItem.Builder().setUri(url)
        
        // URL에 .mkv가 포함되어 있거나 type=ftv, type=movies 등이 포함된 경우 MKV MIME 타입 명시
        if (url.lowercase().contains(".mkv") || url.contains("type=ftv") || url.contains("type=movie")) {
            mediaItemBuilder.setMimeType(MimeTypes.VIDEO_MATROSKA)
        } else if (url.lowercase().contains(".ts") || url.lowercase().contains(".tp") || url.contains("type=air")) {
            mediaItemBuilder.setMimeType(MimeTypes.VIDEO_MP2T)
        }
        
        val mediaItem = mediaItemBuilder.build()
        exoPlayer.setMediaItem(mediaItem)
        if (initialPosition > 0) exoPlayer.seekTo(initialPosition)
        exoPlayer.prepare()
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                // XML에서 PlayerView 로드 (TextureView 사용 설정 포함)
                val view = LayoutInflater.from(ctx).inflate(R.layout.player_view, null) as PlayerView
                view.apply {
                    player = exoPlayer
                    // 화면 클릭 시 컨트롤러 표시 콜백
                    setOnClickListener {
                        currentOnControllerVisibilityChanged?.invoke(true)
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
