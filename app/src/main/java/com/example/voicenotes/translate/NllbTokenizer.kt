package com.example.voicenotes.translate

import java.io.File
import java.text.Normalizer

/**
 * Токенизатор NLLB-200: SentencePiece Unigram (поиск лучшей разбивки алгоритмом Витерби).
 *
 * Поддерживает оба варианта SentencePiece (Unigram и BPE). Читает два компактных файла, которые делает workflow «Publish models» из tokenizer.json:
 *  - vocab.tsv: строка N = токен с id N, формат «оценка<TAB>токен»;
 *  - added.tsv: «id<TAB>токен» для служебных токенов и кодов языков (rus_Cyrl, eng_Latn, kat_Geor…).
 *
 * Нормализация упрощённая (NFKC + сжатие пробелов): для русского, английского и грузинского
 * она совпадает с нормализацией оригинального SentencePiece.
 */
class NllbTokenizer(dir: File) {
    private val pieceToId = HashMap<String, Int>(300_000)
    private val scores: FloatArray
    private val specials = HashMap<String, Int>()
    private var maxPieceLen = 1
    private val unkScore: Float

    /** true, если в SentencePiece-модели использован BPE (метка «#bpe» в added.tsv), иначе Unigram. */
    private val bpe: Boolean

    val eosId: Int
    val unkId: Int
    val padId: Int
    /** Все id начиная с этого (коды языков, <mask>) никогда не должны попадать в перевод. */
    val firstSpecialId: Int

    init {
        val sc = ArrayList<Float>(260_000)
        var minScore = 0f
        File(dir, "vocab.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
            var id = 0
            for (line in lines) {
                val tab = line.indexOf('\t')
                if (tab > 0) {
                    val score = line.substring(0, tab).toFloatOrNull() ?: 0f
                    val piece = line.substring(tab + 1)
                    sc.add(score)
                    if (id >= 4 && piece.isNotEmpty()) {
                        pieceToId.putIfAbsent(piece, id)
                        if (piece.length > maxPieceLen) maxPieceLen = piece.length
                        if (score < minScore) minScore = score
                    }
                } else {
                    sc.add(0f)
                }
                id++
            }
        }
        scores = sc.toFloatArray()
        unkScore = minScore - 10f

        File(dir, "added.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val id = line.substring(0, tab).toIntOrNull() ?: continue
                specials[line.substring(tab + 1)] = id
            }
        }
        bpe = specials.containsKey("#bpe")
        eosId = specials["</s>"] ?: 2
        unkId = specials["<unk>"] ?: 3
        padId = specials["<pad>"] ?: 1
        firstSpecialId = scores.size.coerceAtLeast(256_000)
    }

    /** id кода языка NLLB (например «rus_Cyrl»). */
    fun langId(code: String): Int = specials[code] ?: error("В словаре нет языка $code")

    /** Для NLLB: [код исходного языка] + токены + </s>. */
    fun encode(text: String, srcLang: String): IntArray {
        val out = ArrayList<Int>()
        out.add(langId(srcLang))
        val norm = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .replace(Regex("\\s+"), " ")
            .trim()
        if (norm.isNotEmpty()) {
            for (word in norm.split(' ')) {
                if (word.isNotEmpty()) encodeWord("\u2581$word", out)
            }
        }
        out.add(eosId)
        return out.toIntArray()
    }

    /** BPE как в SentencePiece: пока можно, сливаем соседнюю пару с наибольшей оценкой. */
    private fun encodeWordBpe(word: String, out: MutableList<Int>) {
        val sym = ArrayList<String>()
        var i = 0
        while (i < word.length) {
            val n = if (Character.isHighSurrogate(word[i]) && i + 1 < word.length) 2 else 1
            sym.add(word.substring(i, i + n))
            i += n
        }
        while (sym.size > 1) {
            var bestIdx = -1
            var bestScore = Float.NEGATIVE_INFINITY
            for (j in 0 until sym.size - 1) {
                val id = pieceToId[sym[j] + sym[j + 1]] ?: continue
                val sc = scores[id]
                if (sc > bestScore) { bestScore = sc; bestIdx = j }
            }
            if (bestIdx < 0) break
            sym[bestIdx] = sym[bestIdx] + sym[bestIdx + 1]
            sym.removeAt(bestIdx + 1)
        }
        for (x in sym) out.add(pieceToId[x] ?: unkId)
    }

    /** Лучшая разбивка одного слова (с ведущим «▁») по оценкам словаря. */
    private fun encodeWord(word: String, out: MutableList<Int>) {
        if (bpe) { encodeWordBpe(word, out); return }
        val n = word.length
        val best = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val from = IntArray(n + 1) { -1 }
        val tokenAt = IntArray(n + 1) { unkId }
        best[0] = 0f
        for (i in 0 until n) {
            if (best[i] == Float.NEGATIVE_INFINITY) continue
            // не режем суррогатные пары посередине
            val maxEnd = minOf(n, i + maxPieceLen)
            var end = i + 1
            var foundSingle = false
            while (end <= maxEnd) {
                if (end < n && Character.isLowSurrogate(word[end])) { end++; continue }
                val id = pieceToId[word.substring(i, end)]
                if (id != null) {
                    val s = best[i] + scores[id]
                    if (s > best[end]) { best[end] = s; from[end] = i; tokenAt[end] = id }
                    if (end == i + 1 || (end == i + 2 && Character.isHighSurrogate(word[i]))) foundSingle = true
                }
                end++
            }
            if (!foundSingle) {
                // символа нет в словаре: ставим <unk> на один символ
                val e = if (Character.isHighSurrogate(word[i]) && i + 2 <= n) i + 2 else i + 1
                val s = best[i] + unkScore
                if (s > best[e]) { best[e] = s; from[e] = i; tokenAt[e] = unkId }
            }
        }
        if (best[n] == Float.NEGATIVE_INFINITY) { out.add(unkId); return }
        val rev = ArrayList<Int>()
        var pos = n
        while (pos > 0) {
            rev.add(tokenAt[pos])
            pos = from[pos]
        }
        for (k in rev.indices.reversed()) out.add(rev[k])
    }

    private val idToPiece: Array<String?> by lazy {
        val arr = arrayOfNulls<String>(scores.size)
        for ((p, id) in pieceToId) if (id < arr.size) arr[id] = p
        arr
    }

    /** Склеивает токены в текст; служебные токены пропускаются. */
    fun decode(ids: List<Int>): String {
        val sb = StringBuilder()
        for (id in ids) {
            if (id < 4 || id >= firstSpecialId) continue
            val p = idToPiece.getOrNull(id) ?: continue
            sb.append(p)
        }
        return sb.toString().replace('\u2581', ' ').trim()
    }
}
