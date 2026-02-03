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
import org.nas.videoplayer.domain.repository.VideoRepository
import org.nas.videoplayer.ui.common.MovieRow
import org.nas.videoplayer.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemedCategoryScreen(
    categoryName: String,
    rootPath: String,
    repository: VideoRepository,
    onSeriesClick: (Series) -> Unit
) {
    val isAirScreen = categoryName == "방송중"
    val isAniScreen = categoryName == "애니메이션"

    // 0: 라프텔 애니메이션, 1: 드라마
    var selectedAirMode by remember(categoryName) { mutableStateOf(0) }
    // 0: 라프텔, 1: 시리즈
    var selectedAniMode by remember(categoryName) { mutableStateOf(0) }

    val selectedCategoryText = when {
        isAirScreen -> if (selectedAirMode == 0) "라프텔 애니메이션" else "드라마"
        isAniScreen -> if (selectedAniMode == 0) "라프텔" else "시리즈"
        else -> categoryName
    }
    var expanded by remember { mutableStateOf(false) }

    var themedSections by remember(selectedAirMode, selectedAniMode, categoryName) { mutableStateOf<List<Pair<String, List<Series>>>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    LaunchedEffect(selectedAirMode, selectedAniMode, categoryName) {
        isLoading = true
        try {
            if (isAirScreen) {
                // 방송중일 때는 서버의 전용 엔드포인트(/animations, /dramas)를 직접 호출
                val allSeries = if (selectedAirMode == 0) repository.getAnimations() else repository.getDramas()

                if (allSeries.isNotEmpty()) {
                    // 전체 리스트를 3개의 테마로 나누어 표시
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
                // 애니메이션 및 일반 카테고리 로직
                val currentRootPath = when {
                    isAniScreen && selectedAniMode == 0 -> "애니메이션/라프텔"
                    isAniScreen && selectedAniMode == 1 -> "애니메이션/시리즈"
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
                                content.flatMap { it.movies }.groupBySeries()
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
                                getRandomThemeName(folder.name, index, rootPath.contains("영화"), categoryName) to seriesList
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
        if (isAirScreen || isAniScreen) {
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
                        if (isAirScreen) {
                            DropdownMenuItem(
                                text = { Text("라프텔 애니메이션") },
                                onClick = {
                                    selectedAirMode = 0
                                    expanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("드라마") },
                                onClick = {
                                    selectedAirMode = 1
                                    expanded = false
                                }
                            )
                        } else if (isAniScreen) {
                            DropdownMenuItem(
                                text = { Text("라프텔") },
                                onClick = {
                                    selectedAniMode = 0
                                    expanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("시리즈") },
                                onClick = {
                                    selectedAniMode = 1
                                    expanded = false
                                }
                            )
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

private fun List<Movie>.groupBySeries(): List<Series> = 
    this.groupBy { it.title.cleanTitle(includeYear = false) }
        .map { (title, eps) -> 
            Series(
                title = title, 
                episodes = eps.sortedWith(
                    compareBy<Movie> { it.title.extractSeason() }
                        .thenBy { it.title.extractEpisode()?.filter { char -> char.isDigit() }?.toIntOrNull() ?: 0 }
                ),
                fullPath = null
            ) 
        }
        .sortedBy { it.title }
