package org.nas.videoplayerandroidtv.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import org.nas.videoplayerandroidtv.domain.model.Series
import org.nas.videoplayerandroidtv.cleanTitle

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MovieRow(
    title: String,
    seriesList: List<Series>,
    onSeriesClick: (Series) -> Unit
) {
    if (seriesList.isEmpty()) return
    
    val lazyListState = rememberLazyListState()
    val density = LocalDensity.current
    val standardMargin = 20.dp 
    val marginPx = with(density) { standardMargin.roundToPx() }
    
    // 홈화면과 동일하게 Row별 포커스 인덱스 및 포커스 리퀘스터 관리
    val rowFocusIndices = remember { mutableStateMapOf<String, Int>() }
    val rowKey = remember(title) { "row_$title" }
    val focusRequesters = remember(seriesList.size) { List(seriesList.size) { FocusRequester() } }
    val focusedIndex = rowFocusIndices[rowKey] ?: 0
    
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                letterSpacing = 0.5.sp
            ),
            color = Color(0xFFE0E0E0),
            modifier = Modifier.padding(start = standardMargin, bottom = 2.dp)
        )
        
        LazyRow(
            state = lazyListState,
            modifier = Modifier
                .fillMaxWidth()
                .height(310.dp)
                .focusProperties {
                    enter = { 
                        val lastIdx = rowFocusIndices[rowKey] ?: 0
                        focusRequesters.getOrNull(lastIdx) ?: FocusRequester.Default
                    }
                },
            contentPadding = PaddingValues(start = standardMargin, end = standardMargin, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            itemsIndexed(seriesList, key = { _, it -> it.title + (it.fullPath ?: "") }) { index, series ->
                Box(modifier = Modifier.onFocusChanged { 
                    if (it.isFocused) rowFocusIndices[rowKey] = index 
                }) {
                    MovieListItem(
                        series = series, 
                        index = index,
                        focusedIndex = focusedIndex,
                        state = lazyListState,
                        marginPx = marginPx,
                        focusRequester = focusRequesters[index],
                        onClick = { onSeriesClick(series) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MovieListItem(
    series: Series, 
    index: Int,
    focusedIndex: Int,
    state: LazyListState,
    marginPx: Int,
    focusRequester: FocusRequester,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    LaunchedEffect(isFocused) {
        if (isFocused) {
            state.animateScrollToItem(index, -marginPx)
        }
    }

    val alpha = when {
        isFocused -> 1f
        index < focusedIndex -> 0.05f 
        else -> 1f 
    }

    val fixedWidth = 150.dp
    val posterHeight = 225.dp
    val totalHeight = 290.dp 

    Box(
        modifier = Modifier
            .width(fixedWidth)
            .height(totalHeight)
            .zIndex(if (isFocused) 10f else 1f)
            .alpha(alpha)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Transparent)
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
                TmdbAsyncImage(
                    title = series.title,
                    posterPath = series.posterPath,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            
            if (isFocused) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = series.title.cleanTitle(),
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "98% 일치", color = Color(0xFF46D369), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(text = "2024", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
