package org.nas.videoplayerandroidtv.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nas.videoplayerandroidtv.Screen

@Composable
fun NetflixTopBar(
    currentScreen: Screen,
    homeFocusRequester: FocusRequester? = null,
    onFocusChanged: (Boolean) -> Unit = {},
    onScreenSelected: (Screen) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(64.dp)
            .onFocusChanged { onFocusChanged(it.hasFocus) }
            .padding(horizontal = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = "N",
                color = Color.Red,
                fontSize = 32.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(end = 8.dp)
            )
            
            TopBarItem(
                label = "홈", 
                icon = Icons.Default.Home, 
                screen = Screen.HOME, 
                currentScreen = currentScreen, 
                modifier = if (homeFocusRequester != null) Modifier.focusRequester(homeFocusRequester) else Modifier,
                onClick = onScreenSelected
            )
            TopBarItem(label = "검색", icon = Icons.Default.Search, screen = Screen.SEARCH, currentScreen = currentScreen, onClick = onScreenSelected)
        }
        
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
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val isSelected = currentScreen == screen
    
    val contentColor by animateColorAsState(
        when {
            isFocused -> Color.White
            isSelected -> Color.White
            else -> Color.White.copy(alpha = 0.5f)
        }
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) Color.White.copy(alpha = 0.1f) else Color.Transparent)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable { onClick(screen) }
            .padding(horizontal = if (icon != null) 10.dp else 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (icon != null) {
                Icon(
                    imageVector = icon, 
                    contentDescription = label, 
                    tint = contentColor, 
                    modifier = Modifier.size(24.dp)
                )
            } else {
                Text(
                    text = label,
                    color = contentColor,
                    fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 15.sp
                )
            }
            
            if (isSelected && !isFocused) {
                Spacer(Modifier.height(2.dp))
                Box(modifier = Modifier.size(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.Red))
            }
        }
    }
}

/**
 * 서브 카테고리 칩 컴포넌트 - 컴팩트한 벤토 박스 스타일
 */
@Composable
fun SophisticatedTabChip(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    
    // 애니메이션 설정 - 더욱 컴팩트하게 조정
    val scale by animateFloatAsState(if (isFocused) 1.08f else 1.0f, label = "TabScale")
    val backgroundColor by animateColorAsState(
        if (isFocused) Color.White else Color.White.copy(alpha = 0.05f),
        label = "TabBgColor"
    )
    val contentColor by animateColorAsState(
        if (isFocused) Color.Black else if (isSelected) Color.Red else Color.White.copy(alpha = 0.7f),
        label = "TabContentColor"
    )
    val elevation by animateDpAsState(if (isFocused) 10.dp else 0.dp, label = "TabElevation")

    Surface(
        onClick = onClick,
        modifier = Modifier
            .height(34.dp) // 높이를 줄임
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { isFocused = it.isFocused }
            .focusable(),
        shape = RoundedCornerShape(6.dp), // 조금 더 각진 세련된 느낌
        color = backgroundColor,
        border = BorderStroke(
            width = 1.dp,
            color = if (isFocused) Color.White else if (isSelected) Color.Red.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.08f)
        ),
        shadowElevation = elevation
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 14.dp), // 여백을 줄임
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title,
                color = contentColor,
                fontSize = 13.sp, // 폰트 크기를 살짝 줄임
                fontWeight = if (isSelected || isFocused) FontWeight.ExtraBold else FontWeight.Medium
            )
        }
    }
}
