package com.example.voicenotes.translate

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** Модель перевода ещё не скачана (меню → «Модели распознавания» → «Перевод (NLLB-200)»). */
class NllbNotInstalledException :
    Exception("Модель перевода не скачана: меню → «Модели распознавания» → «Перевод (NLLB-200, 600M)»")

/** Цепочка «Класс: сообщение ← причина», чтобы по тосту было видно, что именно сломалось. */
fun Throwable.describeChain(): String {
    val parts = ArrayList<String>()
    var t: Throwable? = this
    var depth = 0
    while (t != null && depth < 4) {
        parts.add(t.javaClass.simpleName + (t.message?.let { ": $it" } ?: ""))
        t = t.cause
        depth++
    }
    return parts.joinToString(" ← ")
}

/**
 * Подробный отчёт о сбое перевода: цепочка ошибок, стек, наличие классов и библиотек ONNX Runtime.
 * Показывается в окне с кнопкой «Копировать», чтобы его можно было прислать целиком.
 */
fun diagnoseOrt(context: android.content.Context, t: Throwable?): String {
    val sb = StringBuilder()
    if (t != null) sb.appendLine("ОШИБКА: ${t.describeChain()}")
    var cur: Throwable? = t
    var level = 0
    while (cur != null && level < 3) {
        sb.appendLine("-- стек (уровень $level, ${cur.javaClass.name}):")
        cur.stackTrace.take(8).forEach { sb.appendLine("   at $it") }
        cur = cur.cause
        level++
    }
    val cl = TextTranslator::class.java.classLoader
    sb.appendLine("-- классы:")
    for (name in listOf("ai.onnxruntime.OrtEnvironment", "ai.onnxruntime.OnnxRuntime",
        "ai.onnxruntime.OrtSession", "ai.onnxruntime.OnnxTensor", "ai.onnxruntime.OrtException")) {
        val r = try {
            Class.forName(name, false, cl); "есть"
        } catch (e: Throwable) { "НЕТ: ${e.javaClass.simpleName}: ${e.message}" }
        sb.appendLine("   $name: $r")
    }
    val init = try {
        Class.forName("ai.onnxruntime.OnnxRuntime", true, cl); "ок"
    } catch (e: Throwable) { e.describeChain() }
    sb.appendLine("   инициализация OnnxRuntime: $init")
    sb.appendLine("-- нативные библиотеки:")
    for (lib in listOf("onnxruntime", "onnxruntime4j_jni")) {
        val r = try { System.loadLibrary(lib); "загружена" } catch (e: Throwable) { e.describeChain() }
        sb.appendLine("   $lib: $r")
    }
    val dir = java.io.File(context.applicationInfo.nativeLibraryDir)
    val files = dir.listFiles()?.filter { it.name.contains("onnx") || it.name.contains("sherpa") }
    sb.appendLine("-- ${dir.path}:")
    if (files.isNullOrEmpty()) sb.appendLine("   (нет файлов onnx/sherpa; возможно, библиотеки не распакованы)")
    else files.sortedBy { it.name }.forEach { sb.appendLine("   ${it.name} ${it.length() / 1024} КБ") }
    return sb.toString()
}

/**
 * Перевод текста на устройстве моделью NLLB-200 (distilled, 600M, int8) через ONNX Runtime.
 * Текст никуда не отправляется. Языки: "ru", "en", "ka", перевод идёт напрямую между любой парой.
 *
 * Модель тяжёлая (около 1 ГБ в памяти), поэтому грузится при первом переводе
 * и выгружается из памяти после [IDLE_UNLOAD_MS] простоя.
 */
class TextTranslator(modelsRoot: File) {
    private val dir = File(modelsRoot, "models/nllb")
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var unloadJob: Job? = null

    private var env: OrtEnvironment? = null
    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null
    private var tokenizer: NllbTokenizer? = null

    // параметры кэша внимания декодера (читаются из самой модели)
    private var heads = 16
    private var headDim = 64

    fun isInstalled(): Boolean = listOf("encoder.int8.onnx", "decoder.int8.onnx", "vocab.tsv", "added.tsv")
        .all { File(dir, it).let { f -> f.isFile && f.length() > 0 } }

