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
            .height(300.dp) 
            .focusProperties {
                enter = {
                    val lastIdx = rowFocusIndices[rowKey] ?: 0
                    focusRequesters.getOrNull(lastIdx) ?: FocusRequester.Default
                }
            },
        state = state,
        contentPadding = PaddingValues(start = marginValue, end = marginValue, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
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
    rating: String? = null,
    year: String? = null,
    overview: String? = null,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    
    var previewUrl by remember { mutableStateOf(initialVideoUrl) }
    var itemOverview by remember { mutableStateOf(overview) }
    var itemYear by remember { mutableStateOf(year) }
    var itemRating by remember { mutableStateOf(rating) }
    var showPreview by remember { mutableStateOf(false) }
    
    LaunchedEffect(isFocused) {
        if (isFocused) {
            state.animateScrollToItem(index, -marginPx)
            
            if ((previewUrl == null || itemOverview == null) && categoryPath != null) {
                launch {
                    try {
                        val details = repository.getCategoryList(categoryPath, 1, 0)
                        val cat = details.firstOrNull()
                        if (cat != null) {
                            if (previewUrl == null) previewUrl = cat.movies?.find { !it.videoUrl.isNullOrEmpty() }?.videoUrl
                            if (itemOverview == null) itemOverview = cat.overview
                            if (itemYear == null) itemYear = cat.year
                            if (itemRating == null) itemRating = cat.rating
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

    val itemWidth = if (isFocused) 330.dp else 135.dp
    val posterMaxHeight = 185.dp 
    val infoAreaHeight = 85.dp 
    val totalItemHeight = posterMaxHeight + infoAreaHeight

    Box(
        modifier = Modifier
            .width(itemWidth)
            .height(totalItemHeight)
            .zIndex(if (isFocused) 10f else 1f)
            .alpha(1f),
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(posterMaxHeight),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (isFocused) 185.dp else 175.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .border(
                            width = if (isFocused) 3.dp else 0.dp,
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
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(infoAreaHeight)
            ) {
                if (isFocused) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        // 제목, 년도, 연령 정보를 한 줄에 배치
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = title.cleanTitle(),
                                color = Color.White,
                                fontSize = 14.sp, 
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            
                            Spacer(Modifier.width(8.dp))
                            
                            // 년도 정보
                            Text(
                                text = itemYear ?: "2024", 
                                color = Color.White.copy(alpha = 0.6f), 
                                fontSize = 11.sp
                            )
                            
                            Spacer(Modifier.width(6.dp))
                            
                            // 연령 정보 (녹색 강조)
                            Text(
                                text = itemRating ?: "15+", 
                                color = Color(0xFF46D369), 
                                fontWeight = FontWeight.Bold, 
                                fontSize = 11.sp
                            )
                        }

                        // 줄거리 정보를 2줄로 표시
                        if (!itemOverview.isNullOrBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = itemOverview!!,
                                color = Color.LightGray.copy(alpha = 0.8f),
                                fontSize = 11.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
