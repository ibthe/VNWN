package com.example.voicenotes

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.voicenotes.data.AppDatabase
import com.example.voicenotes.data.Note
import com.example.voicenotes.ui.PAPERS
import com.example.voicenotes.speech.AudioRecorder
import com.example.voicenotes.speech.ModelPack
import com.example.voicenotes.speech.ModelStore
import com.example.voicenotes.speech.WhisperRecognizer
import com.example.voicenotes.translate.NllbNotInstalledException
import com.example.voicenotes.translate.TextTranslator
import com.example.voicenotes.translate.describeChain
import com.example.voicenotes.wake.WakeConfig
import com.example.voicenotes.wake.WakeWordService
import com.example.voicenotes.wake.WakeWordState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import com.example.voicenotes.ui.normalized
import com.example.voicenotes.ui.removeNote
import org.json.JSONArray
import org.json.JSONObject

class NotesViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    private val dao = db.noteDao()
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    /** Запись в базу не должна обрываться вместе с экраном или ViewModel. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val modelsRoot = app.filesDir
    private val recognizer = WhisperRecognizer(modelsRoot)

    val notes: StateFlow<List<Note>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    var recording by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var modelLoading by mutableStateOf(true)
        private set
    /** Уровень громкости 0..1 во время записи. */
    var level by mutableStateOf(0f)
        private set
    /** Сколько секунд идёт запись. */
    var seconds by mutableStateOf(0)
        private set
    /** "" = авто, "ru", "en". */
    var language by mutableStateOf(prefs.getString("language", "ru") ?: "ru")
        private set

    /** Выбранное оформление ("light", "dark", "bright"); null — пока не выбрано, берём системное. */
    var themeKey by mutableStateOf(prefs.getString("theme", null))
        private set

    fun selectTheme(key: String) {
        themeKey = key
        prefs.edit().putString("theme", key).apply()
    }

    /** Включена ли блокировка приложения. */
    var lockEnabled by mutableStateOf(prefs.getBoolean("lock", false))
        private set
    /** Закрыто ли приложение сейчас (при запуске сразу закрыто, если блокировка включена). */
    var locked by mutableStateOf(lockEnabled)
        private set
    private var backgroundedAt = 0L

    private val wakeConfig = WakeConfig(app)

    /** Включён ли постоянный слушатель фраз («wolf open», «волк откройся»). */
    var wakeEnabled by mutableStateOf(wakeConfig.enabled)
        private set
    var wakePhrases by mutableStateOf(wakeConfig.phrases)
        private set

    fun updateWake(on: Boolean) {
        wakeConfig.enabled = on
        wakeEnabled = on
        WakeWordService.sync(getApplication<Application>(), reload = true)
    }

    /** Возвращает текст ошибки или null, если фраза добавлена. */
    fun addWakePhrase(raw: String): String? {
        val p = WakeConfig.normalize(raw)
        if (p.length < 4) return "Фраза слишком короткая"
        val cyr = WakeConfig.isCyrillic(p)
        val lat = p.any { it in 'a'..'z' }
        if (cyr && lat) return "Фраза должна быть на одном языке"
        if (!cyr && !lat) return "Используйте буквы"
        if (p in wakePhrases) return "Такая фраза уже есть"
        saveWakePhrases(wakePhrases + p)
        return null
    }

    fun removeWakePhrase(phrase: String) {
        saveWakePhrases(wakePhrases - phrase)
    }

    private fun saveWakePhrases(list: List<String>) {
        wakeConfig.phrases = list
        wakePhrases = list
        if (wakeEnabled) WakeWordService.sync(getApplication<Application>(), reload = true)
    }

    /** Пришёл запрос «сразу диктовать» (ярлык или плитка). */
    var dictationRequest by mutableStateOf(false)
        private set

    fun requestDictation() {
        dictationRequest = true
    }

    fun consumeDictationRequest() {
        dictationRequest = false
    }

    /** На какой язык переводить продиктованный текст: "" = не переводить, "en", "ka". */
    var translateTo by mutableStateOf(prefs.getString("translate_to", "") ?: "")

    var translating by mutableStateOf(false)
        private set
    /** Сообщение об ошибке перевода (пусто, если всё хорошо). */
    var notice by mutableStateOf("")
        private set
    private val translator = TextTranslator(modelsRoot)

    private val recorder = AudioRecorder(viewModelScope) { level = it }
    private var timerJob: Job? = null

    init {
        recognizer.language = language
        reloadModel()
    }

    private fun reloadModel() {
        viewModelScope.launch(Dispatchers.Default) {
            modelLoading = true
            if (ModelStore.installed(modelsRoot, currentPack)) runCatching { recognizer.warmUp() }
            modelLoading = false
        }
    }

    // ---------- модели, которые скачиваются после установки

    /** Увеличивается после скачивания/удаления: по нему интерфейс обновляет статус моделей. */
    var modelsVersion by mutableStateOf(0)
        private set
    var downloadingPack by mutableStateOf<ModelPack?>(null)
        private set
    var downloadProgress by mutableStateOf(0f)
        private set
    var downloadError by mutableStateOf("")
        private set
    var downloadErrorPack by mutableStateOf<ModelPack?>(null)
        private set
    private var downloadJob: Job? = null

    /** Какой набор моделей нужен для выбранного языка речи. */
    val currentPack: ModelPack get() = ModelPack.forLanguage(language)

    fun isInstalled(pack: ModelPack): Boolean {
        modelsVersion // чтение состояния: экран обновится после загрузки
        return ModelStore.installed(modelsRoot, pack)
    }

    /** Какие наборы для голосового запуска ещё не скачаны (по языкам добавленных фраз). */
    fun wakeMissingPacks(): List<ModelPack> {
        modelsVersion
        val need = mutableListOf<ModelPack>()
        if (wakePhrases.any { WakeConfig.isCyrillic(it) }) need += ModelPack.WAKE_RU
        if (wakePhrases.any { !WakeConfig.isCyrillic(it) }) need += ModelPack.WAKE_EN
        return need.filter { !ModelStore.installed(modelsRoot, it) }
    }

    fun downloadModel(pack: ModelPack) {
        if (downloadJob?.isActive == true) return
        downloadError = ""
        downloadErrorPack = null
        downloadProgress = 0f
        downloadingPack = pack
        downloadJob = viewModelScope.launch {
            try {
                ModelStore.download(modelsRoot, pack) { downloadProgress = it }
                modelsVersion++
                if (pack == currentPack) reloadModel()
                if (pack == ModelPack.WAKE_RU || pack == ModelPack.WAKE_EN) {
                    if (wakeEnabled) WakeWordService.sync(getApplication<Application>(), reload = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                downloadErrorPack = pack
                downloadError = when (t) {
                    is java.net.UnknownHostException -> "Не удалось скачать: нет интернета"
                    else -> "Не удалось скачать: ${t.message ?: t.javaClass.simpleName}"
                }
            } finally {
                downloadingPack = null
            }
        }
    }

    /** Останавливает загрузку; скачанная часть сохраняется и докачается при повторном нажатии. */
    fun cancelDownload() {
        downloadJob?.cancel()
    }

    fun deleteModel(pack: ModelPack) {
        if (downloadingPack != null) return
        ModelStore.delete(modelsRoot, pack)
        modelsVersion++
        if (pack == currentPack) {
            recognizer.release()
        }
        if (pack == ModelPack.NLLB) translator.close()
        if (pack == ModelPack.WAKE_RU || pack == ModelPack.WAKE_EN) {
            if (wakeEnabled) WakeWordService.sync(getApplication<Application>(), reload = true)
        }
    }

    /**
     * Переводит произвольный текст (набранный вручную). from = "" — определить язык по письму.
     * Бросает исключение, если перевод не удался (например, модель NLLB ещё не скачана).
     */
    suspend fun translateText(text: String, from: String, to: String): String {
        val src = from.ifEmpty { TextTranslator.detect(text) }
        if (src == to) return text
        return withContext(Dispatchers.Default) { translator.translate(text, src, to) }
    }

    fun selectTranslateTo(code: String) {
        if (recording || busy || code == translateTo) return
        translateTo = code
        notice = ""
        prefs.edit().putString("translate_to", code).apply()
    }

    fun selectLanguage(code: String) {
        if (recording || busy || modelLoading || code == language) return
        language = code
        prefs.edit().putString("language", code).apply()
        recognizer.language = code
        reloadModel()
    }

    fun updateLock(on: Boolean) {
        lockEnabled = on
        locked = false
        prefs.edit().putBoolean("lock", on).apply()
    }

    fun unlock() {
        locked = false
    }

    fun onBackgrounded() {
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    /** Блокируем, если приложение было свёрнуто дольше LOCK_TIMEOUT_MS. */
    fun onForegrounded() {
        wakeEnabled = wakeConfig.enabled
        WakeWordService.sync(getApplication<Application>(), reload = false)
        if (lockEnabled && backgroundedAt != 0L &&
            SystemClock.elapsedRealtime() - backgroundedAt > LOCK_TIMEOUT_MS
        ) locked = true
    }

    private val saveMutex = Mutex()
    /** id заметки, которую сейчас правят; 0 — новая, ещё не попавшая в базу. */
    private var editSession = 0L

    suspend fun load(id: Long): Note {
        editSession = id
        return if (id == 0L) Note(paper = PAPERS.indices.random()) else dao.get(id) ?: Note()
    }

    /**
     * Единственный путь записи заметки. Идёт под замком и не привязан к экрану, поэтому
     * автосохранение и сохранение при выходе не создают дубликатов новой заметки.
     */
    private suspend fun persist(note: Note) {
        if (note.text.isBlank() && note.title.isBlank() && note.strokes == "[]" && note.images == "[]") return
        saveMutex.withLock {
            val target = if (note.id == 0L) editSession else note.id
            val r = dao.upsert(note.copy(id = target, updatedAt = System.currentTimeMillis()))
            if (target == 0L) editSession = r
        }
    }

    /** Сохранение при выходе из редактора или из приложения. */
    fun save(note: Note) {
        appScope.launch { persist(note) }
    }

    /** Автосохранение во время правки: уже начатая запись не обрывается. */
    suspend fun autosave(note: Note) {
        withContext(NonCancellable + Dispatchers.IO) { persist(note) }
    }

    fun setPinned(note: Note, pinned: Boolean) {
        appScope.launch { dao.setPinned(note.id, pinned) }
    }

    /** Размер плитки на главном экране (0 — значок … 3 — во всю ширину). */
    fun setTileSize(note: Note, size: Int) {
        appScope.launch { dao.setTileSize(note.id, size) }
    }

    /** Высота плитки (0 — обычная … 3 — максимум). */
    fun setTileHeight(note: Note, height: Int) {
        appScope.launch { dao.setTileHeight(note.id, height) }
    }

    /** Цвет плитки (ARGB); 0 возвращает цвет листа. */
    fun setTileColor(note: Note, color: Long) {
        appScope.launch { dao.setTileColor(note.id, color) }
    }

    /** Порядок плиток на одном уровне (главный экран или папка) так же, как их отдаёт база. */
    private fun levelOf(all: List<Note>, parent: Long): List<Note> =
        all.filter { it.parentId == parent }
            .sortedWith(compareByDescending<Note> { it.pinned }.thenBy { it.position }.thenByDescending { it.updatedAt })

    private suspend fun writeLayoutRaw(list: List<Note>) {
        list.forEachIndexed { i, n -> dao.setLayout(n.id, i + 1, n.newRow, n.tileSize, n.tileHeight) }
    }

    /** Запоминает расстановку: [list] — плитки сверху вниз; ряды (newRow) и размеры берутся из самих заметок. */
    fun applyLayout(list: List<Note>) {
        appScope.launch { db.withTransaction { writeLayoutRaw(list) } }
    }

    /** Бросили [dragId] на [targetId]: из двух плиток получается папка, а если цель — папка, плитка ложится в неё. */
    fun mergeIntoFolder(dragId: Long, targetId: Long) {
        appScope.launch {
            db.withTransaction<Unit> {
                val all = dao.getAll()
                val root = levelOf(all, 0L)
                val d = root.firstOrNull { it.id == dragId } ?: return@withTransaction
                val t = root.firstOrNull { it.id == targetId } ?: return@withTransaction
                if (d.isFolder || d.id == t.id) return@withTransaction
                val rest = removeNote(normalized(root), dragId)
                if (t.isFolder) {
                    val next = (all.filter { it.parentId == t.id }.maxOfOrNull { it.position } ?: 0) + 1
                    dao.setParent(dragId, t.id, next, false)
                    writeLayoutRaw(rest)
                } else {
                    val slot = rest.first { it.id == targetId }
                    val fid = dao.upsert(
                        Note(
                            title = "Папка", isFolder = true, tileSize = slot.tileSize, pinned = slot.pinned,
                            newRow = slot.newRow, position = slot.position
                        )
                    )
                    dao.setParent(targetId, fid, 1, true)
                    dao.setParent(dragId, fid, 2, false)
                    writeLayoutRaw(rest.map { if (it.id == targetId) slot.copy(id = fid) else it })
                }
            }
        }
    }

    /** Вынимает заметку из папки на главный экран (в конец). Опустевшая папка исчезает. */
    fun removeFromFolder(note: Note) {
        if (note.parentId == 0L) return
        appScope.launch {
            db.withTransaction<Unit> {
                val all = dao.getAll()
                val end = (all.filter { it.parentId == 0L }.maxOfOrNull { it.position } ?: 0) + 1
                dao.setParent(note.id, 0L, end, true)
                if (all.none { it.parentId == note.parentId && it.id != note.id }) dao.deleteById(note.parentId)
            }
        }
    }

    /** Расформировывает папку: заметки встают на её место на главном экране, сама папка удаляется. */
    fun ungroupFolder(folder: Note) {
        appScope.launch {
            db.withTransaction<Unit> {
                val all = dao.getAll()
                val root = levelOf(all, 0L)
                val kids = levelOf(all, folder.id).map { it.copy(parentId = 0L) }
                val at = root.indexOfFirst { it.id == folder.id }
                val merged = if (at < 0) root.filter { it.id != folder.id } + kids
                else root.take(at) + kids + root.drop(at + 1)
                kids.forEach { dao.setParentId(it.id, 0L) }
                writeLayoutRaw(normalized(merged))
                dao.deleteById(folder.id)
            }
        }
    }

    fun renameFolder(folder: Note, title: String) {
        appScope.launch { dao.setTitle(folder.id, title) }
    }

    /** Экстренное удаление: все заметки и все их фото. Вернуть нельзя. */
    fun deleteAll(onDone: () -> Unit) {
        appScope.launch {
            withContext(NonCancellable) {
                saveMutex.withLock {
                    dao.deleteAll()
                    editSession = 0L
                    java.io.File(getApplication<Application>().filesDir, "images").deleteRecursively()
                }
            }
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    fun delete(note: Note) {
        viewModelScope.launch { dao.delete(note) }
    }

    /** Возвращает только что удалённую заметку (с тем же id). */
    fun restore(note: Note) {
        viewModelScope.launch { dao.upsert(note) }
    }

    /** Сохраняет все заметки в JSON-файл по выбранному пользователем адресу. */
    fun exportTo(uri: Uri, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    val all = dao.getAll()
                    val arr = JSONArray()
                    all.forEach {
                        arr.put(
                            JSONObject().put("text", it.text).put("title", it.title).put("updatedAt", it.updatedAt)
                                .put("spans", it.spans).put("bold", it.bold).put("strokes", it.strokes)
                                .put("fontKey", it.fontKey).put("fontSize", it.fontSize.toDouble())
                                .put("lineHeight", it.lineHeight.toDouble()).put("paper", it.paper).put("pinned", it.pinned)
                                .put("tileSize", it.tileSize).put("tileColor", it.tileColor).put("position", it.position)
                                .put("id", it.id).put("isFolder", it.isFolder).put("parentId", it.parentId).put("newRow", it.newRow).put("tileHeight", it.tileHeight)
                        )
                    }
                    val root = JSONObject()
                        .put("app", "VoiceNotesWolf")
                        .put("version", 1)
                        .put("notes", arr)
                    val out = getApplication<Application>().contentResolver
                        .openOutputStream(uri, "wt") ?: error("не удалось открыть файл")
                    out.use { it.write(root.toString(2).toByteArray(Charsets.UTF_8)) }
                    "Сохранено заметок: ${all.size}"
                } catch (e: Exception) {
                    "Не удалось сохранить: ${e.message}"
                }
            }
            onDone(msg)
        }
    }

    /** Добавляет заметки из файла копии. Уже существующие заметки не дублируются. */
    fun importFrom(uri: Uri, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    val input = getApplication<Application>().contentResolver
                        .openInputStream(uri) ?: error("не удалось открыть файл")
                    val json = input.use { it.readBytes().toString(Charsets.UTF_8) }
                    val arr = JSONObject(json).getJSONArray("notes")
                    val existing = dao.getAll().map { it.text to it.updatedAt }.toSet()
                    var added = 0
                    // старый id -> новый id: нужен, чтобы заметки снова попали в свои папки
                    val idMap = HashMap<Long, Long>()
                    val parents = HashMap<Long, Long>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val isFolder = o.optBoolean("isFolder", false)
                        val text = o.getString("text")
                        val ts = o.optLong("updatedAt", System.currentTimeMillis())
                        if (!isFolder && ((text.isBlank() && o.optString("strokes", "[]") == "[]") || (text to ts) in existing)) continue
                        val newId = dao.upsert(
                            Note(
                                text = text, title = o.optString("title", ""), updatedAt = ts,
                                spans = o.optString("spans", "[]"), bold = o.optString("bold", "[]"), strokes = o.optString("strokes", "[]"),
                                fontKey = o.optString("fontKey", "sans"),
                                fontSize = o.optDouble("fontSize", 18.0).toFloat(),
                                lineHeight = o.optDouble("lineHeight", 1.5).toFloat(),
                                paper = o.optInt("paper", 0),
                                pinned = o.optBoolean("pinned", false),
                                tileSize = o.optInt("tileSize", 3),
                                tileColor = o.optLong("tileColor", 0L),
                                position = o.optInt("position", 0),
                                isFolder = isFolder,
                                newRow = o.optBoolean("newRow", false),
                                tileHeight = o.optInt("tileHeight", 0)
                            )
                        )
                        val oldId = o.optLong("id", 0L)
                        if (oldId != 0L) idMap[oldId] = newId
                        val oldParent = o.optLong("parentId", 0L)
                        if (oldParent != 0L) parents[newId] = oldParent
                        if (!isFolder) added++
                    }
                    for ((newId, oldParent) in parents) {
                        val folderId = idMap[oldParent]
                        if (folderId != null) dao.setParentId(newId, folderId)
                    }
                    "Добавлено заметок: $added"
                } catch (e: Exception) {
                    "Не удалось восстановить: ${e.message}"
                }
            }
            onDone(msg)
        }
    }

    fun startRecording() {
        if (recording || !ModelStore.installed(modelsRoot, currentPack)) return
        recording = true
        notice = ""
        seconds = 0
        // Слушатель фраз отпускает микрофон на время диктовки
        WakeWordState.dictating = true
        viewModelScope.launch {
            if (WakeWordState.running) delay(400)
            if (recording) recorder.start() else WakeWordState.dictating = false
        }
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            val t0 = SystemClock.elapsedRealtime()
            while (true) {
                delay(250)
                seconds = ((SystemClock.elapsedRealtime() - t0) / 1000).toInt()
            }
        }
    }

    fun stopRecording(onDone: () -> Unit = {}, onText: (String) -> Unit) {
        viewModelScope.launch {
            timerJob?.cancel()
            recording = false
            level = 0f
            busy = true
            val samples = recorder.stop()
            WakeWordState.dictating = false
            val text = withContext(Dispatchers.Default) {
                try {
                    recognizer.transcribe(samples, AudioRecorder.SAMPLE_RATE)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    "[ошибка распознавания: ${t.message ?: t.javaClass.simpleName}]"
                }
            }
            var result = text
            val canTranslate = text.isNotBlank() &&
                !text.startsWith("[ошибка") &&
                translateTo.isNotEmpty() &&
                language in TRANSLATABLE &&
                language != translateTo
            if (canTranslate) {
                translating = true
                result = try {
                    translator.translate(text, language, translateTo)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    notice = if (t is NllbNotInstalledException) {
                        (t.message ?: "") + " Вставлен исходный текст."
                    } else {
                        "Перевод не удался: ${t.describeChain()}. Вставлен исходный текст."
                    }
                    text
                }
                translating = false
            }
            busy = false
            if (result.isNotBlank()) onText(result)
            onDone()
        }
    }

    fun cancelRecording() {
        if (!recording) return
        timerJob?.cancel()
        viewModelScope.launch {
            recording = false
            level = 0f
            recorder.stop()
            WakeWordState.dictating = false
        }
    }

    override fun onCleared() {
        translator.close()
        WakeWordState.dictating = false
        super.onCleared()
    }
}

private const val LOCK_TIMEOUT_MS = 30_000L

private val TRANSLATABLE = setOf("ru", "en", "ka")
