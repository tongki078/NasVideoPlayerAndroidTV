package org.nas.videoplayerandroidtv.ui.home

import android.graphics.Color
import android.util.Log
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlin.OptIn

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(UnstableApi::class)
@Composable
fun VideoPreview(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var isVideoRendered by remember { mutableStateOf(false) }
    var hasSoughtToMiddle by remember { mutableStateOf(false) }
    val previewDurationMillis = 20000L
    
    val exoPlayer = remember {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")

        // 로딩 속도 최적화를 위한 LoadControl 설정
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                5000,  // minBufferMs
                15000, // maxBufferMs
                500,   // bufferForPlaybackMs (재생 시작에 필요한 최소 버퍼 - 0.5초로 단축)
                1000   // bufferForPlaybackAfterRebufferMs
            )
            .build()

        ExoPlayer.Builder(context)
            .setLoadControl(loadControl) // 최적화된 로드 컨트롤 적용
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(httpDataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .build().apply {
                playWhenReady = true
                volume = 1f
                repeatMode = Player.REPEAT_MODE_ONE
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY && !hasSoughtToMiddle) {
                            val duration = duration
                            if (duration > 0 && duration != C.TIME_UNSET) {
                                seekTo(duration / 3)
                                hasSoughtToMiddle = true
                            }
                        }
                    }

                    override fun onRenderedFirstFrame() {
                        isVideoRendered = true
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e("VideoPreview", "❌ 미리보기 에러: ${error.message} (URL: $url)")
                    }
                })
            }
    }

    LaunchedEffect(url) {
        if (url.isBlank()) return@LaunchedEffect
        isVideoRendered = false
        hasSoughtToMiddle = false

        val mediaItem = MediaItem.Builder().setUri(url).build()
        exoPlayer.setMediaItem(mediaItem)
        
        // 중요: prepare() 전 미리 하이라이트 지점으로 추정되는 곳을 찍어두면 더 빨리 로딩될 수 있음
        // (정확한 길이를 모를 경우 일단 prepare 후 seek 유지)
        exoPlayer.prepare()
        
        delay(previewDurationMillis)
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.stop()
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShutterBackgroundColor(Color.TRANSPARENT)
            }
        },
        modifier = modifier
            .alpha(if (isVideoRendered) 1f else 0f)
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
    )
}
