package org.nas.videoplayerandroidtv

import android.os.Build
import coil3.PlatformContext

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
}

actual fun getPlatform(): Platform = AndroidPlatform()

actual fun getImageCacheDirectory(context: PlatformContext): String {
    return context.cacheDir.absolutePath
}
