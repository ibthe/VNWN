package com.example.voicenotes.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import com.example.voicenotes.NotesViewModel
import com.example.voicenotes.translate.describeChain
import com.example.voicenotes.data.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun NotesApp(vm: NotesViewModel = viewModel()) {
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showWake by rememberSaveable { mutableStateOf(false) }
    var openFolder by rememberSaveable { mutableStateOf<Long?>(null) }

    // Ярлык/плитка/голосовая фраза: открываем новую заметку (редактор сам начнёт запись)
    LaunchedEffect(vm.dictationRequest) {
        if (vm.dictationRequest && editingId == null) {
            showWake = false
            editingId = 0L
        }
    }

    val id = editingId
    when {
        id != null -> NoteEditor(vm, id, onClose = { editingId = null })
        showWake -> WakeSettingsScreen(vm, onBack = { showWake = false })
        else -> NotesList(
            vm,
            onOpen = { editingId = it },
            onOpenWake = { showWake = true },
            openFolder = openFolder,
            onOpenFolder = { openFolder = it }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesList(
    vm: NotesViewModel,
    onOpen: (Long) -> Unit,
    onOpenWake: () -> Unit,
    openFolder: Long?,
    onOpenFolder: (Long?) -> Unit
) {
    val notes by vm.notes.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var showModels by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    var showDeleteAll by remember { mutableStateOf(false) }
    var optionsId by remember { mutableStateOf<Long?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) vm.exportTo(uri) { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.importFrom(uri) { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }

    val deleteNote: (Note) -> Unit = { note ->
        vm.delete(note)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(
                message = "Заметка удалена",
                actionLabel = "Отменить",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) vm.restore(note)
        }
    }

    // На главном экране — плитки без папки и сами папки; заметки внутри папок видны только в папке или в поиске.
    val folderIds = notes.filter { it.isFolder }.map { it.id }.toSet()
    val shown = if (query.isBlank()) notes.filter { it.parentId == 0L || it.parentId !in folderIds }
    else notes
        .filter { !it.isFolder }
        .filter { it.text.contains(query.trim(), ignoreCase = true) || it.title.contains(query.trim(), ignoreCase = true) }
        .map { it.copy(newRow = false) }

    // Открытая папка: окно с такой же сеткой плиток
    val folder = openFolder?.let { id -> notes.firstOrNull { it.id == id && it.isFolder } }
    if (openFolder != null && folder == null && notes.isNotEmpty()) {
        LaunchedEffect(openFolder) { onOpenFolder(null) }
    }
    if (folder != null) {
        val kids = notes.filter { it.parentId == folder.id }
        FolderDialog(folder, kids.size, onClose = { onOpenFolder(null) }) {
            NotesGrid(
                notes = kids,
                canReorder = true,
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 48.dp),
                onOpen = { onOpen(it.id) },
                onOptions = { optionsId = it.id },
                onLayout = { vm.applyLayout(it) },
                onMerge = { _, _ -> },
                onPin = { vm.setPinned(it, !it.pinned) },
                onDelete = { deleteNote(it) },
                modifier = Modifier.fillMaxWidth(),
                allowFolders = false,
                onDragOut = { vm.removeFromFolder(it) }
            )
        }
    }

    notes.firstOrNull { it.id == optionsId }?.let { n ->
        TileOptionsDialog(
            note = n,
            onSize = { vm.setTileSize(n, it) },
            onHeight = { vm.setTileHeight(n, it) },
            onColor = { vm.setTileColor(n, it) },
            onPin = { vm.setPinned(n, !n.pinned) },
            onDelete = { optionsId = null; deleteNote(n) },
            onRename = { vm.renameFolder(n, it) },
            onUngroup = {
                optionsId = null
                if (openFolder == n.id) onOpenFolder(null)
                vm.ungroupFolder(n)
            },
            onLeaveFolder = if (n.parentId != 0L && !n.isFolder) {
                { optionsId = null; vm.removeFromFolder(n) }
            } else null,
            onClose = { optionsId = null }
        )
    }

    if (showModels) ModelsDialog(vm, onClose = { showModels = false })
    if (showDeleteAll) DeleteAllDialog(
        count = notes.size,
        onConfirm = {
            showDeleteAll = false
            vm.deleteAll { Toast.makeText(context, "Все заметки удалены", Toast.LENGTH_SHORT).show() }
        },
        onClose = { showDeleteAll = false }
    )
    if (showTheme) ThemeDialog(LocalThemeMode.current, onPick = { vm.selectTheme(it.key) }, onClose = { showTheme = false })

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = { AppTitle() },
                actions = {
                    if (notes.isNotEmpty()) DangerIconBtn(Ico.TRASH, Modifier.padding(end = Gap.S)) { showDeleteAll = true }
                    Box(Modifier.padding(end = 12.dp)) {
                        IconBtn(Ico.MENU) { menuOpen = true }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            shape = RoundedCornerShape(22.dp),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.widthIn(min = 264.dp).padding(horizontal = 8.dp)
                        ) {
                            MenuRow("Оформление: ${LocalThemeMode.current.label}", accent = true) {
                                menuOpen = false
                                showTheme = true
                            }
                            MenuRow("Сохранить копию в файл") {
                                menuOpen = false
                                exportLauncher.launch("vnwn-backup.json")
                            }
                            MenuRow("Восстановить из файла") {
                                menuOpen = false
                                importLauncher.launch(arrayOf("*/*"))
                            }
                            MenuRow("Модели распознавания") {
                                menuOpen = false
                                showModels = true
                            }
                            MenuRow("Голосовой запуск") {
                                menuOpen = false
                                onOpenWake()
                            }
                            MenuRow(if (vm.lockEnabled) "Выключить блокировку" else "Включить блокировку") {
                                menuOpen = false
                                if (vm.lockEnabled) {
                                    vm.updateLock(false)
                                } else if (
                                    BiometricManager.from(context).canAuthenticate(LOCK_AUTH) ==
                                    BiometricManager.BIOMETRIC_SUCCESS
                                ) {
                                    vm.updateLock(true)
                                    Toast.makeText(context, "Блокировка включена", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(
                                        context,
                                        "Сначала настройте блокировку экрана (PIN, пароль или отпечаток) в настройках телефона",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            val c = MaterialTheme.colorScheme
            Box(
                Modifier.size(64.dp)
                    .pressable(
                        CircleShape,
                        base = c.primary,
                        elevation = 14.dp,
                        onClick = { onOpen(0L) }
                    ),
                contentAlignment = Alignment.Center
            ) { AppIcon(Ico.PLUS, c.onPrimary, 30.dp) }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
        Column(Modifier.fillMaxSize()) {
            if (notes.isNotEmpty()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Поиск по заметкам") },
                    shape = RoundedCornerShape(22.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedBorderColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
            if (shown.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val c = MaterialTheme.colorScheme
                    Box(
                        Modifier.size(84.dp).softCard(c.primaryContainer, CircleShape, 12.dp),
                        contentAlignment = Alignment.Center
                    ) { AppIcon(if (notes.isEmpty()) Ico.MIC else Ico.TEXT, c.onPrimaryContainer, 36.dp) }
                    Spacer(Modifier.height(18.dp))
                    Text(
                        if (notes.isEmpty()) "Пока нет заметок" else "Ничего не найдено",
                        style = MaterialTheme.typography.titleMedium, color = c.onSurface
                    )
                    if (notes.isEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Нажмите «+», чтобы создать первую",
                            style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant
                        )
                    }
                }
            } else {
                NotesGrid(
                    notes = shown,
                    canReorder = query.isBlank(),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 100.dp),
                    onOpen = { if (it.isFolder) onOpenFolder(it.id) else onOpen(it.id) },
                    onOptions = { optionsId = it.id },
                    onLayout = { vm.applyLayout(it) },
                    onMerge = { a, b -> vm.mergeIntoFolder(a, b) },
                    onPin = { vm.setPinned(it, !it.pinned) },
                    onDelete = { deleteNote(it) },
                    folderKids = { fid -> notes.filter { it.parentId == fid } }
                )
            }
        }
        }
    }
}

private fun formatTime(totalSeconds: Int): String =
    "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)

@Composable
internal fun rememberBitmap(name: String, sample: Int = 1): ImageBitmap? {
    val ctx = LocalContext.current
    return produceState<ImageBitmap?>(null, name, sample) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                BitmapFactory.decodeFile(
                    Media.file(ctx, name).path,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )?.asImageBitmap()
            }.getOrNull()
        }
    }.value
}

private enum class Panel { VOICE, TEXT, MARK, DRAW }
private enum class Tool { PEN, MARKER, ERASER, MOVE_ONE, MOVE_ALL }

/** Служебные значения «какой штрих двигаем». */
private const val INK_NONE = -2
private const val INK_ALL = -1

private fun DrawScope.drawInk(k: Ink, d: Float) {
    val col = Color(k.color).let { if (k.marker) it.copy(alpha = 0.38f) else it }
    val w = (if (k.marker) k.width * 3.2f else k.width) * d
    if (k.pts.size < 2) {
        k.pts.firstOrNull()?.let { drawCircle(col, w / 2, it * d) }
        return
    }
    val path = Path()
    val first = k.pts[0] * d
    path.moveTo(first.x, first.y)
    for (i in 1 until k.pts.size) {
        val a = k.pts[i - 1] * d
        val b = k.pts[i] * d
        path.quadraticBezierTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2)
    }
    val last = k.pts.last() * d
    path.lineTo(last.x, last.y)
    drawPath(path, col, style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round))
}


