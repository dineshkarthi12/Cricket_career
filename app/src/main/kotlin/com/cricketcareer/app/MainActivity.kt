package com.cricketcareer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Phase 0 placeholder. The real UI arrives in Phase 6; until then this exists
 * so the module graph is complete and the app is launchable.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface { Text("Cricket Career") }
            }
        }
    }
}
