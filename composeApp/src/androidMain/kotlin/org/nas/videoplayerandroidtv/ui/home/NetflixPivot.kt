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
import androidx.compose.material3.Surface
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
    shouldRequestFocus: Boolean = false,
    onFocusRestored: () -> Unit = {},
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    var previewUrl by remember { mutableStateOf(initialVideoUrl) }
    var itemOverview by remember { mutableStateOf(overview) }
    var itemYear by remember { mutableStateOf(year) }
    var itemRating by remember { mutableStateOf(rating) }
    var itemGenres by remember { mutableStateOf<List<String>>(emptyList()) }
    var itemEpisodeCount by remember { mutableStateOf<Int?>(null) }
    var showPreview by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    var dataLoadingJob by remember { mutableStateOf<Job?>(null) }
    var previewJob by remember { mutableStateOf<Job?>(null) }

    val tags = remember(title) {
        val tagRegex = Regex("\\[(.*?)\\]")
        val matches = tagRegex.findAll(title)
        matches.map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.distinct().toList()
    }

    LaunchedEffect(shouldRequestFocus) {
        if (shouldRequestFocus) {
            focusRequester.requestFocus()
            onFocusRestored()
        }
    }

    LaunchedEffect(isFocused) {
        if (isFocused) {
            try { state.animateScrollToItem(index, -marginPx) } catch (_: Exception) {}
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
                            itemGenres = details.genreNames
                            itemEpisodeCount = details.episodes.size
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
                delay(800)
                if (isFocused) {
                    showPreview = true
                }
            }
        } else {
            showPreview = false
        }
    }

    val itemWidth = if (isFocused) 330.dp else 135.dp
    val posterMaxHeight = 185.dp
    val infoAreaHeight = 130.dp
    val totalItemHeight = posterMaxHeight + infoAreaHeight

    Box(modifier = Modifier.width(itemWidth).height(totalItemHeight).zIndex(if (isFocused) 10f else 1f)) {
        Column(modifier = Modifier.fillMaxSize().focusRequester(focusRequester).clip(RoundedCornerShape(6.dp)).background(Color.Transparent).focusable(interactionSource = interactionSource).clickable(interactionSource = interactionSource, indication = null, onClick = onClick)) {
            Box(modifier = Modifier.fillMaxWidth().height(posterMaxHeight), contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.fillMaxWidth().height(if (isFocused) 185.dp else 175.dp).clip(RoundedCornerShape(6.dp)).border(width = if (isFocused) 3.dp else 0.dp, color = if (isFocused) Color.White else Color.Transparent, shape = RoundedCornerShape(6.dp))) {
                    if (showPreview && !previewUrl.isNullOrBlank()) {
                        VideoPreview(url = previewUrl!!, modifier = Modifier.fillMaxSize())
                    } else {
                        TmdbAsyncImage(title = title, posterPath = posterPath, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    if (tags.isNotEmpty()) {
                        Surface(color = Color.Black.copy(alpha = 0.7f), shape = RoundedCornerShape(bottomEnd = 6.dp), modifier = Modifier.align(Alignment.TopStart)) {
                            Text(text = tags.joinToString(", "), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                        }
                    }
                }
            }

            if (isFocused) {
                Column(modifier = Modifier.fillMaxWidth().height(infoAreaHeight).padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text(text = title.replace(Regex("\\[(더빙|자막)\\]"), "").trim(), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)

                    Spacer(Modifier.height(4.dp))
                    
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        val metaItems = mutableListOf<@Composable () -> Unit>()
                        
                        // 장르
                        if (itemGenres.isNotEmpty()) metaItems.add { Text(text = itemGenres.take(2).joinToString(", "), color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp) }

                        // 연도 (Bold)
                        if (!itemYear.isNullOrBlank()) metaItems.add { Text(text = itemYear!!, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, fontWeight = FontWeight.Bold) }

                        // 에피소드 수 (Bold)
                        if (itemEpisodeCount != null) metaItems.add { Text(text = "에피소드 ${itemEpisodeCount}개", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, fontWeight = FontWeight.Bold) }

                        // 등급 배지
                        if (!itemRating.isNullOrBlank()) {
                            metaItems.add { NetflixRatingBadge(itemRating!!) }
                        }

                        metaItems.forEachIndexed { index, composable ->
                            composable()
                            if (index < metaItems.size - 1) Text(text = " • ", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp))
                        }
                    }

                    if (!itemOverview.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(text = itemOverview!!, color = Color.LightGray.copy(alpha = 0.8f), fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun NetflixRatingBadge(rating: String) {
    val backgroundColor = when {
        rating.contains("19") || rating.contains("청불") -> Color(0xFFE50914)
        rating.contains("15") -> Color(0xFFF5A623)
        rating.contains("12") -> Color(0xFFF8E71C)
        rating.contains("전체") -> Color(0xFF46D369)
        else -> Color.Gray.copy(alpha = 0.5f)
    }
    Surface(color = backgroundColor, shape = RoundedCornerShape(2.dp)) {
        Text(text = rating.filter { it.isDigit() }.ifEmpty { if(rating.contains("전체")) "All" else rating.take(2) }, color = if (backgroundColor == Color(0xFFF8E71C)) Color.Black else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
    }
}
