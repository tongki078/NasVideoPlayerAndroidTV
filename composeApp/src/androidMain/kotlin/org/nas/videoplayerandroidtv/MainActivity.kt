package org.nas.videoplayerandroidtv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.nas.videoplayerandroidtv.data.DatabaseDriverFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val driver = DatabaseDriverFactory(applicationContext).createDriver()
        setContent {
            App(driver)
        }
    }
}
