package com.atomosremote.nativeapp.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val AtomosCyan = Color(0xFF19E7D3)
val AtomosBg = Color(0xFF080B0D)
val AtomosSurface = Color(0xFF11171A)
val AtomosSurface2 = Color(0xFF182125)
val AtomosRed = Color(0xFFFF3B3B)

private val DarkScheme = darkColorScheme(
    primary = AtomosCyan,
    background = AtomosBg,
    surface = AtomosSurface,
    surfaceVariant = AtomosSurface2,
    error = AtomosRed,
    onPrimary = Color.Black,
    onBackground = Color(0xFFF4F8F8),
    onSurface = Color(0xFFF4F8F8)
)

@Composable
fun AtomosTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, typography = Typography(), content = content)
}
