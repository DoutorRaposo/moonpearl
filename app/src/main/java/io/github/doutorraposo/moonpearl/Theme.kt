package io.github.doutorraposo.moonpearl

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val colors = darkColorScheme(
    primary = Color(0xFFE2C15A),
    onPrimary = Color(0xFF2A2100),
    primaryContainer = Color(0xFF4A3D0E),
    onPrimaryContainer = Color(0xFFFFE08A),
    secondary = Color(0xFF8FD18B),
    onSecondary = Color(0xFF0D3A12),
    secondaryContainer = Color(0xFF4A3D0E),
    onSecondaryContainer = Color(0xFFFFE08A),
    background = Color(0xFF0F1410),
    onBackground = Color(0xFFE3E7E0),
    surface = Color(0xFF0F1410),
    onSurface = Color(0xFFE3E7E0),
    surfaceContainer = Color(0xFF1A211B),
    surfaceContainerHigh = Color(0xFF232B24),
    onSurfaceVariant = Color(0xFFB9C2B6),
    outline = Color(0xFF6E786C),
    error = Color(0xFFFFB4AB),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = colors, content = content)
