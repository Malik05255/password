package com.hai.wifiguard.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AppColors = lightColorScheme(
    primary = Color(0xFF175C64),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7EEF0),
    onPrimaryContainer = Color(0xFF0D3E44),
    secondary = Color(0xFF7B5E3B),
    secondaryContainer = Color(0xFFF3E6D3),
    background = Color(0xFFF7F6F2),
    onBackground = Color(0xFF1D2425),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1D2425),
    surfaceVariant = Color(0xFFECEDEA),
    onSurfaceVariant = Color(0xFF555D5E),
    outline = Color(0xFFB8C0C1),
    error = Color(0xFFB3261E),
)

@Composable
fun HaiWifiGuardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColors,
        content = content,
    )
}
