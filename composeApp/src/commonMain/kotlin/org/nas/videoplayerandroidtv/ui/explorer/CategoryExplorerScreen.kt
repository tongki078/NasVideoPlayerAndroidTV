package org.nas.videoplayerandroidtv.ui.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nas.videoplayerandroidtv.domain.model.Category
import org.nas.videoplayerandroidtv.domain.model.Series
import org.nas.videoplayerandroidtv.domain.repository.VideoRepository
import org.nas.videoplayerandroidtv.ui.common.TmdbAsyncImage
import org.nas.videoplayerandroidtv.ui.common.shimmerBrush

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
        Text(
            text = pathStack.last(),
            modifier = Modifier.padding(16.dp),
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )

        if (isLoading) {
            ExplorerSkeletonGrid()
        } else {
            val hasMovies = items.any { it.movies?.isNotEmpty() == true }
            
            if (hasMovies) {
                val seriesList = items.flatMap { it.movies ?: emptyList() }.groupBySeries()
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
                LazyColumn {
                    items(items) { item ->
                        ListItem(
                            headlineContent = { Text(item.name ?: "", color = Color.White) },
                            leadingContent = { Icon(Icons.AutoMirrored.Filled.List, null, tint = Color.Gray) },
                            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color.Gray) },
                            modifier = Modifier.clickable { pathStack = pathStack + (item.name ?: "") },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExplorerSkeletonGrid() {
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

private fun List<org.nas.videoplayerandroidtv.domain.model.Movie>.groupBySeries(): List<Series> = 
    this.groupBy { (it.title ?: "").replace(Regex("(?i)[.\\s_](?:S\\d+E\\d+|S\\d+|E\\d+|\\d+\\s*(?:화|회|기)|Season\\s*\\d+|Part\\s*\\d+).*"), "").trim() }
        .map { (title, eps) -> Series(title, eps.sortedBy { it.title ?: "" }) }
        .sortedBy { it.title }
