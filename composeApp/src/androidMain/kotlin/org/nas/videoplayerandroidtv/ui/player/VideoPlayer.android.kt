package org.nas.videoplayerandroidtv.ui.player

import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
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
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nas.videoplayerandroidtv.R
import org.nas.videoplayerandroidtv.data.network.NasApiClient
import java.net.URLEncoder

// 데이터 클래스에 extraction_triggered 필드 추가
@kotlinx.serialization.Serializable
data class SubtitleInfoResponse(
    val external: List<ExternalSub> = emptyList(),
    val embedded: List<EmbeddedSub>? = null,
    val extraction_triggered: Boolean = false
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
            .build().apply {
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) currentOnVideoEnded?.invoke()
                        if (playbackState == Player.STATE_READY) currentOnDurationDetermined?.invoke(duration)
                    }
                    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                        if (reason == Player.DISCONTINUITY_REASON_SEEK) currentOnSeekFinished?.invoke()
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e("VideoPlayer", "ExoPlayer Error: ", error)
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
            parameters.setOverrideForType(TrackSelectionOverride(subtitleTrackGroups[selectedSubtitleIndex].mediaTrackGroup, 0))
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
            if (exoPlayer.isPlaying) currentOnPositionUpdate?.invoke(exoPlayer.currentPosition)
            delay(500)
        }
    }

    // [수정] 비동기 자막 로딩 로직
    LaunchedEffect(url) {
        if (url.isBlank()) return@LaunchedEffect

        val infoUrl = url.replace("video_serve", "api/subtitle_info")

        // 플레이어를 준비하고 (재)시작하는 함수
        fun prepareAndPlay(subInfo: SubtitleInfoResponse) {
            val mediaItemBuilder = MediaItem.Builder().setUri(url)
            val subtitleConfigs = mutableListOf<MediaItem.SubtitleConfiguration>()
            val subApiBase = url.substringBefore("video_serve") + "api/subtitle_extract"
            val videoType = url.substringAfter("type=").substringBefore("&")

            if (subInfo.external.isNotEmpty()) {
                subInfo.external.forEach { sub ->
                    sub.path?.let { subPath ->
                        val encodedSubPath = URLEncoder.encode(subPath, "UTF-8")
                        val subUrl = "$subApiBase?type=$videoType&sub_path=$encodedSubPath"
                        val mimeType = when (subPath.substringAfterLast('.').lowercase()) {
                            "srt" -> MimeTypes.APPLICATION_SUBRIP
                            "vtt" -> MimeTypes.TEXT_VTT
                            "ass" -> MimeTypes.TEXT_SSA
                            else -> MimeTypes.TEXT_UNKNOWN
                        }
                        val langCode = sub.name?.substringBeforeLast('.')?.substringAfterLast('.') ?: "ko"
                        subtitleConfigs.add(
                            MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUrl))
                                .setMimeType(mimeType)
                                .setLanguage(langCode)
                                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                .build()
                        )
                    }
                }
                mediaItemBuilder.setSubtitleConfigurations(subtitleConfigs)
            }

            val currentPos = if (exoPlayer.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)) exoPlayer.currentPosition else 0L
            val startPos = if (currentPos > 0) currentPos else initialPosition

            exoPlayer.setMediaItem(mediaItemBuilder.build(), startPos)
            exoPlayer.prepare()
        }

        // 1. 첫 자막 정보 요청
        val initialResponse = withContext(Dispatchers.IO) {
            try { NasApiClient.client.get(infoUrl).body() } catch (e: Exception) { SubtitleInfoResponse() }
        }

        // 2. 즉시 재생 시작 (현재 사용 가능한 자막으로)
        prepareAndPlay(initialResponse)

        // 3. 만약 자막 추출이 시작되었다면, 백그라운드에서 완료될 때까지 폴링
        if (initialResponse.extraction_triggered) {
            launch(Dispatchers.IO) {
                repeat(20) { // 최대 1분간 시도 (20 * 3초)
                    delay(3000)
                    val pollResponse: SubtitleInfoResponse = try {
                        NasApiClient.client.get(infoUrl).body()
                    } catch (e: Exception) { return@repeat }

                    // 추출이 끝나고 외부 자막이 생겼으면 플레이어 업데이트 후 폴링 종료
                    if (!pollResponse.extraction_triggered && pollResponse.external.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            Log.d("VideoPlayer", "백그라운드 자막 로딩 완료. 플레이어 업데이트.")
                            prepareAndPlay(pollResponse)
                        }
                        return@launch // 코루틴 종료
                    }
                }
            }
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
            modifier = Modifier.fillMaxSize()
        )
    }
}
