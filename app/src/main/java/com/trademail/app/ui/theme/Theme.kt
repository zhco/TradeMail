package com.trademail.app.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = Blue600,
    onPrimary = Color.White,
    primaryContainer = Blue50,
    secondary = Gray600,
    onSecondary = Color.White,
    background = Gray50,
    surface = Color.White,
    onBackground = Gray900,
    onSurface = Gray900,
    surfaceVariant = Gray100,
    outline = Gray200,
    error = Color(0xFFD93025)
)

@Composable
fun TradeMailTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography(),
        content = content
    )
}
