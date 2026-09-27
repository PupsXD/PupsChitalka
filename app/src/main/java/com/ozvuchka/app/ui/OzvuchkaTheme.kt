package com.ozvuchka.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF51449A),
    onPrimary = Color.White,
    secondary = Color(0xFF7461AB),
    tertiary = Color(0xFFB66C55),
    background = Color(0xFFF8F7F5),
    onBackground = Color(0xFF282538),
    surface = Color.White,
    onSurface = Color(0xFF282538),
    surfaceVariant = Color(0xFFEDEAF1),
    onSurfaceVariant = Color(0xFF615D6C),
    outline = Color(0xFFDAD5E0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC9BEFF),
    onPrimary = Color(0xFF302265),
    secondary = Color(0xFFD2C1F2),
    tertiary = Color(0xFFF3B9A5),
    background = Color(0xFF171620),
    onBackground = Color(0xFFF4F0F7),
    surface = Color(0xFF24222E),
    onSurface = Color(0xFFF4F0F7),
    surfaceVariant = Color(0xFF35313F),
    onSurfaceVariant = Color(0xFFC9C2D0),
    outline = Color(0xFF5B5364),
)

@Composable
fun OzvuchkaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
