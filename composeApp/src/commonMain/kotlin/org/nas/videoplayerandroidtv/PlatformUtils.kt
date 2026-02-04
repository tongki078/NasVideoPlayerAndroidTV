package org.nas.videoplayerandroidtv

import androidx.compose.runtime.Composable

expect fun currentTimeMillis(): Long

expect fun getImageCacheDirectory(context: Any): String

@Composable
expect fun BackHandler(enabled: Boolean = true, onBack: () -> Unit)
