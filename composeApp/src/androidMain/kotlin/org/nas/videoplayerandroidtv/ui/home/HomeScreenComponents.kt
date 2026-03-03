package org.nas.videoplayerandroidtv.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.TextStyle
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
    isFirstLoad: Boolean = false
) {
    val title = series.title
    Box(modifier = Modifier.fillMaxWidth().height(480.dp).background(Color.Black)) {
        TmdbAsyncImage(title = title, posterPath = series.posterPath, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, isLarge = true)
        
        Box(modifier = Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.3f to Color.Black.copy(alpha = 0.1f),
                0.6f to Color.Black.copy(alpha = 0.7f),
                1f to Color.Black
            )
        ))
        
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = horizontalPadding, bottom = 64.dp)
                .fillMaxWidth(0.85f)
        ) {
            Text(
                text = title.cleanTitle(), 
                color = Color.White, 
                style = TextStyle(
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Black, 
                    shadow = Shadow(color = Color.Black.copy(alpha = 0.8f), offset = androidx.compose.ui.geometry.Offset(0f, 4f), blurRadius = 16f),
                    letterSpacing = (-1.5).sp
                )
            )

            Spacer(Modifier.height(16.dp))

            // 넷플릭스 스타일 메타데이터 로우 (시리즈 · 장르 · 연도 · 시즌 · 등급)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val metadataComponents = mutableListOf<@Composable () -> Unit>()

                // 1. 시리즈 / 영화 구분
                metadataComponents.add { MetadataText(text = if (series.category == "movies") "영화" else "시리즈") }

                // 2. 장르
                val genre = series.genreNames.firstOrNull() ?: ""
                if (genre.isNotEmpty()) {
                    metadataComponents.add { MetadataText(text = "$genre 장르") }
                }

                // 3. 연도
                series.year?.let { y ->
                    metadataComponents.add { MetadataText(text = y) }
                }
                
                // 4. 시즌 정보
                if (series.category != "movies" && series.seasonCount != null && series.seasonCount > 0) {
                    metadataComponents.add { MetadataText(text = "시즌 ${series.seasonCount}개") }
                }

                // 5. HD 뱃지
                metadataComponents.add { HeroBadge(text = "HD", isOutlined = true) }
                
                // 6. 연령 등급 뱃지 (가장 우측)
                if (!series.rating.isNullOrBlank()) {
                    metadataComponents.add { RatingBadge(series.rating) }
                }

                // 컴포넌트들을 구분점과 함께 렌더링
                metadataComponents.forEachIndexed { index, component ->
                    component()
                    if (index < metadataComponents.size - 1) {
                        MetadataSeparator()
                    }
                }
            }
            
            if (!series.overview.isNullOrBlank()) {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = series.overview,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 16.sp,
                    maxLines = 2,
                    lineHeight = 24.sp,
                    style = TextStyle(shadow = Shadow(color = Color.Black, blurRadius = 8f))
                )
            }

            Spacer(Modifier.height(32.dp))
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                val isContinuing = watchHistory != null && watchHistory.lastPosition > 0
                val progress = if (isContinuing && watchHistory!!.duration > 0) {
                    watchHistory.lastPosition.toFloat() / watchHistory.duration
                } else null

                val playButtonFocusRequester = remember { FocusRequester() }
                LaunchedEffect(isFirstLoad) {
                    if (isFirstLoad) {
                        delay(300) 
                        try { playButtonFocusRequester.requestFocus() } catch (_: Exception) {}
                    }
                }

                HeroButton(
                    modifier = Modifier.focusRequester(playButtonFocusRequester),
                    text = "재생",
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
private fun MetadataText(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.9f),
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        style = TextStyle(shadow = Shadow(color = Color.Black, blurRadius = 4f))
    )
}

@Composable
private fun MetadataSeparator() {
    Text(
        text = " · ",
        color = Color.White.copy(alpha = 0.4f),
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
private fun RatingBadge(rating: String) {
    val backgroundColor = when {
        rating.contains("19") || rating.contains("18") || rating.contains("청불") -> Color(0xFFE50914) // 빨강
        rating.contains("15") -> Color(0xFFF5A623) // 주황
        rating.contains("12") -> Color(0xFFF8E71C) // 노랑
        rating.contains("전체") || rating.contains("All") -> Color(0xFF46D369) // 초록
        else -> Color.Gray.copy(alpha = 0.5f)
    }
    
    Surface(
        color = backgroundColor,
        shape = RoundedCornerShape(2.dp),
        modifier = Modifier.padding(vertical = 1.dp)
    ) {
        val displayRating = rating.filter { it.isDigit() }.ifEmpty { if(rating.contains("전체")) "All" else rating.take(2) }
        Text(
            text = displayRating,
            color = if (backgroundColor == Color(0xFFF8E71C)) Color.Black else Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
        )
    }
}

@Composable
private fun HeroBadge(text: String, isOutlined: Boolean = false) {
    Surface(
        color = Color.Transparent, 
        shape = RoundedCornerShape(2.dp), 
        border = if (isOutlined) BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)) else null,
        modifier = Modifier.padding(vertical = 1.dp)
    ) {
        Text(text = text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
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
    val scale by animateFloatAsState(if (isFocused) 1.08f else 1.0f)
    
    val backgroundColor by animateColorAsState(when { 
        isFocused -> Color.White 
        isPrimary -> Color.White.copy(alpha = 0.95f)
        else -> Color.Gray.copy(alpha = 0.4f) 
    })
    val contentColor by animateColorAsState(when { 
        isFocused -> Color.Black 
        isPrimary -> Color.Black
        else -> Color.White 
    })

    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = backgroundColor,
        modifier = modifier
            .graphicsLayer { 
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { isFocused = it.isFocused }
            .height(48.dp)
            .widthIn(min = 140.dp)
            .shadow(if (isFocused) 15.dp else 0.dp, CircleShape, spotColor = Color.White)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Row(
                modifier = Modifier.padding(horizontal = 28.dp).fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(icon, null, tint = contentColor, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(text = text, color = contentColor, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
            }
            
            if (progress != null && progress > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(4.dp)
                        .background(Color.Red)
                )
            }
        }
    }
}

@Composable
fun SectionTitle(
    title: String, 
    horizontalPadding: androidx.compose.ui.unit.Dp,
    items: List<org.nas.videoplayerandroidtv.domain.model.Category> = emptyList(),
    isFullList: Boolean = false,
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

        if ((isFullList || title.contains("전체목록") || title.contains("전체 목록")) && items.isNotEmpty()) {
            val standardOrder = listOf(
                "ㄱ", "ㄴ", "ㄷ", "ㄹ", "ㅁ", "ㅂ", "ㅅ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ", "A-Z"
            )

            val itemInitialSounds = remember(items) {
                items.mapIndexedNotNull { index, item ->
                    val sound = item.chosung ?: getInitialSound(item.name)
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
                    val scale by animateFloatAsState(if (isFocused) 1.2f else 1.0f)
                    
                    Surface(
                        color = if (isFocused) Color.White else Color.Transparent,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier
                            .scale(scale)
                            .onFocusChanged { isFocused = it.isFocused }
                            .focusable(hasItems)
                            .clickable(enabled = hasItems) { onIndexClick(targetIndex) }
                    ) {
                        Text(
                            text = sound,
                            color = when {
                                isFocused -> Color.Black
                                hasItems -> Color.Gray
                                else -> Color.DarkGray.copy(alpha = 0.5f)
                            },
                            fontSize = 15.sp,
                            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
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
