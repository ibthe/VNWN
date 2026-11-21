package com.example.voicenotes.wake

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Общее состояние между сервисом-слушателем и остальным приложением (один процесс). */
object WakeWordState {
    /** Текст состояния для экрана настроек. */
    var status by mutableStateOf("")

    /** Сервис сейчас запущен. */
    @Volatile
    var running = false

    /** Идёт диктовка: слушатель должен освободить микрофон. */
    @Volatile
    var dictating = false

    /** Пауза после срабатывания, чтобы не сработать повторно на собственной речи. */
    @Volatile
    var cooldownUntil = 0L
}
