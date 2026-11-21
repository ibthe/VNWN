package com.example.voicenotes.speech

import kotlin.math.PI

/**
 * Подготовка записи к распознаванию, чтобы тихая и «грязная» речь распознавалась лучше:
 * 1) срезаем низкий гул (шум улицы, ветер, вибрации), 2) подтягиваем громкость.
 */
object AudioPreprocess {
    private const val HIGH_PASS_HZ = 100.0
    private const val TARGET_LEVEL = 0.5f
    private const val MAX_GAIN = 25f

    fun enhance(samples: FloatArray, sampleRate: Int): FloatArray {
        if (samples.isEmpty()) return samples
        val out = FloatArray(samples.size)

        // Фильтр высоких частот 1-го порядка
        val rc = 1.0 / (2.0 * PI * HIGH_PASS_HZ)
        val dt = 1.0 / sampleRate
        val a = (rc / (rc + dt)).toFloat()
        var prevX = 0f
        var prevY = 0f
        for (i in samples.indices) {
            val x = samples[i]
            val y = a * (prevY + x - prevX)
            out[i] = y
            prevX = x
            prevY = y
        }

        // Уровень громкости: значение, которое не превышают 99,5% отсчётов (чтобы щелчки не мешали)
        val bins = IntArray(1001)
        for (v in out) {
            val idx = (kotlin.math.abs(v) * 1000f).toInt().coerceIn(0, 1000)
            bins[idx]++
        }
        val threshold = (out.size * 0.995).toLong()
        var acc = 0L
        var level = 1f
        for (i in bins.indices) {
            acc += bins[i]
            if (acc >= threshold) {
                level = (i + 1) / 1000f
                break
            }
        }

        // Только усиливаем (не приглушаем): тихая речь становится заметно громче
        val gain = (TARGET_LEVEL / level).coerceIn(1f, MAX_GAIN)
        if (gain > 1.01f) {
            for (i in out.indices) out[i] = (out[i] * gain).coerceIn(-1f, 1f)
        }
        return out
    }
}
