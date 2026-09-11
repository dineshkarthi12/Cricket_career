package com.cricketcareer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.cricketcareer.app.ui.theme.CricketDynastyTheme

/**
 * The host activity.
 *
 * Navigation and the screen graph arrive with the repository wiring; for now
 * this establishes the theme, which is the thing every screen depends on.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CricketDynastyTheme {
                Surface { Text("Cricket Dynasty") }
            }
        }
    }
}
