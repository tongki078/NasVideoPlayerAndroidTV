package org.nas.videoplayerandroidtv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.nas.videoplayerandroidtv.cleanTitle
import org.nas.videoplayerandroidtv.domain.repository.VideoRepository
import org.nas.videoplayerandroidtv.ui.common.TmdbAsyncImage

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun <T> NetflixTvPivotRow(
    state: LazyListState,
    items: List<T>,
    marginValue: androidx.compose.ui.unit.Dp,
    rowKey: String,
    rowFocusIndices: SnapshotStateMap<String, Int>,
    keySelector: (T) -> Any,
    itemContent: @Composable (item: T, index: Int, state: LazyListState, focusRequester: FocusRequester, marginPx: Int, focusedIndex: Int) -> Unit
) {
    val focusRequesters = remember(items.size) { List(items.size) { FocusRequester() } }
    val density = LocalDensity.current
    val marginPx = with(density) { marginValue.roundToPx() }
    val focusedIndex = rowFocusIndices[rowKey] ?: -1

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp) // 행 높이 소폭 조정
            .focusProperties {
                enter = {
                    val lastIdx = rowFocusIndices[rowKey] ?: 0
                    focusRequesters.getOrNull(lastIdx) ?: FocusRequester.Default
                }
            },
        state = state,
        contentPadding = PaddingValues(start = marginValue, end = marginValue, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        itemsIndexed(items, key = { _, item -> keySelector(item) }) { index, item ->
            Box(modifier = Modifier.onFocusChanged {
                if (it.isFocused) rowFocusIndices[rowKey] = index
            }) {
                itemContent(item, index, state, focusRequesters[index], marginPx, focusedIndex)
            }
        }
    }
}

@Composable
fun NetflixPivotItem(
    title: String,
    posterPath: String?,
    initialVideoUrl: String?,
    categoryPath: String?,
    repository: VideoRepository,
    index: Int,
    focusedIndex: Int,
    state: LazyListState,
    marginPx: Int,
    focusRequester: FocusRequester,
    rating: String = "98% 일치",
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    
    var previewUrl by remember { mutableStateOf(initialVideoUrl) }
    var showPreview by remember { mutableStateOf(false) }
    
    LaunchedEffect(isFocused) {
        if (isFocused) {
            state.animateScrollToItem(index, -marginPx)
            
            if (previewUrl == null && categoryPath != null) {
                launch {
                    try {
                        val details = repository.getCategoryList(categoryPath, 1, 0)
                        val videoUrl = details.firstOrNull()?.movies?.find { !it.videoUrl.isNullOrEmpty() }?.videoUrl
                        if (videoUrl != null) {
                            previewUrl = videoUrl
                        }
                    } catch (_: Exception) {}
                }
            }
            
            delay(1500)
            if (previewUrl != null) {
                showPreview = true
            }
        } else {
            showPreview = false
        }
    }

    // 포커스 시 16:9 비율을 유지하는 너비 (높이 225dp 기준 400dp)
    val itemWidth = if (isFocused) 400.dp else 150.dp
    val posterHeight = 225.dp // 영상/포스터 영역 높이 고정
    val totalHeight = 300.dp 

    val alpha = when {
        isFocused -> 1f
        focusedIndex != -1 && index < focusedIndex -> 0.05f 
        else -> 1f 
    }

    Box(
        modifier = Modifier
            .width(itemWidth)
            .height(totalHeight)
            .zIndex(if (isFocused) 10f else 1f)
            .alpha(alpha),
        contentAlignment = Alignment.TopStart
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Transparent) // 회색 배경 제거 및 투명 설정
                .focusable(interactionSource = interactionSource)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(posterHeight)
                    .clip(RoundedCornerShape(8.dp))
                    .border(
                        width = if (isFocused) 3.dp else 0.dp,
                        color = if (isFocused) Color.White else Color.Transparent,
                        shape = RoundedCornerShape(8.dp)
                    )
            ) {
                if (showPreview && previewUrl != null) {
                    VideoPreview(url = previewUrl!!, modifier = Modifier.fillMaxSize())
                } else {
                    TmdbAsyncImage(
                        title = title, 
                        posterPath = posterPath,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
            
            if (isFocused) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 8.dp) // 좌우 패딩을 줄여서 더 깔끔하게 조정
                ) {
                    Text(
                        text = title.cleanTitle(),
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = rating, color = Color(0xFF46D369), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(text = "2024", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