    /**
     * Переводит текст построчно (абзацы и пустые строки сохраняются), внутри строки — по предложениям.
     * Параметр [online] оставлен для совместимости со старым кодом и игнорируется: всё работает офлайн.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun translate(text: String, from: String, to: String, online: Boolean = false): String {
        if (!isInstalled()) throw NllbNotInstalledException()
        val src = nllbCode(from)
        val dst = nllbCode(to)
        return withContext(Dispatchers.Default) {
            mutex.withLock {
                unloadJob?.cancel()
                try {
                    ensureLoaded()
                    val lines = ArrayList<String>()
                    for (line in text.split("\n")) {
                        ensureActive()
                        if (line.isBlank()) { lines.add(line); continue }
                        val lead = line.takeWhile { it == ' ' || it == '\t' }
                        val out = ArrayList<String>()
                        for (sentence in splitSentences(line.trim())) {
                            ensureActive()
                            out.add(translateSentence(sentence, src, dst))
                        }
                        lines.add(lead + out.joinToString(" "))
                    }
                    lines.joinToString("\n")
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    android.util.Log.e("NLLB", "translate failed", t)
                    throw t
                } finally {
                    scheduleUnload()
                }
            }
        }
    }

    private fun splitSentences(line: String): List<String> {
        val parts = line.split(Regex("(?<=[.!?…])\\s+")).filter { it.isNotBlank() }
        // слишком длинные предложения режем по запятым, чтобы не терять качество
        val out = ArrayList<String>()
        for (p in parts) {
            if (p.length <= 400) { out.add(p); continue }
            var cur = StringBuilder()
            for (piece in p.split(Regex("(?<=[,;:])\\s+"))) {
                if (cur.isNotEmpty() && cur.length + piece.length + 1 > 300) {
                    out.add(cur.toString()); cur = StringBuilder()
                }
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(piece)
            }
            if (cur.isNotEmpty()) out.add(cur.toString())
        }
        return out
    }

    private fun scheduleUnload() {
        unloadJob?.cancel()
        unloadJob = scope.launch {
            delay(IDLE_UNLOAD_MS)
            mutex.withLock { release() }
        }
    }

    private fun ensureLoaded() {
        if (encoder != null && decoder != null && tokenizer != null) return
        release()
        val e = OrtEnvironment.getEnvironment()
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        fun options() = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            setMemoryPatternOptimization(false)
        }
        env = e
        tokenizer = NllbTokenizer(dir)
        encoder = e.createSession(File(dir, "encoder.int8.onnx").absolutePath, options())
        val dec = e.createSession(File(dir, "decoder.int8.onnx").absolutePath, options())
        decoder = dec
        // число голов и размер головы берём из формы входа past_key_values.*
        for ((name, info) in dec.inputInfo) {
            if (!name.startsWith("past_key_values.")) continue
            val shape = (info.info as? TensorInfo)?.shape ?: continue
            if (shape.size == 4) {
                if (shape[1] > 0) heads = shape[1].toInt()
                if (shape[3] > 0) headDim = shape[3].toInt()
            }
            break
        }
    }

    private fun release() {
        runCatching { encoder?.close() }
        runCatching { decoder?.close() }
        encoder = null
        decoder = null
        tokenizer = null
    }

    private fun emptyFloatTensor(env: OrtEnvironment, shape: LongArray): OnnxTensor {
        val buf = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()).asFloatBuffer()
        return OnnxTensor.createTensor(env, buf, shape)
    }

    private fun longTensor(env: OrtEnvironment, values: IntArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(values.size) { values[it].toLong() }), shape)

    /** Одно предложение: энкодер, затем жадное декодирование с кэшем ключей/значений. */
    private suspend fun translateSentence(sentence: String, srcLang: String, dstLang: String): String {
        val tok = tokenizer!!
        val env = env!!
        val enc = encoder!!
        val dec = decoder!!

        val srcIds = tok.encode(sentence, srcLang)
        val tgtLangId = tok.langId(dstLang)
        val maxNew = (srcIds.size * 2 + 16).coerceAtMost(256)

        val toClose = ArrayList<AutoCloseable>()
        var firstResult: OrtSession.Result? = null   // из него берутся ключи/значения энкодера
        var prevResult: OrtSession.Result? = null    // из него берутся ключи/значения декодера
        try {
            val srcShape = longArrayOf(1, srcIds.size.toLong())
            val inputIds = longTensor(env, srcIds, srcShape).also { toClose.add(it) }
            val encMask = longTensor(env, IntArray(srcIds.size) { 1 }, srcShape).also { toClose.add(it) }

            val encResult = enc.run(mapOf("input_ids" to inputIds, "attention_mask" to encMask))
                .also { toClose.add(it) }
            val encHidden = encResult.get(0) as OnnxTensor

            val decInputNames = dec.inputNames
            val emptyPast = HashMap<String, OnnxTensor>()
            for (n in decInputNames) {
                if (!n.startsWith("past_key_values.")) continue
                emptyPast[n] = emptyFloatTensor(env, longArrayOf(1, heads.toLong(), 0, headDim.toLong()))
                    .also { toClose.add(it) }
            }

            val generated = ArrayList<Int>()
            // декодер начинается с </s>, затем принудительно ставим код языка перевода
            var feed = intArrayOf(tok.eosId, tgtLangId)
            val seen = HashMap<Long, MutableSet<Int>>()  // запрет повторов триграмм

            for (step in 0 until maxNew) {
                currentCoroutineContext().ensureActive()
                val useCache = step > 0
                val extra = ArrayList<OnnxTensor>()
                val ids = longTensor(env, feed, longArrayOf(1, feed.size.toLong())).also { extra.add(it) }
                val inputs = HashMap<String, OnnxTensor>()
                for (name in decInputNames) {
                    when {
                        name == "input_ids" -> inputs[name] = ids
                        name == "encoder_hidden_states" -> inputs[name] = encHidden
                        name == "encoder_attention_mask" -> inputs[name] = encMask
                        name == "use_cache_branch" -> {
                            val t = OnnxTensor.createTensor(env, booleanArrayOf(useCache))
                            extra.add(t)
                            inputs[name] = t
                        }
                        name.startsWith("past_key_values.") -> {
                            val key = name.removePrefix("past_key_values.")
                            inputs[name] = if (!useCache) {
                                emptyPast.getValue(name)
                            } else {
                                val holder = if (key.contains(".encoder.")) firstResult!! else prevResult!!
                                holder.get("present.$key").get() as OnnxTensor
                            }
                        }
                    }
                }

                val result = try {
                    dec.run(inputs)
                } finally {
                    extra.forEach { runCatching { it.close() } }
                }

                val logitsT = result.get("logits").get() as OnnxTensor
                val shape = logitsT.info.shape            // [1, seq, vocab]
                val vocab = shape[2].toInt()
                val seq = shape[1].toInt()
                val next = pickToken(logitsT.floatBuffer, (seq - 1) * vocab, vocab, tok, generated, seen)

                if (step == 0) {
                    firstResult = result
                } else if (prevResult !== firstResult) {
                    runCatching { prevResult?.close() }
                }
                prevResult = result

                if (next == tok.eosId) break
                generated.add(next)
                if (generated.size >= 3) {
                    val n = generated.size
                    val k = (generated[n - 3].toLong() shl 32) or generated[n - 2].toLong()
                    seen.getOrPut(k) { HashSet() }.add(generated[n - 1])
                }
                feed = intArrayOf(next)
            }
            return tok.decode(generated)
        } finally {
            if (prevResult !== firstResult) runCatching { prevResult?.close() }
            runCatching { firstResult?.close() }
            toClose.asReversed().forEach { runCatching { it.close() } }
        }
    }

