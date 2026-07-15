package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

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

/**
 * App theme, ported to commonMain (design §1: "no dynamic color"). Dynamic color
 * (Material You) is dropped — it required android.os.Build + LocalContext. The
 * custom layout kept branding stable, so [dynamicColor] already defaulted to
 * false; removing it leaves Android rendering unchanged while making the theme
 * work on web.
 */
@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else HighDensityColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
