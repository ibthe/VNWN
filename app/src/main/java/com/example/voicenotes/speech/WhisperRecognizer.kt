package com.example.voicenotes.speech

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * Локальное распознавание.
 *  - Русский ("ru"): GigaAM v2, модель, обученная именно на русской речи.
 *  - Грузинский ("ka"): Meta Omnilingual ASR (модель на 1600+ языков).
 *  - Английский ("en") и «Авто» (""): Whisper.
 * Перед распознаванием звук очищается и усиливается, Silero VAD режет запись на фразы.
 * Модели скачиваются из приложения (см. ModelStore) и лежат в filesDir/models/.
 */
class WhisperRecognizer(
    private val root: File,
    private val numThreads: Int = 4
) : SpeechRecognizer {

    private fun path(relative: String): String = File(root, relative).absolutePath

    private val lock = Any()
    private var recognizer: OfflineRecognizer? = null
    private var createdLanguage: String = ""

    /** "" = авто (Whisper), "ru" = русский (GigaAM), "ka" = грузинский (Omnilingual), "en" = английский (Whisper). */
    @Volatile
    var language: String = ""

    private fun vadConfig() = VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(
            model = path("models/silero_vad.onnx"),
            threshold = 0.35f,        // ниже порог: тихая речь не теряется
            minSilenceDuration = 0.6f, // короткие паузы внутри фразы не режут её
            minSpeechDuration = 0.2f,
            windowSize = 512,
            maxSpeechDuration = 25f   // модели принимают не больше ~30 с за раз
        ),
        sampleRate = SAMPLE_RATE,
        numThreads = 1,
        provider = "cpu",
        debug = false
    )

    private fun createRecognizer(lang: String): OfflineRecognizer {
        val pack = ModelPack.forLanguage(lang)
        check(ModelStore.installed(root, pack)) { "модель «${pack.title}» не скачана" }
        val modelConfig = if (lang == "ru") {
            OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = path("models/gigaam/model.int8.onnx")),
                tokens = path("models/gigaam/tokens.txt"),
                numThreads = numThreads,
                debug = false,
                provider = "cpu"
            )
        } else if (lang == "ka") {
            OfflineModelConfig(
                omnilingual = OfflineOmnilingualAsrCtcModelConfig(
                    model = path("models/omni/model.int8.onnx")
                ),
                tokens = path("models/omni/tokens.txt"),
                numThreads = numThreads,
                debug = false,
                provider = "cpu"
            )
        } else {
            OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = path("models/whisper/encoder.int8.onnx"),
                    decoder = path("models/whisper/decoder.int8.onnx"),
                    language = lang, // "" = автоопределение
                    task = "transcribe",
                    tailPaddings = 1000
                ),
                tokens = path("models/whisper/tokens.txt"),
                numThreads = numThreads,
                debug = false,
                provider = "cpu"
            )
        }
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = modelConfig
        )
        return OfflineRecognizer(assetManager = null, config = config)
    }

    /** Освобождает модель из памяти (например, перед удалением файлов). */
    fun release() = synchronized(lock) {
        recognizer?.release()
        recognizer = null
        createdLanguage = ""
    }

    /** Загружает модель заранее, чтобы первое распознавание не ждало. */
    fun warmUp() {
        getRecognizer()
    }

    private fun getRecognizer(): OfflineRecognizer = synchronized(lock) {
        val wanted = language
        var rec = recognizer
        if (rec != null && createdLanguage != wanted) {
            rec.release()
            recognizer = null
            rec = null
        }
        rec ?: createRecognizer(wanted).also {
            recognizer = it
            createdLanguage = wanted
        }
    }

    override suspend fun transcribe(samples: FloatArray, sampleRate: Int): String {
        if (samples.size < sampleRate / 4) return ""
        val audio = AudioPreprocess.enhance(samples, sampleRate)
        val rec = getRecognizer()
        val russian = synchronized(lock) { createdLanguage == "ru" }

        val vad = Vad(assetManager = null, config = vadConfig())
        val parts = mutableListOf<String>()
        try {
            val window = 512
            var pos = 0
            while (pos < audio.size) {
                val end = minOf(pos + window, audio.size)
                vad.acceptWaveform(audio.copyOfRange(pos, end))
                pos = end
                drain(vad, rec, audio, russian, parts)
            }
            vad.flush()
            drain(vad, rec, audio, russian, parts)
        } finally {
            vad.release()
        }
        return parts.joinToString(" ")
    }

    private fun drain(
        vad: Vad,
        rec: OfflineRecognizer,
        audio: FloatArray,
        russian: Boolean,
        out: MutableList<String>
    ) {
        while (!vad.empty()) {
            val segment = vad.front()
            vad.pop()
            // Берём чуть больше, чем нашёл VAD: иначе обрезаются начала и концы слов
            val from = maxOf(0, segment.start - PAD)
            val to = minOf(audio.size, segment.start + segment.samples.size + PAD)
            val text = decode(rec, audio.copyOfRange(from, to))
            if (text.isNotBlank()) out.add(if (russian) tidy(text) else text)
        }
    }

    private fun decode(rec: OfflineRecognizer, samples: FloatArray): String {
        val stream = rec.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            rec.decode(stream)
            return rec.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    /** GigaAM отдаёт текст без заглавных букв и точек: добавляем их к каждой фразе. */
    private fun tidy(text: String): String {
        val t = text.trim().replaceFirstChar { it.uppercase() }
        return if (t.last() in ".!?…") t else "$t."
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val PAD = 4000 // 0,25 с
    }
}
