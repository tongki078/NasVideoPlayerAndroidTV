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

    val exoPlayer = remember {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                500,   // minBufferMs: 버퍼링 시간을 줄여 미리보기를 매우 빠르게 시작합니다.
                2000,  // maxBufferMs
                500,   // bufferForPlaybackMs
                500   // bufferForPlaybackAfterRebufferMs: minBufferMs보다 작거나 같아야 합니다.
            )
            .build()

        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
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
                volume = 0f // 미리보기는 기본 음소거
                repeatMode = Player.REPEAT_MODE_ALL
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        Log.d("VideoPreview", "🎬 재생 상태 변경: $state (URL: $url)")
                        if (state == Player.STATE_READY && !hasSoughtToMiddle) {
                            val duration = duration
                            if (duration > 0 && duration != C.TIME_UNSET) {
                                // 로딩 속도 개선을 위해 탐색 시간을 1분(60,000ms)으로 조정합니다.
                                val seekPosition = if (duration > 60000L) 60000L else duration / 4
                                seekTo(seekPosition)
                                hasSoughtToMiddle = true
                            }
                        }
                    }

                    override fun onRenderedFirstFrame() {
                        Log.d("VideoPreview", "✅ 첫 프레임 렌더링 완료")
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
        Log.d("VideoPreview", "🔄 미리보기 로딩 시작: $url")
        isVideoRendered = false
        hasSoughtToMiddle = false

        val mediaItem = MediaItem.Builder().setUri(url).build()
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
    }

    DisposableEffect(Unit) {
        onDispose {
            Log.d("VideoPreview", "⏏️ 미리보기 플레이어 해제")
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
