package org.nas.videoplayerandroidtv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.nas.videoplayerandroidtv.domain.model.SubtitleTrack

@Composable
expect fun VideoPlayer(
    url: String, 
    modifier: Modifier = Modifier,
    initialPosition: Long = 0L,
    seekToPosition: Long = -1L,
    isPlaying: Boolean = true,
    onPositionUpdate: ((Long) -> Unit)? = null,
    onDurationDetermined: ((Long) -> Unit)? = null,
    onControllerVisibilityChanged: ((Boolean) -> Unit)? = null,
    onFullscreenClick: (() -> Unit)? = null,
    onVideoEnded: (() -> Unit)? = null,
    onSeekFinished: (() -> Unit)? = null,
    onSubtitleTracksAvailable: ((List<String>) -> Unit)? = null,
    selectedSubtitleIndex: Int = -2,
    onSubtitleSelected: ((Int) -> Unit)? = null,
    externalSubtitles: List<SubtitleTrack> = emptyList() // 추가
)
