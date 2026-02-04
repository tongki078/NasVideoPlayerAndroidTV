package org.nas.videoplayerandroidtv

import android.content.Context
import androidx.compose.runtime.Composable

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun getImageCacheDirectory(context: Any): String {
    return (context as Context).cacheDir.absolutePath
}

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled, onBack)
}
