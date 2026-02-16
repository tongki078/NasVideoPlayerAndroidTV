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
            .height(32.dp) // 높이를 30% 더 줄임
            .padding(horizontal = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // 좌측: 로고 및 홈/검색 아이콘
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = "N",
                color = Color.Red,
                fontSize = 20.sp, // 크기 조정
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(end = 8.dp)
            )
            
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
    
    // [디자인 단순화] 포커스 시 배경색 변경 제거
    val contentColor by animateColorAsState(
        when {
            isFocused -> Color.White // 포커스 시 흰색
            isSelected -> Color.White // 선택 시 흰색
            else -> Color.White.copy(alpha = 0.6f) // 평소에는 반투명
        }
    )

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Transparent) // 배경색 투명으로 고정
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable { onClick(screen) }
            .padding(horizontal = if (icon != null) 6.dp else 10.dp, vertical = 2.dp), // 패딩 최소화
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (icon != null) {
                Icon(
                    imageVector = icon, 
                    contentDescription = label, 
                    tint = contentColor, 
                    modifier = Modifier.size(15.dp) // 아이콘 크기 조정
                )
            }
            Text(
                text = label,
                color = contentColor,
                fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium,
                fontSize = 10.sp, // 텍스트 크기 조정
                maxLines = 1
            )
        }
    }
}
