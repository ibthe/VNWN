package com.example.voicenotes.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Всё, на что можно нажать: видимая кнопка + при нажатии «вдавливается» (уменьшается) и даёт волну.
 * Если задан base — рисуется объёмная плашка этого цвета.
 */
@Composable
fun Modifier.pressable(
    shape: Shape,
    base: Color? = null,
    elevation: Dp = 6.dp,
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val k by animateFloatAsState(
        if (pressed) 0.93f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "press"
    )
    return this
        .graphicsLayer { scaleX = k; scaleY = k }
        .let { if (base != null) it.softCard(base, shape, if (pressed) elevation / 3 else elevation) else it }
        .clip(shape)
        .clickable(interactionSource = src, indication = LocalIndication.current, enabled = enabled, onClick = onClick)
}

/** Единый набор линейных иконок: сетка 24, линия 2, скруглённые концы. */
enum class Ico { PLUS, MENU, BACK, CLOSE, MIC, TEXT, MARKER, PEN, PHOTO, SHARE, TRASH, COLLAPSE, RESIZE, PIN, FOLDER }

@Composable
fun AppIcon(kind: Ico, tint: Color, dim: Dp = 22.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(dim)) {
        val s = this.size.width / 24f
        val sw = 2f * s
        val st = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun o(x: Float, y: Float) = Offset(x * s, y * s)
        fun line(a: Float, b: Float, c: Float, d: Float) =
            drawLine(tint, o(a, b), o(c, d), strokeWidth = sw, cap = StrokeCap.Round)
        fun poly(vararg v: Float, close: Boolean = false) {
            val p = Path()
            p.moveTo(v[0] * s, v[1] * s)
            for (i in 1 until v.size / 2) p.lineTo(v[2 * i] * s, v[2 * i + 1] * s)
            if (close) p.close()
            drawPath(p, tint, style = st)
        }
        when (kind) {
            Ico.PLUS -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
            Ico.MENU -> { line(5f, 7f, 19f, 7f); line(5f, 12f, 19f, 12f); line(5f, 17f, 19f, 17f) }
            Ico.BACK -> poly(15f, 5f, 8f, 12f, 15f, 19f)
            Ico.CLOSE -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            Ico.MIC -> {
                drawRoundRect(tint, o(9f, 3f), Size(6f * s, 11f * s), CornerRadius(3f * s), style = st)
                drawArc(tint, 0f, 180f, false, o(5f, 6f), Size(14f * s, 14f * s), style = st)
                line(12f, 20f, 12f, 22f); line(8f, 22f, 16f, 22f)
            }
            Ico.TEXT -> { line(6f, 6f, 18f, 6f); line(12f, 6f, 12f, 19f); line(9f, 19f, 15f, 19f) }
            Ico.MARKER -> {
                poly(15f, 4f, 20f, 9f, 11f, 18f, 6f, 18f, 6f, 13f, close = true)
                line(4f, 21f, 20f, 21f)
            }
            Ico.PEN -> {
                poly(4f, 20f, 5f, 15f, 16f, 4f, 20f, 8f, 9f, 19f, close = true)
                line(13f, 7f, 17f, 11f)
            }
            Ico.PHOTO -> {
                drawRoundRect(tint, o(3f, 5f), Size(18f * s, 14f * s), CornerRadius(3f * s), style = st)
                drawCircle(tint, 1.8f * s, o(9f, 10f), style = st)
                poly(4f, 18f, 10f, 13f, 14f, 17f, 17f, 14f, 20f, 17f)
            }
            Ico.SHARE -> { line(12f, 15f, 12f, 4f); poly(8f, 8f, 12f, 4f, 16f, 8f); poly(6f, 12f, 6f, 20f, 18f, 20f, 18f, 12f) }
            Ico.COLLAPSE -> {
                poly(9f, 4f, 9f, 9f, 4f, 9f); poly(15f, 4f, 15f, 9f, 20f, 9f)
                poly(9f, 20f, 9f, 15f, 4f, 15f); poly(15f, 20f, 15f, 15f, 20f, 15f)
            }
            Ico.RESIZE -> { line(8f, 16f, 16f, 8f); poly(11f, 8f, 16f, 8f, 16f, 13f); poly(13f, 16f, 8f, 16f, 8f, 11f) }
            Ico.FOLDER -> poly(3f, 6f, 9f, 6f, 11f, 8f, 21f, 8f, 21f, 19f, 3f, 19f, close = true)
            Ico.PIN -> {
                poly(8f, 4f, 16f, 4f, 15f, 10f, 18f, 14f, 6f, 14f, 9f, 10f, close = true)
                line(12f, 14f, 12f, 21f)
            }
            Ico.TRASH -> { line(4f, 7f, 20f, 7f); poly(6f, 7f, 7f, 20f, 17f, 20f, 18f, 7f); poly(9f, 7f, 9f, 4f, 15f, 4f, 15f, 7f) }
        }
    }
}

