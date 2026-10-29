package com.example.cam2vault.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Cam2VaultColors = darkColorScheme(
    primary = Color(0xFF2457C5),
    onPrimary = Color.White,
    background = Color(0xFF101318),
    onBackground = Color.White,
    surface = Color(0xFF101318),
    onSurface = Color.White
)

@Composable
fun Cam2VaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Cam2VaultColors,
        content = content
    )
}
