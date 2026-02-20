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
import java.net.URLEncoder

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

    LaunchedEffect(selectedSubtitleIndex, subtitleTrackGroups.size) {
        if (selectedSubtitleIndex == -2) return@LaunchedEffect
        
        val parameters = exoPlayer.trackSelectionParameters.buildUpon()
        if (selectedSubtitleIndex == -1) {
            parameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else if (selectedSubtitleIndex >= 0 && selectedSubtitleIndex < subtitleTrackGroups.size) {
            parameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            parameters.setOverrideForType(
                TrackSelectionOverride(subtitleTrackGroups[selectedSubtitleIndex].mediaTrackGroup, 0)
            )
        }
        exoPlayer.trackSelectionParameters = parameters.build()
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
        Log.d("VideoPlayer", "재생 URL: $url")
        
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .setPreferredTextLanguage("ko") // 한국어 자막 우선
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
                val subApiBase = url.substringBefore("video_serve") + "api/subtitle_extract"
                val videoType = url.substringAfter("type=").substringBefore("&")

                // 2. 외부 자막 처리
                if (response.external.isNotEmpty()) {
                    response.external.forEach { sub ->
                        sub.path?.let { subPath ->
                            // [수정] URL 인코딩 및 MIME 타입 개선
                            val encodedSubPath = URLEncoder.encode(subPath, "UTF-8")
                            val subUrl = "$subApiBase?type=$videoType&sub_path=$encodedSubPath"
                            
                            val mimeType = when (subPath.substringAfterLast('.').lowercase()) {
                                "srt" -> MimeTypes.APPLICATION_SUBRIP
                                "vtt" -> MimeTypes.TEXT_VTT
                                "ass" -> MimeTypes.TEXT_SSA
                                "smi" -> MimeTypes.TEXT_UNKNOWN // [수정] APPLICATION_SAMI 대신 TEXT_UNKNOWN 사용
                                else -> MimeTypes.TEXT_UNKNOWN
                            }
                            
                            // [개선] 파일명에서 언어 코드 추출 (e.g., movie.ko.srt)
                            val langCode = sub.name?.substringBeforeLast('.')?.substringAfterLast('.') ?: "ko"

                            val config = MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUrl))
                                .setMimeType(mimeType)
                                .setLanguage(langCode)
                                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                .build()
                            subtitleConfigs.add(config)
                            Log.d("VideoPlayer", "외부 자막 로드: ${sub.name} (URL: $subUrl)")
                        }
                    }
                } 
                // 3. 내장 자막 처리 (외부 자막 없을 시)
                else if (response.embedded != null && response.embedded.isNotEmpty()) {
                    response.embedded.firstOrNull()?.index?.let { subIndex ->
                        val videoPath = url.substringAfter("path=").substringBefore("&")
                        val extractUrl = "$subApiBase?type=$videoType&path=$videoPath&index=$subIndex"
                        
                        val config = MediaItem.SubtitleConfiguration.Builder(Uri.parse(extractUrl))
                            .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                            .setLanguage("ko") // 내장 자막은 언어 정보 파악이 어려워 'ko'로 기본 설정
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .build()
                        subtitleConfigs.add(config)
                        Log.d("VideoPlayer", "내장 자막으로 대체: Index $subIndex (URL: $extractUrl)")
                    }
                } else {
                    Log.d("VideoPlayer", "사용 가능한 자막 없음 (외부/내장)")
                }

                if (subtitleConfigs.isNotEmpty()) {
                    mediaItemBuilder.setSubtitleConfigurations(subtitleConfigs)
                }
            } catch (e: Exception) {
                Log.e("VideoPlayer", "자막 처리 중 에러 발생", e)
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
                    useController = false // 컨트롤러는 우리가 직접 만듭니다.
                    setOnClickListener {
                        currentOnControllerVisibilityChanged?.invoke(true)
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
