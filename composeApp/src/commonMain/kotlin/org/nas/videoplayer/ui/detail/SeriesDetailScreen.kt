package org.nas.videoplayer.ui.detail

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.nas.videoplayer.*
import org.nas.videoplayer.domain.model.Movie
import org.nas.videoplayer.domain.model.Series
import org.nas.videoplayer.domain.repository.VideoRepository
import org.nas.videoplayer.ui.common.TmdbAsyncImage

@Composable
fun SeriesDetailScreen(
    series: Series,
    repository: VideoRepository,
    onBack: () -> Unit,
    onPlay: (Movie, List<Movie>) -> Unit
) {
    var metadata by remember { mutableStateOf<TmdbMetadata?>(null) }
    var credits by remember { mutableStateOf<List<TmdbCast>>(emptyList()) }
    var episodes by remember { mutableStateOf(series.episodes) }
    var isLoadingEpisodes by remember { mutableStateOf(false) }

    LaunchedEffect(series) {
        // 에피소드 로딩 로직
        if (episodes.isEmpty() && series.fullPath != null) {
            isLoadingEpisodes = true
            try {
                val content = repository.getCategoryList(series.fullPath!!)
                val fetchedMovies = content.flatMap { it.movies }.ifEmpty {
                    content.flatMap { folder -> 
                        repository.getCategoryList("${series.fullPath!!}/${folder.name}").flatMap { it.movies }
                    }
                }
                if (fetchedMovies.isNotEmpty()) {
                    episodes = fetchedMovies.sortedWith(
                        compareBy<Movie> { it.title.extractSeason() }
                            .thenBy { it.title.extractEpisode()?.filter { it.isDigit() }?.toIntOrNull() ?: 0 }
                    )
                }
            } finally {
                isLoadingEpisodes = false
            }
        }
        
        // 메타데이터 및 출연진 로딩
        coroutineScope {
            val meta = fetchTmdbMetadata(series.title)
            metadata = meta
            if (meta.tmdbId != null) {
                credits = fetchTmdbCredits(meta.tmdbId!!, meta.mediaType)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black).verticalScroll(rememberScrollState())
    ) {
        // 상단 바
        Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) { 
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
            Text(series.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        // 포스터 이미지
        TmdbAsyncImage(series.title, modifier = Modifier.fillMaxWidth().height(280.dp), contentScale = ContentScale.Crop, isLarge = true)

        // 전체 줄거리
        Text(metadata?.overview ?: "상세 정보를 불러오는 중입니다...", modifier = Modifier.padding(16.dp), color = Color.LightGray, fontSize = 14.sp, lineHeight = 20.sp)

        // 출연진
        if (credits.isNotEmpty()) {
            Text("성우 및 출연진", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp)) {
                items(credits) { cast ->
                    Column(modifier = Modifier.width(90.dp).padding(end = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        AsyncImage(model = "$TMDB_IMAGE_BASE$TMDB_POSTER_SIZE_SMALL${cast.profilePath}", contentDescription = null, modifier = Modifier.size(70.dp).clip(CircleShape).background(Color.DarkGray), contentScale = ContentScale.Crop)
                        Spacer(Modifier.height(4.dp))
                        Text(cast.name, color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 2)
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // 에피소드 리스트
        Text("에피소드", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        
        if (isLoadingEpisodes) {
            Box(Modifier.fillMaxWidth().padding(20.dp), Alignment.Center) { CircularProgressIndicator(color = Color.Red) }
        } else if (episodes.isEmpty()) {
            Text("에피소드 정보를 찾을 수 없습니다.", color = Color.Gray, modifier = Modifier.padding(16.dp))
        } else {
            Column(Modifier.padding(horizontal = 8.dp)) {
                episodes.forEach { ep ->
                    EpisodeItem(ep, metadata, onPlay = { onPlay(ep, episodes) })
                }
            }
        }
        
        Spacer(Modifier.height(50.dp))
    }
}

@Composable
fun EpisodeItem(movie: Movie, seriesMeta: TmdbMetadata?, onPlay: () -> Unit) {
    // TmdbEpisodeDetail -> TmdbEpisode 로 타입 이름 수정
    var episodeDetails by remember { mutableStateOf<TmdbEpisode?>(null) }

    LaunchedEffect(movie, seriesMeta) {
        // TV 시리즈일 경우 에피소드 상세 정보(썸네일, 줄거리) 가져오기
        if (seriesMeta?.tmdbId != null && seriesMeta.mediaType == "tv") {
            val season = movie.title.extractSeason()
            val episodeNum = movie.title.extractEpisode()?.filter { it.isDigit() }?.toIntOrNull() ?: 1
            episodeDetails = fetchTmdbEpisodeDetails(seriesMeta.tmdbId!!, season, episodeNum)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 8.dp).clickable(onClick = onPlay),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 에피소드 썸네일
        Box(modifier = Modifier.width(140.dp).height(80.dp)) {
            AsyncImage(
                model = if (episodeDetails?.stillPath != null) "$TMDB_IMAGE_BASE$TMDB_POSTER_SIZE_SMALL${episodeDetails?.stillPath}" else "",
                contentDescription = null,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)).background(Color.DarkGray),
                contentScale = ContentScale.Crop
            )
        }
        
        Spacer(Modifier.width(16.dp))
        
        // 제목 및 줄거리
        Column(Modifier.weight(1f)) {
            Text(movie.title.prettyTitle(), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(
                text = episodeDetails?.overview ?: "줄거리 정보가 없습니다.",
                color = Color.Gray,
                fontSize = 12.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 16.sp
            )
        }
    }
}
