package com.example.voicenotes.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Typeface
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Фрагмент текста [s, e), выделенный маркером цвета c (индекс в HIGHLIGHTS). */
data class Span(val s: Int, val e: Int, val c: Int)

object Rich {
    fun parse(json: String): List<Span> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).map { val o = a.getJSONObject(it); Span(o.getInt("s"), o.getInt("e"), o.getInt("c")) }
    }.getOrDefault(emptyList())

    fun toJson(l: List<Span>): String = JSONArray().also { a ->
        l.forEach { a.put(JSONObject().put("s", it.s).put("e", it.e).put("c", it.c)) }
    }.toString()

    /** Сдвигает выделения, когда текст изменился (ввод, удаление, вставка). */
    fun adjust(old: String, new: String, spans: List<Span>): List<Span> {
        if (old == new || spans.isEmpty()) return spans
        val m = minOf(old.length, new.length)
        var p = 0
        while (p < m && old[p] == new[p]) p++
        var q = 0
        while (q < m - p && old[old.length - 1 - q] == new[new.length - 1 - q]) q++
        val oEnd = old.length - q
        val d = new.length - old.length
        fun start(x: Int) = if (x >= oEnd) x + d else if (x <= p) x else p
        fun end(x: Int) = if (x <= p) x else if (x >= oEnd) x + d else p
        return spans.mapNotNull {
            val s = start(it.s); val e = end(it.e)
            if (e > s) Span(s, e, it.c) else null
        }
    }

    /** true, если каждый символ диапазона [s, e) входит в какой-нибудь фрагмент (с учётом соседних). */
    fun covers(spans: List<Span>, s: Int, e: Int): Boolean {
        var pos = s
        for (sp in spans.sortedBy { it.s }) {
            if (sp.e <= pos) continue
            if (sp.s > pos) return false
            pos = sp.e
            if (pos >= e) return true
        }
        return pos >= e
    }

    /** Красит [s, e) цветом c; c < 0 снимает выделение. */
    fun apply(spans: List<Span>, s: Int, e: Int, c: Int): List<Span> {
        val out = mutableListOf<Span>()
        for (sp in spans) {
            if (sp.e <= s || sp.s >= e) { out += sp; continue }
            if (sp.s < s) out += Span(sp.s, s, sp.c)
            if (sp.e > e) out += Span(e, sp.e, sp.c)
        }
        if (c >= 0) out += Span(s, e, c)
        return out.sortedBy { it.s }
    }
}

/** Выделенный текст меняет цвет фона и чуть-чуть увеличивается (+12%). */
class HighlightTransformation(
    private val spans: List<Span>,
    private val baseSp: Float,
    private val bold: List<Span> = emptyList()
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val b = AnnotatedString.Builder(text.text)
        spans.forEach {
            val s = it.s.coerceIn(0, text.length)
            val e = it.e.coerceIn(0, text.length)
            if (e > s) b.addStyle(
                SpanStyle(
                    background = HIGHLIGHTS[it.c.mod(HIGHLIGHTS.size)],
                    fontSize = (baseSp * 1.12f).sp,
                    fontWeight = FontWeight.Medium
                ), s, e
            )
        }
        // Жирный накладывается поверх маркера
        bold.forEach {
            val s = it.s.coerceIn(0, text.length)
            val e = it.e.coerceIn(0, text.length)
            if (e > s) b.addStyle(SpanStyle(fontWeight = FontWeight.Bold), s, e)
        }
        return TransformedText(b.toAnnotatedString(), OffsetMapping.Identity)
    }
}

/** Штрих рисунка. Координаты в dp относительно левого верхнего угла листа. */
data class Ink(val color: Int, val width: Float, val marker: Boolean, val pts: List<Offset>) {
    companion object {
        fun parse(json: String): List<Ink> = runCatching {
            val a = JSONArray(json)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                val p = o.getJSONArray("p")
                Ink(
                    o.getInt("c"), o.getDouble("w").toFloat(), o.optBoolean("m"),
                    (0 until p.length() / 2).map { i -> Offset(p.getDouble(2 * i).toFloat(), p.getDouble(2 * i + 1).toFloat()) }
                )
            }
        }.getOrDefault(emptyList())

        fun toJson(l: List<Ink>): String = JSONArray().also { a ->
            l.forEach { k ->
                val p = JSONArray()
                k.pts.forEach { p.put(Math.round(it.x * 10) / 10.0); p.put(Math.round(it.y * 10) / 10.0) }
                a.put(JSONObject().put("c", k.color).put("w", k.width.toDouble()).put("m", k.marker).put("p", p))
            }
        }.toString()
    }
}

