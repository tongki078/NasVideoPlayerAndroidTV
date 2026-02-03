package org.nas.videoplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.nas.videoplayer.domain.model.Movie

@Composable
fun VideoPlayerScreen(
    movie: Movie,
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        Alignment.Center
    ) {
        // 상단 닫기 버튼
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .statusBarsPadding()
        ) {
            Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = Color.White)
        }

        // 비디오 플레이어 영역 (실제 구현은 VideoPlayer.kt 컴포저블을 여기서 호출하거나 추후 통합)
        Text(
            text = "플레이어 준비 중: ${movie.title}",
            color = Color.White
        )
    }
}
