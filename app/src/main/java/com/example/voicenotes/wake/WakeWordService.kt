package com.example.voicenotes.wake

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.voicenotes.MainActivity
import com.example.voicenotes.R
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * Постоянный слушатель: держит микрофон и ищет ключевые фразы (Vosk, режим «грамматики»:
 * распознаются только заданные фразы, поэтому работает быстро и экономно).
 * Русские фразы ищет русская модель, английские — английская.
 */
class WakeWordService : Service() {

    private inner class Worker : Thread("wake-word") {
        @Volatile
        var stopRequested = false

        override fun run() = runLoop { stopRequested }
    }

    private var worker: Worker? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        WakeWordState.running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val config = WakeConfig(this)

        if (intent?.action == ACTION_STOP) {
            config.enabled = false
            stopWorker()
            WakeWordState.status = ""
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            showListeningNotification(config.phrases)
        } catch (e: Exception) {
            WakeWordState.status = "Не удалось запустить: ${e.message}"
            stopSelf()
            return START_NOT_STICKY
        }
        stopWorker()
        worker = Worker().also { it.start() }
        // Не перезапускаем сами: с Android 14 микрофонный сервис нельзя поднять из фона.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopWorker()
        WakeWordState.running = false
        super.onDestroy()
    }

    private fun stopWorker() {
        worker?.let {
            it.stopRequested = true
            runCatching { it.join(2500) }
        }
        worker = null
    }

    // ---------- уведомления

    private fun ensureChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_LISTEN, "Голосовой запуск", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_WAKE, "Фраза услышана", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun showListeningNotification(phrases: List<String>) {
        ensureChannels()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CH_LISTEN)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Волк слушает")
            .setContentText(phrases.joinToString(" · ") { "«$it»" })
            .setContentIntent(open)
            .addAction(0, "Выключить", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_LISTEN, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_LISTEN, notification)
        }
    }

    // ---------- основной цикл

    private fun runLoop(shouldStop: () -> Boolean) {
        val phrases = WakeConfig(this).phrases
            .map { WakeConfig.normalize(it) }
            .filter { it.isNotBlank() }
            .distinct()
        if (phrases.isEmpty()) {
            WakeWordState.status = "Добавьте хотя бы одну фразу"
            return
        }
        val ru = phrases.filter { WakeConfig.isCyrillic(it) }
        val en = phrases.filterNot { WakeConfig.isCyrillic(it) }

        val models = mutableListOf<Model>()
        val recognizers = mutableListOf<Pair<Recognizer, Set<String>>>()
        var audio: AudioRecord? = null
        try {
            WakeWordState.status = "Загружаю модели…"
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            for ((dir, list) in listOf("vosk-ru" to ru, "vosk-en" to en)) {
                if (list.isEmpty()) continue
                val model = Model(prepareModel(dir))
                models.add(model)
                recognizers.add(Recognizer(model, SAMPLE_RATE.toFloat(), grammarFor(list)) to list.toSet())
                if (shouldStop()) return
            }

            val buf = ShortArray(1600)
            while (!shouldStop()) {
                val blocked = WakeWordState.dictating ||
                    SystemClock.elapsedRealtime() < WakeWordState.cooldownUntil
                if (blocked) {
                    audio?.let {
                        runCatching { it.stop() }
                        it.release()
                    }
                    audio = null
                    WakeWordState.status = "Пауза"
                    Thread.sleep(300)
                    continue
                }

                var rec = audio
                if (rec == null) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED
                    ) {
                        WakeWordState.status = "Нет доступа к микрофону"
                        return
                    }
                    rec = openAudio()
                    if (rec == null) {
                        WakeWordState.status = "Микрофон занят, пробую снова…"
                        Thread.sleep(2000)
                        continue
                    }
                    audio = rec
                }
                WakeWordState.status = "Слушаю"

                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) {
                    Thread.sleep(50)
                    continue
                }
                for ((recognizer, set) in recognizers) {
                    if (recognizer.acceptWaveForm(buf, n)) {
                        val text = JSONObject(recognizer.result).optString("text").trim()
                        if (text.isNotEmpty() && text in set) {
                            recognizers.forEach { it.first.reset() }
                            onWake(text)
                            break
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            WakeWordState.status = "Ошибка: ${t.message ?: t.javaClass.simpleName}"
        } finally {
            audio?.let {
                runCatching { it.stop() }
                it.release()
            }
            recognizers.forEach { runCatching { it.first.close() } }
            models.forEach { runCatching { it.close() } }
        }
    }

    @SuppressLint("MissingPermission") // разрешение проверяется перед вызовом
    private fun openAudio(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, SAMPLE_RATE * 2)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return null
        }
        rec.startRecording()
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release()
            return null
        }
        return rec
    }

    private fun grammarFor(phrases: List<String>): String =
        JSONArray(phrases + "[unk]").toString()

    /** Модель фраз скачивается из приложения (меню → «Модели распознавания»). */
    private fun prepareModel(name: String): String {
        val dir = File(filesDir, name)
        check(File(dir, ".ready").exists()) {
            "модель для фраз не скачана (меню → «Модели распознавания»)"
        }
        return dir.absolutePath
    }

    // ---------- срабатывание

    @SuppressLint("MissingPermission")
    private fun onWake(text: String) {
        WakeWordState.cooldownUntil = SystemClock.elapsedRealtime() + COOLDOWN_MS

        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_DICTATE
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // 1) Пробуем открыть сразу. Android разрешает это не всегда (см. «поверх других окон»).
        runCatching { startActivity(intent) }

        // 2) Подстраховка: уведомление, нажатие на которое открывает приложение.
        runCatching {
            val pi = PendingIntent.getActivity(
                this, 2, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(this, CH_WAKE)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("Услышал «$text»")
                .setContentText("Нажмите, чтобы открыть и начать диктовку")
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .setAutoCancel(true)
                .setTimeoutAfter(15_000)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .build()
            NotificationManagerCompat.from(this).notify(NOTIF_WAKE, notification)
        }
    }

    companion object {
        const val ACTION_STOP = "app.voicenoteswolf.nllb.action.STOP_WAKE"
        const val NOTIF_LISTEN = 101
        const val NOTIF_WAKE = 102
        private const val CH_LISTEN = "wake_listen"
        private const val CH_WAKE = "wake_alert"
        private const val SAMPLE_RATE = 16000
        private const val COOLDOWN_MS = 10_000L

        private fun hasMic(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        /**
         * Приводит сервис в соответствие с настройкой.
         * reload = true перезапускает слушатель (например, после смены фраз).
         */
        fun sync(context: Context, reload: Boolean) {
            val config = WakeConfig(context)
            val intent = Intent(context, WakeWordService::class.java)
            if (config.enabled && hasMic(context)) {
                if (reload || !WakeWordState.running) {
                    try {
                        ContextCompat.startForegroundService(context, intent)
                    } catch (e: Exception) {
                        WakeWordState.status = "Не удалось запустить: ${e.message}"
                    }
                }
            } else {
                context.stopService(intent)
                WakeWordState.status = ""
            }
        }
    }
}
