package com.example.voicenotes.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.example.voicenotes.data.Note
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Размеры плиток на главном экране. Сетка — 12 колонок, поэтому в один ряд встаёт 4, 3, 2 или 1 плитка. */
enum class TileSize(val span: Int, val label: String, val perRow: Int) {
    ICON(3, "Значок", 4),
    SMALL(4, "Малая", 3),
    MEDIUM(6, "Средняя", 2),
    WIDE(12, "Широкая", 1);

    companion object {
        fun of(index: Int): TileSize = TileSize.entries.getOrElse(index) { WIDE }
    }
}

/** Высота плитки: во сколько раз она выше обычной. Высокая узкая плитка — «вертикальная». */
internal val TILE_HEIGHT_FACTORS = floatArrayOf(1f, 1.5f, 2f, 3f)
internal val TILE_HEIGHT_LABELS = listOf("Обычная", "Выше", "Высокая", "Макс.")
internal fun heightFactor(level: Int): Float = TILE_HEIGHT_FACTORS[level.coerceIn(0, TILE_HEIGHT_FACTORS.lastIndex)]

/** Цвета плиток, которые можно выбрать: от мягких пастельных до насыщенных. */
val TILE_COLORS = listOf(
    Color(0xFFFFE680), Color(0xFFFFC9A8), Color(0xFFFFB8D0), Color(0xFFD9B8FF),
    Color(0xFFA9D4FF), Color(0xFF9FE3D0), Color(0xFFA8E6B8), Color(0xFFDCD6C4),
    Color(0xFF5B3FD9), Color(0xFFC73A52), Color(0xFF2B5FAF), Color(0xFF1F7A55),
    Color(0xFFD9822B), Color(0xFF2A2740)
)

private fun inkFor(bg: Color) = if (bg.luminance() > 0.42f) PaperInk else Color(0xFFF4F2FF)
private fun mutedFor(bg: Color) = if (bg.luminance() > 0.42f) PaperMuted else Color(0xFFCFCBE6)
private fun Color.toTileLong(): Long = toArgb().toLong() and 0xFFFFFFFFL

/** Состояние перетаскивания: какая плитка взята и где сейчас палец (в координатах сетки). */
@Stable
private class TileDragState {
    var key by mutableStateOf<Long?>(null)
    var finger by mutableStateOf(Offset.Zero)
    /** Где палец держит плитку относительно её левого верхнего угла. */
    var grab by mutableStateOf(Offset.Zero)
    var moved by mutableStateOf(false)
    /** Плитка, над серединой которой палец держится достаточно долго: бросим сюда — получится папка. */
    var mergeKey by mutableStateOf<Long?>(null)
    /** Идёт изменение размера протягиванием за край. */
    var resizing by mutableStateOf(false)
    /** Плитка, над серединой которой палец сейчас (ещё держим или уже готово) и сколько из задержки прошло (0..1). */
    var candidate by mutableStateOf<Long?>(null)
    var dwellProgress by mutableStateOf(0f)
    var dwellKey: Long? = null
    var dwellSince: Long = 0L
}

/** Пустое место справа в неполном ряду: нужно сетке, чтобы следующая плитка начала новый ряд. */
private class GapItem(val key: String, val span: Int)

/**
 * Главный список в виде плиток.
 *  • Нажатие открывает заметку (или папку).
 *  • Потянуть за правый край плитки — меняется размер.
 *  • Удержание берёт плитку: её можно поставить слева/справа от другой, в пустое место ряда,
 *    отдельным рядом (у верхнего/нижнего края плитки или под всеми плитками).
 *    Если подержать над серединой другой плитки — получится папка; отпустить на месте — настройки плитки.
 */
