package org.nas.videoplayerandroidtv.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nas.videoplayerandroidtv.Screen

@Composable
fun NetflixTopBar(
    currentScreen: Screen, 
    homeFocusRequester: FocusRequester? = null, // [추가] 홈 버튼 포커스 요청용
    onFocusChanged: (Boolean) -> Unit = {}, // [추가] 상단바 포커스 여부 알림
    onScreenSelected: (Screen) -> Unit
) {
    var isAnyItemFocused by remember { mutableStateOf(false) }
    
    LaunchedEffect(isAnyItemFocused) {
        onFocusChanged(isAnyItemFocused)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(64.dp)
            .padding(horizontal = 48.dp)
            .onFocusChanged { 
                // Row 자체보다는 자식들이 포커스 되는지 확인
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // 좌측: 로고 및 홈/검색 아이콘
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
                focusRequester = homeFocusRequester, // 홈 버튼에 전달
                onFocusChanged = { if(it) isAnyItemFocused = true else checkAnyFocused(isAnyItemFocused) { isAnyItemFocused = false } },
                onClick = onScreenSelected
            )
            TopBarItem(
                label = "검색", 
                icon = Icons.Default.Search, 
                screen = Screen.SEARCH, 
                currentScreen = currentScreen,
                onFocusChanged = { if(it) isAnyItemFocused = true else isAnyItemFocused = false },
                onClick = onScreenSelected
            )
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
                TopBarItem(
                    label = label, 
                    screen = screen, 
                    currentScreen = currentScreen,
                    onFocusChanged = { if(it) isAnyItemFocused = true else isAnyItemFocused = false },
                    onClick = onScreenSelected
                )
            }
        }
    }
}

// 헬퍼 함수: 자식들 포커스 체크 (간소화)
private fun checkAnyFocused(current: Boolean, update: () -> Unit) {
    // 실제로는 더 복잡한 체크가 필요할 수 있으나 여기서는 단순화
    update()
}

@Composable
private fun TopBarItem(
    label: String,
    screen: Screen,
    currentScreen: Screen,
    onClick: (Screen) -> Unit,
    icon: ImageVector? = null,
    focusRequester: FocusRequester? = null, // [추가]
    onFocusChanged: (Boolean) -> Unit = {} // [추가]
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
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) Color.White.copy(alpha = 0.1f) else Color.Transparent)
            .onFocusChanged { 
                isFocused = it.isFocused 
                onFocusChanged(it.isFocused)
            }
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
 * App.kt에서 사용되는 서브 카테고리 칩 컴포넌트
 */
@Composable
fun SophisticatedTabChip(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    
    val backgroundColor by animateColorAsState(
        when {
            isFocused -> Color.White
            isSelected -> Color.Red
            else -> Color.White.copy(alpha = 0.1f)
        }
    )
    
    val contentColor by animateColorAsState(
        if (isFocused) Color.Black else Color.White
    )
    
    val elevation by animateDpAsState(if (isFocused) 8.dp else 0.dp)

    Surface(
        onClick = onClick,
        modifier = Modifier
            .height(36.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable(),
        shape = RoundedCornerShape(18.dp),
        color = backgroundColor,
        tonalElevation = elevation,
        shadowElevation = elevation
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title,
                color = contentColor,
                fontSize = 14.sp,
                fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}
