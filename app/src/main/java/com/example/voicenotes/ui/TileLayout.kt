package com.example.voicenotes.ui

import com.example.voicenotes.data.Note

/**
 * Раскладка плиток по рядам. Сетка — 12 колонок. Новый ряд начинается, если у плитки стоит newRow,
 * если она не помещается в остаток ряда или если меняется группа (закреплённые / обычные).
 * Пустое место справа в ряду остаётся пустым, пока плитку туда не поставят.
 */
internal const val GRID_COLUMNS = 12

internal fun spanOf(n: Note): Int = TileSize.of(n.tileSize).span

internal fun layoutRows(list: List<Note>): List<List<Note>> {
    val rows = ArrayList<MutableList<Note>>()
    var used = 0
    for (n in list) {
        val s = spanOf(n)
        val cur = rows.lastOrNull()
        if (cur == null || n.newRow || used + s > GRID_COLUMNS || cur.last().pinned != n.pinned) {
            rows.add(mutableListOf(n))
            used = s
        } else {
            cur.add(n)
            used += s
        }
    }
    return rows
}

/** Фиксирует раскладку: у первой плитки каждого ряда newRow = true, у остальных false. */
internal fun normalized(list: List<Note>): List<Note> =
    layoutRows(list).flatMap { row ->
        row.mapIndexed { i, n -> if (n.newRow == (i == 0)) n else n.copy(newRow = i == 0) }
    }

internal fun sameLayout(a: List<Note>, b: List<Note>): Boolean =
    a.size == b.size && a.indices.all {
        a[it].id == b[it].id && a[it].newRow == b[it].newRow && a[it].tileSize == b[it].tileSize &&
            a[it].tileHeight == b[it].tileHeight
    }

/** Убирает плитку, не ломая ряды остальных (если убрали начало ряда, начало переходит к соседу). */
internal fun removeNote(list: List<Note>, id: Long): List<Note> {
    val i = list.indexOfFirst { it.id == id }
    if (i < 0) return list
    val m = list.toMutableList()
    val gone = m.removeAt(i)
    if (gone.newRow && i < m.size && !m[i].newRow) m[i] = m[i].copy(newRow = true)
    return m
}

internal enum class Place { BEFORE, JOIN, NEW_ROW }

/**
 * Вставляет [d] в позицию [index]. BEFORE — встать перед плиткой (если она начинала ряд, начало ряда переходит к [d]),
 * JOIN — встать в конец ряда, NEW_ROW — занять отдельный ряд. Закреплённые остаются выше обычных.
 */
internal fun insertNote(list: List<Note>, index: Int, d: Note, place: Place): List<Note> {
    val pinnedCount = list.count { it.pinned }
    val lo = if (d.pinned) 0 else pinnedCount
    val hi = if (d.pinned) pinnedCount else list.size
    val at = index.coerceIn(lo, hi)
    val mode = if (at != index) Place.NEW_ROW else place
    val m = list.toMutableList()
    when (mode) {
        Place.BEFORE -> {
            if (at < m.size && m[at].newRow) {
                m[at] = m[at].copy(newRow = false)
                m.add(at, d.copy(newRow = true))
            } else {
                m.add(at, d.copy(newRow = false))
            }
        }
        Place.JOIN -> m.add(at, d.copy(newRow = false))
        Place.NEW_ROW -> {
            if (at < m.size && !m[at].newRow) m[at] = m[at].copy(newRow = true)
            m.add(at, d.copy(newRow = true))
        }
    }
    return m
}

/** Поставить [d] рядом с [t]: слева (before) или справа. Если не помещается — займёт свой ряд. */
internal fun moveBeside(list: List<Note>, d: Note, t: Note, before: Boolean): List<Note> {
    val m = removeNote(list, d.id)
    val ti = m.indexOfFirst { it.id == t.id }
    if (ti < 0) return list
    return normalized(insertNote(m, if (before) ti else ti + 1, d, if (before) Place.BEFORE else Place.JOIN))
}

/** Отдельный новый ряд над или под рядом, в котором лежит [t]. */
internal fun moveToNewRow(list: List<Note>, d: Note, t: Note, above: Boolean): List<Note> {
    val m = removeNote(list, d.id)
    val row = layoutRows(m).firstOrNull { r -> r.any { it.id == t.id } } ?: return list
    val start = m.indexOfFirst { it.id == row.first().id }
    if (start < 0) return list
    return normalized(insertNote(m, if (above) start else start + row.size, d, Place.NEW_ROW))
}

/** В конец ряда с номером [rowIndex] (палец над пустым местом справа). */
internal fun moveToRowEnd(list: List<Note>, d: Note, rowIndex: Int): List<Note> {
    val last = layoutRows(list).getOrNull(rowIndex)?.lastOrNull { it.id != d.id } ?: return list
    val m = removeNote(list, d.id)
    val li = m.indexOfFirst { it.id == last.id }
    if (li < 0) return list
    return normalized(insertNote(m, li + 1, d, Place.JOIN))
}

/** На свободное место под всеми плитками — отдельным рядом. */
internal fun moveToEnd(list: List<Note>, d: Note): List<Note> {
    val m = removeNote(list, d.id)
    return normalized(insertNote(m, m.size, d, Place.NEW_ROW))
}

/** На свободное место над всеми плитками. */
internal fun moveToStart(list: List<Note>, d: Note): List<Note> {
    val m = removeNote(list, d.id)
    return normalized(insertNote(m, 0, d, Place.NEW_ROW))
}