/** Системные шрифты и шрифты пользователя (.ttf/.otf, копируются в память приложения). */
object Fonts {
    val builtIn = listOf(
        "sans" to "Обычный", "serif" to "С засечками", "mono" to "Моноширинный",
        "hand" to "Рукописный", "condensed" to "Узкий", "casual" to "Casual", "light" to "Тонкий"
    )
    private val cache = mutableMapOf<String, FontFamily>()
    private fun dir(ctx: Context) = File(ctx.filesDir, "fonts").apply { mkdirs() }

    fun custom(ctx: Context): List<Pair<String, String>> =
        dir(ctx).listFiles()?.sortedBy { it.name }?.map { "f:${it.name}" to it.nameWithoutExtension } ?: emptyList()

    fun label(ctx: Context, key: String): String =
        (builtIn + custom(ctx)).firstOrNull { it.first == key }?.second ?: "Обычный"

    private fun tf(name: String) = FontFamily(Typeface.create(name, Typeface.NORMAL))

    fun family(ctx: Context, key: String): FontFamily = cache.getOrPut(key) {
        when {
            key == "serif" -> FontFamily.Serif
            key == "mono" -> FontFamily.Monospace
            key == "hand" -> FontFamily.Cursive
            key == "condensed" -> tf("sans-serif-condensed")
            key == "casual" -> tf("casual")
            key == "light" -> tf("sans-serif-light")
            key.startsWith("f:") -> runCatching {
                FontFamily(Font(File(dir(ctx), key.removePrefix("f:"))))
            }.getOrDefault(FontFamily.SansSerif)
            else -> FontFamily.SansSerif
        }
    }

    /** Возвращает ключ добавленного шрифта или null, если файл не подошёл. */
    fun add(ctx: Context, uri: Uri): String? = runCatching {
        var name = "font"
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) name = c.getString(i)
        }
        if (!name.lowercase().let { it.endsWith(".ttf") || it.endsWith(".otf") }) return@runCatching null
        val safe = name.replace(Regex("[^A-Za-z0-9А-Яа-яЁё._ -]"), "_")
        val f = File(dir(ctx), safe)
        ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        runCatching { Typeface.createFromFile(f) }.onFailure { f.delete() }.getOrThrow()
        cache.remove("f:$safe")
        "f:$safe"
    }.getOrNull()

    fun remove(ctx: Context, key: String) {
        File(dir(ctx), key.removePrefix("f:")).delete()
        cache.remove(key)
    }
}

/**
 * Фото на листе: имя файла, положение левого верхнего угла (dp), ширина (dp) и пропорция высота/ширина.
 * w <= 0 означает старую заметку, где фото шли подряд сверху: положение выберет редактор.
 */
data class Photo(val name: String, val x: Float = 0f, val y: Float = 0f, val w: Float = 0f, val ratio: Float = 0.75f)

/** Фотографии заметок хранятся в памяти приложения, уменьшенными до ~2000 px. */
object Media {
    private fun dir(ctx: Context) = File(ctx.filesDir, "images").apply { mkdirs() }
    fun file(ctx: Context, name: String) = File(dir(ctx), name)

    /** Читает и новый формат (объекты с положением), и старый (просто имена файлов). */
    fun photos(json: String): List<Photo> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { i ->
            when (val v = a.get(i)) {
                is String -> Photo(v)
                is JSONObject -> Photo(
                    v.getString("n"), v.optDouble("x", 0.0).toFloat(), v.optDouble("y", 0.0).toFloat(),
                    v.optDouble("w", 0.0).toFloat(), v.optDouble("r", 0.75).toFloat()
                )
                else -> null
            }
        }
    }.getOrDefault(emptyList())

    fun names(json: String): List<String> = photos(json).map { it.name }

    fun toJson(l: List<Photo>): String = JSONArray().also { a ->
        l.forEach {
            a.put(
                JSONObject().put("n", it.name).put("x", it.x.toDouble()).put("y", it.y.toDouble())
                    .put("w", it.w.toDouble()).put("r", it.ratio.toDouble())
            )
        }
    }.toString()

    /** Высота/ширина файла без загрузки картинки в память. */
    fun ratio(ctx: Context, name: String): Float = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file(ctx, name).path, o)
        if (o.outWidth > 0) o.outHeight.toFloat() / o.outWidth else 0.75f
    }.getOrDefault(0.75f)

    fun import(ctx: Context, uri: Uri): String? = runCatching {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2000) sample *= 2
        var bmp = cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("decode")
        val rot = cr.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
            }
        } ?: 0f
        if (rot != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
        val name = "${UUID.randomUUID()}.jpg"
        FileOutputStream(file(ctx, name)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        name
    }.getOrNull()
}
