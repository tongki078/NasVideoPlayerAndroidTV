package org.nas.videoplayerandroidtv.ui.search

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nas.videoplayerandroidtv.*
import org.nas.videoplayerandroidtv.data.SearchHistory
import org.nas.videoplayerandroidtv.domain.model.Series
import org.nas.videoplayerandroidtv.ui.common.TmdbAsyncImage
import org.nas.videoplayerandroidtv.ui.common.shimmerBrush

@Composable
fun SearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    selectedCategory: String,
    onCategoryChange: (String) -> Unit,
    recentQueries: List<SearchHistory>,
    searchResults: List<Series>,
    isLoading: Boolean,
    onSaveQuery: (String) -> Unit,
    onDeleteQuery: (String) -> Unit,
    onSeriesClick: (Series) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
        SearchTextField(
            query = query,
            onQueryChange = onQueryChange,
            onSaveQuery = onSaveQuery
        )

        CategoryFilters(
            selectedCategory = selectedCategory,
            onCategoryChange = onCategoryChange
        )

        if (isLoading) {
            SearchSkeletonGrid()
        } else if (query.isEmpty()) {
            RecentSearchesList(
                queries = recentQueries,
                onQueryClick = onQueryChange,
                onDeleteClick = onDeleteQuery
            )
        } else {
            SearchResultsGrid(
                results = searchResults,
                onSeriesClick = onSeriesClick
            )
        }
    }
}

@Composable
private fun SearchTextField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSaveQuery: (String) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .onFocusChanged { isFocused = it.isFocused },
        placeholder = { Text("영화, 애니메이션, TV 프로그램 검색", color = Color.Gray) },
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
            focusedBorderColor = Color.Red,
            unfocusedBorderColor = Color.DarkGray,
            cursorColor = Color.Red
        ),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) onSaveQuery(query) })
    )
}

@Composable
private fun CategoryFilters(
    selectedCategory: String,
    onCategoryChange: (String) -> Unit
) {
    LazyRow(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        items(listOf("전체", "영화", "애니메이션", "TV")) { cat ->
            var isFocused by remember { mutableStateOf(false) }
            val scale by animateFloatAsState(if (isFocused) 1.1f else 1f)
            
            FilterChip(
                selected = selectedCategory == cat,
                onClick = { onCategoryChange(cat) },
                label = { Text(cat) },
                modifier = Modifier
                    .padding(end = 8.dp)
                    .scale(scale)
                    .onFocusChanged { isFocused = it.isFocused }
                    .focusable(),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color.Red,
                    selectedLabelColor = Color.White,
                    labelColor = Color.Gray
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selectedCategory == cat,
                    borderColor = if (isFocused) Color.White else Color.Transparent,
                    borderWidth = if (isFocused) 2.dp else 0.dp
                )
            )
        }
    }
}

@Composable
private fun RecentSearchesList(
    queries: List<SearchHistory>,
    onQueryClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "최근 검색어",
            modifier = Modifier.padding(16.dp),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(queries) { history ->
                RecentSearchItem(
                    history = history,
                    onQueryClick = { onQueryClick(history.query) },
                    onDeleteClick = { onDeleteClick(history.query) }
                )
            }
        }
    }
}

@Composable
private fun RecentSearchItem(
    history: SearchHistory,
    onQueryClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    var metadata by remember(history.query) { mutableStateOf<TmdbMetadata?>(null) }
    
    LaunchedEffect(history.query) {
        metadata = fetchTmdbMetadata(history.query)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(onClick = onQueryClick)
            .background(if (isFocused) Color.DarkGray.copy(alpha = 0.5f) else Color.Transparent)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) Color.White else Color.Transparent
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(140.dp)
                .height(80.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.DarkGray)
        ) {
            TmdbAsyncImage(
                title = history.query,
                modifier = Modifier.fillMaxSize()
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(
                text = history.query,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = metadata?.overview ?: "상세 정보가 없습니다.",
                color = Color.Gray,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 16.sp
            )
        }

        IconButton(onClick = onDeleteClick) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Delete",
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun SearchResultsGrid(
    results: List<Series>,
    onSeriesClick: (Series) -> Unit
) {
    if (results.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text("검색 결과가 없습니다.", color = Color.Gray)
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(results) { series ->
                var isFocused by remember { mutableStateOf(false) }
                val scale by animateFloatAsState(if (isFocused) 1.1f else 1f)

                Card(
                    modifier = Modifier
                        .aspectRatio(0.67f)
                        .scale(scale)
                        .onFocusChanged { isFocused = it.isFocused }
                        .border(
                            width = if (isFocused) 3.dp else 0.dp,
                            color = if (isFocused) Color.White else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .focusable()
                        .clickable { onSeriesClick(series) }
                ) {
                    TmdbAsyncImage(series.title, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun SearchSkeletonGrid() {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(9) {
            Box(
                modifier = Modifier
                    .aspectRatio(0.67f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(shimmerBrush())
            )
        }
    }
}
