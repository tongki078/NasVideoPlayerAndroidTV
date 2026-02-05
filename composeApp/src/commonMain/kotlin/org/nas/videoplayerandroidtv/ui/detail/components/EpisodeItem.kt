package org.nas.videoplayerandroidtv.ui.detail.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import org.nas.videoplayerandroidtv.*
import org.nas.videoplayerandroidtv.domain.model.Movie
import org.nas.videoplayerandroidtv.ui.common.shimmerBrush

@Composable
fun EpisodeList(episodes: List<Movie>, metadata: TmdbMetadata?, onPlay: (Movie) -> Unit) {
    Text(
        text = "에피소드", 
        color = Color.White, 
        fontWeight = FontWeight.Bold, 
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 12.dp), 
        fontSize = 20.sp
    )
    Column(Modifier.padding(horizontal = 8.dp)) {
        episodes.forEach { ep -> 
            EpisodeItem(ep, metadata, onPlay = { onPlay(ep) }) 
        }
    }
}

@Composable
fun EpisodeItem(movie: Movie, seriesMeta: TmdbMetadata?, onPlay: () -> Unit) {
    var episodeDetails by remember { mutableStateOf<TmdbEpisode?>(null) }
    var isFocused by remember { mutableStateOf(false) }

    LaunchedEffect(movie, seriesMeta) {
        if (seriesMeta?.tmdbId != null && seriesMeta.mediaType == "tv") {
            val season = movie.title.extractSeason()
            val episodeNum = movie.title.extractEpisode()?.filter { it.isDigit() }?.toIntOrNull() ?: 1
            episodeDetails = fetchTmdbEpisodeDetails(seriesMeta.tmdbId, season, episodeNum)
        }
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 8.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(onClick = onPlay)
            .background(if (isFocused) Color.White.copy(alpha = 0.15f) else Color.Transparent, RoundedCornerShape(8.dp))
            .border(
                width = if (isFocused) 3.dp else 0.dp,
                color = if (isFocused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(12.dp), 
        verticalAlignment = Alignment.CenterVertically
    ) {
        val imageUrl = when {
            episodeDetails?.stillPath != null -> "$TMDB_IMAGE_BASE$TMDB_POSTER_SIZE_SMALL${episodeDetails?.stillPath}"
            seriesMeta?.posterUrl != null -> seriesMeta.posterUrl
            else -> ""
        }
        
        var isLoading by remember { mutableStateOf(true) }
        Box(
            modifier = Modifier
                .width(160.dp)
                .height(90.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(shimmerBrush(showShimmer = isLoading && imageUrl.isNotEmpty()))
        ) {
            if (imageUrl.isNotEmpty()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = movie.title,
                    onState = { state -> isLoading = state is AsyncImagePainter.State.Loading },
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }

        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = movie.title.prettyTitle(), 
                color = if (isFocused) Color.Yellow else Color.White, 
                fontSize = 16.sp, 
                fontWeight = FontWeight.Bold, 
                maxLines = 1, 
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = episodeDetails?.overview ?: "줄거리 정보가 없습니다.", 
                color = Color.Gray, 
                fontSize = 13.sp, 
                maxLines = 2, 
                overflow = TextOverflow.Ellipsis, 
                lineHeight = 18.sp
            )
        }
    }
}