@Composable
fun NotesGrid(
    notes: List<Note>,
    canReorder: Boolean,
    contentPadding: PaddingValues,
    onOpen: (Note) -> Unit,
    onOptions: (Note) -> Unit,
    onLayout: (List<Note>) -> Unit,
    onMerge: (Long, Long) -> Unit,
    onPin: (Note) -> Unit,
    onDelete: (Note) -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
    folderKids: (Long) -> List<Note> = { emptyList() },
    allowFolders: Boolean = true,
    onDragOut: ((Note) -> Unit)? = null
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val paperList = papers()
    val folderBase = MaterialTheme.colorScheme.secondaryContainer
    val gridState = rememberLazyGridState()
    val dnd = remember { TileDragState() }

    // Рабочий порядок: во время перетаскивания меняется сразу, в базу уходит один раз — когда палец отпущен.
    var working by remember { mutableStateOf(notes) }
    LaunchedEffect(notes) { if (dnd.key == null && !dnd.resizing) working = notes }

    val notesNow by rememberUpdatedState(notes)
    val canReorderNow by rememberUpdatedState(canReorder)
    val allowFoldersNow by rememberUpdatedState(allowFolders)
    val openNow by rememberUpdatedState(onOpen)
    val optionsNow by rememberUpdatedState(onOptions)
    val layoutNow by rememberUpdatedState(onLayout)
    val mergeNow by rememberUpdatedState(onMerge)
    val dragOutNow by rememberUpdatedState(onDragOut)

    val padStart = with(density) { contentPadding.calculateStartPadding(LayoutDirection.Ltr).toPx() }
    val padEnd = with(density) { contentPadding.calculateEndPadding(LayoutDirection.Ltr).toPx() }
    val gapPx = with(density) { 10.dp.toPx() }
    val gripW = with(density) { 30.dp.toPx() }
    val gripH = with(density) { 56.dp.toPx() }
    val gripBottomW = with(density) { 64.dp.toPx() }
    val gripBottomH = with(density) { 30.dp.toPx() }

    fun contains(info: LazyGridItemInfo, p: Offset) =
        p.x >= info.offset.x && p.x < info.offset.x + info.size.width &&
            p.y >= info.offset.y && p.y < info.offset.y + info.size.height

    /** Плитка под пальцем (пустые места в рядах не считаются). */
    fun tileAt(p: Offset) = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key is Long && contains(it, p) }

    /** Правый край плитки — «ручка» для изменения размера. */
    fun inGrip(info: LazyGridItemInfo, p: Offset): Boolean {
        val cy = info.offset.y + info.size.height / 2f
        val right = (info.offset.x + info.size.width).toFloat()
        return p.x >= right - gripW && p.x <= right && abs(p.y - cy) <= gripH / 2f
    }

    /** Нижний край плитки (по центру) — «ручка» для изменения высоты. */
    fun inBottomGrip(info: LazyGridItemInfo, p: Offset): Boolean {
        val cx = info.offset.x + info.size.width / 2f
        val bottom = (info.offset.y + info.size.height).toFloat()
        return abs(p.x - cx) <= gripBottomW / 2f && p.y >= bottom - gripBottomH && p.y <= bottom
    }

    /** Ширина плитки в пикселях при заданном числе колонок. */
    fun spanWidth(span: Int): Float {
        val inner = gridState.layoutInfo.viewportSize.width - padStart - padEnd
        val unit = (inner - gapPx * (GRID_COLUMNS - 1)) / GRID_COLUMNS
        return span * unit + (span - 1) * gapPx
    }

    /** Решает, куда встанет взятая плитка, исходя из того, где сейчас палец. */
    fun updateTarget(now: Long) {
        val k = dnd.key ?: return
        val cur = working
        val d = cur.firstOrNull { it.id == k } ?: return
        val p = dnd.finger
        val infos = gridState.layoutInfo.visibleItemsInfo.filter { it.key is Long }
        val under = infos.firstOrNull { contains(it, p) }
        var next: List<Note>? = null
        var mergeCandidate: Long? = null

        if (under != null) {
            val tid = under.key as Long
            val t = cur.firstOrNull { it.id == tid }
            if (tid != k && t != null) {
                val fx = (p.x - under.offset.x) / under.size.width
                val fy = (p.y - under.offset.y) / under.size.height
                next = when {
                    fy < 0.22f -> moveToNewRow(cur, d, t, above = true)
                    fy > 0.78f -> moveToNewRow(cur, d, t, above = false)
                    fx < 0.3f -> moveBeside(cur, d, t, before = true)
                    fx > 0.7f -> moveBeside(cur, d, t, before = false)
                    allowFoldersNow && !d.isFolder -> {
                        mergeCandidate = tid
                        null
                    }
                    else -> moveBeside(cur, d, t, before = fx < 0.5f)
                }
            }
        } else {
            val rows = layoutRows(cur)
            val rowInfos = rows.map { row -> row.mapNotNull { n -> infos.firstOrNull { it.key == n.id } } }
            val firstVisible = rowInfos.indexOfFirst { it.isNotEmpty() }
            val lastVisible = rowInfos.indexOfLast { it.isNotEmpty() }
            if (firstVisible >= 0) {
                // палец над пустым местом справа в ряду
                val hitRow = rowInfos.indexOfFirst { r ->
                    r.isNotEmpty() &&
                        p.y >= r.minOf { it.offset.y } && p.y < r.maxOf { it.offset.y + it.size.height } &&
                        p.x >= r.maxOf { it.offset.x + it.size.width }
                }
                val bottomLast = rowInfos[lastVisible].maxOf { it.offset.y + it.size.height }
                val topFirst = rowInfos[firstVisible].minOf { it.offset.y }
                next = when {
                    hitRow >= 0 -> moveToRowEnd(cur, d, hitRow)
                    lastVisible == rows.lastIndex && p.y >= bottomLast -> moveToEnd(cur, d)
                    firstVisible == 0 && p.y < topFirst -> moveToStart(cur, d)
                    else -> null
                }
            }
        }

        val n = next
        if (n != null && !sameLayout(n, cur)) working = n

        // Удержание над серединой плитки — получится папка
        if (mergeCandidate != null) {
            if (dnd.dwellKey != mergeCandidate) {
                dnd.dwellKey = mergeCandidate
                dnd.dwellSince = now
            } else if (dnd.mergeKey != mergeCandidate && now - dnd.dwellSince > 450_000_000L) {
                dnd.mergeKey = mergeCandidate
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            dnd.candidate = mergeCandidate
            dnd.dwellProgress =
                if (dnd.mergeKey == mergeCandidate) 1f else ((now - dnd.dwellSince) / 450_000_000f).coerceIn(0f, 1f)
        } else {
            dnd.dwellKey = null
            dnd.mergeKey = null
            dnd.candidate = null
            dnd.dwellProgress = 0f
        }
    }

    // Автопрокрутка, пока плитку держат у верхнего или нижнего края, и выбор места для плитки.
    LaunchedEffect(dnd.key) {
        if (dnd.key == null) return@LaunchedEffect
        val edge = with(density) { 80.dp.toPx() }
        while (true) {
            val size = gridState.layoutInfo.viewportSize
            val h = size.height.toFloat()
            val f = dnd.finger
            val inside = f.x >= 0f && f.x <= size.width && f.y >= 0f && f.y <= h
            val dy = when {
                dragOutNow != null && !inside -> 0f
                f.y < edge -> -(edge - f.y) / edge * 26f
                f.y > h - edge -> (f.y - (h - edge)) / edge * 26f
                else -> 0f
            }
            if (dy != 0f) gridState.scrollBy(dy)
            if (dnd.moved && canReorderNow) updateTarget(System.nanoTime())
            withFrameNanos { }
        }
    }

    val gestures = Modifier.pointerInput(canReorder) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = true)
            val hit = tileAt(down.position) ?: return@awaitEachGesture
            val note0 = working.firstOrNull { it.id == hit.key } ?: return@awaitEachGesture
            val slop = viewConfiguration.touchSlop
            // 0 — обычное нажатие, 1 — ручка справа (ширина), 2 — ручка снизу (высота)
            val axis = when {
                !canReorder -> 0
                inGrip(hit, down.position) -> 1
                inBottomGrip(hit, down.position) -> 2
                else -> 0
            }

            // 1 — быстрое нажатие (открыть), 2 — жест съели (прокрутка или кнопка на плитке),
            // 3 — потянули за ручку (размер), null — долгое нажатие: на ручке тоже размер (держим и тянем),
            // на самой плитке — переставить / настройки
            val first = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                var res = 0
                while (res == 0) {
                    val ev = awaitPointerEvent(if (axis != 0) PointerEventPass.Initial else PointerEventPass.Main)
                    val ch = ev.changes.firstOrNull { it.id == down.id }
                    if (ch == null || (axis == 0 && ch.isConsumed)) {
                        res = 2
                    } else if (!ch.pressed) {
                        res = 1
                    } else if (axis != 0) {
                        val dx = ch.position.x - down.position.x
                        val dy = ch.position.y - down.position.y
                        if ((if (axis == 1) abs(dx) else abs(dy)) > slop) {
                            ch.consume()
                            res = 3
                        }
                    }
                }
                res
            }

            val resize = axis != 0 && (first == 3 || first == null)
            if (resize && first == null) haptic.performHapticFeedback(HapticFeedbackType.LongPress)

            when {
                first == 1 -> openNow(note0)
                first == 2 -> Unit
                resize -> {
                    // Меняем размер: плитка растёт и уменьшается прямо под пальцем, соседи переезжают
                    working = normalized(working)
                    dnd.resizing = true
                    val left = hit.offset.x.toFloat()
                    val grabDx = (hit.offset.x + hit.size.width) - down.position.x
                    val sizes = TileSize.entries
                    val h0Level = note0.tileHeight
                    val baseH = hit.size.height / heightFactor(h0Level)
                    val step = maxOf(baseH * 0.5f, with(density) { 36.dp.toPx() })
                    val startY = down.position.y
                    var go = true
                    while (go) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val ch = ev.changes.firstOrNull { it.id == down.id }
                        if (ch == null || !ch.pressed) {
                            go = false
                        } else {
                            ch.consume()
                            if (axis == 1) {
                                val want = ch.position.x + grabDx - left
                                val idx = sizes.indices.minByOrNull { abs(spanWidth(sizes[it].span) - want) } ?: note0.tileSize
                                val curIdx = working.firstOrNull { it.id == note0.id }?.tileSize
                                if (curIdx != idx) {
                                    working = working.map { if (it.id == note0.id) it.copy(tileSize = idx) else it }
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            } else {
                                val lv = (h0Level + ((ch.position.y - startY) / step).roundToInt())
                                    .coerceIn(0, TILE_HEIGHT_FACTORS.lastIndex)
                                val curLv = working.firstOrNull { it.id == note0.id }?.tileHeight
                                if (curLv != lv) {
                                    working = working.map { if (it.id == note0.id) it.copy(tileHeight = lv) else it }
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                        }
                    }
                    dnd.resizing = false
                    val fixed = normalized(working)
                    if (sameLayout(fixed, normalized(notesNow))) {
                        working = notesNow
                    } else {
                        working = fixed
                        layoutNow(fixed)
                    }
                }
                else -> {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    dnd.key = note0.id
                    dnd.finger = down.position
                    dnd.grab = Offset(down.position.x - hit.offset.x, down.position.y - hit.offset.y)
                    dnd.moved = false
                    dnd.mergeKey = null
                    dnd.dwellKey = null
                    if (canReorder) working = normalized(working)
                    val ok = drag(down.id) { change ->
                        change.consume()
                        dnd.finger = change.position
                        if (!dnd.moved && (change.position - down.position).getDistance() > slop) dnd.moved = true
                    }
                    val moved = dnd.moved
                    val merge = dnd.mergeKey
                    val f = dnd.finger
                    val vp = gridState.layoutInfo.viewportSize
                    val outside = f.x < 0f || f.y < 0f || f.x > vp.width || f.y > vp.height
                    dnd.key = null
                    dnd.moved = false
                    dnd.mergeKey = null
                    dnd.dwellKey = null
                    dnd.candidate = null
                    dnd.dwellProgress = 0f
                    val fixed = working
                    val out = dragOutNow
                    when {
                        !ok -> working = notesNow
                        moved && canReorder && outside && out != null -> {
                            out(note0)
                            working = notesNow
                        }
                        moved && canReorder && merge != null && allowFoldersNow -> {
                            mergeNow(note0.id, merge)
                            working = notesNow
                        }
                        moved && canReorder && !sameLayout(fixed, normalized(notesNow)) -> layoutNow(fixed)
                        moved -> working = notesNow
                        else -> {
                            working = notesNow
                            // отпустили на месте — настройки плитки
                            notesNow.firstOrNull { it.id == note0.id }?.let { optionsNow(it) }
                        }
                    }
                }
            }
        }
    }

    // Плитки по рядам + пустые места справа в неполных рядах
    val entries = remember(working) {
        buildList<Any> {
            layoutRows(working).forEach { row ->
                addAll(row)
                val used = row.sumOf { spanOf(it) }
                if (used < GRID_COLUMNS) add(GapItem("gap:" + row.first().id, GRID_COLUMNS - used))
            }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        state = gridState,
        modifier = modifier.then(gestures),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        userScrollEnabled = dnd.key == null && !dnd.resizing
    ) {
        items(
            entries,
            key = { if (it is Note) it.id else (it as GapItem).key },
            span = { GridItemSpan(if (it is Note) spanOf(it) else (it as GapItem).span) }
        ) { entry ->
            if (entry is Note) {
                val note = entry
                val dragged = dnd.key == note.id
                val size = TileSize.of(note.tileSize)
                val paper = paperList[note.paper.mod(paperList.size)]
                val base = when {
                    note.tileColor != 0L -> Color(note.tileColor)
                    note.isFolder -> folderBase
                    else -> paper
                }
                val target = dnd.candidate == note.id
                val content: @Composable (Modifier) -> Unit = { m ->
                    NoteTile(
                        note = note,
                        size = size,
                        base = base,
                        dragged = dragged,
                        merging = dnd.mergeKey == note.id,
                        mergeProgress = if (target) dnd.dwellProgress else 0f,
                        grip = canReorder,
                        kids = if (note.isFolder) folderKids(note.id) else emptyList(),
                        context = context,
                        onPin = { onPin(note) },
                        onDelete = { onDelete(note) },
                        modifier = m
                    )
                }
                if (dragged) {
                    // Взятая плитка летит за пальцем, а на её месте в сетке — пунктирный слот: сюда она встанет,
                    // если отпустить. Над серединой другой плитки слот прячется: тогда получится папка.
                    Box(Modifier.fillMaxWidth().then(tileSized(note, size)).zIndex(1f)) {
                        if (dnd.candidate == null) SlotGhost()
                        content(
                            Modifier.graphicsLayer {
                                val info = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == note.id }
                                if (info != null) {
                                    translationX = dnd.finger.x - dnd.grab.x - info.offset.x
                                    translationY = dnd.finger.y - dnd.grab.y - info.offset.y
                                }
                                scaleX = 1.06f
                                scaleY = 1.06f
                                alpha = 0.95f
                            }
                        )
                    }
                } else {
                    content(Modifier.animateItem())
                }
            } else {
                Spacer(Modifier.fillMaxWidth())
            }
        }
    }
}

