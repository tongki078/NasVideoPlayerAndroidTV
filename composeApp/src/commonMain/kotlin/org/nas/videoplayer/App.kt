package org.nas.videoplayer

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun App() {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFE50914),
            background = Color.Black,
            surface = Color(0xFF121212),
            onBackground = Color.White,
            onSurface = Color.White
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Scaffold(
                bottomBar = { NetflixBottomNavigation() }
            ) { paddingValues ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    item { HeroSection() }
                    item { MovieRow("지금 뜨는 콘텐츠") }
                    item { MovieRow("시청 중인 콘텐츠") }
                    item { MovieRow("새로 올라온 콘텐츠") }
                    item { MovieRow("액션 & 어드벤처") }
                    item { Spacer(modifier = Modifier.height(20.dp)) }
                }
            }
        }
    }
}

@Composable
fun HeroSection() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(500.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.DarkGray)
        ) {
            Text(
                "Featured Content",
                modifier = Modifier.align(Alignment.Center),
                color = Color.Gray
            )
        }
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black),
                        startY = 300f
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "오늘의 추천 영화",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Text("▶ 재생", color = Color.Black, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Gray.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Text("ⓘ 정보", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun MovieRow(title: String) {
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(10) {
                MoviePosterCard()
            }
        }
    }
}

@Composable
fun MoviePosterCard() {
    Card(
        modifier = Modifier
            .width(120.dp)
            .height(180.dp)
            .clickable { },
        shape = RoundedCornerShape(4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF2C2C2C))
        )
    }
}

@Composable
fun NetflixBottomNavigation() {
    NavigationBar(
        containerColor = Color.Black,
        contentColor = Color.White
    ) {
        NavigationBarItem(
            selected = true,
            onClick = {},
            icon = { Text("🏠", color = Color.White) }, // 아이콘 대신 이모지 사용
            label = { Text("홈") }
        )
        NavigationBarItem(
            selected = false,
            onClick = {},
            icon = { Text("🔍", color = Color.White) }, // 아이콘 대신 이모지 사용
            label = { Text("검색") }
        )
    }
}
