package org.nas.videoplayerandroidtv

import android.os.Build
import coil3.PlatformContext
import java.text.Normalizer

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
}

actual fun getPlatform(): Platform = AndroidPlatform()

actual fun getImageCacheDirectory(context: PlatformContext): String {
    return context.cacheDir.absolutePath + "/image_cache"
}

/**
 * 자소 분리된 한글(NFD)을 완성형(NFC)으로 변환합니다.
 */
actual fun String.toNfc(): String {
    return if (Normalizer.isNormalized(this, Normalizer.Form.NFC)) {
        this
    } else {
        Normalizer.normalize(this, Normalizer.Form.NFC)
    }
}
