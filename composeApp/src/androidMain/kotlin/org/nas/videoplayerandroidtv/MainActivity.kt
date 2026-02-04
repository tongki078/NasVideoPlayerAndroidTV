package org.nas.videoplayerandroidtv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import org.nas.videoplayerandroidtv.data.DatabaseDriverFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val driver = DatabaseDriverFactory(applicationContext).createDriver()
        setContent {
            App(driver)
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    val driver = DatabaseDriverFactory(androidx.compose.ui.platform.LocalContext.current).createDriver()
    App(driver)
}
