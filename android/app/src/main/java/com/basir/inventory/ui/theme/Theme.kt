package com.basir.inventory.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Brand = Color(0xFF0B6E99)
val BrandDark = Color(0xFF08506F)
val BrandLight = Color(0xFF2BA3C4)
val OutOfStock = Color(0xFFC62828)
val LowStock = Color(0xFFE67E00)
val Good = Color(0xFF2E7D32)

private val Colors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3EEF8),
    onPrimaryContainer = BrandDark,
    secondary = BrandLight,
    secondaryContainer = Color(0xFFE1F4FA),
    onSecondaryContainer = BrandDark,
    tertiary = LowStock,
    error = OutOfStock,
    errorContainer = Color(0xFFFDE7E7),
    background = Color(0xFFF7FAFC),
    surface = Color.White,
    surfaceVariant = Color(0xFFEFF3F6),
)

@Composable
fun AjwaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
