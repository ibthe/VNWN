package com.example.voicenotes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.voicenotes.NotesViewModel
import com.example.voicenotes.speech.ModelPack

/** Карточка одного набора моделей: скачать / прогресс / скачано. */
@Composable
fun ModelDownloadCard(
    vm: NotesViewModel,
    pack: ModelPack,
    hint: String? = null,
    showDelete: Boolean = false
) {
    val installed = vm.isInstalled(pack)
    val active = vm.downloadingPack == pack

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Gap.M), verticalArrangement = Arrangement.spacedBy(Gap.S)) {
            Text(pack.title, style = MaterialTheme.typography.titleSmall)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall)
            when {
                active -> {
                    LinearProgressIndicator(
                        progress = { vm.downloadProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${(vm.downloadProgress * 100).toInt()}%", Modifier.weight(1f))
                        PillButton("Пауза") { vm.cancelDownload() }
                    }
                }
                installed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Скачано", Modifier.weight(1f))
                    if (showDelete) {
                        PillButton("Удалить", enabled = vm.downloadingPack == null) { vm.deleteModel(pack) }
                    }
                }
                else -> Button(
                    enabled = vm.downloadingPack == null,
                    onClick = { vm.downloadModel(pack) }
                ) { Text("Скачать · ${pack.sizeMb} МБ") }
            }
            if (!active && !installed && vm.downloadErrorPack == pack && vm.downloadError.isNotBlank()) {
                Text(
                    vm.downloadError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/** Окно со всеми наборами моделей (меню → «Модели распознавания»). */
@Composable
fun ModelsDialog(vm: NotesViewModel, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { PillButton("Закрыть", primary = true, onClick = onClose) },
        title = { Text("Модели распознавания") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Gap.M)
            ) {
                Text(
                    "Модели скачиваются один раз и хранятся на телефоне. " +
                        "После этого распознавание работает без интернета. " +
                        "Если загрузка оборвалась, нажмите «Скачать» ещё раз: она продолжится.",
                    style = MaterialTheme.typography.bodySmall
                )
                ModelPack.entries.forEach { ModelDownloadCard(vm, it, showDelete = true) }
            }
        }
    )
}
