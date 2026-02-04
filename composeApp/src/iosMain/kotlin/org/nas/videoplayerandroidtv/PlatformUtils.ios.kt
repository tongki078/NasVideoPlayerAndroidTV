package org.nas.videoplayerandroidtv

import androidx.compose.runtime.Composable
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.NSTemporaryDirectory

actual fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

actual fun getImageCacheDirectory(context: Any): String {
    return NSTemporaryDirectory()
}

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    // iOS에서는 시스템 뒤로가기 버튼이 없으므로 빈 구현 유지
}
