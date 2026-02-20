package org.nas.videoplayerandroidtv.ui.player

import android.net.Uri
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
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.nas.videoplayerandroidtv.R
import org.nas.videoplayerandroidtv.data.network.NasApiClient

// 자막 정보 파싱을 위한 간단한 데이터 클래스
@kotlinx.serialization.Serializable
data class SubtitleInfoResponse(
    val external: List<ExternalSub> = emptyList(),
    val embedded: List<EmbeddedSub>? = null
)

@kotlinx.serialization.Serializable
data class ExternalSub(
    val name: String? = null,
    val path: String? = null
)

@kotlinx.serialization.Serializable
data class EmbeddedSub(
    val index: Int? = null
)

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
            .setUserAgent("NasVideoPlayer/1.0 (Android TV; ExoPlayer)")

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
        
        // 자막 언어 설정
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .setPreferredTextLanguage("ko")
            .build()
        
        val mediaItemBuilder = MediaItem.Builder().setUri(url)
        val isMkv = url.lowercase().contains(".mkv") || url.contains("type=ftv") || url.contains("type=movie")

        if (isMkv) {
            mediaItemBuilder.setMimeType(MimeTypes.VIDEO_MATROSKA)
            
            try {
                // 1. 서버에 자막 정보 요청
                val infoUrl = url.replace("video_serve", "api/subtitle_info")
                val response: SubtitleInfoResponse = withContext(Dispatchers.IO) {
                    NasApiClient.client.get(infoUrl).body()
                }

                val subtitleConfigs = mutableListOf<MediaItem.SubtitleConfiguration>()

                // 2. 외부 자막(.ko.srt 등)이 발견되면 최우선 등록
                if (response.external.isNotEmpty()) {
                    response.external.forEach { sub ->
                        val subUrl = url.replace("video_serve", "api/subtitle_extract")
                            .split("&path=")[0] + "&sub_path=" + (sub.path ?: "")
                        
                        val config = MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUrl))
                            .setMimeType(if (sub.path?.lowercase()?.endsWith(".srt") == true) MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_UNKNOWN)
                            .setLanguage("ko")
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED)
                            .build()
                        subtitleConfigs.add(config)
                        Log.d("VideoPlayer", "Subtitle Success: External subtitle loaded -> ${sub.name}")
                    }
                } 
                // 3. 외부 자막이 없고 내장 자막이 있다면 추출 API 연결
                else if (response.embedded != null && response.embedded.isNotEmpty()) {
                    val extractUrl = url.replace("video_serve", "api/subtitle_extract") + "&index=0"
                    val config = MediaItem.SubtitleConfiguration.Builder(Uri.parse(extractUrl))
                        .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                        .setLanguage("ko")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED)
                        .build()
                    subtitleConfigs.add(config)
                    Log.d("VideoPlayer", "Subtitle Success: Falling back to embedded subtitle extraction")
                } else {
                    Log.d("VideoPlayer", "Subtitle: No subtitles found (External or Embedded)")
                }

                if (subtitleConfigs.isNotEmpty()) {
                    mediaItemBuilder.setSubtitleConfigurations(subtitleConfigs)
                }
            } catch (e: Exception) {
                Log.e("VideoPlayer", "Subtitle detection failed", e)
            }
        } else if (url.lowercase().contains(".ts") || url.contains("type=air")) {
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
                val view = LayoutInflater.from(ctx).inflate(R.layout.player_view, null) as PlayerView
                view.apply {
                    player = exoPlayer
                    setOnClickListener {
                        currentOnControllerVisibilityChanged?.invoke(true)
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
