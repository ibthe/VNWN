package com.example.voicenotes

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.voicenotes.ui.AppTheme
import com.example.voicenotes.ui.LOCK_AUTH
import com.example.voicenotes.ui.LockScreen
import com.example.voicenotes.ui.NotesApp
import com.example.voicenotes.ui.ThemeMode
import com.example.voicenotes.wake.WakeWordService

// FragmentActivity нужен для BiometricPrompt; для Compose он работает так же, как ComponentActivity.
class MainActivity : FragmentActivity() {
    private val vm: NotesViewModel by viewModels()
    private var promptShowing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            AppTheme(ThemeMode.of(vm.themeKey, isSystemInDarkTheme())) {
                // При включённой блокировке содержимое не попадает в скриншоты и список недавних
                LaunchedEffect(vm.lockEnabled) {
                    if (vm.lockEnabled) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
                LaunchedEffect(vm.locked) { if (vm.locked) promptUnlock() }
                // Пока идёт запись, экран не гаснет: иначе при паузе в речи он выключался и запись обрывалась
                LaunchedEffect(vm.recording) {
                    if (vm.recording) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }

                Box(Modifier.fillMaxSize()) {
                    NotesApp(vm)
                    if (vm.locked) LockScreen(onUnlock = { promptUnlock() })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Ярлык или плитка просят сразу начать диктовку. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_DICTATE) {
            NotificationManagerCompat.from(this).cancel(WakeWordService.NOTIF_WAKE)
            vm.requestDictation()
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onForegrounded()
    }

    override fun onStop() {
        super.onStop()
        vm.onBackgrounded()
    }

    private fun promptUnlock() {
        if (promptShowing) return
        if (BiometricManager.from(this).canAuthenticate(LOCK_AUTH) != BiometricManager.BIOMETRIC_SUCCESS) {
            // На телефоне больше нет блокировки экрана: защищать нечем, выключаем защиту.
            vm.updateLock(false)
            Toast.makeText(
                this,
                "На телефоне нет блокировки экрана, защита приложения выключена",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        promptShowing = true
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    promptShowing = false
                    vm.unlock()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    promptShowing = false
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("VNW-N")
            .setSubtitle("Подтвердите, что это вы")
            .setAllowedAuthenticators(LOCK_AUTH)
            .build()
        prompt.authenticate(info)
    }

    companion object {
        const val ACTION_DICTATE = "app.voicenoteswolf.nllb.action.DICTATE"
    }
}