private fun distToSegment(p: Offset, a: Offset, b: Offset): Float {
    val ab = b - a
    val len2 = ab.x * ab.x + ab.y * ab.y
    if (len2 == 0f) return (p - a).getDistance()
    val t = (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / len2).coerceIn(0f, 1f)
    return (p - (a + ab * t)).getDistance()
}

private fun Ink.hitDistance(p: Offset): Float {
    if (pts.isEmpty()) return Float.MAX_VALUE
    if (pts.size == 1) return (p - pts[0]).getDistance()
    var best = Float.MAX_VALUE
    for (i in 1 until pts.size) best = minOf(best, distToSegment(p, pts[i - 1], pts[i]))
    return best
}

/** Верхний штрих под пальцем (координаты в dp) или INK_NONE. */
private fun pickInk(inks: List<Ink>, p: Offset): Int {
    for (i in inks.indices.reversed()) {
        val k = inks[i]
        val reach = (if (k.marker) k.width * 3.2f else k.width) / 2f + 12f
        if (k.hitDistance(p) <= reach) return i
    }
    return INK_NONE
}

/**
 * Двойное нажатие в любом месте листа. Второе касание «съедается», чтобы текстовое поле
 * не выделяло слово, а сам лист разворачивался или сворачивался.
 */
private fun Modifier.doubleTap(enabled: Boolean, onDouble: () -> Unit): Modifier = composed {
    val action by rememberUpdatedState(onDouble)
    pointerInput(enabled) {
        if (!enabled) return@pointerInput
        var lastUp = 0L
        var lastPos = Offset.Zero
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val second = lastUp != 0L && down.uptimeMillis - lastUp < 300L &&
                (down.position - lastPos).getDistance() < viewConfiguration.touchSlop * 5
            if (second) down.consume()
            var moved = false
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (second) ch.consume()
                if ((ch.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                if (!ch.pressed) {
                    when {
                        moved -> lastUp = 0L
                        second -> { lastUp = 0L; action() }
                        else -> { lastUp = ch.uptimeMillis; lastPos = down.position }
                    }
                    break
                }
            }
        }
    }
}

