package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80,
    background = DeepNavyText,
    surface = SlateDark,
    onPrimary = OnPrimaryWhite,
    onBackground = OnPrimaryWhite,
    onSurface = OnPrimaryWhite
)

private val HighDensityColorScheme = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = OnPrimaryWhite,
    secondary = MidBlueText,
    onSecondary = OnPrimaryWhite,
    background = LightBackground,
    onBackground = SlateDark,
    surface = CardBackgroundWhite,
    onSurface = SlateDark,
    surfaceVariant = SlateIce,
    onSurfaceVariant = SlateMedium,
    outline = SlateBorder,
    primaryContainer = SoftBlueContainer,
    onPrimaryContainer = DeepNavyText,
    secondaryContainer = SoftBlueContainer,
    onSecondaryContainer = DeepNavyText
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Keep theme branding stable by default as requested by the custom layout
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> HighDensityColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
