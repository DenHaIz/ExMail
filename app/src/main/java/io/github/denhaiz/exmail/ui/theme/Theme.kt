package io.github.denhaiz.exmail.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Brand = Color(0xFF0F6CBD)

private val Light = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E8FA),
    onPrimaryContainer = Color(0xFF002A4D),
    secondary = Color(0xFF4F6070),
    secondaryContainer = Color(0xFFE3EAF2),
    onSecondaryContainer = Color(0xFF0B1D2C),
    tertiary = Color(0xFF00897B),
    error = Color(0xFFC62828),
    errorContainer = Color(0xFFFDE7E7),
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF1F4F8),
    surfaceContainer = Color(0xFFF4F6F9),
    surfaceContainerHigh = Color(0xFFEEF1F5),
    surfaceContainerLow = Color(0xFFF8F9FB),
    outline = Color(0xFF7A8794),
    outlineVariant = Color(0xFFD9DFE6),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8CC2F5),
    onPrimary = Color(0xFF00325A),
    primaryContainer = Color(0xFF0B4F8A),
    onPrimaryContainer = Color(0xFFD6E8FA),
    secondary = Color(0xFFB8C8D8),
    secondaryContainer = Color(0xFF34495B),
    tertiary = Color(0xFF6FD6C8),
    error = Color(0xFFFF8A80),
    background = Color(0xFF111418),
    surface = Color(0xFF111418),
    surfaceVariant = Color(0xFF242A31),
    surfaceContainer = Color(0xFF1A1F25),
    surfaceContainerHigh = Color(0xFF22282F),
    surfaceContainerLow = Color(0xFF161A1F),
    outlineVariant = Color(0xFF39424C),
)

@Composable
fun ExMailTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}

private val avatarPalette = listOf(
    0xFF1E88E5, 0xFF43A047, 0xFFE53935, 0xFF8E24AA, 0xFFFB8C00,
    0xFF00897B, 0xFF3949AB, 0xFFD81B60, 0xFF6D4C41, 0xFF546E7A,
).map { Color(it) }

/** Цвет аватара по имени — стабильный и различимый. */
fun avatarColor(key: String): Color = avatarPalette[(key.lowercase().hashCode() and 0x7fffffff) % avatarPalette.size]