/** Старые заметки хранили фото подряд сверху без положения: раскладываем их по листу один раз. */
private fun placeLegacyPhotos(ctx: android.content.Context, list: List<Photo>): List<Photo> {
    var cursor = 338f
    return list.map { p ->
        if (p.w > 0f) p
        else {
            val r = Media.ratio(ctx, p.name)
            val np = Photo(p.name, 18f, cursor, 300f, r)
            cursor += 300f * r + 12f
            np
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteEditor(vm: NotesViewModel, id: Long, onClose: () -> Unit) {
    var note by remember { mutableStateOf<Note?>(null) }
    var tfv by remember { mutableStateOf(TextFieldValue("")) }
    var spans by remember { mutableStateOf<List<Span>>(emptyList()) }
    var bold by remember { mutableStateOf<List<Span>>(emptyList()) }
    var title by remember { mutableStateOf("") }
    var translateLang by remember { mutableStateOf("en") }
    var sourceLang by remember { mutableStateOf("") }
    var translatingText by remember { mutableStateOf(false) }
    var quickAppend by remember { mutableStateOf(false) }
    var beforeTranslate by remember { mutableStateOf<Triple<String, List<Span>, List<Span>>?>(null) }
    var inks by remember { mutableStateOf<List<Ink>>(emptyList()) }
    var photos by remember { mutableStateOf<List<Photo>>(emptyList()) }
    var selPhoto by remember { mutableStateOf<String?>(null) }
    var pinned by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var fontKey by remember { mutableStateOf("sans") }
    var fontSize by remember { mutableFloatStateOf(18f) }
    var lineH by remember { mutableFloatStateOf(1.5f) }
    var paperIdx by remember { mutableIntStateOf(0) }
    var panel by remember { mutableStateOf(Panel.VOICE) }
    var tool by remember { mutableStateOf(Tool.PEN) }
    var penColor by remember { mutableIntStateOf(PEN_COLORS[0].toArgb()) }
    var thick by remember { mutableFloatStateOf(3f) }
    var showFonts by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var sheetW by remember { mutableFloatStateOf(0f) }
    var textH by remember { mutableFloatStateOf(338f) }
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()

    LaunchedEffect(id) {
        val l = vm.load(id)
        note = l
        tfv = TextFieldValue(l.text)
        spans = Rich.parse(l.spans)
        bold = Rich.parse(l.bold)
        title = l.title
        inks = Ink.parse(l.strokes)
        val loaded = Media.photos(l.images)
        photos = withContext(Dispatchers.IO) { placeLegacyPhotos(context, loaded) }
        fontKey = l.fontKey
        fontSize = l.fontSize
        lineH = l.lineHeight
        paperIdx = l.paper
        pinned = l.pinned
        ready = true
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) vm.startRecording() }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) scope.launch {
            val n = withContext(Dispatchers.IO) { Media.import(context, uri) }
            if (n != null) {
                val r = withContext(Dispatchers.IO) { Media.ratio(context, n) }
                val w = minOf(260f, (sheetW - 36f).coerceAtLeast(120f))
                // Новое фото кладём под текст, чтобы оно ничего не закрыло; дальше его можно перетащить
                val top = maxOf(textH, photos.maxOfOrNull { it.y + it.w * it.ratio } ?: 0f) + 12f
                photos = photos + Photo(n, 18f, top, w, r)
                selPhoto = n
                if (panel == Panel.DRAW) panel = Panel.TEXT
            } else Toast.makeText(context, "Не удалось добавить фото", Toast.LENGTH_SHORT).show()
        }
    }

    fun startDictation() {
        if (vm.recording || vm.busy) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) vm.startRecording() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(vm.dictationRequest, vm.locked) {
        if (vm.dictationRequest && !vm.locked) {
            vm.consumeDictationRequest()
            startDictation()
        }
    }

    fun snapshot(): Note? = note?.takeIf { ready }?.copy(
        text = tfv.text, title = title.trim(), spans = Rich.toJson(spans), bold = Rich.toJson(bold), strokes = Ink.toJson(inks),
        images = Media.toJson(photos), fontKey = fontKey, fontSize = fontSize,
        lineHeight = lineH, paper = paperIdx, pinned = pinned
    )

    // Автосохранение: через секунду после последней правки, чтобы ничего не терялось,
    // даже если приложение закроют или система его выгрузит.
    val editKey = listOf(tfv.text, title, spans, bold, inks, photos, fontKey, fontSize, lineH, paperIdx, pinned, ready)
    LaunchedEffect(editKey) {
        if (!ready) return@LaunchedEffect
        delay(1000)
        snapshot()?.let { vm.autosave(it) }
    }

    // Сразу сохраняем, когда приложение уходит с экрана
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestSnapshot by rememberUpdatedState<() -> Note?>({ snapshot() })
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) latestSnapshot()?.let { vm.save(it) }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    fun close() {
        vm.cancelRecording()
        snapshot()?.let { vm.save(it) }
        onClose()
    }
    BackHandler { if (fullscreen) fullscreen = false else close() }

    fun setText(nt: String) {
        spans = Rich.adjust(tfv.text, nt, spans)
        bold = Rich.adjust(tfv.text, nt, bold)
        tfv = TextFieldValue(nt, TextRange(nt.length))
    }

    /** Жирный для выделенного текста; если он уже весь жирный — снимает. */
    fun toggleBold() {
        val sel = tfv.selection
        if (sel.collapsed) {
            Toast.makeText(context, "Сначала выделите текст", Toast.LENGTH_SHORT).show()
            return
        }
        val covered = Rich.covers(bold, sel.min, sel.max)
        bold = Rich.apply(bold, sel.min, sel.max, if (covered) -1 else 0)
        tfv = tfv.copy(selection = TextRange(sel.max))
    }

    /** Переводит выделенный фрагмент (или весь текст) на выбранный язык. */
    fun translateNow(append: Boolean, target: String = translateLang) {
        if (translatingText) return
        val sel = tfv.selection
        val whole = sel.collapsed
        val s = if (whole) 0 else sel.min
        val e = if (whole) tfv.text.length else sel.max
        val src = tfv.text.substring(s, e)
        if (src.isBlank()) {
            Toast.makeText(context, "Нет текста для перевода", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            translatingText = true
            try {
                val res = vm.translateText(src, sourceLang, target)
                val old = tfv.text
                val before = Triple(old, spans, bold)
                val nt = if (append) old.substring(0, e) + "\n\n" + res + old.substring(e)
                         else old.substring(0, s) + res + old.substring(e)
                val oldSpans = spans; val oldBold = bold
                spans = Rich.adjust(old, nt, oldSpans)
                bold = Rich.adjust(old, nt, oldBold)
                val cursor = if (append) e + 2 + res.length else s + res.length
                tfv = TextFieldValue(nt, TextRange(cursor))
                beforeTranslate = before
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                Toast.makeText(
                    context,
                    if (t is com.example.voicenotes.translate.NllbNotInstalledException) t.message ?: ""
                    else "Перевод не удался: ${t.describeChain()}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                translatingText = false
            }
        }
    }

    fun mark(c: Int) {
        val sel = tfv.selection
        if (sel.collapsed) {
            Toast.makeText(context, "Сначала выделите текст", Toast.LENGTH_SHORT).show()
            return
        }
        val covered = spans.any { it.s <= sel.min && it.e >= sel.max && it.c == c }
        spans = Rich.apply(spans, sel.min, sel.max, if (covered) -1 else c)
        tfv = tfv.copy(selection = TextRange(sel.max))
    }

    if (showFonts) FontDialog(fontKey, onPick = { fontKey = it }, onClose = { showFonts = false })

    val cs = MaterialTheme.colorScheme
    val cs0 = cs
    val paperList = papers()
    val paper = paperList[paperIdx.mod(paperList.size)]
    val family = remember(fontKey) { Fonts.family(context, fontKey) }
    val textStyle = TextStyle(
        color = PaperInk, fontFamily = family,
        fontSize = fontSize.sp, lineHeight = (fontSize * lineH).sp
    )
    val drawing = panel == Panel.DRAW
    val photosBottom = photos.maxOfOrNull { it.y + it.w * it.ratio + 18f } ?: 0f

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            if (!fullscreen) TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = {
                    // Название заметки: коснитесь и впишите своё
                    BasicTextField(
                        value = title,
                        onValueChange = { title = it.replace("\n", " ").take(80) },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = cs0.onBackground, fontSize = 18.sp, fontWeight = FontWeight.SemiBold
                        ),
                        cursorBrush = SolidColor(cs0.primary),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (title.isEmpty()) Text(
                                    if (id == 0L) "Новая заметка" else "Без названия",
                                    color = cs0.onSurfaceVariant, fontSize = 18.sp,
                                    fontWeight = FontWeight.SemiBold, maxLines = 1
                                )
                                inner()
                            }
                        }
                    )
                },
                navigationIcon = { Box(Modifier.padding(start = 12.dp)) { IconBtn(Ico.BACK) { close() } } },
                actions = {
                    Row(
                        Modifier.padding(end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Gap.S)
                    ) {
                        ToggleIconBtn(Ico.PIN, pinned) { pinned = !pinned }
                        IconBtn(Ico.SHARE) {
                            if (tfv.text.isBlank() && title.isBlank()) {
                                Toast.makeText(context, "Нечем делиться: заметка пустая", Toast.LENGTH_SHORT).show()
                            } else {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        if (title.isBlank()) tfv.text else title.trim() + "\n\n" + tfv.text
                                    )
                                }
                                context.startActivity(Intent.createChooser(send, null))
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .padding(horizontal = if (fullscreen) 0.dp else Gap.M)
                .padding(bottom = if (fullscreen) 0.dp else Gap.S),
            verticalArrangement = Arrangement.spacedBy(Gap.M)
        ) {
            // ---------- Лист бумаги: светлый фон, тёмный текст. Двойное нажатие — на весь экран
            val sheetShape = RoundedCornerShape(if (fullscreen) 0.dp else 28.dp)
            Box(
                Modifier.fillMaxWidth().weight(1f)
                    .softCard(paper, sheetShape, if (fullscreen) 0.dp else 16.dp)
            ) {
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Box(
                        Modifier.fillMaxWidth()
                            .heightIn(min = photosBottom.dp)
                            .onSizeChanged { sheetW = it.width / density }
                    ) {
                        Column(
                            Modifier.fillMaxWidth()
                                .onSizeChanged { textH = it.height / density }
                                .padding(18.dp)
                        ) {
                            BasicTextField(
                                value = tfv,
                                onValueChange = { nv ->
                                    if (nv.text != tfv.text) {
                                        spans = Rich.adjust(tfv.text, nv.text, spans)
                                        bold = Rich.adjust(tfv.text, nv.text, bold)
                                    }
                                    tfv = nv
                                },
                                enabled = !drawing,
                                textStyle = textStyle,
                                cursorBrush = SolidColor(Color(0xFF5B3FD9)),
                                visualTransformation = remember(spans, bold, fontSize) {
                                    HighlightTransformation(spans, fontSize, bold)
                                },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp),
                                decorationBox = { inner ->
                                    Box {
                                        if (tfv.text.isEmpty()) Text(
                                            "Текст заметки…",
                                            style = textStyle.copy(color = PaperMuted)
                                        )
                                        inner()
                                    }
                                }
                            )
                        }
                        // Фото лежат поверх текста; выбранное поднимается наверх
                        photos.sortedBy { if (it.name == selPhoto) 1 else 0 }.forEach { p ->
                            key(p.name) {
                                PhotoItem(
                                    p = p,
                                    selected = p.name == selPhoto && !drawing,
                                    enabled = !drawing,
                                    maxW = sheetW,
                                    onSelect = { selPhoto = p.name },
                                    onChange = { np -> photos = photos.map { if (it.name == np.name) np else it } },
                                    onDelete = {
                                        photos = photos.filterNot { it.name == p.name }
                                        Media.file(context, p.name).delete()
                                        selPhoto = null
                                    }
                                )
                            }
                        }
                        InkLayer(
                            inks = inks, drawing = drawing, tool = tool, color = penColor, thick = thick,
                            onAdd = { inks = inks + it },
                            onErase = { p, r ->
                                inks = inks.filterNot { k -> k.pts.any { (it - p).getDistance() < r } }
                            },
                            onMove = { idx, delta ->
                                inks = inks.mapIndexed { i, k ->
                                    if (idx == INK_ALL || i == idx) k.copy(pts = k.pts.map { it + delta }) else k
                                }
                            },
                            modifier = Modifier.matchParentSize()
                        )
                    }
                }
                // Развернуть лист / вернуть обратно (двойное нажатие убрано: оно мешало выделять текст)
                Box(
                    Modifier.align(if (fullscreen) Alignment.TopEnd else Alignment.BottomEnd)
                        .padding(Gap.M).size(40.dp)
                        .pressable(CircleShape, cs.surfaceContainerHigh, 4.dp) {
                            fullscreen = !fullscreen
                            selPhoto = null
                        },
                    contentAlignment = Alignment.Center
                ) { AppIcon(if (fullscreen) Ico.COLLAPSE else Ico.RESIZE, cs.onSurface, 20.dp) }
            }

            // ---------- Быстрый перевод выделенного фрагмента
            if (!fullscreen && !drawing && !tfv.selection.collapsed) Row(
                Modifier.fillMaxWidth().softCard(cs.surfaceContainer, RoundedCornerShape(22.dp), 8.dp)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Gap.M, vertical = Gap.S),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Gap.S)
            ) {
                Text(
                    if (translatingText) "Перевожу…" else "Перевести выделенное:",
                    color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelLarge
                )
                listOf("ru" to "Русский", "en" to "English", "ka" to "ქართული").forEach { (code, label) ->
                    AppChip(
                        selected = false, enabled = !translatingText,
                        onClick = { translateNow(append = quickAppend, target = code) },
                        label = { Text(label) }
                    )
                }
                AppChip(selected = quickAppend, onClick = { quickAppend = !quickAppend }, label = { Text("Добавить ниже") })
            }

            // ---------- Панель инструментов
            if (!fullscreen) Column(
                Modifier.fillMaxWidth().softCard(cs.surfaceContainer, RoundedCornerShape(26.dp), 12.dp)
                    .padding(Gap.M),
                verticalArrangement = Arrangement.spacedBy(Gap.M)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Gap.S)) {
                    DockTab(Ico.MIC, "Голос", panel == Panel.VOICE, Modifier.weight(1f)) { panel = Panel.VOICE }
                    DockTab(Ico.TEXT, "Текст", panel == Panel.TEXT, Modifier.weight(1f)) { panel = Panel.TEXT }
                    DockTab(Ico.MARKER, "Маркер", panel == Panel.MARK, Modifier.weight(1f)) { panel = Panel.MARK }
                    DockTab(Ico.PEN, "Рисунок", panel == Panel.DRAW, Modifier.weight(1f)) { panel = Panel.DRAW }
                    DockTab(Ico.PHOTO, "Фото", false, Modifier.weight(1f)) {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                }
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Gap.L)
                ) {
                    when (panel) {
                        Panel.VOICE -> VoicePanel(vm, onDictate = {
                            if (vm.recording) {
                                vm.stopRecording { t ->
                                    setText(if (tfv.text.isBlank()) t else tfv.text.trimEnd() + " " + t)
                                }
                            } else startDictation()
                        }, onOk = {
                            // «ОК»: дослушать и распознать запись (если идёт), сохранить и выйти на главный экран
                            if (vm.recording) {
                                vm.stopRecording(onDone = { close() }) { t ->
                                    setText(if (tfv.text.isBlank()) t else tfv.text.trimEnd() + " " + t)
                                }
                            } else close()
                        })

                        Panel.TEXT -> {
                            Section("Начертание") {
                                Hint("Выделите текст и нажмите «Жирный». Повторное нажатие снимает.")
                                Row(horizontalArrangement = Arrangement.spacedBy(Gap.S)) {
                                    PillButton("Жирный", primary = Rich.covers(bold, tfv.selection.min, tfv.selection.max) && !tfv.selection.collapsed) { toggleBold() }
                                    PillButton("Снять жирный", enabled = bold.isNotEmpty()) { bold = emptyList() }
                                }
                            }
                            Section("Перевод текста") {
                                Hint("Печатайте текст сами, затем переведите его. Если выделен фрагмент — переведётся он, иначе весь текст.")
                                Text("Исходный язык", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                Row(
                                    Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(Gap.S)
                                ) {
                                    listOf("" to "Авто", "ru" to "Русский", "en" to "English", "ka" to "ქართული").forEach { (code, label) ->
                                        AppChip(selected = sourceLang == code, onClick = { sourceLang = code }, label = { Text(label) })
                                    }
                                }
                                Text("Перевести на", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                Row(
                                    Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(Gap.S)
                                ) {
                                    listOf("ru" to "Русский", "en" to "English", "ka" to "ქართული").forEach { (code, label) ->
                                        AppChip(selected = translateLang == code, onClick = { translateLang = code }, label = { Text(label) })
                                    }
                                }
                                TranslateModeChips(vm)
                                Row(
                                    Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(Gap.S)
                                ) {
                                    PillButton(
                                        if (translatingText) "Перевожу…" else "Заменить переводом",
                                        primary = true, enabled = !translatingText && tfv.text.isNotBlank()
                                    ) { translateNow(append = false) }
                                    PillButton(
                                        "Добавить ниже",
                                        enabled = !translatingText && tfv.text.isNotBlank()
                                    ) { translateNow(append = true) }
                                }
                                if (beforeTranslate != null) PillButton("Вернуть до перевода") {
                                    beforeTranslate?.let { (t, sp, bd) ->
                                        spans = sp; bold = bd
                                        tfv = TextFieldValue(t, TextRange(t.length))
                                    }
                                    beforeTranslate = null
                                }
                                Hint("Перевод выполняет NLLB-200 прямо на телефоне, без интернета. Модель скачивается один раз: меню → «Модели распознавания».")
                            }
                            Section("Шрифт") {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .pressable(RoundedCornerShape(18.dp), cs.surfaceContainerHighest, 3.dp) { showFonts = true }
                                        .padding(horizontal = Gap.L, vertical = Gap.M),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(Fonts.label(context, fontKey), fontFamily = family, fontSize = 17.sp, color = cs.onSurface, modifier = Modifier.weight(1f))
                                    Text("›", color = cs.onSurfaceVariant, fontSize = 20.sp)
                                }
                            }
                            Section("Размер текста: ${fontSize.toInt()}") { AppSlider(fontSize, 12f..38f) { fontSize = it } }
                            Section("Межстрочный интервал: ${"%.1f".format(lineH)}") { AppSlider(lineH, 1.1f..2.6f) { lineH = it } }
                            Section("Цвет листа") {
                                Row(horizontalArrangement = Arrangement.spacedBy(Gap.M)) {
                                    paperList.forEachIndexed { i, c -> Swatch(c, i == paperIdx) { paperIdx = i } }
                                }
                            }
                            Section("Лист") {
                                Hint("Кнопка со стрелками в углу листа открывает его на весь экран, ещё раз — возвращает обратно.")
                                if (photos.isNotEmpty()) Hint(
                                    "Фото: коснитесь, чтобы выбрать, и тяните. Размер меняет уголок или щипок двумя пальцами."
                                )
                            }
                        }

                        Panel.MARK -> {
                            Section("Маркер") {
                                Hint("Выделите текст пальцем и нажмите цвет. Повторное нажатие того же цвета снимает маркер.")
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(Gap.M),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    HIGHLIGHTS.forEachIndexed { i, c -> Swatch(c, false) { mark(i) } }
                                    PillButton("Снять") {
                                        val sel = tfv.selection
                                        if (!sel.collapsed) spans = Rich.apply(spans, sel.min, sel.max, -1)
                                    }
                                    PillButton("Жирный") { toggleBold() }
                                }
                            }
                        }

                        Panel.DRAW -> {
                            Section("Инструмент") {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(Gap.S),
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    listOf(
                                        Tool.PEN to "Ручка", Tool.MARKER to "Фломастер", Tool.ERASER to "Ластик",
                                        Tool.MOVE_ONE to "Двигать линию", Tool.MOVE_ALL to "Весь рисунок"
                                    ).forEach { (t, l) ->
                                        AppChip(selected = tool == t, onClick = { tool = t }, label = { Text(l) })
                                    }
                                }
                                Hint(
                                    when (tool) {
                                        Tool.MOVE_ONE -> "Коснитесь линии и перетащите её."
                                        Tool.MOVE_ALL -> "Перетащите весь рисунок целиком."
                                        else -> "Рисуйте прямо на листе. Чтобы печатать или прокручивать, выберите другую вкладку."
                                    }
                                )
                            }
                            if (tool == Tool.PEN || tool == Tool.MARKER) {
                                Section("Цвет") {
                                    Row(horizontalArrangement = Arrangement.spacedBy(Gap.M)) {
                                        PEN_COLORS.forEach { c ->
                                            Swatch(c, penColor == c.toArgb()) { penColor = c.toArgb() }
                                        }
                                    }
                                }
                                Section("Толщина: ${"%.1f".format(thick)}") { AppSlider(thick, 1f..12f) { thick = it } }
                            }
                            Section("Правка") {
                                Row(horizontalArrangement = Arrangement.spacedBy(Gap.S)) {
                                    PillButton("Отменить", enabled = inks.isNotEmpty()) { inks = inks.dropLast(1) }
                                    PillButton("Очистить", enabled = inks.isNotEmpty()) { inks = emptyList() }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Слой рисования поверх листа. Координаты хранятся в dp, чтобы не зависеть от плотности экрана. */
@Composable
private fun InkLayer(
    inks: List<Ink>, drawing: Boolean, tool: Tool, color: Int, thick: Float,
    onAdd: (Ink) -> Unit, onErase: (Offset, Float) -> Unit, onMove: (Int, Offset) -> Unit,
    modifier: Modifier
) {
    val d = LocalDensity.current.density
    var live by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var moveIdx by remember { mutableIntStateOf(INK_NONE) }
    var moveDelta by remember { mutableStateOf(Offset.Zero) }
    val add by rememberUpdatedState(onAdd)
    val erase by rememberUpdatedState(onErase)
    val move by rememberUpdatedState(onMove)
    val all by rememberUpdatedState(inks)
    val halo = Color(0x665B3FD9).toArgb()

    // Без режима рисования слой не принимает касания вовсе, иначе он закрывает текст и не даёт его выделить
    Canvas(
        modifier.then(if (!drawing) Modifier else Modifier.pointerInput(drawing, tool, color, thick) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()

                if (tool == Tool.MOVE_ONE || tool == Tool.MOVE_ALL) {
                    val idx = if (tool == Tool.MOVE_ALL) {
                        if (all.isEmpty()) INK_NONE else INK_ALL
                    } else pickInk(all, down.position / d)
                    if (idx == INK_NONE) {
                        do {
                            val ev = awaitPointerEvent()
                            ev.changes.forEach { it.consume() }
                        } while (ev.changes.any { it.pressed })
                        return@awaitEachGesture
                    }
                    moveIdx = idx
                    moveDelta = Offset.Zero
                    var total = Offset.Zero
                    do {
                        val ev = awaitPointerEvent()
                        ev.changes.forEach {
                            if (it.positionChanged()) {
                                total += (it.position - it.previousPosition) / d
                                it.consume()
                            }
                        }
                        moveDelta = total
                    } while (ev.changes.any { it.pressed })
                    if (total != Offset.Zero) move(idx, total)
                    moveIdx = INK_NONE
                    moveDelta = Offset.Zero
                    return@awaitEachGesture
                }

                val pts = mutableListOf(down.position)
                if (tool == Tool.ERASER) erase(down.position / d, 14f) else live = pts.toList()
                do {
                    val ev = awaitPointerEvent()
                    ev.changes.forEach {
                        if (it.positionChanged()) {
                            pts += it.position
                            it.consume()
                            if (tool == Tool.ERASER) erase(it.position / d, 14f) else live = pts.toList()
                        }
                    }
                } while (ev.changes.any { it.pressed })
                if (tool != Tool.ERASER) {
                    add(Ink(color, thick, tool == Tool.MARKER, pts.map { it / d }))
                }
                live = emptyList()
            }
        })
    ) {
        inks.forEachIndexed { i, k ->
            if (moveIdx == INK_ALL || moveIdx == i) {
                val moved = k.pts.map { it + moveDelta }
                if (moveIdx == i) drawInk(Ink(halo, k.width + 8f, false, moved), d)
                drawInk(Ink(k.color, k.width, k.marker, moved), d)
            } else drawInk(k, d)
        }
        if (live.isNotEmpty()) drawInk(Ink(color, thick, tool == Tool.MARKER, live.map { it / d }), d)
    }
}

/**
 * Фото на листе. Тянуть — двигать, щипок или уголок — менять размер.
 * Положение и ширина в dp, остаётся внутри листа.
 */
@Composable
private fun PhotoItem(
    p: Photo, selected: Boolean, enabled: Boolean, maxW: Float,
    onSelect: () -> Unit, onChange: (Photo) -> Unit, onDelete: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val bmp = rememberBitmap(p.name)
    val d = LocalDensity.current.density
    var x by remember { mutableFloatStateOf(p.x) }
    var y by remember { mutableFloatStateOf(p.y) }
    var w by remember { mutableFloatStateOf(p.w) }
    val limit by rememberUpdatedState(maxW)
    val change by rememberUpdatedState(onChange)
    val select by rememberUpdatedState(onSelect)
    val shape = RoundedCornerShape(14.dp)

    fun commit() {
        val mw = if (limit > 0f) limit else 1200f
        w = w.coerceIn(80f, maxOf(80f, mw))
        x = x.coerceIn(0f, maxOf(0f, mw - w))
        y = y.coerceAtLeast(0f)
        change(Photo(p.name, x, y, w, p.ratio))
    }

    if (bmp == null) return
    Box(
        Modifier
            .offset { IntOffset((x * d).roundToInt(), (y * d).roundToInt()) }
            .width(w.dp)
            .then(
                if (!enabled) Modifier else Modifier
                    .pointerInput(Unit) { detectTapGestures(onTap = { select() }) }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            select()
                            val nw = w * zoom
                            x += pan.x / d - (nw - w) / 2f
                            y += pan.y / d - (nw - w) * p.ratio / 2f
                            w = nw
                            commit()
                        }
                    }
            )
    ) {
        Image(
            bmp, null,
            Modifier.fillMaxWidth().shadow(6.dp, shape).clip(shape),
            contentScale = ContentScale.FillWidth
        )
        if (selected) {
            Box(Modifier.matchParentSize().border(2.dp, cs.primary, shape))
            Box(
                Modifier.align(Alignment.TopEnd).padding(Gap.S).size(36.dp)
                    .pressable(CircleShape, Color(0xFF26233A), 4.dp) { onDelete() },
                contentAlignment = Alignment.Center
            ) { AppIcon(Ico.CLOSE, Color.White, 16.dp) }
            Box(
                Modifier.align(Alignment.BottomEnd).padding(Gap.S).size(36.dp)
                    .shadow(4.dp, CircleShape).background(cs.primary, CircleShape)
                    .pointerInput(Unit) {
                        detectDragGestures { c, drag ->
                            c.consume()
                            w += drag.x / d
                            commit()
                        }
                    },
                contentAlignment = Alignment.Center
            ) { AppIcon(Ico.RESIZE, cs.onPrimary, 20.dp) }
        }
    }
}