/** Окно открытой папки; внутри — такая же сетка плиток. */
@Composable
fun FolderDialog(folder: Note, count: Int, onClose: () -> Unit, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(28.dp),
            color = cs.surfaceContainerHigh,
            tonalElevation = 6.dp
        ) {
            Column(Modifier.padding(top = 18.dp, bottom = 8.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 22.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        folder.title.ifBlank { "Папка" },
                        style = MaterialTheme.typography.titleLarge, color = cs.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text("$count", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.height(10.dp))
                if (count == 0) {
                    Text(
                        "Папка пуста",
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 24.dp)
                    )
                } else {
                    Box(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 460.dp)) { content() }
                }
            }
        }
    }
}

private fun notesWord(n: Int): String {
    val m100 = n % 100
    val m10 = n % 10
    return when {
        m100 in 11..14 -> "заметок"
        m10 == 1 -> "заметка"
        m10 in 2..4 -> "заметки"
        else -> "заметок"
    }
}

/** Крошечная карточка заметки внутри папки: цвет плитки и условные строки текста. */
@Composable
private fun MiniNote(n: Note?, cell: Dp, ink: Color) {
    val paperList = papers()
    val shape = RoundedCornerShape(cell / 3.5f)
    if (n == null) {
        Box(
            Modifier.size(cell).clip(shape)
                .background(ink.copy(alpha = 0.04f))
                .border(1.dp, ink.copy(alpha = 0.14f), shape)
        )
        return
    }
    val color = if (n.tileColor != 0L) Color(n.tileColor) else paperList[n.paper.mod(paperList.size)]
    val line = inkFor(color).copy(alpha = 0.38f)
    Column(
        Modifier.size(cell).shadow(1.5.dp, shape).clip(shape).background(color).padding(cell / 6),
        verticalArrangement = Arrangement.spacedBy(cell / 12)
    ) {
        if (cell >= 22.dp) {
            Box(Modifier.fillMaxWidth(0.8f).height(cell / 10).clip(CircleShape).background(line))
            Box(Modifier.fillMaxWidth(0.55f).height(cell / 10).clip(CircleShape).background(line))
            Box(Modifier.fillMaxWidth(0.7f).height(cell / 10).clip(CircleShape).background(line))
        }
    }
}

