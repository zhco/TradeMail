package com.trademail.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.trademail.app.ui.navigation.NavGraph
import com.trademail.app.ui.theme.TradeMailTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TradeMailTheme {
                val navController = rememberNavController()
                NavGraph(navController = navController)
            }
        }
    }
}
