package com.yiyu.ledger.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Composable fun YiyuTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Forest, onPrimary = OnForest, primaryContainer = Sage, onPrimaryContainer = Forest,
        secondary = Muted, onSecondary = OnForest, secondaryContainer = Field, onSecondaryContainer = Ink,
        tertiary = IncomeColor, onTertiary = OnForest, tertiaryContainer = Field, onTertiaryContainer = Ink,
        background = Cream, onBackground = Ink, surface = Card, onSurface = Ink, surfaceTint = Forest,
        surfaceVariant = Field, onSurfaceVariant = Muted, surfaceBright = Card, surfaceDim = Field,
        surfaceContainerLowest = Card, surfaceContainerLow = Cream, surfaceContainer = Card,
        surfaceContainerHigh = Card, surfaceContainerHighest = Field,
        inverseSurface = Forest, inverseOnSurface = OnForest, inversePrimary = Sage,
        outline = Outline, outlineVariant = Outline, error = ErrorColor, onError = OnForest,
        errorContainer = Field, onErrorContainer = ErrorColor),
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 32.sp),
            headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 26.sp),
            titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 21.sp),
            titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp),
            bodyLarge = TextStyle(fontSize = 16.sp), bodyMedium = TextStyle(fontSize = 14.sp), labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp)
        ), content = content)
}
