package org.nas.videoplayer.ui.detail

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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

    LaunchedEffect(series) {
        if (episodes.isEmpty() && series.fullPath != null) {
            episodes = repository.getCategoryList(series.fullPath!!).flatMap { it.movies }
        }
        coroutineScope {
            val meta = fetchTmdbMetadata(series.title)
            metadata = meta
            if (meta.tmdbId != null) {
                credits = fetchTmdbCredits(meta.tmdbId!!, meta.mediaType)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) { 
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
            Text(series.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        TmdbAsyncImage(
            title = series.title, 
            modifier = Modifier.fillMaxWidth().height(280.dp), 
            contentScale = ContentScale.Crop, 
            isLarge = true
        )

        Text(
            text = metadata?.overview ?: "상세 정보를 불러오는 중입니다...",
            modifier = Modifier.padding(16.dp),
            color = Color.LightGray,
            fontSize = 14.sp,
            lineHeight = 20.sp
        )

        if (credits.isNotEmpty()) {
            Text("성우 및 출연진", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp)) {
                items(credits) { cast ->
                    Column(
                        modifier = Modifier.width(90.dp).padding(end = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AsyncImage(
                            model = "$TMDB_IMAGE_BASE$TMDB_POSTER_SIZE_SMALL${cast.profilePath}",
                            contentDescription = null,
                            modifier = Modifier.size(70.dp).clip(CircleShape).background(Color.DarkGray),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(cast.name, color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 2)
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Text("에피소드", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        episodes.forEach { ep ->
            ListItem(
                headlineContent = { Text(ep.title.prettyTitle(), color = Color.White, fontSize = 15.sp) },
                modifier = Modifier.clickable { onPlay(ep, episodes) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
        }
        
        Spacer(Modifier.height(50.dp))
    }
}
