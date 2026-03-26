package com.streeter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.streeter.ui.navigation.StreeterNavGraph
import com.streeter.ui.theme.StreeterTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StreeterTheme {
                val navController = rememberNavController()
                StreeterNavGraph(navController = navController)
            }
        }
    }
}
