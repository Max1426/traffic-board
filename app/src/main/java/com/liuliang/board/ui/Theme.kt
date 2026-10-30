package com.liuliang.board.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.*
import android.app.Activity
import android.os.Build
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.liuliang.board.model.ThemeMode
import com.liuliang.board.model.UiPreferences
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

val LocalDarkAppearance = staticCompositionLocalOf { false }
val Background: Color @Composable get() = MaterialTheme.colorScheme.surface
val Panel: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainerLow
val PanelRaised: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainerHighest
val Accent: Color @Composable get() = MaterialTheme.colorScheme.primary
val Ink: Color @Composable get() = MaterialTheme.colorScheme.onSurface
val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
val Mint: Color @Composable get() = if (LocalDarkAppearance.current) Color(0xFF8BD8BF) else Color(0xFF176B57)
val Amber: Color @Composable get() = if (LocalDarkAppearance.current) Color(0xFFFFD29A) else Color(0xFF815000)
val Danger: Color @Composable get() = MaterialTheme.colorScheme.error

@Composable fun BoardTheme(preferences: UiPreferences = UiPreferences(), content: @Composable () -> Unit) {
    val dark = when (preferences.themeMode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    val context = LocalContext.current
    val colors = when {
        preferences.dynamicColors && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFFAFC6FF), onPrimary = Color(0xFF002E69),
            primaryContainer = Color(0xFF23457F), onPrimaryContainer = Color(0xFFD8E2FF),
            secondary = Color(0xFFBFC6DC), secondaryContainer = Color(0xFF3E475C), onSecondaryContainer = Color(0xFFDCE2F9),
            tertiary = Color(0xFF8BD8BF), onTertiary = Color(0xFF00382A))
        else -> lightColorScheme(primary = Color(0xFF365D9C), onPrimary = Color.White,
            primaryContainer = Color(0xFFD8E2FF), onPrimaryContainer = Color(0xFF001A42),
            secondary = Color(0xFF565F74), secondaryContainer = Color(0xFFDCE2F9), onSecondaryContainer = Color(0xFF141C2C),
            tertiary = Color(0xFF176B57), onTertiary = Color.White)
    }
    val view = LocalView.current
    SideEffect {
        (context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val enabled by rememberUpdatedState(preferences.hapticsEnabled)
    val feedback = remember(view) { BoardFeedback({ enabled }, { view.performHapticFeedback(it) }) }
    CompositionLocalProvider(LocalDarkAppearance provides dark, LocalBoardFeedback provides feedback) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}

enum class Glyph { CHART, PLUS, REFRESH, SETTINGS, BACK, ARROW, CLOUD, INFO, EDIT, EYE, CLOCK }

@Composable fun BoardIcon(glyph: Glyph, modifier: Modifier = Modifier, tint: Color = Accent, description: String? = null) {
    if (glyph == Glyph.SETTINGS) {
        androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(com.liuliang.board.R.drawable.ic_settings), description, modifier.size(24.dp), tint)
        return
    }
    Canvas(modifier.size(24.dp).then(if (description == null) Modifier else Modifier.semantics { contentDescription = description })) {
        val scale = size.minDimension / 24f
        fun point(x: Float, y: Float) = Offset(x * scale, y * scale)
        fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(tint, point(x, y), point(x2, y2), 1.9f * scale, StrokeCap.Round)
        fun circle(x: Float, y: Float, radius: Float) = drawCircle(tint, radius * scale, point(x, y), style = Stroke(1.8f * scale))
        when (glyph) {
            Glyph.CHART -> { line(4f, 20f, 4f, 14f); line(12f, 20f, 12f, 9f); line(20f, 20f, 20f, 4f) }
            Glyph.PLUS -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
            Glyph.BACK -> { line(19f, 12f, 5f, 12f); line(5f, 12f, 11f, 6f); line(5f, 12f, 11f, 18f) }
            Glyph.ARROW -> { line(9f, 6f, 15f, 12f); line(15f, 12f, 9f, 18f) }
            Glyph.REFRESH -> {
                drawArc(tint, 35f, 285f, false, point(4f, 4f), Size(16 * scale, 16 * scale), style = Stroke(1.9f * scale, cap = StrokeCap.Round))
                line(20f, 4f, 20f, 10f); line(14f, 10f, 20f, 10f)
            }
            Glyph.SETTINGS -> Unit
            Glyph.CLOUD -> { val p = Path().apply { moveTo(6 * scale, 18 * scale); cubicTo(-1 * scale, 18 * scale, 1 * scale, 9 * scale, 7 * scale, 10 * scale); cubicTo(7 * scale, 2 * scale, 18 * scale, 2 * scale, 19 * scale, 11 * scale); cubicTo(25 * scale, 11 * scale, 24 * scale, 18 * scale, 19 * scale, 18 * scale); close() }; drawPath(p, tint, style = Stroke(1.8f * scale)) }
            Glyph.INFO -> { circle(12f, 12f, 9f); line(12f, 11f, 12f, 17f); drawCircle(tint, scale, point(12f, 7f)) }
            Glyph.EDIT -> { line(5f, 16f, 16f, 5f); line(8f, 19f, 19f, 8f); line(16f, 5f, 19f, 8f); line(5f, 16f, 4f, 20f); line(4f, 20f, 8f, 19f) }
            Glyph.EYE -> { val p = Path().apply { moveTo(2 * scale, 12 * scale); quadraticTo(12 * scale, -1 * scale, 22 * scale, 12 * scale); quadraticTo(12 * scale, 25 * scale, 2 * scale, 12 * scale) }; drawPath(p, tint, style = Stroke(1.7f * scale)); circle(12f, 12f, 3f) }
            Glyph.CLOCK -> { circle(12f, 12f, 9f); line(12f, 6f, 12f, 12f); line(12f, 12f, 16f, 14f) }
        }
    }
}