    /** Жадный выбор с запретом служебных токенов и повтора уже встречавшихся триграмм. */
    private fun pickToken(
        buf: FloatBuffer, base: Int, vocab: Int, tok: NllbTokenizer,
        generated: List<Int>, seen: Map<Long, Set<Int>>
    ): Int {
        val banned: Set<Int>? = if (generated.size >= 2) {
            val n = generated.size
            seen[(generated[n - 2].toLong() shl 32) or generated[n - 1].toLong()]
        } else null
        val allowEos = generated.isNotEmpty()
        var best = -1
        var bestV = Float.NEGATIVE_INFINITY
        val limit = minOf(vocab, tok.firstSpecialId)
        for (i in 0 until limit) {
            if (i == tok.eosId) {
                if (!allowEos) continue
            } else if (i < 4) {
                continue
            }
            if (banned != null && i in banned) continue
            val v = buf.get(base + i)
            if (v > bestV) { bestV = v; best = i }
        }
        return if (best < 0) tok.eosId else best
    }

    fun close() {
        unloadJob?.cancel()
        scope.launch { mutex.withLock { release() } }
    }

    companion object {
        private const val IDLE_UNLOAD_MS = 90_000L

        /** Язык по преобладающему письму: грузинский, русский (кириллица) или английский. */
        fun detect(text: String): String {
            var ka = 0; var ru = 0; var en = 0
            for (ch in text) when (ch.code) {
                in 0x10A0..0x10FF, in 0x2D00..0x2D2F -> ka++
                in 0x0400..0x04FF -> ru++
                in 0x41..0x5A, in 0x61..0x7A -> en++
            }
            return when {
                ka >= ru && ka >= en && ka > 0 -> "ka"
                ru >= en && ru > 0 -> "ru"
                else -> "en"
            }
        }

        private fun nllbCode(lang: String): String = when (lang) {
            "ru" -> "rus_Cyrl"
            "en" -> "eng_Latn"
            "ka" -> "kat_Geor"
            else -> error("Язык не поддерживается: $lang")
        }
    }
}
