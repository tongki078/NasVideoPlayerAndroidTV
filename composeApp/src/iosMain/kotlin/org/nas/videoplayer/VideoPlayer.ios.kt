package org.nas.videoplayer

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.interop.LocalUIViewController
import platform.AVFoundation.*
import platform.AVKit.*
import platform.Foundation.*
import platform.UIKit.*
import platform.QuartzCore.CATransaction
import platform.QuartzCore.kCATransactionDisableActions
import kotlinx.cinterop.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@Composable
actual fun VideoPlayer(
    url: String,
    modifier: Modifier,
    onFullscreenClick: (() -> Unit)?
) {
    val viewController = LocalUIViewController.current
    val player = remember { AVPlayer() }

    DisposableEffect(Unit) {
        onDispose {
            player.pause()
            player.replaceCurrentItemWithPlayerItem(null)
        }
    }

    val playerViewController = remember {
        AVPlayerViewController().apply {
            this.player = player
            this.showsPlaybackControls = true
            this.videoGravity = AVLayerVideoGravityResizeAspect
        }
    }

    LaunchedEffect(url) {
        if (url.isBlank()) return@LaunchedEffect

        val nsUrl = try {
            // 이미 인코딩된 URL(%)이 포함되어 있으면 그대로 사용, 없으면 인코딩 진행
            if (url.contains("%")) {
                NSURL.URLWithString(url)
            } else {
                val nsString = url as Any as NSString
                // URL의 구조(: / ? & =)는 유지하면서 한글과 공백만 안전하게 인코딩하기 위한 캐릭터셋 설정
                val allowedSet = NSMutableCharacterSet.characterSetWithCharactersInString(":/?#[]@!$&'()*+,;=")
                allowedSet.formUnionWithCharacterSet(NSCharacterSet.URLQueryAllowedCharacterSet)
                allowedSet.formUnionWithCharacterSet(NSCharacterSet.URLPathAllowedCharacterSet)

                val encodedUrl = nsString.stringByAddingPercentEncodingWithAllowedCharacters(allowedSet)
                NSURL.URLWithString(encodedUrl ?: url)
            }
        } catch (e: Exception) {
            NSURL.URLWithString(url)
        }

        if (nsUrl == null) {
            println("❌ [iOS_DEBUG] Invalid URL: $url")
            return@LaunchedEffect
        }

        player.pause()

        // 서버의 is_ios 판정을 위해 User-Agent 설정
        val headers = NSDictionary.dictionaryWithObject(
            `object` = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
            forKey = "User-Agent" as NSString
        )

        val assetOptions = NSDictionary.dictionaryWithObject(
            `object` = headers,
            forKey = "AVURLAssetHTTPHeaderFieldsKey" as NSString
        )

        val asset = AVURLAsset.URLAssetWithURL(nsUrl, options = assetOptions as Map<Any?, *>)
        val item = AVPlayerItem.playerItemWithAsset(asset)

        player.replaceCurrentItemWithPlayerItem(item)

        var checkCount = 0
        while (isActive && checkCount < 100) {
            if (item.status == AVPlayerItemStatusReadyToPlay) {
                player.play()
                break
            } else if (item.status == AVPlayerItemStatusFailed) {
                println("❌ [iOS_DEBUG] Playback Failed: ${item.error?.localizedDescription}")
                break
            }
            delay(500)
            checkCount++
        }
    }

    UIKitView(
        factory = {
            val container = UIView()
            val videoView = playerViewController.view
            videoView.setAutoresizingMask(UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight)
            container.addSubview(videoView)
            if (playerViewController.parentViewController == null) {
                viewController.addChildViewController(playerViewController)
                playerViewController.didMoveToParentViewController(viewController)
            }
            container
        },
        modifier = modifier,
        update = { container ->
            CATransaction.begin()
            CATransaction.setValue(true, kCATransactionDisableActions)
            playerViewController.view.setFrame(container.bounds)
            CATransaction.commit()
        }
    )
}
