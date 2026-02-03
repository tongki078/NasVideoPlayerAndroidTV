package org.nas.videoplayer.ui.category

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.nas.videoplayer.domain.model.Series
import org.nas.videoplayer.domain.model.Movie
import org.nas.videoplayer.domain.model.Category
import org.nas.videoplayer.domain.repository.VideoRepository
import org.nas.videoplayer.ui.common.MovieRow
import org.nas.videoplayer.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemedCategoryScreen(
    categoryName: String,
    rootPath: String,
    repository: VideoRepository,
    selectedMode: Int,            // App.kt 에서 주입받음
    onModeChange: (Int) -> Unit,  // App.kt 에서 주입받음
    onSeriesClick: (Series) -> Unit
) {
    val isAirScreen = categoryName == "방송중"
    val isAniScreen = categoryName == "애니메이션"
    val isMovieScreen = categoryName == "영화"
    val isForeignTvScreen = categoryName == "외국TV"

    // 현재 선택된 모드에 따른 텍스트 표시
    val selectedCategoryText = when {
        isAirScreen -> if (selectedMode == 0) "라프텔 애니메이션" else "드라마"
        isAniScreen -> if (selectedMode == 0) "라프텔" else "시리즈"
        isMovieScreen -> when(selectedMode) {
            0 -> "최신"
            1 -> "UHD"
            else -> "제목"
        }
        isForeignTvScreen -> when(selectedMode) {
            0 -> "중국 드라마"
            1 -> "일본 드라마"
            2 -> "미국 드라마"
            3 -> "기타국가 드라마"
            else -> "다큐"
        }
        else -> categoryName
    }
    
    var expanded by remember { mutableStateOf(false) }
    var themedSections by remember(selectedMode, categoryName) { mutableStateOf<List<Pair<String, List<Series>>>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    LaunchedEffect(selectedMode, categoryName) {
        isLoading = true
        try {
            if (isAirScreen) {
                val allSeries = if (selectedMode == 0) repository.getAnimations() else repository.getDramas()
                if (allSeries.isNotEmpty()) {
                    val shuffled = allSeries.shuffled()
                    val chunkSize = (shuffled.size / 3).coerceAtLeast(1)
                    themedSections = listOf(
                        getRandomThemeName("인기", 0, false, selectedCategoryText) to shuffled.take(chunkSize),
                        getRandomThemeName("최근 업데이트", 1, false, selectedCategoryText) to shuffled.drop(chunkSize).take(chunkSize),
                        getRandomThemeName("오늘의 추천", 2, false, selectedCategoryText) to shuffled.drop(chunkSize * 2)
                    ).filter { it.second.isNotEmpty() }
                } else {
                    themedSections = emptyList()
                }
            } else {
                val currentRootPath = when {
                    isAniScreen && selectedMode == 0 -> "애니메이션/라프텔"
                    isAniScreen && selectedMode == 1 -> "애니메이션/시리즈"
                    isMovieScreen && selectedMode == 0 -> "영화/최신"
                    isMovieScreen && selectedMode == 1 -> "영화/UHD"
                    isMovieScreen && selectedMode == 2 -> "영화/제목"
                    isForeignTvScreen && selectedMode == 0 -> "외국TV/중국 드라마"
                    isForeignTvScreen && selectedMode == 1 -> "외국TV/일본 드라마"
                    isForeignTvScreen && selectedMode == 2 -> "외국TV/미국 드라마"
                    isForeignTvScreen && selectedMode == 3 -> "외국TV/기타국가 드라마"
                    isForeignTvScreen && selectedMode == 4 -> "외국TV/다큐"
                    else -> rootPath
                }

                val themeFolders = repository.getCategoryList(currentRootPath)
                coroutineScope {
                    themedSections = themeFolders.take(4).mapIndexed { index, folder ->
                        async {
                            val folderPath = "$currentRootPath/${folder.name}"
                            val content = repository.getCategoryList(folderPath)
                            val hasDirectMovies = content.any { it.movies.isNotEmpty() }
                            val seriesList = if (hasDirectMovies) {
                                content.flatMap { it.movies }.groupBySeries(folderPath)
                            } else {
                                content.map { subFolder ->
                                    Series(
                                        title = subFolder.name.cleanTitle(includeYear = false),
                                        episodes = emptyList(),
                                        fullPath = "$folderPath/${subFolder.name}"
                                    )
                                }.filter { it.title.length > 1 }
                            }
                            if (seriesList.isNotEmpty()) {
                                getRandomThemeName(folder.name, index, currentRootPath.contains("영화"), categoryName) to seriesList
                            } else null
                        }
                    }.awaitAll().filterNotNull()
                }
            }
        } catch (e: Exception) {
            themedSections = emptyList()
        } finally {
            isLoading = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (isAirScreen || isAniScreen || isMovieScreen || isForeignTvScreen) {
            Box(modifier = Modifier.padding(16.dp)) {
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = selectedCategoryText,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("카테고리 선택", color = Color.Gray) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Red,
                            unfocusedBorderColor = Color.Gray,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedLabelColor = Color.Red,
                            unfocusedLabelColor = Color.Gray
                        ),
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        when {
                            isAirScreen -> {
                                DropdownMenuItem(text = { Text("라프텔 애니메이션") }, onClick = { onModeChange(0); expanded = false })
                                DropdownMenuItem(text = { Text("드라마") }, onClick = { onModeChange(1); expanded = false })
                            }
                            isAniScreen -> {
                                DropdownMenuItem(text = { Text("라프텔") }, onClick = { onModeChange(0); expanded = false })
                                DropdownMenuItem(text = { Text("시리즈") }, onClick = { onModeChange(1); expanded = false })
                            }
                            isMovieScreen -> {
                                DropdownMenuItem(text = { Text("최신") }, onClick = { onModeChange(0); expanded = false })
                                DropdownMenuItem(text = { Text("UHD") }, onClick = { onModeChange(1); expanded = false })
                                DropdownMenuItem(text = { Text("제목") }, onClick = { onModeChange(2); expanded = false })
                            }
                            isForeignTvScreen -> {
                                DropdownMenuItem(text = { Text("중국 드라마") }, onClick = { onModeChange(0); expanded = false })
                                DropdownMenuItem(text = { Text("일본 드라마") }, onClick = { onModeChange(1); expanded = false })
                                DropdownMenuItem(text = { Text("미국 드라마") }, onClick = { onModeChange(2); expanded = false })
                                DropdownMenuItem(text = { Text("기타국가 드라마") }, onClick = { onModeChange(3); expanded = false })
                                DropdownMenuItem(text = { Text("다큐") }, onClick = { onModeChange(4); expanded = false })
                            }
                        }
                    }
                }
            }
        }

        if (isLoading && themedSections.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = Color.Red)
            }
        } else if (themedSections.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("불러올 영상이 없습니다.", color = Color.Gray)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(), 
                contentPadding = PaddingValues(bottom = 100.dp)
            ) {
                items(themedSections) { (title, seriesList) ->
                    MovieRow(
                        title = title,
                        seriesList = seriesList,
                        onSeriesClick = onSeriesClick
                    )
                }
            }
        }
    }
}

private fun List<Movie>.groupBySeries(basePath: String? = null): List<Series> = 
    this.groupBy { it.title.cleanTitle(includeYear = false) }
        .map { (title, eps) -> 
            Series(
                title = title, 
                episodes = eps.sortedWith(
                    compareBy<Movie> { it.title.extractSeason() }
                        .thenBy { it.title.extractEpisode()?.filter { char -> char.isDigit() }?.toIntOrNull() ?: 0 }
                ),
                fullPath = basePath
            ) 
        }
        .sortedBy { it.title }