/** Квадратная «стеклянная» подложка [plate] с четырьмя мини-карточками; если заметок больше четырёх — «+N». */
@Composable
private fun FolderMinis(kids: List<Note>, plate: Dp, ink: Color) {
    val pad = plate * 0.08f
    val gap = plate * 0.06f
    val cell = (plate - pad * 2 - gap) / 2
    val shape = RoundedCornerShape(plate * 0.22f)
    Column(
        Modifier.size(plate).clip(shape)
            .background(ink.copy(alpha = 0.07f))
            .border(1.dp, ink.copy(alpha = 0.10f), shape)
            .padding(pad),
        verticalArrangement = Arrangement.spacedBy(gap)
    ) {
        for (r in 0..1) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                for (c in 0..1) {
                    val i = r * 2 + c
                    if (i == 3 && kids.size > 4) {
                        val mshape = RoundedCornerShape(cell / 3.5f)
                        Box(
                            Modifier.size(cell).clip(mshape).background(ink.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("+${kids.size - 3}", color = ink, fontWeight = FontWeight.Bold, fontSize = (cell.value * 0.42f).sp, maxLines = 1)
                        }
                    } else MiniNote(kids.getOrNull(i), cell, ink)
                }
            }
        }
    }
}

private fun kidLabel(n: Note): String =
    n.title.ifBlank { n.text.lineSequence().firstOrNull().orEmpty() }.trim().ifBlank { "Заметка" }

