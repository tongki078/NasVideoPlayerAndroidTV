package org.nas.videoplayerandroidtv.ui.detail.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.nas.videoplayerandroidtv.ui.common.shimmerBrush

@Composable
fun ShimmeringBox(modifier: Modifier) {
    Box(modifier = modifier.background(shimmerBrush()))
}

@Composable
fun HeaderSkeleton() {
    Column {
        ShimmeringBox(modifier = Modifier.fillMaxWidth().height(300.dp))
        Spacer(Modifier.height(16.dp))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ShimmeringBox(modifier = Modifier.width(200.dp).height(30.dp))
            ShimmeringBox(modifier = Modifier.fillMaxWidth().height(20.dp))
            ShimmeringBox(modifier = Modifier.fillMaxWidth(0.7f).height(20.dp))
        }
    }
}

@Composable
fun EpisodeListSkeleton() {
    Column(Modifier.padding(16.dp)) {
        repeat(3) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ShimmeringBox(modifier = Modifier.width(140.dp).height(80.dp).clip(RoundedCornerShape(4.dp)))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ShimmeringBox(modifier = Modifier.fillMaxWidth(0.8f).height(14.dp))
                    ShimmeringBox(modifier = Modifier.fillMaxWidth(0.95f).height(12.dp))
                }
            }
        }
    }
}
