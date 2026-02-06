package org.nas.videoplayerandroidtv.ui.category

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import org.nas.videoplayerandroidtv.domain.model.Series
import org.nas.videoplayerandroidtv.domain.repository.VideoRepository
import org.nas.videoplayerandroidtv.ui.common.MovieRow
import org.nas.videoplayerandroidtv.*

private object ThemeConfig {
    val ACTION_ADVENTURE = listOf(28, 12, 10759, 10765)
    val FANTASY_SCI_FI = listOf(14, 878)
    val COMEDY_LIFE = listOf(35, 10762, 10763, 10767)
    val MYSTERY_THRILLER = listOf(9648, 53, 27, 80)
    val DRAMA_ROMANCE = listOf(18, 10749, 10766, 10764)
    val FAMILY_ANIMATION = listOf(10751, 16)
}

private data class ThemeSection(val id: String, val title: String, val seriesList: List<Series>)

@Composable
fun ThemedCategoryScreen(
    categoryName: String,
    rootPath: String,
    repository: VideoRepository,
    selectedMode: Int,
    onModeChange: (Int) -> Unit,
    lazyListState: LazyListState = rememberLazyListState(),
    onSeriesClick: (Series) -> Unit
) {
    val isMovieScreen = categoryName == "영화"
    val isAniScreen = categoryName == "애니메이션"
    val isAirScreen = categoryName == "방송중"
    val isForeignTVScreen = categoryName == "외국TV"
    val isKoreanTVScreen = categoryName == "국내TV"

    val modes = when {
        isAirScreen -> listOf("라프텔 애니메이션", "드라마")
        isAniScreen -> listOf("라프텔", "시리즈")
        isMovieScreen -> listOf("제목", "UHD", "최신")
        isForeignTVScreen -> listOf("미국 드라마", "중국 드라마", "일본 드라마", "기타국가 드라마", "다큐")
        isKoreanTVScreen -> listOf("드라마", "시트콤", "예능", "교양", "다큐멘터리")
        else -> emptyList()
    }

    var themedSections by remember { mutableStateOf(emptyList<ThemeSection>()) }
    var isLoading by remember(selectedMode, categoryName) { mutableStateOf(true) }

    LaunchedEffect(selectedMode, categoryName) {
        isLoading = true
        try {
            val result = withContext(Dispatchers.Default) {
                val limit = 1000
                when {
                    isMovieScreen -> when (selectedMode) {
                        0 -> repository.getMoviesByTitle(limit, 0)
                        1 -> repository.getUhdMovies(limit, 0)
                        else -> repository.getLatestMovies(limit, 0)
                    }
                    isAniScreen -> if (selectedMode == 0) repository.getAnimationsRaftel(limit, 0) else repository.getAnimationsSeries(limit, 0)
                    isAirScreen -> if (selectedMode == 0) repository.getAnimationsAir() else repository.getDramasAir()
                    isForeignTVScreen -> when (selectedMode) {
                        0 -> repository.getFtvUs(limit, 0); 1 -> repository.getFtvCn(limit, 0)
                        2 -> repository.getFtvJp(limit, 0); 3 -> repository.getFtvEtc(limit, 0)
                        4 -> repository.getFtvDocu(limit, 0); else -> emptyList()
                    }
                    isKoreanTVScreen -> when (selectedMode) {
                        0 -> repository.getKtvDrama(limit, 0); 1 -> repository.getKtvSitcom(limit, 0)
                        2 -> repository.getKtvVariety(limit, 0); 3 -> repository.getKtvEdu(limit, 0)
                        4 -> repository.getKtvDocu(limit, 0); else -> emptyList()
                    }
                    else -> emptyList()
                }
            }
            
            val sections = withContext(Dispatchers.Default) {
                val tA = mutableListOf<Series>(); val tF = mutableListOf<Series>()
                val tC = mutableListOf<Series>(); val tT = mutableListOf<Series>()
                val tR = mutableListOf<Series>(); val tM = mutableListOf<Series>()
                val tE = mutableListOf<Series>()

                // 중요: 원본 Series 객체의 모든 정보(fullPath 포함)를 그대로 보존하면서 분류
                result.forEach { s ->
                    val gIds = s.genreIds
                    when {
                        gIds.any { it in ThemeConfig.ACTION_ADVENTURE } -> tA.add(s)
                        gIds.any { it in ThemeConfig.FANTASY_SCI_FI } -> tF.add(s)
                        gIds.any { it in ThemeConfig.COMEDY_LIFE } -> tC.add(s)
                        gIds.any { it in ThemeConfig.MYSTERY_THRILLER } -> tT.add(s)
                        gIds.any { it in ThemeConfig.DRAMA_ROMANCE } -> tR.add(s)
                        gIds.any { it in ThemeConfig.FAMILY_ANIMATION } -> tM.add(s)
                        else -> tE.add(s)
                    }
                }
                
                mutableListOf<ThemeSection>().apply {
                    val distinct = { list: List<Series> -> list.distinctBy { it.fullPath ?: it.title } }
                    if (tA.isNotEmpty()) add(ThemeSection("action", "박진감 넘치는 액션 & 어드벤처", distinct(tA)))
                    if (tF.isNotEmpty()) add(ThemeSection("fantasy", "상상 그 이상! 판타지 & SF", distinct(tF)))
                    if (tC.isNotEmpty()) add(ThemeSection("comedy", "유쾌한 즐거움! 코미디 & 라이프", distinct(tC)))
                    if (tT.isNotEmpty()) add(ThemeSection("thriller", "숨막히는 미스터리 & 스릴러", distinct(tT)))
                    if (tR.isNotEmpty()) add(ThemeSection("romance", "달콤하고 절절한 로맨스 & 드라마", distinct(tR)))
                    if (tM.isNotEmpty()) add(ThemeSection("family", "온 가족이 함께! 패밀리 & 애니", distinct(tM)))
                    
                    if (isEmpty() && result.isNotEmpty()) {
                        add(ThemeSection("all", "추천 작품", distinct(result)))
                    } else if (tE.isNotEmpty()) {
                        add(ThemeSection("etc", "놓치면 아쉬운 더 많은 작품들", distinct(tE)))
                    }
                }
            }
            themedSections = sections
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isLoading = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0F0F0F))) {
        if (modes.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp, start = 48.dp, end = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(modes.size) { index ->
                    CategoryTabItem(text = modes[index], isSelected = selectedMode == index, onClick = { onModeChange(index) })
                }
            }
        }

        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp), color = Color.Red, trackColor = Color.Transparent)
        } else {
            Spacer(Modifier.height(2.dp))
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (!isLoading && themedSections.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("표시할 영상이 없습니다.", color = Color.Gray)
                        Spacer(Modifier.height(8.dp))
                        Text("서버에서 인덱싱이 완료될 때까지 잠시만 기다려 주세요.", color = Color.Gray.copy(alpha = 0.6f), fontSize = 12.sp)
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize(), state = lazyListState, contentPadding = PaddingValues(bottom = 60.dp)) {
                    items(themedSections, key = { it.id }) { section ->
                        MovieRow(title = section.title, seriesList = section.seriesList, onSeriesClick = onSeriesClick)
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryTabItem(text: String, isSelected: Boolean, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val backgroundColor by animateColorAsState(if (isFocused) Color.White else if (isSelected) Color.Red else Color.Gray.copy(alpha = 0.2f))
    val textColor by animateColorAsState(if (isFocused) Color.Black else if (isSelected) Color.White else Color.Gray)
    Box(
        modifier = Modifier.clip(RoundedCornerShape(24.dp)).background(backgroundColor).onFocusChanged { isFocused = it.isFocused }.focusable().clickable { onClick() }.padding(horizontal = 20.dp, vertical = 8.dp).scale(if (isFocused) 1.1f else 1.0f),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = textColor, fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium, fontSize = 15.sp)
    }
}
