package org.nas.videoplayerandroidtv.ui.category

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.nas.videoplayerandroidtv.domain.model.Series
import org.nas.videoplayerandroidtv.domain.model.Movie
import org.nas.videoplayerandroidtv.domain.model.Category
import org.nas.videoplayerandroidtv.domain.repository.VideoRepository
import org.nas.videoplayerandroidtv.ui.common.MovieRow
import org.nas.videoplayerandroidtv.*
import org.nas.videoplayerandroidtv.ui.common.shimmerBrush

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemedCategoryScreen(
    categoryName: String,
    rootPath: String,
    repository: VideoRepository,
    selectedMode: Int,
    onModeChange: (Int) -> Unit,
    lazyListState: LazyListState = rememberLazyListState(), // 스크롤 상태 보존
    onSeriesClick: (Series) -> Unit
) {
    val isAirScreen = categoryName == "방송중"
    val isAniScreen = categoryName == "애니메이션"
    val isMovieScreen = categoryName == "영화"
    val isForeignTvScreen = categoryName == "외국TV"
    val isKoreanTvScreen = categoryName == "국내TV"

    val selectedCategoryText = when {
        isAirScreen -> if (selectedMode == 0) "라프텔 애니메이션" else "드라마"
        isAniScreen -> if (selectedMode == 0) "라프텔" else "시리즈"
        isMovieScreen -> when(selectedMode) { 0 -> "최신"; 1 -> "UHD"; else -> "제목" }
        isForeignTvScreen -> when(selectedMode) { 0 -> "중국 드라마"; 1 -> "일본 드라마"; 2 -> "미국 드라마"; 3 -> "기타국가 드라마"; else -> "다큐" }
        isKoreanTvScreen -> when(selectedMode) { 0 -> "드라마"; 1 -> "시트콤"; 2 -> "교양"; 3 -> "다큐멘터리"; else -> "예능" }
        else -> categoryName
    }
    
    var expanded by remember { mutableStateOf(false) }
    var themedSections by remember(selectedMode, categoryName) { mutableStateOf<List<Pair<String, List<Series>>>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(selectedMode, categoryName) {
        isLoading = true
        try {
            val sections = if (isAirScreen) {
                val allSeries = if (selectedMode == 0) repository.getAnimations() else repository.getDramas()
                if (allSeries.isNotEmpty()) {
                    val shuffled = allSeries.shuffled()
                    val chunkSize = (shuffled.size / 3).coerceAtLeast(1)
                    listOf(
                        getRandomThemeName("인기", 0, false, selectedCategoryText) to shuffled.take(chunkSize),
                        getRandomThemeName("최근 업데이트", 1, false, selectedCategoryText) to shuffled.drop(chunkSize).take(chunkSize),
                        getRandomThemeName("오늘의 추천", 2, false, selectedCategoryText) to shuffled.drop(chunkSize * 2)
                    ).filter { it.second.isNotEmpty() }
                } else emptyList()
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
                    isKoreanTvScreen && selectedMode == 0 -> "국내TV/드라마"
                    isKoreanTvScreen && selectedMode == 1 -> "국내TV/시트콤"
                    isKoreanTvScreen && selectedMode == 2 -> "국내TV/교양"
                    isKoreanTvScreen && selectedMode == 3 -> "국내TV/다큐멘터리"
                    isKoreanTvScreen && selectedMode == 4 -> "국내TV/예능"
                    else -> rootPath
                }

                val themeFolders = repository.getCategoryList(currentRootPath)
                coroutineScope {
                    themeFolders.take(4).mapIndexed { index, folder ->
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

            coroutineScope {
                sections.flatMap { it.second.take(5) }.map { series ->
                    async { fetchTmdbMetadata(series.title) }
                }.awaitAll()
            }

            themedSections = sections
        } catch (e: Exception) {
            themedSections = emptyList()
        } finally {
            isLoading = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (isAirScreen || isAniScreen || isMovieScreen || isForeignTvScreen || isKoreanTvScreen) {
            ExposedCategoryDropdown(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                selectedText = selectedCategoryText
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
                    isKoreanTvScreen -> {
                        DropdownMenuItem(text = { Text("드라마") }, onClick = { onModeChange(0); expanded = false })
                        DropdownMenuItem(text = { Text("시트콤") }, onClick = { onModeChange(1); expanded = false })
                        DropdownMenuItem(text = { Text("교양") }, onClick = { onModeChange(2); expanded = false })
                        DropdownMenuItem(text = { Text("다큐멘터리") }, onClick = { onModeChange(3); expanded = false })
                        DropdownMenuItem(text = { Text("예능") }, onClick = { onModeChange(4); expanded = false })
                    }
                }
            }
        }

        if (isLoading) {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp)) {
                items(3) { CategorySectionSkeleton() }
            }
        } else if (themedSections.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("불러올 영상이 없습니다.", color = Color.Gray)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(), 
                state = lazyListState,
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

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ExposedCategoryDropdown(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    selectedText: String,
    content: @Composable ColumnScope.() -> Unit
) {
     Box(modifier = Modifier.padding(16.dp)) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = onExpandedChange,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = selectedText,
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
                onDismissRequest = { onExpandedChange(false) },
                content = content
            )
        }
    }
}

@Composable
private fun CategorySectionSkeleton() {
    Column(Modifier.padding(vertical = 16.dp)) {
        Box(Modifier.padding(horizontal = 16.dp).width(150.dp).height(24.dp).clip(RoundedCornerShape(4.dp)).background(shimmerBrush()))
        Spacer(Modifier.height(16.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp)) {
            items(5) {
                Box(
                    modifier = Modifier
                        .size(130.dp, 200.dp)
                        .padding(end = 12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(shimmerBrush())
                )
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
