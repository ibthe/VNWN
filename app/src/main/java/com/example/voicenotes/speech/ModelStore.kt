package com.example.voicenotes.speech

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Один файл модели: [remote] - имя в релизе `models`, [local] - путь внутри filesDir. */
class ModelItem(val remote: String, val local: String, val unzip: Boolean = false)

private val VAD = ModelItem("silero_vad.onnx", "models/silero_vad.onnx")

/** Наборы моделей, которые пользователь скачивает из приложения. */
enum class ModelPack(val title: String, val sizeMb: Int, val items: List<ModelItem>) {
    RU(
        "Русский (GigaAM)", 230,
        listOf(
            VAD,
            ModelItem("gigaam-model.int8.onnx", "models/gigaam/model.int8.onnx"),
            ModelItem("gigaam-tokens.txt", "models/gigaam/tokens.txt")
        )
    ),
    KA(
        "Грузинский (Omnilingual)", 350,
        listOf(
            VAD,
            ModelItem("omni-model.int8.onnx", "models/omni/model.int8.onnx"),
            ModelItem("omni-tokens.txt", "models/omni/tokens.txt")
        )
    ),
    EN(
        "English и «Авто» (Whisper)", 360,
        listOf(
            VAD,
            ModelItem("whisper-encoder.int8.onnx", "models/whisper/encoder.int8.onnx"),
            ModelItem("whisper-decoder.int8.onnx", "models/whisper/decoder.int8.onnx"),
            ModelItem("whisper-tokens.txt", "models/whisper/tokens.txt")
        )
    ),
    NLLB(
        "Перевод (NLLB-200, 600M)", 900,
        listOf(
            ModelItem("nllb-encoder.int8.onnx", "models/nllb/encoder.int8.onnx"),
            ModelItem("nllb-decoder.int8.onnx", "models/nllb/decoder.int8.onnx"),
            ModelItem("nllb-vocab.tsv", "models/nllb/vocab.tsv"),
            ModelItem("nllb-added.tsv", "models/nllb/added.tsv")
        )
    ),
    WAKE_RU(
        "Голосовой запуск: русские фразы (Vosk)", 45,
        listOf(ModelItem("vosk-ru.zip", "vosk-ru", unzip = true))
    ),
    WAKE_EN(
        "Голосовой запуск: английские фразы (Vosk)", 40,
        listOf(ModelItem("vosk-en.zip", "vosk-en", unzip = true))
    );

    companion object {
        /** Какой набор нужен для распознавания речи на этом языке. */
        fun forLanguage(lang: String): ModelPack = when (lang) {
            "ru" -> RU
            "ka" -> KA
            else -> EN
        }
    }
}

/**
 * Скачивание моделей после установки: так APK остаётся маленьким.
 * Файлы берутся из релиза `models` этого репозитория (его создаёт workflow «Publish models»).
 * Загрузка продолжается с того места, где оборвалась (файлы `.part`).
 */
object ModelStore {
    const val BASE_URL = "https://github.com/ibthe/VNWN/releases/download/models"

    private class NotFound(message: String) : Exception(message)

    fun installed(root: File, pack: ModelPack): Boolean = pack.items.all { ready(root, it) }

    private fun ready(root: File, item: ModelItem): Boolean =
        if (item.unzip) File(File(root, item.local), ".ready").exists()
        else File(root, item.local).let { it.isFile && it.length() > 0 }

    /** Удаляет набор с телефона (маленькая VAD-модель остаётся: она общая). */
    fun delete(root: File, pack: ModelPack) {
        for (item in pack.items) {
            if (item.remote == VAD.remote) continue
            File(root, item.local).deleteRecursively()
        }
    }

    suspend fun download(root: File, pack: ModelPack, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.IO) {
            val total = pack.sizeMb * 1_000_000L
            val need = total * 12 / 10
            if (root.usableSpace < need) {
                throw IOException("Недостаточно места: нужно около ${need / 1_000_000} МБ")
            }
            var done = 0L
            var last = -1f
            val report: (Long) -> Unit = { delta ->
                done += delta
                val f = (done.toFloat() / total).coerceIn(0f, 0.99f)
                if (f - last >= 0.005f) {
                    last = f
                    onProgress(f)
                }
            }
            for (item in pack.items) {
                if (ready(root, item)) continue
                if (item.unzip) fetchZip(root, item, report) else fetchFile(root, item, report)
            }
            onProgress(1f)
        }

    private suspend fun fetchFile(root: File, item: ModelItem, report: (Long) -> Unit) {
        val target = File(root, item.local)
        val part = File(root, item.local + ".part")
        part.parentFile?.mkdirs()
        httpGet("$BASE_URL/${item.remote}", part, report)
        target.delete()
        if (!part.renameTo(target)) throw IOException("не удалось сохранить ${item.local}")
    }

    private suspend fun fetchZip(root: File, item: ModelItem, report: (Long) -> Unit) {
        val zip = File(root, item.remote)
        val part = File(root, item.remote + ".part")
        httpGet("$BASE_URL/${item.remote}", part, report)
        zip.delete()
        if (!part.renameTo(zip)) throw IOException("не удалось сохранить ${item.remote}")

        val dest = File(root, item.local)
        val tmp = File(root, item.local + ".tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        unzipStripTop(zip, tmp)
        File(tmp, ".ready").writeText("ok")
        dest.deleteRecursively()
        if (!tmp.renameTo(dest)) throw IOException("не удалось распаковать ${item.remote}")
        zip.delete()
    }

    /** Распаковывает zip, отбрасывая верхнюю папку (у Vosk все файлы лежат в одной папке). */
    private fun unzipStripTop(zip: File, dest: File) {
        val base = dest.canonicalPath + File.separator
        ZipInputStream(FileInputStream(zip).buffered()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val rel = entry.name.substringAfter('/', "")
                if (rel.isNotEmpty()) {
                    val out = File(dest, rel)
                    if (!out.canonicalPath.startsWith(base)) throw IOException("повреждённый архив")
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { zin.copyTo(it) }
                    }
                }
                entry = zin.nextEntry
            }
        }
    }

    private suspend fun httpGet(url: String, part: File, report: (Long) -> Unit) {
        var attempt = 0
        while (true) {
            try {
                httpOnce(url, part, report)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                attempt++
                if (attempt >= 4) throw e
                delay(1500L * attempt)
            }
        }
    }

    private suspend fun httpOnce(url: String, part: File, report: (Long) -> Unit) {
        val have = if (part.exists()) part.length() else 0L
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            conn.connect()
            val code = conn.responseCode
            when (code) {
                200, 206 -> Unit
                404 -> throw NotFound(
                    "файл не найден на GitHub. Запустите workflow «Publish models» " +
                        "и убедитесь, что репозиторий публичный"
                )
                416 -> {
                    part.delete() // докачивать нечего или файл битый: начнём заново
                    throw IOException("повторная загрузка")
                }
                else -> throw IOException("ошибка сервера: HTTP $code")
            }
            val append = code == 206
            if (!append) part.delete()
            val rest = conn.contentLengthLong
            val expected = if (rest >= 0) rest + (if (append) have else 0L) else -1L
            if (append) report(have)

            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        report(n.toLong())
                    }
                }
            }
            if (expected >= 0 && part.length() != expected) {
                throw IOException("файл скачан не полностью")
            }
        } finally {
            conn.disconnect()
        }
    }
}
