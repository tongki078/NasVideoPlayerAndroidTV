package org.nas.videoplayer.ui.explorer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nas.videoplayer.domain.model.Category
import org.nas.videoplayer.domain.model.Series
import org.nas.videoplayer.domain.repository.VideoRepository
import org.nas.videoplayer.ui.common.TmdbAsyncImage

@Composable
fun CategoryExplorerScreen(
    title: String,
    rootPath: String,
    repository: VideoRepository,
    onSeriesClick: (Series) -> Unit
) {
    var pathStack by remember(rootPath) { mutableStateOf(listOf(rootPath)) }
    var items by remember { mutableStateOf<List<Category>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    LaunchedEffect(pathStack) {
        isLoading = true
        items = repository.getCategoryList(pathStack.joinToString("/"))
        isLoading = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 상단 경로 표시 (Breadcrumbs 대용)
        Text(
            text = pathStack.last(),
            modifier = Modifier.padding(16.dp),
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )

        if (isLoading) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = Color.Red)
            }
        } else {
            val hasMovies = items.any { it.movies.isNotEmpty() }
            
            if (hasMovies) {
                // 영화/에피소드가 있는 경우 그리드 표시
                val seriesList = items.flatMap { it.movies }.groupBySeries()
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(seriesList) { series ->
                        Card(modifier = Modifier.aspectRatio(0.67f).clickable { onSeriesClick(series) }) {
                            TmdbAsyncImage(series.title, Modifier.fillMaxSize())
                        }
                    }
                }
            } else {
                // 폴더만 있는 경우 리스트 표시
                LazyColumn {
                    items(items) { item ->
                        ListItem(
                            headlineContent = { Text(item.name, color = Color.White) },
                            leadingContent = { Icon(Icons.AutoMirrored.Filled.List, null, tint = Color.Gray) },
                            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color.Gray) },
                            modifier = Modifier.clickable { pathStack = pathStack + item.name },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            }
        }
    }
}

// 로컬 헬퍼 함수 (RepositoryImpl에 있는 것과 동일한 로직)
private fun List<org.nas.videoplayer.domain.model.Movie>.groupBySeries(): List<Series> = 
    this.groupBy { it.title.replace(Regex("(?i)[.\\s_](?:S\\d+E\\d+|S\\d+|E\\d+|\\d+\\s*(?:화|회|기)|Season\\s*\\d+|Part\\s*\\d+).*"), "").trim() }
        .map { (title, eps) -> Series(title, eps.sortedBy { it.title }) }
        .sortedBy { it.title }
