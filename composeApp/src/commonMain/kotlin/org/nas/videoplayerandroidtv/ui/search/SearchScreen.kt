package org.nas.videoplayerandroidtv.ui.search

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import org.nas.videoplayerandroidtv.data.SearchHistory
import org.nas.videoplayerandroidtv.domain.model.Series
import org.nas.videoplayerandroidtv.ui.common.TmdbAsyncImage
import org.nas.videoplayerandroidtv.ui.common.shimmerBrush
import org.nas.videoplayerandroidtv.util.TitleUtils.cleanTitle

@Composable
fun SearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    recentQueries: List<SearchHistory>,
    searchResults: List<Series>,
    isLoading: Boolean,
    onSaveQuery: (String) -> Unit,
    onDeleteQuery: (String) -> Unit,
    onSeriesClick: (Series) -> Unit
) {
    Row(modifier = Modifier.fillMaxSize().background(Color(0xFF0F0F0F)).statusBarsPadding()) {
        
        // --- [좌측: 검색 입력 및 최근 검색어 (350.dp)] ---
        Column(
            modifier = Modifier
                .width(350.dp)
                .fillMaxHeight()
                .padding(24.dp)
        ) {
            Text(
                "검색",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            // 검색창
            SearchInputField(query, onQueryChange, onSaveQuery)

            Spacer(modifier = Modifier.height(32.dp))

            // 최근 검색어 섹션
            if (recentQueries.isNotEmpty()) {
                Text(
                    "최근 검색어",
                    color = Color.Gray,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(recentQueries) { history ->
                        RecentSearchItem(
                            history = history,
                            onQueryClick = { 
                                onQueryChange(history.query)
                                onSaveQuery(history.query)
                            },
                            onDeleteClick = { onDeleteQuery(history.query) }
                        )
                    }
                }
            }
        }

        // --- [우측: 검색 결과 그리드 (나머지 영역)] ---
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            when {
                query.isEmpty() -> {
                    // 검색 전 가이드 메시지
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Search, null, modifier = Modifier.size(80.dp), tint = Color.DarkGray)
                            Text("찾고 싶은 영화나 TV 프로그램을 검색해 보세요.", color = Color.Gray, fontSize = 18.sp)
                        }
                    }
                }
                isLoading && searchResults.isEmpty() -> {
                    SearchSkeletonGrid()
                }
                searchResults.isNotEmpty() -> {
                    SearchResultsGrid(searchResults, onSeriesClick)
                }
                !isLoading && searchResults.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text("검색 결과가 없습니다.", color = Color.Gray, fontSize = 18.sp)
                    }
                }
            }

            // 검색 중 상단 로딩 표시
            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.TopCenter),
                    color = Color.Red, trackColor = Color.Transparent
                )
            }
        }
    }
}

@Composable
private fun SearchInputField(query: String, onQueryChange: (String) -> Unit, onSaveQuery: (String) -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        placeholder = { Text("제목 입력", color = Color.Gray) },
        leadingIcon = { Icon(Icons.Default.Search, null, tint = if (isFocused) Color.Red else Color.Gray) },
        trailingIcon = { 
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) { 
                    Icon(Icons.Default.Close, null, tint = Color.Gray) 
                } 
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedContainerColor = Color(0xFF222222),
            unfocusedContainerColor = Color(0xFF181818),
            focusedBorderColor = Color.Red,
            unfocusedBorderColor = Color.Transparent,
            cursorColor = Color.Red
        ),
        shape = RoundedCornerShape(8.dp),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) onSaveQuery(query) })
    )
}

@Composable
private fun RecentSearchItem(history: SearchHistory, onQueryClick: () -> Unit, onDeleteClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(onClick = onQueryClick)
            .background(if (isFocused) Color.White.copy(alpha = 0.1f) else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Refresh, null, tint = if (isFocused) Color.White else Color.Gray, modifier = Modifier.size(18.dp))
        Text(
            text = history.query,
            color = if (isFocused) Color.White else Color.LightGray,
            fontSize = 16.sp,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (isFocused) {
            IconButton(onClick = onDeleteClick, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Close, null, tint = Color.Gray, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun SearchResultsGrid(results: List<Series>, onSeriesClick: (Series) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(4), // TV 화면에 맞게 4열로 큼직하게 배치
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        items(results) { series ->
            SearchGridItem(series, onSeriesClick)
        }
    }
}

@Composable
private fun SearchGridItem(series: Series, onSeriesClick: (Series) -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) 1.1f else 1.0f)
    
    Column(
        modifier = Modifier
            .onFocusChanged { isFocused = it.isFocused }
            .zIndex(if (isFocused) 10f else 1f)
            .scale(scale)
            .focusable()
            .clickable { onSeriesClick(series) },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .clip(RoundedCornerShape(8.dp))
                .border(
                    BorderStroke(3.dp, if (isFocused) Color.White else Color.Transparent),
                    RoundedCornerShape(8.dp)
                )
        ) {
            TmdbAsyncImage(
                title = series.title,
                posterPath = series.posterPath,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        
        // 포커스 시 제목 표시 - 정제된 제목 사용
        if (isFocused) {
            Text(
                text = series.title.cleanTitle(),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.padding(top = 8.dp),
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SearchSkeletonGrid() {
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        items(8) { 
            Box(
                modifier = Modifier
                    .aspectRatio(0.68f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(shimmerBrush())
            ) 
        }
    }
}
