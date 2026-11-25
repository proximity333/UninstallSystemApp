package com.proximity333.uninstallsystemapp.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val FallbackLight = lightColorScheme(
    primary = Color(0xFF1F6FEB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E4FF),
    onPrimaryContainer = Color(0xFF001B3D),
    secondary = Color(0xFF4B6359),
    tertiary = Color(0xFF7A5900),
    error = Color(0xFFBA1A1A),
    surface = Color(0xFFF8FAFD),
    surfaceVariant = Color(0xFFE3E8F0),
)

private val FallbackDark = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF00325B),
    primaryContainer = Color(0xFF004787),
    onPrimaryContainer = Color(0xFFD8E4FF),
    secondary = Color(0xFFB3CCC0),
    tertiary = Color(0xFFF5BF48),
    error = Color(0xFFFFB4AB),
    surface = Color(0xFF111318),
    surfaceVariant = Color(0xFF44474E),
)

/** Uses the device wallpaper palette on Android 12+ and a restrained blue palette elsewhere. */
@Composable
fun UninstallerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> FallbackDark
        else -> FallbackLight
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}