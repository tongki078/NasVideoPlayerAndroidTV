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

    // [최적화] 서버의 새로운 preview_serve 엔드포인트 활용
    val previewUrl = remember(url) {
        if (url.contains("video_serve")) {
            url.replace("video_serve", "preview_serve")
        } else {
            url
        }
    }

    val exoPlayer = remember {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")

        // [획기적 개선] 버퍼링을 극한으로 줄여 즉시 재생 시도 (소리 인코딩 시간 고려)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                100,   // minBufferMs: 200 -> 100으로 더 단축
                500,   // maxBufferMs: 1000 -> 500으로 더 단축
                100,   // bufferForPlaybackMs
                100    // bufferForPlaybackAfterRebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(httpDataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true // 오디오 포커스 관리 활성화 (소리 재생 필수)
            )
            .build().apply {
                playWhenReady = true
                volume = 0.6f // 미리보기 소리를 60% 정도로 활성화
                repeatMode = Player.REPEAT_MODE_ALL
                addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        isVideoRendered = true
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e("VideoPreview", "❌ 미리보기 에러: ${error.message} (URL: $previewUrl)")
                    }
                })
            }
    }

    LaunchedEffect(previewUrl) {
        if (previewUrl.isBlank()) return@LaunchedEffect
        isVideoRendered = false
        exoPlayer.stop() // 이전 재생 확실히 중단
        val mediaItem = MediaItem.Builder().setUri(previewUrl).build()
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
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
