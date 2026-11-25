package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.data.repository.BrandState

@Immutable
data class PrajusBrandColors(
    val navy: Color,
    val blue: Color,
    val gold: Color,
    val amberSurface: Color,
    val amberInk: Color,
    val pillBlueSurface: Color,
    val pillBlueInk: Color,
    val divider: Color,
    val mutedText: Color
)

val LocalPrajusColors = staticCompositionLocalOf {
    PrajusBrandColors(
        navy = Color(0xFF1D2F4D),
        blue = Color(0xFF2F5C8E),
        gold = Color(0xFFCFA75B),
        amberSurface = Color(0xFFFBF0D6),
        amberInk = Color(0xFF7A5200),
        pillBlueSurface = Color(0xFFE6EDF5),
        pillBlueInk = Color(0xFF2F5C8E),
        divider = Color(0xFFDFE3EA),
        mutedText = Color(0xFF5D6778)
    )
}

fun parseHexColor(hex: String, fallback: Color): Color {
    return runCatching {
        val clean = hex.trim().removePrefix("#")
        Color(("FF$clean").toLong(16))
    }.getOrElse { fallback }
}

private val PrajusShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp)
)

@Composable
fun PrajusTheme(
    brand: BrandState = BrandState(),
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val navy = parseHexColor(brand.azulEscuro, Color(0xFF1D2F4D))
    val blue = parseHexColor(brand.azul, Color(0xFF2F5C8E))
    val gold = parseHexColor(brand.dourado, Color(0xFFCFA75B))

    val colorScheme = if (darkTheme) {
        val surface = Color(0xFF172033)
        darkColorScheme(
            primary = lerp(blue, Color.White, 0.25f),
            onPrimary = Color(0xFF0A1424),
            primaryContainer = navy,
            onPrimaryContainer = Color(0xFFE9EDF5),
            secondary = gold,
            onSecondary = Color(0xFF1D1404),
            secondaryContainer = Color(0xFF3A2D10),
            onSecondaryContainer = Color(0xFFF0C978),
            background = Color(0xFF0F1624),
            onBackground = Color(0xFFE9EDF5),
            surface = surface,
            onSurface = Color(0xFFE9EDF5),
            surfaceVariant = Color(0xFF1E2940),
            onSurfaceVariant = Color(0xFF9AA6BA),
            outline = Color(0xFF27324A),
            outlineVariant = Color(0xFF27324A),
            error = Color(0xFFFF8F86),
            onError = Color(0xFF3E0B07)
        )
    } else {
        val surface = Color(0xFFFFFFFF)
        lightColorScheme(
            primary = blue,
            onPrimary = Color.White,
            primaryContainer = navy,
            onPrimaryContainer = Color(0xFFE9EDF5),
            secondary = gold,
            onSecondary = Color(0xFF1D1404),
            secondaryContainer = Color(0xFFFBF0D6),
            onSecondaryContainer = Color(0xFF7A5200),
            background = Color(0xFFF4F5F7),
            onBackground = Color(0xFF1A2233),
            surface = surface,
            onSurface = Color(0xFF1A2233),
            surfaceVariant = Color(0xFFEDF0F5),
            onSurfaceVariant = Color(0xFF5D6778),
            outline = Color(0xFFDFE3EA),
            outlineVariant = Color(0xFFDFE3EA),
            error = Color(0xFFB3261E),
            onError = Color.White
        )
    }

    val extended = if (darkTheme) {
        PrajusBrandColors(
            navy = navy,
            blue = blue,
            gold = gold,
            amberSurface = Color(0xFF3A2D10),
            amberInk = Color(0xFFF0C978),
            pillBlueSurface = lerp(Color(0xFF172033), blue, 0.25f),
            pillBlueInk = lerp(blue, Color.White, 0.45f),
            divider = Color(0xFF27324A),
            mutedText = Color(0xFF9AA6BA)
        )
    } else {
        PrajusBrandColors(
            navy = navy,
            blue = blue,
            gold = gold,
            amberSurface = Color(0xFFFBF0D6),
            amberInk = Color(0xFF7A5200),
            pillBlueSurface = lerp(Color.White, blue, 0.12f),
            pillBlueInk = blue,
            divider = Color(0xFFDFE3EA),
            mutedText = Color(0xFF5D6778)
        )
    }

    CompositionLocalProvider(LocalPrajusColors provides extended) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = PrajusTypography,
            shapes = PrajusShapes,
            content = content
        )
    }
}
