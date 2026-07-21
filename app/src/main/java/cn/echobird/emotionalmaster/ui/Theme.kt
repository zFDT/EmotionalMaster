package cn.echobird.emotionalmaster.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFFA5482F),
    onPrimary = Color.White,
    secondary = Color(0xFF59654B),
    onSecondary = Color.White,
    background = Color(0xFFF5F1E8),
    onBackground = Color(0xFF241C18),
    surface = Color(0xFFFCFAF5),
    onSurface = Color(0xFF241C18),
    surfaceContainerLowest = Color(0xFFFFFDF8),
    surfaceContainerLow = Color(0xFFF8F4EC),
    surfaceContainer = Color(0xFFF2ECE2),
    surfaceContainerHigh = Color(0xFFECE5D8),
    surfaceContainerHighest = Color(0xFFE5DCCF),
    surfaceVariant = Color(0xFFECE5D8),
    onSurfaceVariant = Color(0xFF625A52),
    outline = Color(0xFFB8AA98),
    error = Color(0xFF9F302B),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE78B70),
    onPrimary = Color(0xFF2A120C),
    secondary = Color(0xFFB8C7A5),
    onSecondary = Color(0xFF17210F),
    background = Color(0xFF171512),
    onBackground = Color(0xFFF4EEE5),
    surface = Color(0xFF211E1A),
    onSurface = Color(0xFFF4EEE5),
    surfaceContainerLowest = Color(0xFF12100E),
    surfaceContainerLow = Color(0xFF1C1916),
    surfaceContainer = Color(0xFF25211D),
    surfaceContainerHigh = Color(0xFF2B2722),
    surfaceContainerHighest = Color(0xFF37312B),
    surfaceVariant = Color(0xFF2B2722),
    onSurfaceVariant = Color(0xFFCFC4B8),
    outline = Color(0xFF71675D),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val AppTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 30.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        lineHeight = 25.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 22.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(28.dp),
)

@Composable
fun EmotionalMasterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
