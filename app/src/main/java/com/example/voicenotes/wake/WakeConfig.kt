package com.example.voicenotes.wake

import android.content.Context

/** Настройки голосового запуска: включён ли слушатель и список фраз. */
class WakeConfig(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("wake", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) {
            prefs.edit().putBoolean("enabled", value).apply()
        }

    var phrases: List<String>
        get() {
            val raw = prefs.getString("phrases", null) ?: return DEFAULT_PHRASES
            return raw.split('\n').filter { it.isNotBlank() }
        }
        set(value) {
            prefs.edit().putString("phrases", value.joinToString("\n")).apply()
        }

    companion object {
        val DEFAULT_PHRASES = listOf("wolf open", "волк откройся")

        /** Нижний регистр, только буквы и цифры, одинарные пробелы. */
        fun normalize(s: String): String =
            s.lowercase()
                .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

        fun isCyrillic(s: String): Boolean = s.any { it in 'а'..'я' || it == 'ё' }
    }
}
