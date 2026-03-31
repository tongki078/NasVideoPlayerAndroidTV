package org.nas.videoplayerandroidtv.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import org.nas.videoplayerandroidtv.util.TitleUtils.cleanTitle
import org.nas.videoplayerandroidtv.domain.model.Series
import org.nas.videoplayerandroidtv.ui.common.TmdbAsyncImage
import org.nas.videoplayerandroidtv.data.WatchHistory
import kotlinx.coroutines.delay

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HeroSection(
    series: Series, 
    watchHistory: WatchHistory? = null, 
    onPlayClick: () -> Unit,
    onDetailClick: () -> Unit,
    onNextEpisodeClick: (() -> Unit)? = null,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    isFirstLoad: Boolean = false
) {
    val title = series.title

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(440.dp)
            .padding(horizontal = horizontalPadding * 2, vertical = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(
                BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                RoundedCornerShape(12.dp)
            )
            .background(Color.Black)
    ) {
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
                .padding(start = 32.dp, bottom = 48.dp)
                .fillMaxWidth(0.85f)
        ) {
            Text(
                text = title.cleanTitle(), 
                color = Color.White, 
                style = TextStyle(
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Black, 
                    shadow = Shadow(color = Color.Black.copy(alpha = 0.8f), offset = androidx.compose.ui.geometry.Offset(0f, 4f), blurRadius = 16f),
                    letterSpacing = (-1.0).sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val metadataItems = mutableListOf<@Composable () -> Unit>()

                metadataItems.add { MetadataText(text = if (series.category == "movies") "영화" else "시리즈") }

                val genre = series.genreNames.firstOrNull() ?: ""
                if (genre.isNotEmpty()) {
                    metadataItems.add { MetadataText(text = "$genre 장르") }
                }

                series.year?.let { y -> metadataItems.add { MetadataText(text = y) } }
                
                if (series.category != "movies" && series.seasonCount != null && series.seasonCount > 0) {
                    metadataItems.add { MetadataText(text = "시즌 ${series.seasonCount}개") }
                }
                
                metadataItems.add { HeroBadge(text = "HD", isOutlined = true) }

                if (!series.rating.isNullOrBlank()) {
                    metadataItems.add { RatingBadge(series.rating) }
                }

                metadataItems.forEachIndexed { index, component ->
                    component()
                    if (index < metadataItems.size - 1) {
                        MetadataSeparator()
                    }
                }
            }
            
            if (!series.overview.isNullOrBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = series.overview,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 14.sp,
                    maxLines = 2,
                    lineHeight = 22.sp,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(shadow = Shadow(color = Color.Black, blurRadius = 8f))
                )
            }

            Spacer(Modifier.height(28.dp))
            
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
                    text = if (isContinuing) "계속 시청" else "재생",
                    icon = Icons.Default.PlayArrow,
                    isPrimary = true,
                    progress = progress,
                    onClick = onPlayClick
                )

                if (onNextEpisodeClick != null) {
                    Spacer(Modifier.width(16.dp))
                    HeroButton(
                        text = "다음화 보기",
                        icon = Icons.AutoMirrored.Filled.ArrowForward,
                        isPrimary = false,
                        onClick = onNextEpisodeClick
                    )
                }

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
fun SkeletonHero(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(440.dp)
            .padding(horizontal = 40.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.DarkGray.copy(alpha = 0.3f))
    )
}

@Composable
fun SkeletonRow(margin: androidx.compose.ui.unit.Dp) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Box(modifier = Modifier.padding(start = margin).width(120.dp).height(20.dp).background(Color.DarkGray.copy(alpha = 0.3f), RoundedCornerShape(4.dp)))
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.padding(start = margin), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(6) {
                Box(modifier = Modifier.width(150.dp).height(220.dp).background(Color.DarkGray.copy(alpha = 0.2f), RoundedCornerShape(8.dp)))
            }
        }
    }
}

@Composable
private fun MetadataText(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.9f),
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(shadow = Shadow(color = Color.Black, blurRadius = 4f))
    )
}

@Composable
private fun MetadataSeparator() {
    Text(
        text = " · ",
        color = Color.White.copy(alpha = 0.4f),
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
private fun RatingBadge(rating: String) {
    val backgroundColor = when {
        rating.contains("19") || rating.contains("18") || rating.contains("청불") -> Color(0xFFE50914)
        rating.contains("15") -> Color(0xFFF5A623)
        rating.contains("12") -> Color(0xFFF8E71C)
        rating.contains("전체") || rating.contains("All") -> Color(0xFF46D369)
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
            fontSize = 11.sp,
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
        Text(text = text, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
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
            .height(40.dp)
            .widthIn(min = 110.dp)
            .shadow(if (isFocused) 15.dp else 0.dp, CircleShape, spotColor = Color.White)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(icon, null, tint = contentColor, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(text = text, color = contentColor, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
            }
            
            if (progress != null && progress > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(3.dp)
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
        androidx.tv.material3.Text(
            text = title,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            letterSpacing = (-0.5).sp,
            modifier = Modifier.padding(end = 16.dp)
        )

        if ((isFullList || title.contains("전체목록") || title.contains("전체 목록")) && items.isNotEmpty()) {
            // 🔴 초성 필터 로직을 별도 컴포저블로 분리하여 성능 격리
            ChosungFilterRow(items = items, onIndexClick = onIndexClick)
        }
    }
}

@Composable
private fun ChosungFilterRow(
    items: List<org.nas.videoplayerandroidtv.domain.model.Category>,
    onIndexClick: (Int) -> Unit
) {
    val standardOrder = remember {
        listOf("ㄱ", "ㄴ", "ㄷ", "ㄹ", "ㅁ", "ㅂ", "ㅅ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ", "A-Z", "0-9", "기타")
    }

    // 🔴 최적화: items 리스트가 바뀌어도 실제 맵 내용이 같으면 재계산을 방지
    val soundToIndexMap by remember(items) {
        derivedStateOf {
            val map = mutableMapOf<String, Int>()
            items.forEachIndexed { index, item ->
                val raw = item.chosung ?: "기타"
                val mapped = when {
                    raw.length == 1 && raw[0] in 'A'..'Z' -> "A-Z"
                    raw == "0-9" -> "0-9"
                    raw == "기타" -> "기타"
                    else -> raw
                }
                if (!map.containsKey(mapped)) map[mapped] = index
            }
            map
        }
    }

    LazyRow(
        modifier = Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        items(items = standardOrder, key = { it }) { sound ->
            val targetIndex = soundToIndexMap[sound] ?: -1
            val hasItems = targetIndex != -1

            // 🔴 InteractionSource를 사용하여 포커스 이벤트 최적화 (Modifier.onFocusChanged 보다 가벼움)
            val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val isFocused by interactionSource.collectIsFocusedAsState()

            Surface(
                onClick = { if (hasItems) onIndexClick(targetIndex) },
                color = if (isFocused) Color.White else Color.White.copy(alpha = 0.1f),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .size(width = 42.dp, height = 32.dp)
                    .focusable(interactionSource = interactionSource),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    androidx.tv.material3.Text(
                        text = sound,
                        color = if (isFocused) Color.Black else if (hasItems) Color.White else Color.White.copy(alpha = 0.2f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}