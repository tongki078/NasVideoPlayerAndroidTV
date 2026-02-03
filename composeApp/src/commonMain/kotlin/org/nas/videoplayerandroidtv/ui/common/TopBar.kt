package org.nas.videoplayerandroidtv.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nas.videoplayerandroidtv.domain.model.Screen

@Composable
fun NetflixTopBar(currentScreen: Screen, onScreenSelected: (Screen) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(64.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "N",
            color = Color.Red,
            fontSize = 32.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.clickable { onScreenSelected(Screen.HOME) }
        )
        Spacer(modifier = Modifier.width(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val menuItems = listOf(
                "방송중" to Screen.ON_AIR,
                "애니" to Screen.ANIMATIONS,
                "영화" to Screen.MOVIES,
                "외국 TV" to Screen.FOREIGN_TV,
                "국내 TV" to Screen.KOREAN_TV
            )
            menuItems.forEach { (label, screen) ->
                Text(
                    text = label,
                    color = if (currentScreen == screen) Color.White else Color.Gray,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.clickable { onScreenSelected(screen) }
                )
            }
        }
    }
}
