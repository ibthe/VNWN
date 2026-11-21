package com.example.voicenotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Палитра по правилу 60-30-10: нейтральный фиолетово-серый фон (60%), поверхности того же оттенка (30%)
// и один акцент — индиго (10%). Без чистого чёрного/белого и без кислотных цветов: глазам комфортно.
private val DarkColors = darkColorScheme(
    primary = Color(0xFF5446C8), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE3DFFB), onPrimaryContainer = Color(0xFF1F1860),
    secondary = Color(0xFFB9B4D6), onSecondary = Color(0xFF26233A),
    secondaryContainer = Color(0xFF34314A), onSecondaryContainer = Color(0xFFE3E0F5),
    tertiary = Color(0xFFD9C2A0), onTertiary = Color(0xFF3A2A10),
    background = Color(0xFF13121A), onBackground = Color(0xFFE8E6F0),
    surface = Color(0xFF13121A), onSurface = Color(0xFFE8E6F0),
    surfaceVariant = Color(0xFF2A2837), onSurfaceVariant = Color(0xFFB8B5C9),
    surfaceContainerLow = Color(0xFF1A1823), surfaceContainer = Color(0xFF201E2B),
    surfaceContainerHigh = Color(0xFF282633), surfaceContainerHighest = Color(0xFF302E3C),
    outline = Color(0xFF8D8AA2), outlineVariant = Color(0xFF3F3C50),
    error = Color(0xFFFFB4AB), onError = Color(0xFF561E19)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF5446C8), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE3DFFB), onPrimaryContainer = Color(0xFF1F1860),
    secondary = Color(0xFF5F5B7A), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE6E3F2), onSecondaryContainer = Color(0xFF1F1D30),
    tertiary = Color(0xFF7A5C2E), onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF5F4F9), onBackground = Color(0xFF1D1B29),
    surface = Color(0xFFF5F4F9), onSurface = Color(0xFF1D1B29),
    surfaceVariant = Color(0xFFE5E3EF), onSurfaceVariant = Color(0xFF4B4860),
    surfaceContainerLow = Color(0xFFF0EFF6), surfaceContainer = Color(0xFFEAE8F2),
    surfaceContainerHigh = Color(0xFFE3E1EE), surfaceContainerHighest = Color(0xFFDCDAE8),
    outline = Color(0xFF7A7790), outlineVariant = Color(0xFFCAC8D8)
)


// «Женское»: нежные, но яркие розово-сиреневые тона.
private val FeminineColors = lightColorScheme(
    primary = Color(0xFFD0357A), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD3E6), onPrimaryContainer = Color(0xFF5B0A33),
    secondary = Color(0xFF8E4FD6), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEBD5FF), onSecondaryContainer = Color(0xFF33105C),
    tertiary = Color(0xFFFF7E5F), onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFFE0EE), onBackground = Color(0xFF3A0F2B),
    surface = Color(0xFFFFE0EE), onSurface = Color(0xFF3A0F2B),
    surfaceVariant = Color(0xFFFFD0E6), onSurfaceVariant = Color(0xFF6B2A55),
    surfaceContainerLow = Color(0xFFFFF1F8), surfaceContainer = Color(0xFFFFE3F0),
    surfaceContainerHigh = Color(0xFFFFD6EA), surfaceContainerHighest = Color(0xFFFFC8E3),
    outline = Color(0xFF9C4E83), outlineVariant = Color(0xFFF0B3D5),
    error = Color(0xFFC4003C), onError = Color(0xFFFFFFFF)
)

// «Мужское»: графит и сталь, акцент — раскалённый оранжевый. Жёстко и собранно.
private val MasculineColors = darkColorScheme(
    primary = Color(0xFFFF7A1A), onPrimary = Color(0xFF1B0F02),
    primaryContainer = Color(0xFF4A2A0E), onPrimaryContainer = Color(0xFFFFDCBF),
    secondary = Color(0xFF9FB3C8), onSecondary = Color(0xFF12202E),
    secondaryContainer = Color(0xFF2C3A48), onSecondaryContainer = Color(0xFFD6E3F0),
    tertiary = Color(0xFFC9A227), onTertiary = Color(0xFF2A2000),
    background = Color(0xFF0E1013), onBackground = Color(0xFFE6E8EB),
    surface = Color(0xFF0E1013), onSurface = Color(0xFFE6E8EB),
    surfaceVariant = Color(0xFF23282E), onSurfaceVariant = Color(0xFFB4BBC3),
    surfaceContainerLow = Color(0xFF151A1F), surfaceContainer = Color(0xFF1B2127),
    surfaceContainerHigh = Color(0xFF242B33), surfaceContainerHighest = Color(0xFF2D353E),
    outline = Color(0xFF7C8791), outlineVariant = Color(0xFF3A434C),
    error = Color(0xFFFF6B5E), onError = Color(0xFF3B0905)
)

