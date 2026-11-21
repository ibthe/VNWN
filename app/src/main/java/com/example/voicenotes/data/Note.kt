package com.example.voicenotes.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String = "",
    /** Название заметки (пусто — в списке показывается начало текста). */
    val title: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    /** Выделенные маркером фрагменты (JSON). */
    val spans: String = "[]",
    /** Жирные фрагменты (JSON, тот же формат, что у spans; c всегда 0). */
    val bold: String = "[]",
    /** Рисунок поверх текста (JSON). */
    val strokes: String = "[]",
    /** Имена файлов фотографий (JSON). */
    val images: String = "[]",
    val fontKey: String = "sans",
    val fontSize: Float = 18f,
    val lineHeight: Float = 1.5f,
    /** Индекс цвета листа. */
    val paper: Int = 0,
    /** Закреплена ли заметка (всегда сверху списка). */
    val pinned: Boolean = false,
    /** Размер плитки на главном экране: 0 — значок, 1 — малая, 2 — средняя, 3 — во всю ширину. */
    val tileSize: Int = 3,
    /** Свой цвет плитки (ARGB); 0 — цвет листа. */
    val tileColor: Long = 0L,
    /** Место в ручном порядке; 0 — ещё не расставлялась (тогда порядок по времени правки). */
    val position: Int = 0,
    /** Это папка: плитка, внутри которой лежат другие заметки (title — имя папки). */
    val isFolder: Boolean = false,
    /** id папки, в которой лежит заметка; 0 — главный экран. */
    val parentId: Long = 0L,
    /** Плитка начинает новый ряд. Так хранится свободная расстановка: пустые места в рядах остаются пустыми. */
    val newRow: Boolean = false,
    /** Высота плитки: 0 — обычная, 1 — выше, 2 — высокая, 3 — максимум (вертикальная плитка). */
    val tileHeight: Int = 0
)
