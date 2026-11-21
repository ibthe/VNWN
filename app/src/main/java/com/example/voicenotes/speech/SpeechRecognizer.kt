package com.example.voicenotes.speech

/**
 * Единый интерфейс распознавания речи.
 * Позже сюда подключается Whisper (sherpa-onnx), а потом, возможно, и чеченская модель.
 */
interface SpeechRecognizer {
    /** samples: моно PCM, float в диапазоне [-1, 1]. */
    suspend fun transcribe(samples: FloatArray, sampleRate: Int): String
}
