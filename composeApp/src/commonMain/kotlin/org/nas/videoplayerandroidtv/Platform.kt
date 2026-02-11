package org.nas.videoplayerandroidtv

import coil3.PlatformContext

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

// PlatformContext(안드로이드의 Context 등)를 전달받아 캐시 경로를 반환
expect fun getImageCacheDirectory(context: PlatformContext): String

/**
 * 자소 분리된 한글(NFD)을 완성형(NFC)으로 변환합니다.
 */
expect fun String.toNfc(): String