@Composable
fun IconBtn(kind: Ico, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier.size(44.dp).pressable(CircleShape, cs.surfaceContainerHigh, 6.dp, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { AppIcon(kind, cs.onSurface) }
}

@Composable
fun PillButton(
    label: String,
    modifier: Modifier = Modifier,
    icon: Ico? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val bg = if (danger) DangerRed else if (primary) cs.primary else cs.secondaryContainer
    val fg = if (danger) OnDangerRed else if (primary) cs.onPrimary else cs.onSecondaryContainer
    Row(
        modifier.alpha(if (enabled) 1f else 0.45f)
            .pressable(RoundedCornerShape(50), bg, 4.dp, enabled, onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (icon != null) AppIcon(icon, fg, 18.dp)
        Text(label, color = fg, style = MaterialTheme.typography.labelLarge)
    }
}

/** Чип без скрытых полей под зону нажатия: высота ровно 36 dp, поэтому отступы вокруг предсказуемы. */
@Composable
fun AppChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit, enabled: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    val bg = if (selected) cs.primary else cs.surfaceContainerHighest
    val fg = if (selected) cs.onPrimary else cs.onSurface
    Box(
        Modifier.alpha(if (enabled) 1f else 0.45f)
            .height(36.dp)
            .pressable(RoundedCornerShape(50), bg, 3.dp, enabled, onClick)
            .then(if (selected) Modifier else Modifier.border(1.dp, cs.outlineVariant, RoundedCornerShape(50)))
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(LocalContentColor provides fg) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge) { label() }
        }
    }
}

/**
 * Раздел панели: подпись, под ней содержимое. Расстояние подпись → содержимое одинаково везде (Gap.S),
 * поэтому «Язык речи», «Перевод», «Шрифт» и остальные выглядят одинаково.
 */
@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Gap.S)) {
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
        content()
    }
}

/** Пояснение под строкой выбора. */
@Composable
fun Hint(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, color = color, style = MaterialTheme.typography.bodySmall)
}

/** Ползунок высотой 32 dp без встроенных полей: подпись над ним стоит так же, как над чипами. */
@Composable
fun AppSlider(value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val cur by rememberUpdatedState(onChange)
    val r by rememberUpdatedState(range)
    val track = cs.outlineVariant
    val active = cs.primary
    val frac = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    Canvas(
        Modifier.fillMaxWidth().height(32.dp).pointerInput(Unit) {
            val pad = 14.dp.toPx()
            fun at(x: Float): Float {
                val f = ((x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f)
                return r.start + f * (r.endInclusive - r.start)
            }
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                cur(at(down.position.x))
                do {
                    val ev = awaitPointerEvent()
                    ev.changes.forEach { if (it.positionChanged()) { it.consume(); cur(at(it.position.x)) } }
                } while (ev.changes.any { it.pressed })
            }
        }
    ) {
        val pad = 14.dp.toPx()
        val cy = size.height / 2
        val x = pad + frac * (size.width - 2 * pad)
        drawLine(track, Offset(pad, cy), Offset(size.width - pad, cy), 5.dp.toPx(), StrokeCap.Round)
        drawLine(active, Offset(pad, cy), Offset(x, cy), 5.dp.toPx(), StrokeCap.Round)
        drawCircle(Color.Black.copy(alpha = 0.18f), 13.dp.toPx(), Offset(x, cy + 1.dp.toPx()))
        drawCircle(Color.White, 13.dp.toPx(), Offset(x, cy))
        drawCircle(active, 9.dp.toPx(), Offset(x, cy))
    }
}

