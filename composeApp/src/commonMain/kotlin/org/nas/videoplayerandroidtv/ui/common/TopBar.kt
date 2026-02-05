package org.nas.videoplayerandroidtv.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nas.videoplayerandroidtv.Screen

@Composable
fun NetflixTopBar(currentScreen: Screen, onScreenSelected: (Screen) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(64.dp) // 높이를 살짝 줄임
            .padding(horizontal = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // 좌측: 로고 및 홈/검색 아이콘
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "N",
                color = Color.Red,
                fontSize = 32.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(end = 8.dp)
            )
            
            // 텍스트 없이 아이콘만 표시하는 아이템들
            TopBarItem(label = "홈", icon = Icons.Default.Home, screen = Screen.HOME, currentScreen = currentScreen, onClick = onScreenSelected)
            TopBarItem(label = "검색", icon = Icons.Default.Search, screen = Screen.SEARCH, currentScreen = currentScreen, onClick = onScreenSelected)
        }
        
        // 우측: 카테고리 텍스트 메뉴
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val menuItems = listOf(
                "방송중" to Screen.ON_AIR,
                "애니" to Screen.ANIMATIONS,
                "영화" to Screen.MOVIES,
                "외국 TV" to Screen.FOREIGN_TV,
                "국내 TV" to Screen.KOREAN_TV
            )
            
            menuItems.forEach { (label, screen) ->
                TopBarItem(label = label, screen = screen, currentScreen = currentScreen, onClick = onScreenSelected)
            }
        }
    }
}

@Composable
private fun TopBarItem(
    label: String,
    screen: Screen,
    currentScreen: Screen,
    onClick: (Screen) -> Unit,
    icon: ImageVector? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val isSelected = currentScreen == screen
    
    val backgroundColor by animateColorAsState(
        when {
            isFocused -> Color.White
            isSelected -> Color.Red.copy(alpha = 0.9f)
            else -> Color.Transparent
        }
    )
    
    val contentColor by animateColorAsState(
        when {
            isFocused -> Color.Black
            isSelected -> Color.White
            else -> Color.Gray
        }
    )

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable { onClick(screen) }
            .padding(horizontal = if (icon != null) 10.dp else 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) {
            // 아이콘이 있는 경우 텍스트를 절대 그리지 않음
            Icon(
                imageVector = icon, 
                contentDescription = label, 
                tint = contentColor, 
                modifier = Modifier.size(24.dp)
            )
        } else {
            // 아이콘이 없는 경우에만 텍스트 표시
            Text(
                text = label,
                color = contentColor,
                fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium,
                fontSize = 15.sp
            )
        }
    }
}
