package com.example.voicenotes.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.voicenotes.NotesViewModel
import com.example.voicenotes.wake.WakeWordState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WakeSettingsScreen(vm: NotesViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)

    var newPhrase by remember { mutableStateOf("") }
    var overlayOk by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    // Обновляем статус разрешения, когда пользователь возвращается из системных настроек
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) overlayOk = Settings.canDrawOverlays(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val micOk = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (micOk) vm.updateWake(true)
        else Toast.makeText(context, "Нужен доступ к микрофону", Toast.LENGTH_LONG).show()
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                title = { Text("Голосовой запуск") },
                navigationIcon = { Box(Modifier.padding(start = 12.dp)) { IconBtn(Ico.BACK, onClick = onBack) } }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(Gap.L)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Слушать фразы-ключи", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Когда вы произносите фразу, приложение открывается и начинает диктовку.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = vm.wakeEnabled,
                    onCheckedChange = { on ->
                        if (!on) {
                            vm.updateWake(false)
                        } else if (vm.wakeMissingPacks().isNotEmpty()) {
                            Toast.makeText(
                                context,
                                "Сначала скачайте модели для фраз (карточки ниже)",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
                            if (Build.VERSION.SDK_INT >= 33) {
                                perms.add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            permissions.launch(perms.toTypedArray())
                        }
                    }
                )
            }

            if (vm.wakeEnabled && WakeWordState.status.isNotBlank()) {
                Text("Состояние: ${WakeWordState.status}")
            }

            vm.wakeMissingPacks().forEach {
                ModelDownloadCard(vm, it, hint = "Для голосового запуска нужна эта модель.")
            }

            Text("Фразы", style = MaterialTheme.typography.titleMedium)
            if (vm.wakePhrases.isEmpty()) {
                Text("Фраз нет. Добавьте хотя бы одну.")
            }
            vm.wakePhrases.forEach { phrase ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("«$phrase»", Modifier.weight(1f))
                    PillButton("Убрать") { vm.removeWakePhrase(phrase) }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Gap.S)
            ) {
                OutlinedTextField(
                    value = newPhrase,
                    onValueChange = { newPhrase = it },
                    singleLine = true,
                    label = { Text("Новая фраза") },
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = {
                    val error = vm.addWakePhrase(newPhrase)
                    if (error == null) newPhrase = ""
                    else Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                }) { Text("Добавить") }
            }
            Text(
                "Фраза должна быть на одном языке (русском или английском), лучше из двух слов. " +
                    "Используйте обычные слова: редкие и выдуманные слова распознаются плохо или вовсе не распознаются.",
                style = MaterialTheme.typography.bodySmall
            )

            Text("Открытие приложения", style = MaterialTheme.typography.titleMedium)
            if (overlayOk) {
                Text("Разрешено показывать приложение поверх других окон.")
            } else {
                Text(
                    "Чтобы Android позволил приложению открыться само, нужно разрешение " +
                        "«Поверх других приложений». Без него придёт уведомление, нажмите на него.",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }) { Text("Разрешить") }
            }

            Text("Что нужно знать", style = MaterialTheme.typography.titleMedium)
            Text(
                "• Микрофон работает постоянно: в строке состояния будет значок, а батарея " +
                    "разряжается быстрее.\n" +
                    "• После перезагрузки телефона откройте приложение один раз, и слушатель " +
                    "включится снова.\n" +
                    "• Во время звонка или записи голосового в другом приложении фраза не слышна.\n" +
                    "• Пока вы диктуете в приложении, слушатель ставится на паузу.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
