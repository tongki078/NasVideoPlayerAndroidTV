package org.nas.videoplayerandroidtv.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nas.videoplayerandroidtv.util.TitleUtils.cleanTitle
import org.nas.videoplayerandroidtv.domain.model.Series
import org.nas.videoplayerandroidtv.ui.common.TmdbAsyncImage
import org.nas.videoplayerandroidtv.data.WatchHistory
import org.nas.videoplayerandroidtv.util.TitleUtils.getInitialSound
import kotlinx.coroutines.delay

@Composable
fun HeroSection(
    series: Series, 
    watchHistory: WatchHistory? = null, 
    onPlayClick: () -> Unit,
    onDetailClick: () -> Unit,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    isFirstLoad: Boolean = false // 🔴 파라미터 추가
) {
    val title = series.title
    Box(modifier = Modifier.fillMaxWidth().height(460.dp).background(Color.Black)) {
        TmdbAsyncImage(title = title, posterPath = series.posterPath, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, isLarge = true)
        
        Box(modifier = Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.4f to Color.Black.copy(alpha = 0.2f),
                0.7f to Color.Black.copy(alpha = 0.8f),
                1f to Color.Black
            )
        ))
        
        Column(modifier = Modifier.align(Alignment.BottomStart).padding(start = horizontalPadding, bottom = 60.dp).fillMaxWidth(0.8f)) {
            Text(
                text = title.cleanTitle(), 
                color = Color.White, 
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.ExtraBold, 
                    shadow = Shadow(color = Color.Black.copy(alpha = 0.6f), blurRadius = 16f),
                    letterSpacing = (-0.5).sp
                )
            )

            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                series.year?.let { HeroBadge(text = it, isOutlined = true) }
                series.rating?.let { HeroBadge(text = it, color = Color(0xFFE50914)) }
                series.genreNames.take(2).forEach { HeroBadge(text = it, color = Color.White.copy(alpha = 0.15f)) }
            }
            
            if (!series.overview.isNullOrBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = series.overview,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 15.sp,
                    maxLines = 2,
                    lineHeight = 22.sp,
                )
            }

            if (series.actors.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "출연: " + series.actors.take(3).joinToString { it.name },
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 13.sp,
                    maxLines = 1
                )
            }

            Spacer(Modifier.height(32.dp))
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                val isContinuing = watchHistory != null && watchHistory.lastPosition > 0
                val progress = if (isContinuing && watchHistory!!.duration > 0) {
                    watchHistory.lastPosition.toFloat() / watchHistory.duration
                } else null

                // 🔴 [수정] 앱 시작 시 재생 버튼이 최초 포커스를 갖도록 설정 (isFirstLoad일 때만)
                val playButtonFocusRequester = remember { FocusRequester() }
                LaunchedEffect(isFirstLoad) {
                    if (isFirstLoad) {
                        delay(300) // UI가 그려진 후 포커스 요청
                        try { playButtonFocusRequester.requestFocus() } catch (_: Exception) {}
                    }
                }

                HeroButton(
                    modifier = Modifier.focusRequester(playButtonFocusRequester),
                    text = if (isContinuing) "계속 시청" else "재생",
                    icon = Icons.Default.PlayArrow,
                    isPrimary = true,
                    progress = progress,
                    onClick = onPlayClick
                )

                Spacer(Modifier.width(16.dp))

                HeroButton(
                    text = "상세 정보",
                    icon = Icons.Default.Info,
                    isPrimary = false,
                    onClick = onDetailClick
                )
            }
        }
    }
}