/** Плашка с числом заметок. */
@Composable
private fun CountChip(count: Int, ink: Color, muted: Color, withWord: Boolean) {
    Row(
        Modifier.clip(CircleShape).background(ink.copy(alpha = 0.08f)).padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        AppIcon(Ico.FOLDER, muted, 13.dp)
        Text(if (withWord) "$count ${notesWord(count)}" else "$count", color = muted, fontSize = 12.sp, maxLines = 1)
    }
}

/** Содержимое свёрнутой папки: подложка растёт вместе с плиткой, лишнего пустого места нет. */
@Composable
private fun FolderContent(note: Note, size: TileSize, kids: List<Note>, ink: Color, muted: Color) {
    val name = note.title.ifBlank { "Папка" }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        when (size) {
            TileSize.ICON, TileSize.SMALL -> {
                val labelH = if (size == TileSize.ICON) 18.dp else 24.dp
                val plate = minOf(maxWidth - 12.dp, maxHeight - labelH - 8.dp).coerceAtLeast(24.dp)
                Column(
                    Modifier.fillMaxSize().padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    FolderMinis(kids, plate, ink)
                    Text(
                        name, color = ink, fontWeight = FontWeight.SemiBold,
                        fontSize = if (size == TileSize.ICON) 10.sp else 12.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            TileSize.MEDIUM -> {
                val plate = minOf(maxHeight - 24.dp - 44.dp, maxWidth * 0.5f).coerceAtLeast(40.dp)
                Column(
                    Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        FolderMinis(kids, plate, ink)
                        CountChip(kids.size, ink, muted, withWord = false)
                    }
                    Text(
                        name, color = ink, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 20.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
            }

            TileSize.WIDE -> {
                val plate = (maxHeight - 24.dp).coerceAtLeast(40.dp)
                Row(
                    Modifier.fillMaxSize().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    FolderMinis(kids, plate, ink)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            name, color = ink, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        if (kids.isNotEmpty()) Text(
                            kids.take(3).joinToString(" · ") { kidLabel(it) },
                            color = muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        CountChip(kids.size, ink, muted, withWord = true)
                    }
                }
            }
        }
    }
}

/** Пунктирный слот на месте, куда встанет взятая плитка (если отпустить). */
@Composable
private fun BoxScope.SlotGhost() {
    val c = MaterialTheme.colorScheme.primary
    Box(
        Modifier.matchParentSize().drawBehind {
            val r = CornerRadius(20.dp.toPx())
            drawRoundRect(c.copy(alpha = 0.10f), cornerRadius = r)
            drawRoundRect(
                c.copy(alpha = 0.75f), cornerRadius = r,
                style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 12f)))
            )
        }
    )
}