@Composable
private fun DockTab(icon: Ico, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val fg = if (selected) cs.onPrimary else cs.onSurface
    Column(
        modifier
            .pressable(RoundedCornerShape(18.dp), if (selected) cs.primary else cs.surfaceContainerHighest, 4.dp, onClick = onClick)
            .padding(vertical = Gap.S),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        AppIcon(icon, fg, 22.dp)
        Text(label, fontSize = 11.sp, maxLines = 1, textAlign = TextAlign.Center, color = fg, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Swatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(38.dp)
            .border(if (selected) 3.dp else 0.dp, if (selected) cs.primary else Color.Transparent, CircleShape)
            .padding(if (selected) 4.dp else 0.dp)
            .pressable(CircleShape, color, 3.dp, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { if (selected) Text("✓", color = if (color.luminance() < 0.3f) Color.White else PaperInk, fontSize = 15.sp) }
}

@Composable
private fun FontDialog(current: String, onPick: (String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val k = Fonts.add(ctx, uri)
            if (k == null) Toast.makeText(ctx, "Нужен файл шрифта .ttf или .otf", Toast.LENGTH_LONG).show()
            else { tick++; onPick(k) }
        }
    }
    val list = remember(tick) { Fonts.builtIn + Fonts.custom(ctx) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Шрифт") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Gap.S)) {
                list.forEach { (key, name) ->
                    Row(
                        Modifier.fillMaxWidth()
                            .pressable(
                                RoundedCornerShape(14.dp),
                                if (key == current) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHighest,
                                2.dp
                            ) { onPick(key) }
                            .padding(vertical = 10.dp, horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            name, fontFamily = Fonts.family(ctx, key), fontSize = 20.sp,
                            modifier = Modifier.weight(1f)
                        )
                        if (key == current) Text("✓", color = MaterialTheme.colorScheme.primary, fontSize = 18.sp)
                        if (key.startsWith("f:")) PillButton("Удалить", Modifier.padding(start = 8.dp)) {
                            Fonts.remove(ctx, key)
                            if (key == current) onPick("sans")
                            tick++
                        }
                    }
                }
            }
        },
        confirmButton = { PillButton("Добавить шрифт", icon = Ico.PLUS, primary = true) { picker.launch(arrayOf("*/*")) } },
        dismissButton = { PillButton("Готово", onClick = onClose) }
    )
}


