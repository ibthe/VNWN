package com.example.voicenotes.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Отпечаток/лицо или PIN, пароль, графический ключ телефона. */
val LOCK_AUTH: Int =
    BiometricManager.Authenticators.BIOMETRIC_WEAK or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

/**
 * Закрывает всё приложение сверху. Заметки под ним остаются в памяти,
 * поэтому несохранённый текст не теряется, когда приложение блокируется.
 */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    val activity = LocalContext.current as? Activity
    BackHandler { activity?.moveTaskToBack(true) }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Voice Notes Wolf", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text("Приложение заблокировано")
            Spacer(Modifier.height(24.dp))
            Button(onClick = onUnlock) { Text("Разблокировать") }
        }
    }
}