@Composable
private fun HeroBadge(text: String, color: Color = Color.White.copy(alpha = 0.15f), isOutlined: Boolean = false) {
    Surface(
        color = if (isOutlined) Color.Transparent else color, 
        shape = RoundedCornerShape(4.dp), 
        border = if (isOutlined) BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)) else null, 
        modifier = Modifier.padding(end = 8.dp)
    ) {
        Text(text = text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
private fun HeroButton(
    modifier: Modifier = Modifier,
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isPrimary: Boolean,
    progress: Float? = null,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) 1.05f else 1.0f)
    
    val backgroundColor by animateColorAsState(when { 
        isFocused -> Color.White 
        isPrimary -> Color.White.copy(alpha = 0.9f)
        else -> Color.White.copy(alpha = 0.2f) 
    })
    val contentColor by animateColorAsState(when { 
        isFocused -> Color.Black 
        isPrimary -> Color.Black
        else -> Color.White 
    })

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(4.dp), // 심플한 사각형 모서리
        color = backgroundColor,
        modifier = modifier
            .graphicsLayer { 
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { isFocused = it.isFocused }
            .height(36.dp) // 높이 축소
            .widthIn(min = 120.dp) // 최소 너비 축소
            .shadow(if (isFocused) 10.dp else 0.dp, RoundedCornerShape(4.dp), spotColor = Color.White.copy(alpha = 0.5f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(icon, null, tint = contentColor, modifier = Modifier.size(18.dp)) // 아이콘 크기 축소
                Spacer(Modifier.width(8.dp))
                Text(text = text, color = contentColor, fontWeight = FontWeight.Bold, fontSize = 13.sp) // 폰트 크기 축소
            }
            
            if (progress != null && progress > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(if (isFocused) Color.Red else Color.Red.copy(alpha = 0.8f))
                )
            }
        }
    }
}

// 초성 리스트를 항상 고정으로 표시하고, 해당 초성이 없으면 비활성화
@Composable
fun SectionTitle(
    title: String, 
    horizontalPadding: androidx.compose.ui.unit.Dp,
    items: List<org.nas.videoplayerandroidtv.domain.model.Category> = emptyList(),
    onIndexClick: (Int) -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = horizontalPadding, top = 28.dp, bottom = 14.dp, end = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title, 
            color = Color.White, 
            fontWeight = FontWeight.Bold, 
            fontSize = 20.sp, 
            letterSpacing = (-0.5).sp,
            modifier = Modifier.padding(end = 16.dp)
        )

        // "전체 목록"일 경우에만 초성 인덱스 칩 렌더링
        if (title.contains("전체 목록") && items.isNotEmpty()) {
            val standardOrder = listOf(
                "ㄱ", "ㄴ", "ㄷ", "ㄹ", "ㅁ", "ㅂ", "ㅅ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ", "A-Z"
            )

            // 작품 리스트에서 첫 등장하는 초성의 인덱스를 맵핑
            val itemInitialSounds = remember(items) {
                items.mapIndexedNotNull { index, item ->
                    val sound = getInitialSound(item.name)
                    if (sound != "#") sound to index else null
                }.distinctBy { it.first }.toMap()
            }

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                itemsIndexed(standardOrder) { _, sound ->
                    val targetIndex = itemInitialSounds[sound] ?: 0 
                    val hasItems = itemInitialSounds.containsKey(sound)
                    
                    var isFocused by remember { mutableStateOf(false) }
                    val scale by animateFloatAsState(if (isFocused) 1.1f else 1.0f)
                    
                    Surface(
                        color = if (isFocused) Color.White else Color.Transparent,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier
                            .scale(scale)
                            .onFocusChanged { isFocused = it.isFocused }
                            .focusable(hasItems) // 작품이 없으면 포커스 안 가도록 설정
                            .clickable(enabled = hasItems) { onIndexClick(targetIndex) }
                    ) {
                        Text(
                            text = sound,
                            color = when {
                                isFocused -> Color.Black
                                hasItems -> Color.Gray
                                else -> Color.DarkGray.copy(alpha = 0.5f) // 비활성화된 느낌
                            },
                            fontSize = 14.sp,
                            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SkeletonHero() {
    Box(modifier = Modifier.fillMaxWidth().height(460.dp).background(Color.Black))
}

@Composable
fun SkeletonRow(horizontalPadding: androidx.compose.ui.unit.Dp) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Box(modifier = Modifier.padding(start = horizontalPadding, bottom = 12.dp).width(120.dp).height(20.dp).background(Color(0xFF1A1A1A)))
        LazyRow(contentPadding = PaddingValues(start = horizontalPadding, end = horizontalPadding), horizontalArrangement = Arrangement.spacedBy(12.dp), userScrollEnabled = false) {
            items(5) {
                Box(modifier = Modifier.width(150.dp).height(210.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1A1A1A)))
            }
        }
    }
}