/** Перевод всегда идёт на устройстве моделью NLLB-200: текст никуда не отправляется. */
@Composable
private fun TranslateModeChips(vm: NotesViewModel) {
    Hint(
        if (vm.isInstalled(com.example.voicenotes.speech.ModelPack.NLLB))
            "Перевод на устройстве (NLLB-200). Текст не покидает телефон."
        else
            "Модель перевода не скачана: меню → «Модели распознавания» → «Перевод (NLLB-200, 600M)»."
    )
}

@Composable
private fun VoicePanel(vm: NotesViewModel, onDictate: () -> Unit, onOk: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Section("Язык речи") {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Gap.S)
        ) {
            val canSwitch = !vm.recording && !vm.busy && !vm.modelLoading
            listOf("" to "Авто", "ru" to "Русский", "en" to "English", "ka" to "ქართული").forEach { (code, label) ->
                AppChip(selected = vm.language == code, onClick = { vm.selectLanguage(code) }, label = { Text(label) }, enabled = canSwitch)
            }
        }
        if (vm.language == "" || vm.language == "en") {
            Hint("Для русской речи выберите «Русский»: так работает более точная модель.")
        }
    }
    Section("Перевод") {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Gap.S)
        ) {
            val canSwitchTranslate = !vm.recording && !vm.busy
            listOf("" to "Нет", "en" to "English", "ka" to "ქართული").forEach { (code, label) ->
                AppChip(
                    selected = vm.translateTo == code,
                    onClick = { vm.selectTranslateTo(code) },
                    label = { Text(label) },
                    enabled = canSwitchTranslate && (code == "" || code != vm.language)
                )
            }
        }
        TranslateModeChips(vm)
        if (vm.translateTo != "") {
            Hint(
                if (vm.language == "") "Перевод работает, когда язык речи выбран вручную."
                else "Говорите на выбранном языке речи, в заметку попадёт перевод. " +
                    "Перевод идёт на устройстве (NLLB-200); сначала скачайте модель: меню → «Модели распознавания»."
            )
        }
        if (vm.notice.isNotBlank()) Hint(vm.notice, cs.error)
    }
    if (!vm.isInstalled(vm.currentPack)) {
        ModelDownloadCard(
            vm, vm.currentPack,
            hint = "Для этого языка нужна модель. Она скачивается один раз, " +
                "дальше распознавание работает без интернета."
        )
    }
    if (vm.recording) {
        Section("Идёт запись · ${formatTime(vm.seconds)}") {
            LinearProgressIndicator(progress = { vm.level }, modifier = Modifier.fillMaxWidth())
        }
    }
    val enabled = !vm.busy && (vm.recording || (!vm.modelLoading && vm.isInstalled(vm.currentPack)))
    val bg = if (vm.recording) cs.error else cs.primary
    val fg = if (vm.recording) cs.onError else cs.onPrimary
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Gap.S),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier.weight(1f).alpha(if (enabled) 1f else 0.5f)
                .pressable(RoundedCornerShape(50), bg, 8.dp, enabled, onDictate)
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(Ico.MIC, fg, 20.dp)
            Spacer(Modifier.width(Gap.S))
            Text(
                when {
                    vm.recording -> "Стоп"
                    !vm.isInstalled(vm.currentPack) -> "Сначала скачайте модель"
                    vm.modelLoading -> "Загружаю модель…"
                    vm.translating -> "Перевожу…"
                    vm.busy -> "Распознаю…"
                    else -> "Диктовать"
                },
                color = fg, style = MaterialTheme.typography.titleSmall
            )
        }
        // Маленькая «ОК»: остановить запись (если идёт), сохранить заметку и выйти на главный экран
        val okEnabled = !vm.busy
        Box(
            Modifier.alpha(if (okEnabled) 1f else 0.5f)
                .pressable(RoundedCornerShape(50), cs.secondaryContainer, 6.dp, okEnabled, onOk)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("ОК", color = cs.onSecondaryContainer, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
    }
}
