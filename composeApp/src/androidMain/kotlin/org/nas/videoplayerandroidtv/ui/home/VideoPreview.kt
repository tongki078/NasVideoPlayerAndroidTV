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
    var isVideoReady by remember { mutableStateOf(false) }
    // 미리보기 재생 시간 제한 (20초)
    val previewDurationMillis = 20000L
    
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
            .build().apply {
                playWhenReady = true
                volume = 1f
                repeatMode = Player.REPEAT_MODE_ONE
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            isVideoReady = true
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e("VideoPreview", "❌ 미리보기 에러: ${error.message} (URL: $url)")
                    }
                })
            }
    }

    LaunchedEffect(url) {
        if (url.isBlank()) return@LaunchedEffect
        val mediaItem = MediaItem.Builder().setUri(url).build()
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        
        // 일정 시간 재생 후 정지
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
                // 영상을 16:9 영역에 꽉 채우도록 설정
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShutterBackgroundColor(Color.TRANSPARENT)
            }
        },
        modifier = modifier
            .alpha(if (isVideoReady) 1f else 0f)
            .fillMaxWidth()
            .aspectRatio(16f / 9f) // 미리보기 화면을 16:9 비율로 고정
    )
}
