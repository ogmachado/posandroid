package com.idos.pos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Bootstrap-only entry point (Phase 0). This is a placeholder screen with no
 * navigation or feature wiring — [core.di.AppContainer], real navigation, and
 * the first feature screen land in later phases (see design.md / tasks.md).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        BootstrapPlaceholder()
                    }
                }
            }
        }
    }
}

@Composable
private fun BootstrapPlaceholder() {
    Text(text = "IDOS POS — project bootstrap")
}
