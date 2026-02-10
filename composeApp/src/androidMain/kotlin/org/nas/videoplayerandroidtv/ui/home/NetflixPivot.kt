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
            .height(280.dp) // 행 전체 높이를 360dp에서 280dp로 대폭 축소
            .focusProperties {
                enter = {
                    val lastIdx = rowFocusIndices[rowKey] ?: 0
                    focusRequesters.getOrNull(lastIdx) ?: FocusRequester.Default
                }
            },
        state = state,
        contentPadding = PaddingValues(start = marginValue, end = marginValue, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), // 간격도 약간 좁힘
        verticalAlignment = Alignment.CenterVertically 
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
            
            delay(600)
            if (previewUrl != null) {
                showPreview = true
            }
        } else {
            showPreview = false
        }
    }

    // 75인치 대형 TV를 고려하여 전체적인 DP 스케일 하향 조정
    // 포커스 시 16:9 비율 유지 (높이 180dp 기준 너비 320dp)
    val itemWidth = if (isFocused) 320.dp else 120.dp
    val posterMaxHeight = 180.dp // 기존 245dp에서 180dp로 축소
    val infoAreaHeight = 70.dp 
    val totalItemHeight = posterMaxHeight + infoAreaHeight

    val alpha = when {
        isFocused -> 1f
        focusedIndex != -1 && index < focusedIndex -> 0.05f 
        else -> 1f 
    }

    Box(
        modifier = Modifier
            .width(itemWidth)
            .height(totalItemHeight)
            .zIndex(if (isFocused) 10f else 1f)
            .alpha(alpha),
        contentAlignment = Alignment.TopStart
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Transparent) 
                .focusable(interactionSource = interactionSource)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
        ) {
            // 영상/포스터 영역
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(posterMaxHeight),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (isFocused) 180.dp else 165.dp) // 기본 165dp, 포커스 시 180dp
                        .clip(RoundedCornerShape(6.dp))
                        .border(
                            width = if (isFocused) 2.dp else 0.dp,
                            color = if (isFocused) Color.White else Color.Transparent,
                            shape = RoundedCornerShape(6.dp)
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
            }
            
            // 정보 영역
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(infoAreaHeight)
            ) {
                if (isFocused) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 4.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = title.cleanTitle(),
                            color = Color.White,
                            fontSize = 13.sp, // 폰트 크기도 소폭 하향
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = rating, color = Color(0xFF46D369), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            Spacer(Modifier.width(6.dp))
                            Text(text = "2024", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}