/** Значок «будет папка»: кольцо заполняется, пока держишь плитку над серединой другой. */
@Composable
private fun MergeBadge(progress: Float, ready: Boolean, label: String?) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.clip(CircleShape).background(cs.primary)
            .padding(start = 5.dp, end = if (label != null) 12.dp else 5.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val sw = 3.dp.toPx()
                val tl = Offset(sw / 2f, sw / 2f)
                val sz = Size(this.size.width - sw, this.size.height - sw)
                drawArc(cs.onPrimary.copy(alpha = 0.3f), -90f, 360f, false, tl, sz, style = Stroke(sw))
                drawArc(cs.onPrimary, -90f, 360f * (if (ready) 1f else progress), false, tl, sz, style = Stroke(sw))
            }
            AppIcon(Ico.FOLDER, cs.onPrimary, 14.dp)
        }
        if (label != null) Text(label, color = cs.onPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Высота и форма плитки на сетке. */
private fun tileSized(note: Note, size: TileSize): Modifier {
    val hf = heightFactor(note.tileHeight)
    return when (size) {
        TileSize.ICON, TileSize.SMALL -> Modifier.aspectRatio(1f / hf)
        TileSize.MEDIUM -> Modifier.height(140.dp * hf)
        TileSize.WIDE -> when {
            note.isFolder -> Modifier.height(104.dp * hf)
            note.tileHeight > 0 -> Modifier.heightIn(min = 132.dp * hf)
            else -> Modifier
        }
    }
}

@Composable
private fun NoteTile(
    note: Note,
    size: TileSize,
    base: Color,
    dragged: Boolean,
    merging: Boolean,
    mergeProgress: Float,
    grip: Boolean,
    kids: List<Note>,
    context: Context,
    onPin: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier
) {
    val ink = inkFor(base)
    val muted = mutedFor(base)
    val pinColor = if (base.luminance() > 0.42f) PaperPin else Color.White
    val fam = remember(note.fontKey) { Fonts.family(context, note.fontKey) }
    val shape = RoundedCornerShape(if (size == TileSize.WIDE || size == TileSize.MEDIUM) 24.dp else 20.dp)
    val date = remember(note.updatedAt) {
        SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(note.updatedAt))
    }
    val extra = note.tileHeight * 3 // на высокой плитке помещается больше строк текста
    val sized = tileSized(note, size)

    // Цель слияния: плитка слегка «подпрыгивает», подсвечивается и показывает значок «будет папка»
    val targeted = merging || mergeProgress > 0f
    val pop = if (merging) 1.06f else 1f + 0.04f * mergeProgress
    val ring = MaterialTheme.colorScheme.primary
    Box(
        modifier.fillMaxWidth().then(sized)
            .graphicsLayer { scaleX = pop; scaleY = pop }
            .softCard(base, shape, if (dragged) 22.dp else if (targeted) 18.dp else 10.dp)
            .clip(shape)
            .then(
                if (targeted) Modifier.border(
                    if (merging) 4.dp else 2.dp,
                    ring.copy(alpha = if (merging) 1f else 0.35f + 0.65f * mergeProgress), shape
                ) else Modifier
            )
    ) {
        if (note.isFolder) FolderContent(note, size, kids, ink, muted)
        else when (size) {
            TileSize.ICON -> {
                val label = note.title.ifBlank { note.text }.trim()
                val glyph = if (label.isEmpty()) "•"
                else label.substring(0, label.offsetByCodePoints(0, 1)).uppercase()
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(glyph, color = ink, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(
                        label.lineSequence().firstOrNull().orEmpty(),
                        color = muted, fontSize = 10.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
                    )
                }
            }

            TileSize.SMALL -> Column(Modifier.fillMaxSize().padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 8.dp)) {
                if (note.title.isNotBlank()) Text(
                    note.title, color = ink, fontFamily = fam, fontWeight = FontWeight.Bold,
                    fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                if (note.text.isNotBlank() || note.title.isBlank()) Text(
                    note.text.ifBlank { "Без текста" },
                    color = if (note.text.isBlank()) muted else ink, fontFamily = fam,
                    fontSize = 12.sp, lineHeight = 16.sp,
                    maxLines = (if (note.title.isBlank()) 5 else 3) + extra, overflow = TextOverflow.Ellipsis
                )
            }

            TileSize.MEDIUM -> Column(Modifier.fillMaxSize().padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 10.dp)) {
                Column(Modifier.weight(1f)) {
                    if (note.title.isNotBlank()) Text(
                        note.title, color = ink, fontFamily = fam, fontWeight = FontWeight.Bold,
                        fontSize = 16.sp, lineHeight = 21.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    if (note.text.isNotBlank() || note.title.isBlank()) Text(
                        note.text.ifBlank { "Без текста" },
                        color = if (note.text.isBlank()) muted else ink, fontFamily = fam,
                        fontSize = 14.sp, lineHeight = 19.sp,
                        maxLines = (if (note.title.isBlank()) 4 else 2) + extra, overflow = TextOverflow.Ellipsis
                    )
                }
                Text(date, color = muted, fontSize = 11.sp, maxLines = 1)
            }

            TileSize.WIDE -> {
                val thumb = remember(note.images) { Media.names(note.images).firstOrNull() }
                Column(Modifier.fillMaxWidth().padding(start = 18.dp, top = 16.dp, end = 8.dp, bottom = 6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            if (note.title.isNotBlank()) Text(
                                note.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                color = ink, fontFamily = fam, fontWeight = FontWeight.Bold,
                                fontSize = 18.sp, lineHeight = 24.sp
                            )
                            if (note.text.isNotBlank() || note.title.isBlank()) Text(
                                note.text.ifBlank { "Без текста" },
                                maxLines = (if (note.title.isBlank()) 4 else 3) + extra, overflow = TextOverflow.Ellipsis,
                                color = if (note.text.isBlank()) muted else ink, fontFamily = fam,
                                fontSize = 16.sp, lineHeight = 23.sp
                            )
                        }
                        if (thumb != null) {
                            val bmp = rememberBitmap(thumb, 4)
                            if (bmp != null) Image(
                                bmp, null,
                                Modifier.padding(end = 10.dp).size(64.dp).clip(RoundedCornerShape(14.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            date + (if (note.strokes != "[]") "  ·  ✏" else "") +
                                (if (note.pinned) "  ·  закреплено" else ""),
                            color = if (note.pinned) pinColor else muted,
                            fontSize = 12.sp, modifier = Modifier.weight(1f)
                        )
                        Box(
                            Modifier.padding(bottom = 6.dp, end = Gap.S).size(38.dp)
                                .pressable(
                                    CircleShape,
                                    if (note.pinned) PaperPin else Color(0x14000000).compositeOver(base), 2.dp,
                                    onClick = onPin
                                ),
                            contentAlignment = Alignment.Center
                        ) { AppIcon(Ico.PIN, if (note.pinned) Color.White else ink, 18.dp) }
                        Box(
                            Modifier.padding(bottom = 6.dp, end = 4.dp).size(38.dp)
                                .pressable(CircleShape, Color(0x14000000).compositeOver(base), 2.dp, onClick = onDelete),
                            contentAlignment = Alignment.Center
                        ) { AppIcon(Ico.TRASH, Color(0xFF9C2F3A), 18.dp) }
                    }
                }
            }
        }
        if (targeted) {
            Box(Modifier.matchParentSize().background(ring.copy(alpha = if (merging) 0.28f else 0.08f + 0.14f * mergeProgress)))
            Box(Modifier.align(Alignment.Center)) {
                MergeBadge(
                    mergeProgress, merging,
                    when {
                        size == TileSize.ICON || size == TileSize.SMALL -> null
                        merging -> if (note.isFolder) "Отпустите — в папку" else "Отпустите — папка"
                        else -> "Подержите…"
                    }
                )
            }
        }
        // У маленьких плиток нет кнопки «закрепить», поэтому закрепление отмечено значком в углу.
        if (note.pinned && (size != TileSize.WIDE || note.isFolder)) {
            Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) { AppIcon(Ico.PIN, pinColor, 14.dp) }
        }
        // Ручки: справа — ширина, снизу — высота. Нажмите на ручку и, не отпуская, тяните.
        if (grip && !dragged) {
            Box(
                Modifier.align(Alignment.CenterEnd).padding(end = 3.dp).size(width = 4.dp, height = 24.dp)
                    .clip(CircleShape).background(ink.copy(alpha = 0.28f))
            )
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(width = 24.dp, height = 4.dp)
                    .clip(CircleShape).background(ink.copy(alpha = 0.28f))
            )
        }
    }
}

