package org.nas.videoplayerandroidtv.ui.player

import android.graphics.Color as AndroidColor
import android.net.Uri
import android.util.Log
import android.util.TypedValue
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
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.nas.videoplayerandroidtv.R
import org.nas.videoplayerandroidtv.data.network.NasApiClient
import org.nas.videoplayerandroidtv.domain.model.SubtitleTrack
import java.net.URLEncoder

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
    val currentOnSubtitleTracksAvailable by rememberUpdatedState(onSubtitleTracksAvailable)

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
                    override fun onTracksChanged(tracks: Tracks) {
                        val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                        Log.d("VideoPlayer", "Tracks Changed: Found ${textGroups.size} subtitle tracks")
                        
                        subtitleTrackGroups.clear()
                        subtitleTrackGroups.addAll(textGroups)
                        
                        val names = textGroups.mapIndexed { index, group ->
                            val format = group.getTrackFormat(0)
                            format.label ?: format.language ?: "자막 ${index + 1}"
                        }
                        currentOnSubtitleTracksAvailable?.invoke(names)

                        // [자동 선택 로직 강화] 사용자가 아직 선택하지 않은 상태라면 한국어 자막 자동 활성화
                        if (selectedSubtitleIndex == -2 && textGroups.isNotEmpty()) {
                            // ko, kor, kor-KR 등의 언어 코드가 포함된 트랙 우선 검색
                            val koTrackIndex = textGroups.indexOfFirst { group ->
                                val lang = group.getTrackFormat(0).language?.lowercase() ?: ""
                                lang.contains("ko") || lang.contains("kor")
                            }
                            
                            val targetIdx = if (koTrackIndex != -1) koTrackIndex else 0
                            val parameters = trackSelectionParameters.buildUpon()
                            parameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            parameters.setOverrideForType(TrackSelectionOverride(textGroups[targetIdx].mediaTrackGroup, 0))
                            trackSelectionParameters = parameters.build()
                            Log.d("VideoPlayer", "Auto-selected subtitle track: index $targetIdx")
                        }
                    }
                })
            }
    }

    // 자막 선택 반영
    LaunchedEffect(selectedSubtitleIndex, subtitleTrackGroups.size) {
        if (selectedSubtitleIndex == -2) return@LaunchedEffect
        val parameters = exoPlayer.trackSelectionParameters.buildUpon()
        if (selectedSubtitleIndex == -1) {
            parameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            Log.d("VideoPlayer", "Subtitles Disabled")
        } else if (selectedSubtitleIndex >= 0 && selectedSubtitleIndex < subtitleTrackGroups.size) {
            parameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            parameters.setOverrideForType(TrackSelectionOverride(subtitleTrackGroups[selectedSubtitleIndex].mediaTrackGroup, 0))
            Log.d("VideoPlayer", "Subtitle selected: $selectedSubtitleIndex")
        }
        exoPlayer.trackSelectionParameters = parameters.build()
    }

    LaunchedEffect(url) {
        if (url.isBlank()) return@LaunchedEffect

        val infoUrl = if (url.startsWith("http")) {
            url.replace("video_serve", "api/subtitle_info")
        } else {
            "${NasApiClient.BASE_URL}${url.replace("video_serve", "api/subtitle_info")}"
        }

        Log.d("VideoPlayer", "Fetching subtitle info from: $infoUrl")
        
        val subInfo: SubtitleInfoResponse = withContext(Dispatchers.IO) {
            try { 
                NasApiClient.client.get(infoUrl).body() 
            } catch (e: Exception) { 
                Log.e("VideoPlayer", "Failed to fetch subtitle info", e)
                SubtitleInfoResponse() 
            }
        }

        val mediaItemBuilder = MediaItem.Builder().setUri(Uri.parse(url))
        val subtitleConfigs = mutableListOf<MediaItem.SubtitleConfiguration>()
        val subApiBase = url.substringBefore("video_serve") + "api/subtitle_extract"
        val videoType = url.substringAfter("type=").substringBefore("&")

        // 외부 자막 등록
        if (subInfo.external.isNotEmpty()) {
            subInfo.external.forEach { sub ->
                sub.path?.let { subPath ->
                    val encodedSubPath = URLEncoder.encode(subPath, "UTF-8")
                    val subUrl = if (subApiBase.startsWith("http")) {
                        "$subApiBase?type=$videoType&sub_path=$encodedSubPath"
                    } else {
                        "${NasApiClient.BASE_URL}$subApiBase?type=$videoType&sub_path=$encodedSubPath"
                    }
                    
                    val mimeType = when (subPath.substringAfterLast('.').lowercase()) {
                        "srt" -> MimeTypes.APPLICATION_SUBRIP
                        "vtt" -> MimeTypes.TEXT_VTT
                        "ass", "ssa" -> MimeTypes.TEXT_SSA
                        else -> MimeTypes.APPLICATION_SUBRIP
                    }
                    
                    subtitleConfigs.add(
                        MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUrl))
                            .setMimeType(mimeType)
                            .setLanguage(sub.name?.substringBeforeLast('.')?.substringAfterLast('.') ?: "ko")
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .build()
                    )
                }
            }
            mediaItemBuilder.setSubtitleConfigurations(subtitleConfigs)
        }

        // 🔥 [버그 수정] exoPlayer에 미디어를 세팅한 뒤, 여기서 바로 seekTo를 호출합니다!
        exoPlayer.setMediaItem(mediaItemBuilder.build())
        if (initialPosition > 0) {
            Log.d("VideoPlayer", "Restoring playback position to: $initialPosition ms")
            exoPlayer.seekTo(initialPosition)
        }
        exoPlayer.prepare()
        Log.d("VideoPlayer", "Player prepared with ${subtitleConfigs.size} external subtitle configs")
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
                    
                    // 넷플릭스 스타일 자막 뷰 설정
                    subtitleView?.apply {
                        setFixedTextSize(TypedValue.COMPLEX_UNIT_DIP, 32f)
                        val netflixStyle = CaptionStyleCompat(
                            AndroidColor.WHITE,              // 글자색
                            AndroidColor.TRANSPARENT,        // 배경색
                            AndroidColor.TRANSPARENT,        // 윈도우 배경색
                            CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, // 그림자 엣지
                            AndroidColor.BLACK,              // 그림자 색상
                            null                            // 서체
                        )
                        setStyle(netflixStyle)
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
