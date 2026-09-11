package com.ambercabinet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.ambercabinet.ui.nav.AmberNavGraph
import com.ambercabinet.ui.theme.AmberTheme
import com.ambercabinet.ui.theme.Bg
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AmberTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
                    AmberNavGraph(rememberNavController())
                }
            }
        }
    }
}
