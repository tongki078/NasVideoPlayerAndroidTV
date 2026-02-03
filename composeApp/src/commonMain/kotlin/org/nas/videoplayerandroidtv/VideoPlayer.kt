package org.nas.videoplayerandroidtv

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun VideoPlayer(
    url: String, 
    modifier: Modifier = Modifier,
    initialPosition: Long = 0L,
    onPositionUpdate: ((Long) -> Unit)? = null,
    onControllerVisibilityChanged: ((Boolean) -> Unit)? = null,
    onFullscreenClick: (() -> Unit)? = null,
    onVideoEnded: (() -> Unit)? = null
)
