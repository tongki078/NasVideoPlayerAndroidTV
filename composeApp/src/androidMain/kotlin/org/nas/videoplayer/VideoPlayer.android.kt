package org.nas.videoplayer

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.ui.PlayerView
import android.util.Log

@OptIn(UnstableApi::class)
@Composable
actual fun VideoPlayer(
    url: String,
    modifier: Modifier,
    onFullscreenClick: (() -> Unit)?
) {
    val context = LocalContext.current

    // ExoPlayer 인스턴스 생성 및 리스너 추가
    val exoPlayer = remember {
        // 하드웨어 디코더 실패 시 소프트웨어 디코더 등으로 전환하도록 설정
        val renderersFactory = DefaultRenderersFactory(context).apply {
            setEnableDecoderFallback(true)
        }
        
        ExoPlayer.Builder(context, renderersFactory).build().apply {
            playWhenReady = true
            // 비디오 스케일링 설정
            setVideoScalingMode(android.media.MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)

            // 에러 추적을 위한 리스너
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Log.e("VideoPlayer", "재생 에러 발생: ${error.errorCodeName} - ${error.message}")
                    error.printStackTrace()
                }

                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_BUFFERING -> Log.d("VideoPlayer", "버퍼링 중...")
                        Player.STATE_READY -> Log.d("VideoPlayer", "재생 준비 완료")
                        Player.STATE_ENDED -> Log.d("VideoPlayer", "재생 완료")
                    }
                }
            })
        }
    }

    // URL 변경 시 대응
    LaunchedEffect(url) {
        if (url.isBlank()) return@LaunchedEffect

        Log.d("VideoPlayer", "새로운 URL 시도: $url")

        // URL 파라미터를 제외하고 순수 파일 확장자로 판별
        val cleanPath = url.split("?")[0].lowercase()
        val mediaItemBuilder = MediaItem.Builder().setUri(url)

        if (cleanPath.endsWith(".mkv")) {
            mediaItemBuilder.setMimeType(MimeTypes.VIDEO_MATROSKA)
        } else if (cleanPath.endsWith(".mp4")) {
            mediaItemBuilder.setMimeType(MimeTypes.VIDEO_MP4)
        }

        exoPlayer.setMediaItem(mediaItemBuilder.build())
        exoPlayer.prepare()
        exoPlayer.play()
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.stop()
            exoPlayer.release()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                    controllerShowTimeoutMs = 3000
                    setFullscreenButtonClickListener {
                        onFullscreenClick?.invoke()
                    }
                }
            },
            modifier = Modifier.matchParentSize(),
            update = { playerView ->
                playerView.setFullscreenButtonClickListener {
                    onFullscreenClick?.invoke()
                }
            }
        )
    }
}
