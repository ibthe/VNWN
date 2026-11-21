package com.example.voicenotes.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Записывает с микрофона 16 кГц, моно, и отдаёт float-сэмплы.
 * onLevel вызывается ~10 раз в секунду с уровнем громкости 0..1.
 */
class AudioRecorder(
    private val scope: CoroutineScope,
    private val onLevel: (Float) -> Unit = {}
) {
    companion object {
        const val SAMPLE_RATE = 16000
    }

    private var record: AudioRecord? = null
    private var job: Job? = null
    private val chunks = mutableListOf<FloatArray>()
    private val effects = mutableListOf<AudioEffect>()

    @SuppressLint("MissingPermission") // разрешение проверяется в UI до вызова
    fun start() {
        if (job != null) return
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, SAMPLE_RATE)
        )
        record = rec
        enableEffects(rec.audioSessionId)
        synchronized(chunks) { chunks.clear() }
        rec.startRecording()
        job = scope.launch(Dispatchers.IO) {
            val buf = ShortArray(1600)
            while (isActive) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    val f = FloatArray(n) { buf[it] / 32768f }
                    synchronized(chunks) { chunks.add(f) }
                    var sum = 0.0
                    for (v in f) sum += v * v
                    val rms = sqrt(sum / n).toFloat()
                    onLevel((rms * 12f).coerceIn(0f, 1f))
                }
            }
        }
    }

    suspend fun stop(): FloatArray {
        job?.cancelAndJoin()
        job = null
        releaseEffects()
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
        return synchronized(chunks) {
            val out = FloatArray(chunks.sumOf { it.size })
            var pos = 0
            for (c in chunks) {
                c.copyInto(out, pos)
                pos += c.size
            }
            out
        }
    }

    /** Системное шумоподавление и автоусиление, если телефон их поддерживает. */
    private fun enableEffects(sessionId: Int) {
        releaseEffects()
        runCatching {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(sessionId)?.let {
                    it.setEnabled(true)
                    effects.add(it)
                }
            }
        }
        runCatching {
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(sessionId)?.let {
                    it.setEnabled(true)
                    effects.add(it)
                }
            }
        }
    }

    private fun releaseEffects() {
        effects.forEach { runCatching { it.release() } }
        effects.clear()
    }
}
