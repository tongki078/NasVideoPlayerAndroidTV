package org.nas.videoplayer

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.play
import platform.AVFoundation.pause
import platform.AVKit.AVPlayerViewController
import platform.Foundation.NSURL
import platform.UIKit.UIView
import platform.AVKit.AVPlayerViewControllerDelegateProtocol
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun VideoPlayer(
    url: String, 
    modifier: Modifier,
    onFullscreenClick: (() -> Unit)?
) {
    val player = remember(url) {
        val nsUrl = NSURL.URLWithString(url)
        nsUrl?.let { AVPlayer(uRL = it) }
    }

    val playerViewController = remember { 
        AVPlayerViewController().apply {
            showsPlaybackControls = true
            videoGravity = AVLayerVideoGravityResizeAspect
        }
    }

    // URL 변경 시 플레이어 교체
    LaunchedEffect(player) {
        playerViewController.player = player
        player?.play()
    }

    UIKitView(
        factory = {
            playerViewController.view
        },
        modifier = modifier,
        onRelease = {
            player?.pause()
            playerViewController.player = null
        },
        interactive = true
    )

    DisposableEffect(Unit) {
        onDispose {
            player?.pause()
        }
    }
}
