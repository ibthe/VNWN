package com.example.voicenotes.translate

/**
 * Лёгкая очистка текста перед подачей в NLLB и разбиение длинных предложений на короткие фразы.
 *
 * Правила намеренно консервативные: они убирают то, что почти наверняка мусор
 * (дубли знаков препинания, звуки-паразиты «э-э», запинки «я я пошёл», сленговые сокращения),
 * но не трогают запятые, несущие смысл, и не переписывают грамматику.
 */
object TextPreprocessor {
    /** Предложения длиннее этого числа слов режутся на фразы по «, а», «, но», «, чтобы» и т. п. */
    private const val MAX_WORDS = 20
    /** Фраза не должна получаться короче этого числа слов: иначе перевод теряет контекст. */
    private const val MIN_CHUNK_WORDS = 4

    class Chunk(val text: String, val continues: Boolean, val separator: String = ",")

    private val ruSlang = mapOf(
        "щас" to "сейчас", "ща" to "сейчас", "чё" to "что", "чо" to "что", "че" to "что",
        "норм" to "нормально", "спс" to "спасибо", "пжлст" to "пожалуйста", "плиз" to "пожалуйста",
        "кст" to "кстати", "тока" to "только", "хз" to "не знаю", "мб" to "может быть",
        "сорян" to "извините", "ваще" to "вообще", "чтоль" to "что ли", "здрасьте" to "здравствуйте",
        "оч" to "очень", "ладн" to "ладно", "каво" to "кого", "чево" to "чего", "тыща" to "тысяча"
    )
    private val enSlang = mapOf(
        "gonna" to "going to", "wanna" to "want to", "gotta" to "have to", "kinda" to "kind of",
        "u" to "you", "ur" to "your", "thx" to "thanks", "pls" to "please", "plz" to "please",
        "idk" to "I don't know", "btw" to "by the way", "dunno" to "don't know", "lemme" to "let me",
        "gimme" to "give me", "cuz" to "because", "tho" to "though", "ya" to "you"
    )

    // «э-э», «эм», «ммм», «хм», «um», «uh»: звуки-паразиты, которые модель принимает за слова
    private val fillers = Regex(
        "(?<![\\p{L}\\p{N}])(?:э+[-\\s]?э*м*|эм+|м{2,}|м-м+|хм+|у-у+|um+|uh+|er+m?|hmm+)(?![\\p{L}\\p{N}])[,]?\\s*",
        RegexOption.IGNORE_CASE
    )
    private val leadingNu = Regex("^\\s*ну[,\\s]+", RegexOption.IGNORE_CASE)

    // запинка: короткое слово подряд дважды («я я пошёл», «the the»)
    private val stutter = Regex("(?<![\\p{L}\\p{N}])(\\p{L}{1,3})(?:\\s+\\1)+(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)

    private val splitPoints = Regex(
        "(?<=;)\\s+|(?<=,)\\s+(?=(?:а|но|однако|чтобы|потому что|хотя|зато|but|because|although|so that|however|while|" +
            "მაგრამ|რადგან|თუმცა|რათა)\\s)",
        RegexOption.IGNORE_CASE
    )

    /** Очищает одну строку исходного текста. [lang] — "ru", "en" или "ka". */
    fun clean(line: String, lang: String): String {
        var s = line.replace('\u00A0', ' ')
        s = s.replace(fillers, "")
        s = s.replace(leadingNu, "")
        s = s.replace(stutter) { it.groupValues[1] }
        s = replaceSlang(s, lang)

        // знаки препинания
        s = s.replace(Regex("(?:\\.{2,}|…+)\\s*(?=\\p{Ll})"), ", ")   // «ну... не знаю» → «ну, не знаю»
        s = s.replace(Regex("\\.{2,}|…+"), ".")
        s = s.replace(Regex("([!?])[!?]+"), "$1")
        s = s.replace(Regex("([,;:])\\1+"), "$1")
        s = s.replace(Regex("[,;]\\s*([,;])"), "$1")
        s = s.replace(Regex("\\s+([,.;:!?])"), "$1")
        s = s.replace(Regex("[,;:]+\\s*([.!?])"), "$1")
        s = s.replace(Regex("([,;:])(?=\\p{L})"), "$1 ")
        s = s.replace(Regex("^[\\s,;:]+"), "")
        s = s.replace(Regex("\\s+"), " ").trim()
        return s
    }

    private fun replaceSlang(text: String, lang: String): String {
        val table = when (lang) {
            "ru" -> ruSlang
            "en" -> enSlang
            else -> return text
        }
        return Regex("(?<![\\p{L}\\p{N}])\\p{L}+(?![\\p{L}\\p{N}])").replace(text) { m ->
            val w = m.value
            val rep = table[w.lowercase()] ?: return@replace w
            if (w.length > 1 && w.all { it.isUpperCase() }) rep.uppercase()
            else if (w[0].isUpperCase()) rep.replaceFirstChar { it.uppercase() }
            else rep
        }
    }

    /**
     * Делит строку на предложения, а слишком длинные предложения (больше [MAX_WORDS] слов)
     * ещё и на фразы по точкам с запятой и запятым перед союзами.
     */
    fun split(line: String): List<Chunk> {
        val out = ArrayList<Chunk>()
        for (sentence in line.split(Regex("(?<=[.!?…])\\s+")).filter { it.isNotBlank() }) {
            val s = capitalize(sentence.trim())
            if (wordCount(s) <= MAX_WORDS) { out.add(Chunk(s, false)); continue }
            val pieces = s.split(splitPoints).filter { it.isNotBlank() }
            // склеиваем слишком короткие куски с соседями
            val merged = ArrayList<String>()
            var cur = StringBuilder()
            for (piece in pieces) {
                if (cur.isNotEmpty() && wordCount(cur.toString()) >= MIN_CHUNK_WORDS && wordCount(piece) >= MIN_CHUNK_WORDS) {
                    merged.add(cur.toString()); cur = StringBuilder()
                }
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(piece.trim())
            }
            if (cur.isNotEmpty()) {
                if (merged.isNotEmpty() && wordCount(cur.toString()) < MIN_CHUNK_WORDS) {
                    merged[merged.size - 1] = merged.last() + " " + cur
                } else merged.add(cur.toString())
            }
            for ((i, m) in merged.withIndex()) {
                if (i == merged.size - 1) {
                    out.add(Chunk(m.trim(), false))
                } else {
                    val sep = if (m.trimEnd().endsWith(";")) ";" else ","
                    out.add(Chunk(m.trimEnd(',', ';', ':', ' '), true, sep))
                }
            }
        }
        return out
    }

    private fun wordCount(s: String) = s.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    private fun capitalize(s: String): String =
        if (s.isNotEmpty() && s[0].isLowerCase()) s.replaceFirstChar { it.uppercase() } else s
}
