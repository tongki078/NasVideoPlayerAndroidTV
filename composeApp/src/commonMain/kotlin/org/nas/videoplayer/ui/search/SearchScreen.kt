package org.nas.videoplayer.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.nas.videoplayer.ui.common.TmdbAsyncImage
import org.nas.videoplayer.data.SearchHistory
import org.nas.videoplayer.domain.model.Series

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
        // 1. 검색창
        SearchTextField(
            query = query,
            onQueryChange = onQueryChange,
            onSaveQuery = onSaveQuery
        )

        // 2. 카테고리 필터
        CategoryFilters(
            selectedCategory = selectedCategory,
            onCategoryChange = onCategoryChange
        )

        // 3. 결과 표시부 (로딩 / 최근검색어 / 결과그리드)
        if (isLoading) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = Color.Red)
            }
        } else if (query.isEmpty()) {
            RecentQueriesList(
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
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        placeholder = { Text("영화, 애니메이션, TV 프로그램 검색", color = Color.Gray) },
        leadingIcon = { Icon(Icons.Default.Search, null, tint = Color.Gray) },
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
            FilterChip(
                selected = selectedCategory == cat,
                onClick = { onCategoryChange(cat) },
                label = { Text(cat) },
                modifier = Modifier.padding(end = 8.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color.Red.copy(alpha = 0.2f),
                    selectedLabelColor = Color.White,
                    labelColor = Color.Gray
                )
            )
        }
    }
}

@Composable
private fun RecentQueriesList(
    queries: List<SearchHistory>,
    onQueryClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit
) {
    if (queries.isNotEmpty()) {
        Text("최근 검색어", Modifier.padding(16.dp), color = Color.White, fontWeight = FontWeight.Bold)
        LazyColumn {
            items(queries) { history ->
                ListItem(
                    headlineContent = { Text(history.query, color = Color.White) },
                    leadingContent = { Icon(Icons.Default.Refresh, null, tint = Color.Gray) },
                    trailingContent = {
                        IconButton(onClick = { onDeleteClick(history.query) }) {
                            Icon(Icons.Default.Close, null, tint = Color.Gray)
                        }
                    },
                    modifier = Modifier.clickable { onQueryClick(history.query) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
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
                Card(
                    modifier = Modifier.aspectRatio(0.67f).clickable { onSeriesClick(series) }
                ) {
                    TmdbAsyncImage(series.title, Modifier.fillMaxSize())
                }
            }
        }
    }
}