/** Пункт выпадающего меню: отдельная заметная плашка, а не голый текст. */
@Composable
fun MenuRow(text: String, accent: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg = if (danger) cs.error else if (accent) cs.primaryContainer else cs.surfaceContainerHighest
    val fg = if (danger) cs.onError else if (accent) cs.onPrimaryContainer else cs.onSurface
    Box(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .pressable(RoundedCornerShape(14.dp), bg, 2.dp, onClick = onClick)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) { Text(text, color = fg, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium) }
}

/** Выбор оформления: светлое, тёмное, яркое. */
@Composable
fun ThemeDialog(current: ThemeMode, onPick: (ThemeMode) -> Unit, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Оформление") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Gap.S)) {
                ThemeMode.entries.forEach { m ->
                    val sel = m == current
                    Row(
                        Modifier.fillMaxWidth()
                            .pressable(
                                RoundedCornerShape(18.dp),
                                if (sel) cs.primaryContainer else cs.surfaceContainerHighest, 2.dp
                            ) { onPick(m) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Gap.M)
                    ) {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape)
                                .background(Brush.linearGradient(m.preview))
                                .border(1.dp, cs.outline, CircleShape),
                            contentAlignment = Alignment.Center
                        ) { Box(Modifier.size(16.dp).clip(CircleShape).background(m.accent)) }
                        Text(
                            m.label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                            color = if (sel) cs.onPrimaryContainer else cs.onSurface
                        )
                        if (sel) Text("✓", color = cs.primary, fontSize = 20.sp)
                    }
                }
            }
        },
        confirmButton = { PillButton("Готово", primary = true, onClick = onClose) }
    )
}

/** Цвета названия: те же, что у иконки приложения (янтарь → оранжевый → красный). */
private val WolfGold = Color(0xFFFFD27C)
private val WolfOrange = Color(0xFFFF8A3D)
private val WolfRed = Color(0xFFE5484D)

/** Название приложения: заглавными, с разрядкой; «WOLF» в градиенте иконки. */
@Composable
fun AppTitle() {
    val cs = MaterialTheme.colorScheme
    Column {
        Text(
            "VOICE NOTES", fontSize = 11.sp, letterSpacing = 5.sp,
            fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant
        )
        Text(
            "WOLF",
            style = TextStyle(
                brush = Brush.linearGradient(listOf(WolfGold, WolfOrange, WolfRed)),
                fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 9.sp
            )
        )
    }
}

/** Круглая кнопка-переключатель (например, «закрепить»): включённая — цветная. */
@Composable
fun ToggleIconBtn(kind: Ico, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier.size(44.dp).pressable(CircleShape, if (on) cs.primary else cs.surfaceContainerHigh, 6.dp, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { AppIcon(kind, if (on) cs.onPrimary else cs.onSurface) }
}

/** Красный кнопок удаления: один и тот же во всех темах (как в светлой). */
val DangerRed = Color(0xFFB3261E)
val OnDangerRed = Color(0xFFFFFFFF)

/** Экстренная кнопка: красная, всегда на виду. */
@Composable
fun DangerIconBtn(kind: Ico, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier.size(44.dp).pressable(CircleShape, DangerRed, 6.dp, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { AppIcon(kind, OnDangerRed) }
}

/** Подтверждение удаления всего: действие необратимо. */
@Composable
fun DeleteAllDialog(count: Int, onConfirm: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Удалить всё?") },
        text = {
            Text(
                "Все заметки ($count), включая закреплённые, а также рисунки и фото будут удалены " +
                    "без возможности восстановления."
            )
        },
        confirmButton = { PillButton("Удалить всё", icon = Ico.TRASH, danger = true, onClick = onConfirm) },
        dismissButton = { PillButton("Отмена", onClick = onClose) }
    )
}