/** Настройка плитки: размер (сколько штук в ряду), цвет, закрепление, удаление. */
@Composable
fun TileOptionsDialog(
    note: Note,
    onSize: (Int) -> Unit,
    onHeight: (Int) -> Unit,
    onColor: (Long) -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
    onUngroup: () -> Unit,
    onLeaveFolder: (() -> Unit)?,
    onClose: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val papers = papers()
    var folderName by remember(note.id) { mutableStateOf(note.title) }
    val paper = papers[note.paper.mod(papers.size)]
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text(
                if (note.isFolder) note.title.ifBlank { "Папка" }
                else note.title.ifBlank { note.text.lineSequence().firstOrNull().orEmpty().ifBlank { "Заметка" } },
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Gap.M)) {
                if (note.isFolder) OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it; onRename(it) },
                    singleLine = true,
                    label = { Text("Название папки") },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Размер плитки", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TileSize.entries.forEachIndexed { i, s ->
                        val sel = note.tileSize == i
                        Column(
                            Modifier.weight(1f)
                                .pressable(
                                    RoundedCornerShape(14.dp),
                                    if (sel) cs.primary else cs.surfaceContainerHighest, 2.dp
                                ) { onSize(i) }
                                .padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                s.label, fontSize = 12.sp, maxLines = 1, fontWeight = FontWeight.Medium,
                                color = if (sel) cs.onPrimary else cs.onSurface
                            )
                            Text(
                                "×${s.perRow}", fontSize = 11.sp,
                                color = if (sel) cs.onPrimary else cs.onSurfaceVariant
                            )
                        }
                    }
                }
                Text(
                    "Цифра — сколько плиток помещается в один ряд.",
                    fontSize = 12.sp, color = cs.onSurfaceVariant
                )

                Text("Высота плитки", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TILE_HEIGHT_LABELS.forEachIndexed { i, label ->
                        val sel = note.tileHeight == i
                        Box(
                            Modifier.weight(1f)
                                .pressable(
                                    RoundedCornerShape(14.dp),
                                    if (sel) cs.primary else cs.surfaceContainerHighest, 2.dp
                                ) { onHeight(i) }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label, fontSize = 12.sp, maxLines = 1, fontWeight = FontWeight.Medium,
                                color = if (sel) cs.onPrimary else cs.onSurface
                            )
                        }
                    }
                }
                Text(
                    "Размер можно менять и прямо на плитке: нажмите полоску у правого края (ширина) или у нижнего (высота) и, не отпуская, тяните. Узкая и высокая плитка получается вертикальной.",
                    fontSize = 12.sp, color = cs.onSurfaceVariant
                )

                Text("Цвет плитки", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Первая точка — цвет листа по умолчанию
                    TileSwatch(paper, note.tileColor == 0L, mark = "↺") { onColor(0L) }
                    TILE_COLORS.forEach { c ->
                        TileSwatch(c, note.tileColor == c.toTileLong()) { onColor(c.toTileLong()) }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(Gap.S)) {
                    PillButton(if (note.pinned) "Открепить" else "Закрепить", icon = Ico.PIN, onClick = onPin)
                    if (note.isFolder) PillButton("Расформировать", icon = Ico.FOLDER, onClick = onUngroup)
                    else PillButton("Удалить", icon = Ico.TRASH, danger = true, onClick = onDelete)
                }
                if (onLeaveFolder != null) PillButton("Вынуть из папки", icon = Ico.FOLDER, onClick = onLeaveFolder)
            }
        },
        confirmButton = { PillButton("Готово", primary = true, onClick = onClose) }
    )
}

@Composable
private fun TileSwatch(color: Color, selected: Boolean, mark: String = "", onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(38.dp)
            .border(if (selected) 3.dp else 0.dp, if (selected) cs.primary else Color.Transparent, CircleShape)
            .padding(if (selected) 4.dp else 0.dp)
            .pressable(CircleShape, color, 3.dp, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        val fg = inkFor(color)
        if (selected) Text("✓", color = fg, fontSize = 15.sp)
        else if (mark.isNotEmpty()) Text(mark, color = fg, fontSize = 15.sp)
    }
}
