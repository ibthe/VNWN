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
 * Качество: перед переводом текст чистится и режется на короткие фразы ([TextPreprocessor]),
 * декодирование идёт лучевым поиском (beam search), а не жадным.
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
     * Переводит текст построчно (абзацы и пустые строки сохраняются). Каждая строка очищается,
     * делится на предложения, длинные предложения ещё и на фразы.
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
                        val cleaned = TextPreprocessor.clean(line, from)
                        if (cleaned.isEmpty()) { lines.add(line); continue }
                        val out = StringBuilder()
                        for (chunk in TextPreprocessor.split(cleaned)) {
                            ensureActive()
                            var piece = translateSentence(chunk.text, src, dst)
                            if (chunk.continues) {
                                // фраза из середины предложения: вместо точки в конце ставим запятую/«;»
                                piece = piece.trimEnd('.', '!', '?', '…', ',', ';', ':', ' ') + chunk.separator
                            }
                            if (out.isNotEmpty()) out.append(' ')
                            out.append(piece)
                        }
                        lines.add(lead + out.toString())
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

    private fun directFloats(n: Int): FloatBuffer =
        ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private fun emptyFloatTensor(env: OrtEnvironment, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, directFloats(0), shape)

    private fun longTensor(env: OrtEnvironment, values: IntArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(values.size) { values[it].toLong() }), shape)

    /** Новый тензор [лучи, ...] из строк старого тензора с номерами [src] (перестановка лучей в кэше). */
    private fun reorderBeams(env: OrtEnvironment, t: OnnxTensor, src: IntArray): OnnxTensor {
        val shape = t.info.shape                       // [лучи, головы, длина, размер головы]
        val per = (shape[1] * shape[2] * shape[3]).toInt()
        val from = t.floatBuffer
        val out = directFloats(src.size * per)
        val tmp = FloatArray(per)
        for (i in src.indices) {
            from.position(src[i] * per)
            from.get(tmp)
            out.put(tmp)
        }
        out.rewind()
        val newShape = shape.clone().also { it[0] = src.size.toLong() }
        return OnnxTensor.createTensor(env, out, newShape)
    }

    private class Beam(val tokens: ArrayList<Int>, val score: Float)
    private class Finished(val tokens: List<Int>, val norm: Float)

    /**
     * Одно предложение: энкодер, затем лучевой поиск ([BEAM_SIZE] лучей) с кэшем ключей/значений.
     * Применяются штраф за повторы ([REPETITION_PENALTY]), запрет повторов триграмм
     * и нормализация оценки по длине ([LENGTH_PENALTY]).
     */
    private suspend fun translateSentence(sentence: String, srcLang: String, dstLang: String): String {
        val tok = tokenizer!!
        val env = env!!
        val enc = encoder!!
        val dec = decoder!!

        val srcIds = tok.encode(sentence, srcLang)
        val tgtLangId = tok.langId(dstLang)
        val maxNew = (srcIds.size * 2 + 16).coerceAtMost(256)
        val nb = BEAM_SIZE
        val topK = nb * 2
        val limit = tok.firstSpecialId

        val toClose = ArrayList<AutoCloseable>()
        var firstResult: OrtSession.Result? = null     // из него берутся ключи/значения энкодера
        var lastResult: OrtSession.Result? = null
        var pastTensors: List<OnnxTensor> = emptyList() // кэш декодера после перестановки лучей
        try {
            val srcLen = srcIds.size
            val encResult = enc.run(
                mapOf(
                    "input_ids" to longTensor(env, srcIds, longArrayOf(1, srcLen.toLong())).also { toClose.add(it) },
                    "attention_mask" to longTensor(env, IntArray(srcLen) { 1 }, longArrayOf(1, srcLen.toLong())).also { toClose.add(it) }
                )
            ).also { toClose.add(it) }
            val hidden1 = encResult.get(0) as OnnxTensor
            val dModel = hidden1.info.shape[2].toInt()

            // выход энкодера копируем на все лучи
            val hiddenArr = FloatArray(srcLen * dModel)
            hidden1.floatBuffer.also { it.rewind() }.get(hiddenArr)
            val rep = directFloats(nb * srcLen * dModel)
            repeat(nb) { rep.put(hiddenArr) }
            rep.rewind()
            val encHidden = OnnxTensor.createTensor(env, rep, longArrayOf(nb.toLong(), srcLen.toLong(), dModel.toLong()))
                .also { toClose.add(it) }
            val encMask = longTensor(env, IntArray(nb * srcLen) { 1 }, longArrayOf(nb.toLong(), srcLen.toLong()))
                .also { toClose.add(it) }

            val decInputNames = dec.inputNames
            val emptyPast = HashMap<String, OnnxTensor>()
            for (n in decInputNames) {
                if (!n.startsWith("past_key_values.")) continue
                emptyPast[n] = emptyFloatTensor(env, longArrayOf(nb.toLong(), heads.toLong(), 0, headDim.toLong()))
                    .also { toClose.add(it) }
            }

            var beams = ArrayList<Beam>()
            for (i in 0 until nb) beams.add(Beam(ArrayList(), if (i == 0) 0f else Float.NEGATIVE_INFINITY))
            val finished = ArrayList<Finished>()
            val row = FloatArray(limit)
            val topVal = FloatArray(topK)
            val topIdx = IntArray(topK)
            var pastMap = HashMap<String, OnnxTensor>()

            for (step in 0 until maxNew) {
                currentCoroutineContext().ensureActive()
                val useCache = step > 0
                val extra = ArrayList<OnnxTensor>()

                // вход декодера: на первом шаге [</s>, код языка], дальше последний токен каждого луча
                val feedLen = if (useCache) 1 else 2
                val feed = IntArray(nb * feedLen)
                for (b in 0 until nb) {
                    if (useCache) feed[b] = beams[b].tokens.last()
                    else { feed[b * 2] = tok.eosId; feed[b * 2 + 1] = tgtLangId }
                }
                val ids = longTensor(env, feed, longArrayOf(nb.toLong(), feedLen.toLong())).also { extra.add(it) }

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
                            inputs[name] = when {
                                !useCache -> emptyPast.getValue(name)
                                key.contains(".encoder.") -> firstResult!!.get("present.$key").get() as OnnxTensor
                                else -> pastMap.getValue(key)
                            }
                        }
                    }
                }

                val result = try {
                    dec.run(inputs)
                } finally {
                    extra.forEach { runCatching { it.close() } }
                }
                if (step == 0) firstResult = result
                lastResult = result
                // кэш прошлого шага использован: освобождаем
                pastTensors.forEach { runCatching { it.close() } }
                pastTensors = emptyList()

                val logitsT = result.get("logits").get() as OnnxTensor
                val lshape = logitsT.info.shape            // [лучи, длина, словарь]
                val seq = lshape[1].toInt()
                val vocab = lshape[2].toInt()
                val lbuf = logitsT.floatBuffer
                val n = minOf(limit, vocab)

                // кандидаты: (луч, токен, суммарная оценка)
                val candBeam = ArrayList<Int>()
                val candTok = ArrayList<Int>()
                val candScore = ArrayList<Float>()
                for (b in 0 until nb) {
                    val beam = beams[b]
                    if (beam.score == Float.NEGATIVE_INFINITY) continue
                    lbuf.position((b * seq + (seq - 1)) * vocab)
                    lbuf.get(row, 0, n)

                    // служебные токены запрещены; </s> разрешён, когда уже что-то переведено
                    for (i in 0 until 4) if (i != tok.eosId) row[i] = Float.NEGATIVE_INFINITY
                    if (beam.tokens.isEmpty()) row[tok.eosId] = Float.NEGATIVE_INFINITY
                    // штраф за повтор уже использованных токенов
                    if (REPETITION_PENALTY != 1f) {
                        for (t in beam.tokens.toSet()) {
                            if (t < n && row[t] != Float.NEGATIVE_INFINITY) {
                                row[t] = if (row[t] < 0) row[t] * REPETITION_PENALTY else row[t] / REPETITION_PENALTY
                            }
                        }
                    }
                    // запрет повтора триграмм
                    val tk = beam.tokens
                    if (tk.size >= 2) {
                        val a = tk[tk.size - 2]; val c = tk[tk.size - 1]
                        for (i in 0 until tk.size - 2) {
                            if (tk[i] == a && tk[i + 1] == c && tk[i + 2] < n) row[tk[i + 2]] = Float.NEGATIVE_INFINITY
                        }
                    }

                    // log-softmax и лучшие topK токенов за один просмотр словаря
                    var mx = Float.NEGATIVE_INFINITY
                    for (i in 0 until n) if (row[i] > mx) mx = row[i]
                    var sum = 0.0
                    java.util.Arrays.fill(topVal, Float.NEGATIVE_INFINITY)
                    for (i in 0 until n) {
                        val v = row[i]
                        if (v == Float.NEGATIVE_INFINITY) continue
                        sum += Math.exp((v - mx).toDouble())
                        if (v > topVal[topK - 1]) {
                            var j = topK - 1
                            while (j > 0 && topVal[j - 1] < v) { topVal[j] = topVal[j - 1]; topIdx[j] = topIdx[j - 1]; j-- }
                            topVal[j] = v; topIdx[j] = i
                        }
                    }
                    val lse = mx + Math.log(sum).toFloat()
                    for (j in 0 until topK) {
                        if (topVal[j] == Float.NEGATIVE_INFINITY) break
                        candBeam.add(b); candTok.add(topIdx[j]); candScore.add(beam.score + (topVal[j] - lse))
                    }
                }

                val order = candScore.indices.sortedByDescending { candScore[it] }
                val next = ArrayList<Beam>()
                val srcBeam = ArrayList<Int>()
                for ((rank, ci) in order.withIndex()) {
                    val b = candBeam[ci]; val t = candTok[ci]; val sc = candScore[ci]
                    if (t == tok.eosId) {
                        if (rank >= nb) continue           // </s> учитывается только среди лучших nb
                        val len = beams[b].tokens.size.coerceAtLeast(1)
                        finished.add(Finished(ArrayList(beams[b].tokens), sc / Math.pow(len.toDouble(), LENGTH_PENALTY.toDouble()).toFloat()))
                    } else {
                        next.add(Beam(ArrayList(beams[b].tokens).also { it.add(t) }, sc))
                        srcBeam.add(b)
                    }
                    if (next.size == nb) break
                }
                finished.sortByDescending { it.norm }
                while (finished.size > nb) finished.removeAt(finished.size - 1)

                if (next.isEmpty()) break
                val curLen = next[0].tokens.size
                val bestAlive = next.maxOf { it.score } / Math.pow(curLen.toDouble(), LENGTH_PENALTY.toDouble()).toFloat()
                if (finished.size >= nb && bestAlive <= finished.last().norm) break

                // дополняем до nb лучей (если кандидатов не хватило) и переставляем кэш под новые лучи
                while (next.size < nb) { next.add(Beam(ArrayList(next[0].tokens), Float.NEGATIVE_INFINITY)); srcBeam.add(srcBeam[0]) }
                beams = next
                val srcArr = srcBeam.toIntArray()
                val newPast = ArrayList<OnnxTensor>()
                val newMap = HashMap<String, OnnxTensor>()
                for (name in decInputNames) {
                    if (!name.startsWith("past_key_values.")) continue
                    val key = name.removePrefix("past_key_values.")
                    if (key.contains(".encoder.")) continue
                    val t = reorderBeams(env, result.get("present.$key").get() as OnnxTensor, srcArr)
                    newPast.add(t); newMap[key] = t
                }
                pastTensors = newPast
                pastMap = newMap
                if (lastResult !== firstResult) runCatching { lastResult?.close() }
            }

            // если ни один луч не закончился сам, берём лучший из живых
            if (finished.isEmpty()) {
                for (b in beams) if (b.score != Float.NEGATIVE_INFINITY && b.tokens.isNotEmpty()) {
                    finished.add(Finished(b.tokens, b.score / Math.pow(b.tokens.size.toDouble(), LENGTH_PENALTY.toDouble()).toFloat()))
                }
                finished.sortByDescending { it.norm }
            }
            return tok.decode(finished.firstOrNull()?.tokens ?: emptyList())
        } finally {
            pastTensors.forEach { runCatching { it.close() } }
            if (lastResult !== firstResult) runCatching { lastResult?.close() }
            runCatching { firstResult?.close() }
            toClose.asReversed().forEach { runCatching { it.close() } }
        }
    }

    fun close() {
        unloadJob?.cancel()
        scope.launch { mutex.withLock { release() } }
    }

    companion object {
        private const val IDLE_UNLOAD_MS = 90_000L

        /** Число лучей поиска. Больше — точнее, но медленнее (1 = жадный поиск). */
        private const val BEAM_SIZE = 4
        /** Оценка делится на (длина в токенах)^LENGTH_PENALTY; >1 поощряет более длинные переводы. */
        private const val LENGTH_PENALTY = 1.0f
        /** Штраф за повтор токена; 1.0 — выключен. Слишком большой портит имена и служебные слова. */
        private const val REPETITION_PENALTY = 1.1f

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
