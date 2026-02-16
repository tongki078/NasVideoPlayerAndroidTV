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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.nas.videoplayerandroidtv.domain.repository.VideoRepository
import org.nas.videoplayerandroidtv.ui.common.TmdbAsyncImage
import org.nas.videoplayerandroidtv.util.TitleUtils.cleanTitle

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
                    val visibleItems = state.layoutInfo.visibleItemsInfo
                    val isLastItemVisible = visibleItems.any { it.index == lastIdx }

                    if (isLastItemVisible && lastIdx in focusRequesters.indices) {
                        focusRequesters[lastIdx]
                    } else {
                        visibleItems.firstOrNull()?.let {
                            if (it.index in focusRequesters.indices) focusRequesters[it.index]
                            else FocusRequester.Default
                        } ?: FocusRequester.Default
                    }
                }
            },
        state = state,
        contentPadding = PaddingValues(start = marginValue, end = marginValue, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        itemsIndexed(items, key = { _, item -> keySelector(item) }) { index, item ->
            Box(modifier = Modifier.onFocusChanged {
                if (it.isFocused) {
                    rowFocusIndices[rowKey] = index
                }
            }) {
                val fr = focusRequesters.getOrElse(index) { FocusRequester.Default }
                itemContent(item, index, state, fr, marginPx, focusedIndex)
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

    val coroutineScope = rememberCoroutineScope()
    var dataLoadingJob by remember { mutableStateOf<Job?>(null) }
    var previewJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(isFocused) {
        if (isFocused) {
            try {
                state.animateScrollToItem(index, -marginPx)
            } catch (_: Exception) {}

            dataLoadingJob?.cancel()
            dataLoadingJob = coroutineScope.launch {
                if (categoryPath != null && (itemOverview.isNullOrBlank() || previewUrl == null)) {
                    try {
                        val details = repository.getSeriesDetail(categoryPath)
                        if (details != null) {
                            if (previewUrl == null) previewUrl = details.episodes.find { !it.videoUrl.isNullOrEmpty() }?.videoUrl
                            if (itemOverview.isNullOrBlank()) itemOverview = details.overview
                            if (itemYear.isNullOrBlank()) itemYear = details.year
                            if (itemRating.isNullOrBlank()) itemRating = details.rating
                        }
                    } catch (_: Exception) {}
                }
            }
        } else {
            dataLoadingJob?.cancel()
            previewJob?.cancel()
            showPreview = false
        }
    }

    LaunchedEffect(isFocused, previewUrl) {
        previewJob?.cancel()
        if (isFocused && !previewUrl.isNullOrBlank()) {
            previewJob = coroutineScope.launch {
                delay(200) // 짧은 지연 시간 후 미리보기 시작
                if (isFocused) { // 지연 후에도 포커스가 유지되는지 확인
                    showPreview = true
                }
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
                    if (showPreview && !previewUrl.isNullOrBlank()) {
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

            if (isFocused) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(infoAreaHeight)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = title.cleanTitle(),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        if (!itemYear.isNullOrBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Text(text = itemYear!!, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                        }

                        if (!itemRating.isNullOrBlank()) {
                            Spacer(Modifier.width(6.dp))
                            Text(text = itemRating!!, color = Color(0xFF46D369), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }

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