/** Четыре оформления: светлое, тёмное, женское и мужское. */
enum class ThemeMode(val key: String, val label: String, val preview: List<Color>, val accent: Color, val lightBars: Boolean) {
    LIGHT("light", "Светлое", listOf(Color(0xFFF5F4F9), Color(0xFFE9E7F3)), Color(0xFF5446C8), true),
    DARK("dark", "Тёмное", listOf(Color(0xFF13121A), Color(0xFF1B1928)), Color(0xFF5446C8), false),
    FEMININE("feminine", "Женское", listOf(Color(0xFFFFE3F0), Color(0xFFF2D0FF)), Color(0xFFD0357A), true),
    MASCULINE("masculine", "Мужское", listOf(Color(0xFF0E1013), Color(0xFF1F252B)), Color(0xFFFF7A1A), false);

    companion object {
        /** Сохранённый выбор; если его ещё нет (или тема убрана) — по системной теме телефона. */
        fun of(key: String?, systemDark: Boolean): ThemeMode =
            entries.firstOrNull { it.key == key } ?: if (systemDark) DARK else LIGHT
    }
}

val LocalThemeMode = compositionLocalOf { ThemeMode.LIGHT }

// «Бумага»: приглушённые светлые тона одной насыщенности + тёмный текст (контраст > 12:1).
val PaperInk = Color(0xFF1F1B2E)
val PaperMuted = Color(0xFF5A5670)
/** Акцент закреплённых заметок на листе. */
val PaperPin = Color(0xFF5B3FD9)
val PAPERS = listOf(
    Color(0xFFFBF3DC), // тёплый крем
    Color(0xFFE3F1E4), // шалфей
    Color(0xFFE0EBF8), // дымчато-голубой
    Color(0xFFEBE4F8), // сирень
    Color(0xFFF8E4E4)  // пудровый
)
val FEMININE_PAPERS = listOf(
    Color(0xFFFFD1E3), // розовый
    Color(0xFFEBD2FF), // сирень
    Color(0xFFFFE0C7), // персик
    Color(0xFFCDEFE2), // мята
    Color(0xFFFFF1B0)  // сливочный
)
val MASCULINE_PAPERS = listOf(
    Color(0xFFDADFE5), // сталь
    Color(0xFFD3D9CF), // хаки
    Color(0xFFE5DCCB), // песок
    Color(0xFFCFD8E3), // грифельный
    Color(0xFFE2D3CB)  // глина
)

/** Цвета листов для текущего оформления. */
@Composable
fun papers(): List<Color> = when (LocalThemeMode.current) {
    ThemeMode.FEMININE -> FEMININE_PAPERS
    ThemeMode.MASCULINE -> MASCULINE_PAPERS
    else -> PAPERS
}

val HIGHLIGHTS = listOf(Color(0xFFFFE680), Color(0xFFA8E6B8), Color(0xFFA9D4FF), Color(0xFFFFB8D0))
val PEN_COLORS = listOf(PaperInk, Color(0xFF4B3FC4), Color(0xFFC73A52), Color(0xFF1F7A55))

/** Современный «мягкий объём»: цветная тень, лёгкий градиент и световая кромка. */
fun Modifier.softCard(
    base: Color,
    shape: Shape = RoundedCornerShape(24.dp),
    elevation: Dp = 10.dp
): Modifier {
    val dark = base.luminance() < 0.2f
    val top = lerp(base, Color.White, if (dark) 0.07f else 0.4f)
    val bottom = lerp(base, Color.Black, if (dark) 0.12f else 0.05f)
    val tint = Color(0xFF3B2A8F)
    return this
        .shadow(elevation, shape, ambientColor = tint.copy(alpha = 0.18f), spotColor = tint.copy(alpha = 0.38f))
        .background(Brush.verticalGradient(listOf(top, base, bottom)), shape)
        .border(
            1.dp,
            Brush.linearGradient(listOf(Color.White.copy(alpha = if (dark) 0.14f else 0.85f), Color.White.copy(alpha = 0f))),
            shape
        )
}

@Composable
fun AppTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val scheme = when (mode) {
        ThemeMode.DARK -> DarkColors
        ThemeMode.FEMININE -> FeminineColors
        ThemeMode.MASCULINE -> MasculineColors
        ThemeMode.LIGHT -> LightColors
    }
    val bg = mode.preview
    val view = LocalView.current
    SideEffect {
        val w = (view.context as? Activity)?.window ?: return@SideEffect
        w.statusBarColor = bg.first().toArgb()
        w.navigationBarColor = bg.last().toArgb()
        WindowCompat.getInsetsController(w, view).apply {
            isAppearanceLightStatusBars = mode.lightBars
            isAppearanceLightNavigationBars = mode.lightBars
        }
    }
    MaterialTheme(
        colorScheme = scheme,
        shapes = Shapes(
            small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(28.dp)
        )
    ) {
        // Базовый цвет текста: без него всё вне карточек рисовалось чёрным (на тёмном фоне не видно).
        CompositionLocalProvider(
            LocalContentColor provides scheme.onBackground,
            LocalThemeMode provides mode
        ) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(bg))) { content() }
        }
    }
}

/** Единая шкала отступов: всё в интерфейсе кратно 4 dp. */
object Gap {
    /** Между подписью и тем, что под ней; между строкой и её подсказкой. */
    val S = 8.dp
    /** Между крупными блоками внутри карточки и между карточками. */
    val M = 12.dp
    /** Между разделами панелей и экранов. */
    val L = 16.dp
}
