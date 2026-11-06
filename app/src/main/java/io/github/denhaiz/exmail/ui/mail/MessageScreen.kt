package io.github.denhaiz.exmail.ui.mail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.MimeTypeMap
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import kotlin.math.roundToInt
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.ScrollState
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.Forward
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.outlined.ReplyAll
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.TextDecrease
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.TextIncrease
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import io.github.denhaiz.exmail.data.AccountStore
import io.github.denhaiz.exmail.data.AppSettings
import io.github.denhaiz.exmail.data.FileSaver
import io.github.denhaiz.exmail.data.InlineImages
import io.github.denhaiz.exmail.data.db.FolderEntity
import io.github.denhaiz.exmail.data.db.MessageEntity
import io.github.denhaiz.exmail.data.repo.CalendarRepository
import io.github.denhaiz.exmail.data.repo.MailRepository
import io.github.denhaiz.exmail.data.userMessage
import io.github.denhaiz.exmail.ews.AttachmentInfo
import io.github.denhaiz.exmail.ews.ItemRef
import io.github.denhaiz.exmail.ews.Mailbox
import io.github.denhaiz.exmail.ews.MeetingResponse
import io.github.denhaiz.exmail.ews.MessageDetail
import io.github.denhaiz.exmail.ui.common.Avatar
import io.github.denhaiz.exmail.ui.common.ConfirmDialog
import io.github.denhaiz.exmail.ui.common.EmptyState
import io.github.denhaiz.exmail.ui.common.Format
import io.github.denhaiz.exmail.ui.common.HtmlView
import io.github.denhaiz.exmail.ui.common.LocalAppScope
import io.github.denhaiz.exmail.ui.common.MailPageStyle
import io.github.denhaiz.exmail.ui.common.Motion
import io.github.denhaiz.exmail.ui.common.mailPageStyle
import java.io.File
import java.time.Instant
import javax.inject.Inject

@HiltViewModel
class MessageViewModel @Inject constructor(
    private val repo: MailRepository,
    private val calendar: CalendarRepository,
    private val store: AccountStore,
    @ApplicationContext private val context: Context,
    saved: SavedStateHandle,
) : ViewModel() {
    val id: String = checkNotNull(saved["id"])
    /** Открыто из списка папки (не из поиска) — соседние письма листаются свайпом. */
    private val browse: Boolean = saved.get<String>("browse") != "0"

    var detail by mutableStateOf<MessageDetail?>(null)
        private set
    /** Заголовок письма из списка — показывается сразу, пока загружается текст. */
    var preview by mutableStateOf<MessageDetail?>(null)
        private set
    var html by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    /** Вложение, которое сейчас скачивается (открытие или сохранение). */
    var downloading by mutableStateOf<String?>(null)
        private set
    /** Соседние письма папки: новее (выше в списке) и старее (ниже) — для перелистывания свайпом. */
    var newerId by mutableStateOf<String?>(null)
        private set
    var olderId by mutableStateOf<String?>(null)
        private set
    var showImages by mutableStateOf(false)
    /** null — по настройке «Тёмные письма»; иначе выбор для этого письма. */
    var darkOverride by mutableStateOf<Boolean?>(null)

    val settings = store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val folders = repo.folders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    sealed interface Event {
        data class Message(val text: String) : Event
        data object Close : Event
        data class Open(val file: File, val contentType: String) : Event
        /** Файл сохранён в «Загрузки» (Android 10+). */
        data class Saved(val name: String, val uri: Uri, val contentType: String) : Event
        /** Android 8–9: пользователь выбирает место сохранения сам. */
        data class SaveAs(val file: File, val name: String, val contentType: String) : Event
    }

    init {
        load()
    }

    fun load() {
        error = null
        viewModelScope.launch {
            if (preview == null) preview = repo.cachedMessage(id)?.toPreview()
            if (browse) repo.neighbors(id)?.let { (newer, older) ->
                newerId = newer
                olderId = older
            }
            try {
                val d = repo.getMessage(id)
                // Текст с подставленными адресами встроенных картинок — страница загружается один раз, без мигания
                html = repo.renderableHtml(d)
                detail = d
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (detail == null) error = e.userMessage() else _events.send(Event.Message(e.userMessage()))
            }
        }
    }

    override fun onCleared() {
        repo.cancelInlinePrefetch(id)
    }

    fun setTextZoom(percent: Int) = viewModelScope.launch { store.setMessageZoom(percent) }

    fun toggleFlag() = action {
        val d = detail ?: preview ?: return@action
        val flagged = !d.flagged
        repo.setFlag(listOf(d.ref.id), flagged)
        // За время запроса письмо могло загрузиться — меняем то, что сейчас на экране, а не старую копию
        detail?.let { detail = it.copy(flagged = flagged) }
        preview = preview?.copy(flagged = flagged)
        _events.send(Event.Message(if (flagged) "Отмечено флажком" else "Флажок снят"))
    }

    fun markUnread() = action {
        repo.markRead(listOf(id), false)
        _events.send(Event.Close)
    }

    fun delete() = action {
        repo.delete(listOf(id))
        _events.send(Event.Close)
    }

    fun move(folder: FolderEntity) = action {
        repo.move(listOf(id), folder.id)
        _events.send(Event.Message("Перемещено в «${folder.name}»"))
        _events.send(Event.Close)
    }

    fun respond(r: MeetingResponse) = action {
        repo.respondToMeeting(id, r)
        calendar.invalidate()
        _events.send(
            Event.Message(
                when (r) {
                    MeetingResponse.ACCEPT -> "Приглашение принято"
                    MeetingResponse.TENTATIVE -> "Ответ «под вопросом» отправлен"
                    MeetingResponse.DECLINE -> "Приглашение отклонено"
                },
            ),
        )
        _events.send(Event.Close)
    }

    /** Открыть вложение во внешнем приложении. */
    fun open(att: AttachmentInfo) = download(att) { file -> _events.send(Event.Open(file, att.contentType)) }

    /** Сохранить вложение в «Загрузки». */
    fun save(att: AttachmentInfo) = download(att) { file -> store(file, att.name, att.contentType) }

    /** Сохранить все файлы письма в «Загрузки». */
    fun saveAll(list: List<AttachmentInfo>) {
        if (downloading != null) return
        viewModelScope.launch {
            var saved = 0
            for (att in list.filter { !it.isItem }) {
                downloading = att.id
                try {
                    val file = repo.downloadAttachment(att)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        FileSaver.saveToDownloads(context, file, att.name, FileSaver.mimeOf(att.name, att.contentType))
                        saved++
                    } else {
                        _events.send(Event.SaveAs(file, att.name, FileSaver.mimeOf(att.name, att.contentType)))
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e // закрыли экран или начали новый запрос — это не ошибка
                    _events.send(Event.Message("«${att.name}» не сохранено: ${e.userMessage()}"))
                }
            }
            downloading = null
            if (saved > 0) _events.send(Event.Message("Сохранено файлов: $saved — «Загрузки/${FileSaver.FOLDER}»"))
        }
    }

    /** Картинка из текста письма (долгое нажатие): сохранить или открыть. */
    fun saveImage(src: String, openAfter: Boolean = false) {
        if (downloading != null) return
        downloading = IMAGE
        viewModelScope.launch {
            try {
                val (file, mime) = repo.downloadImage(src)
                if (openAfter) _events.send(Event.Open(file, mime)) else store(file, file.name, mime)
            } catch (e: Exception) {
                if (e is CancellationException) throw e // закрыли экран или начали новый запрос — это не ошибка
                _events.send(Event.Message("Не удалось сохранить картинку: ${e.userMessage()}"))
            } finally {
                downloading = null
            }
        }
    }

    private suspend fun store(file: File, name: String, contentType: String) {
        val mime = FileSaver.mimeOf(name, contentType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = FileSaver.saveToDownloads(context, file, name, mime)
            _events.send(Event.Saved(name, uri, mime))
        } else {
            _events.send(Event.SaveAs(file, name, mime))
        }
    }

    private fun download(att: AttachmentInfo, then: suspend (File) -> Unit) {
        if (downloading != null) return
        if (att.isItem) {
            viewModelScope.launch { _events.send(Event.Message("Вложенные письма пока открываются только в Outlook")) }
            return
        }
        downloading = att.id
        viewModelScope.launch {
            try {
                then(repo.downloadAttachment(att))
            } catch (e: Exception) {
                if (e is CancellationException) throw e // закрыли экран или начали новый запрос — это не ошибка
                _events.send(Event.Message("Не удалось скачать «${att.name}»: ${e.userMessage()}"))
            } finally {
                downloading = null
            }
        }
    }

    /** Копирование в место, выбранное пользователем (Android 8–9). */
    fun copyTo(file: File, target: Uri) = viewModelScope.launch {
        try {
            FileSaver.copyTo(context, file, target)
            _events.send(Event.Message("Файл сохранён"))
        } catch (e: Exception) {
            if (e is CancellationException) throw e // закрыли экран или начали новый запрос — это не ошибка
            _events.send(Event.Message("Не сохранено: ${e.userMessage()}"))
        }
    }

    private fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                if (e is CancellationException) throw e // закрыли экран или начали новый запрос — это не ошибка
                _events.send(Event.Message(e.userMessage()))
            } finally {
                busy = false
            }
        }
    }

    companion object {
        const val IMAGE = "image"
    }
}

/** Заголовок письма из списка: тема, отправитель, время — без текста и вложений. */
private fun MessageEntity.toPreview() = MessageDetail(
    ref = ItemRef(id, changeKey),
    subject = subject,
    from = Mailbox(fromName, fromEmail),
    to = if (displayTo.isBlank()) emptyList() else listOf(Mailbox(displayTo, "")),
    cc = emptyList(),
    bcc = emptyList(),
    received = if (receivedAt > 0) Instant.ofEpochMilli(receivedAt) else null,
    bodyHtml = "",
    isRead = true,
    flagged = flagged,
    importance = if (highImportance) "High" else "Normal",
    itemClass = itemClass,
    attachments = emptyList(),
    meeting = null,
    parentFolderId = folderId,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageScreen(
    onBack: () -> Unit,
    onReply: (id: String, mode: String) -> Unit,
    onComposeTo: (String) -> Unit,
    onOpenContact: (String) -> Unit,
    snackbar: SnackbarHostState,
    /** Открыть соседнее письмо вместо текущего; [direction] +1 — более старое (свайп влево), −1 — более новое. */
    onOpenSibling: (id: String, direction: Int) -> Unit = { _, _ -> },
    vm: MessageViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val screenScope = rememberCoroutineScope()
    val scope = LocalAppScope.current ?: screenScope
    val settings by vm.settings.collectAsState()
    val folders by vm.folders.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var moveDialog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var zoomBar by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var imageMenu by remember { mutableStateOf<String?>(null) }
    var pendingSave by remember { mutableStateOf<File?>(null) }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val file = pendingSave
        pendingSave = null
        if (uri != null && file != null) vm.copyTo(file, uri)
    }

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is MessageViewModel.Event.Message -> scope.launch { snackbar.showSnackbar(e.text) }
                MessageViewModel.Event.Close -> onBack()
                is MessageViewModel.Event.Open -> openFile(context, e.file, e.contentType)?.let { scope.launch { snackbar.showSnackbar(it) } }
                is MessageViewModel.Event.Saved -> scope.launch {
                    val r = snackbar.showSnackbar(
                        "«${e.name}» сохранено в «Загрузки/${FileSaver.FOLDER}»",
                        actionLabel = "Открыть",
                        duration = SnackbarDuration.Long,
                    )
                    if (r == SnackbarResult.ActionPerformed) openUri(context, e.uri, e.contentType, e.name)?.let { snackbar.showSnackbar(it) }
                }
                is MessageViewModel.Event.SaveAs -> {
                    pendingSave = e.file
                    saveAs.launch(FileSaver.safeName(e.name))
                }
            }
        }
    }

    val appDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val style = mailPageStyle(vm.darkOverride ?: settings.darkMessages)

    val loaded = vm.detail
    val d = loaded ?: vm.preview
    val scroll = rememberScrollState()
    // Заголовок письма ушёл вверх — тема видна в верхней панели
    var subjectPx by remember { mutableIntStateOf(Int.MAX_VALUE) }
    val subjectInBar by remember { derivedStateOf { scroll.value > subjectPx } }
    var web by remember { mutableStateOf<WebView?>(null) }
    // Перелистывание: сдвиг содержимого за пальцем
    val swipeX = remember { Animatable(0f) }
    var widthPx by remember { mutableIntStateOf(1) }
    // Щипок: размер текста меняется сразу, сохраняется, когда пальцы отпущены
    var pinchZoom by remember { mutableStateOf<Int?>(null) }
    val textZoom = pinchZoom ?: settings.messageZoom
    // Размер сохранён — подсказка щипка убирается, текст не «прыгает» обратно
    LaunchedEffect(settings.messageZoom) { if (pinchZoom == settings.messageZoom) pinchZoom = null }
    val gestures = remember(vm) {
        object : MessageGestures {
            override fun canSwipe(direction: Int): Boolean {
                if (zoomBar) return false // ползунок размера текста тянут вбок — это не перелистывание
                val target = if (direction > 0) vm.olderId else vm.newerId
                // Широкое письмо, которое само прокручивается вбок, — свайп для прокрутки, а не для перелистывания
                return target != null && web?.canScrollHorizontally(direction) != true
            }

            override fun onDrag(dx: Float) {
                scope.launch { swipeX.snapTo(dx) }
            }

            override fun onRelease(dx: Float, velocity: Float) {
                val direction = if (dx < 0) 1 else -1
                val target = if (direction > 0) vm.olderId else vm.newerId
                val far = kotlin.math.abs(dx) > widthPx * 0.3f
                val fling = kotlin.math.abs(velocity) > 1200f && (velocity < 0) == (dx < 0) && kotlin.math.abs(dx) > widthPx * 0.1f
                if (target != null && (far || fling)) onOpenSibling(target, direction)
                else scope.launch { swipeX.animateTo(0f, tween(Motion.MEDIUM)) }
            }

            // Новый щипок сразу после предыдущего продолжает от показанного размера, а не от ещё не сохранённого
            override val zoom: Int get() = pinchZoom ?: settings.messageZoom

            override fun onPinch(zoom: Float) {
                pinchZoom = ((zoom / 10).roundToInt() * 10).coerceIn(70, 200)
            }

            override fun onPinchEnd() {
                pinchZoom?.let { vm.setTextZoom(it) }
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад") } },
                title = {
                    AnimatedVisibility(subjectInBar && d != null, enter = fadeIn(tween(Motion.SHORT)), exit = fadeOut(tween(Motion.SHORT))) {
                        Text(
                            d?.subject?.ifBlank { "(без темы)" }.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    if (d != null) {
                        IconButton(onClick = { confirmDelete = true }, enabled = !vm.busy) { Icon(Icons.Outlined.Delete, "Удалить") }
                        IconButton(onClick = vm::markUnread, enabled = !vm.busy) {
                            Icon(Icons.Outlined.MarkEmailUnread, "Пометить непрочитанным")
                        }
                        IconButton(onClick = vm::toggleFlag, enabled = !vm.busy) {
                            if (d.flagged) Icon(Icons.Filled.Flag, "Снять флажок", tint = Color(0xFFD32F2F))
                            else Icon(Icons.Outlined.Flag, "Отметить флажком")
                        }
                        Box {
                            IconButton(onClick = { menu = true }, enabled = loaded != null) { Icon(Icons.Outlined.MoreVert, "Ещё") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                MenuItem("Размер текста", Icons.Outlined.TextFields) { menu = false; zoomBar = !zoomBar }
                                MenuItem("На весь экран", Icons.Outlined.Fullscreen) { menu = false; fullscreen = true }
                                if (appDark) {
                                    MenuItem(
                                        if (style.darken) "Исходные цвета письма" else "Тёмный вид письма",
                                        Icons.Outlined.Contrast,
                                    ) { menu = false; vm.darkOverride = !style.darken }
                                }
                                MenuItem("Переместить в папку", Icons.AutoMirrored.Outlined.DriveFileMove) { menu = false; moveDialog = true }
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (d != null && !d.isMeetingRequest) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        BottomAction(Icons.AutoMirrored.Outlined.Reply, "Ответить") { onReply(d.ref.id, "reply") }
                        BottomAction(Icons.AutoMirrored.Outlined.ReplyAll, "Всем") { onReply(d.ref.id, "replyAll") }
                        BottomAction(Icons.AutoMirrored.Outlined.Forward, "Переслать") { onReply(d.ref.id, "forward") }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
                .messageGestures(gestures)
                .graphicsLayer {
                    translationX = swipeX.value
                    alpha = 1f - (kotlin.math.abs(swipeX.value) / widthPx).coerceIn(0f, 1f) * 0.5f
                },
        ) {
            when {
                vm.error != null -> EmptyState(
                    Icons.Outlined.Description,
                    "Не удалось открыть письмо",
                    vm.error,
                    actionLabel = "Повторить",
                    onAction = vm::load,
                )
                d == null -> Column(Modifier.fillMaxSize()) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                else -> MessageContent(
                    d = d,
                    html = if (loaded != null) vm.html ?: loaded.bodyHtml else null,
                    loadImages = settings.loadImages || vm.showImages,
                    onShowImages = { vm.showImages = true },
                    downloading = vm.downloading,
                    busy = vm.busy,
                    onAttachment = vm::open,
                    onSaveAttachment = vm::save,
                    onSaveAll = vm::saveAll,
                    onRespond = vm::respond,
                    onMailto = onComposeTo,
                    onContact = onOpenContact,
                    style = style,
                    textZoom = textZoom,
                    zoomBar = zoomBar,
                    onTextZoom = { vm.setTextZoom(it) },
                    onCloseZoomBar = { zoomBar = false },
                    onFullscreen = { fullscreen = true },
                    onImageLongPress = { imageMenu = it },
                    scroll = scroll,
                    onWebView = { web = it },
                    onSubjectHeight = { subjectPx = it },
                )
            }
            // Подсказка размера текста во время щипка
            pinchZoom?.let { z ->
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                ) {
                    Text(
                        "Текст $z %",
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    if (fullscreen && loaded != null) {
        FullscreenMessage(
            subject = loaded.subject,
            html = vm.html ?: loaded.bodyHtml,
            loadImages = settings.loadImages || vm.showImages,
            style = style,
            textZoom = settings.messageZoom,
            onMailto = { fullscreen = false; onComposeTo(it) },
            onImageLongPress = { imageMenu = it },
            onDismiss = { fullscreen = false },
        )
    }
    imageMenu?.let { src ->
        AlertDialog(
            onDismissRequest = { imageMenu = null },
            icon = { Icon(Icons.Outlined.Image, null) },
            title = { Text("Картинка из письма") },
            text = {
                Column {
                    ListItem(
                        headlineContent = { Text("Сохранить в «Загрузки»") },
                        leadingContent = { Icon(Icons.Outlined.Download, null) },
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { imageMenu = null; vm.saveImage(src) },
                    )
                    ListItem(
                        headlineContent = { Text("Открыть в другом приложении") },
                        leadingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null) },
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { imageMenu = null; vm.saveImage(src, openAfter = true) },
                    )
                }
            },
            confirmButton = { TextButton(onClick = { imageMenu = null }) { Text("Отмена") } },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Удалить письмо?",
            text = "Письмо будет перемещено в папку «Удалённые».",
            confirm = "Удалить",
            destructive = true,
            onConfirm = vm::delete,
            onDismiss = { confirmDelete = false },
        )
    }
    if (moveDialog) {
        MoveDialog(folders.filter { it.id != d?.parentFolderId }, onPick = vm::move, onDismiss = { moveDialog = false })
    }
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text, maxLines = 1) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}

@Composable
private fun BottomAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.widthIn(min = 96.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
internal fun MessageContent(
    d: MessageDetail,
    html: String?,
    loadImages: Boolean,
    onShowImages: () -> Unit,
    downloading: String?,
    busy: Boolean,
    onAttachment: (AttachmentInfo) -> Unit,
    onRespond: (MeetingResponse) -> Unit,
    onMailto: (String) -> Unit,
    onContact: (String) -> Unit,
    onSaveAttachment: (AttachmentInfo) -> Unit = {},
    onSaveAll: (List<AttachmentInfo>) -> Unit = {},
    style: MailPageStyle = mailPageStyle(),
    textZoom: Int = 100,
    zoomBar: Boolean = false,
    onTextZoom: (Int) -> Unit = {},
    onCloseZoomBar: () -> Unit = {},
    onFullscreen: () -> Unit = {},
    onImageLongPress: ((String) -> Unit)? = null,
    scroll: ScrollState = rememberScrollState(),
    onWebView: (WebView) -> Unit = {},
    onSubjectHeight: (Int) -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    var showInline by remember { mutableStateOf(false) }
    val hasRemoteImages = remember(html) { html != null && REMOTE_IMAGE.containsMatchIn(html) }
    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
        Row(
            Modifier.onSizeChanged { onSubjectHeight(it.height) }.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (d.importance == "High") {
                Icon(Icons.Filled.PriorityHigh, "Важное", Modifier.padding(top = 4.dp).size(20.dp), tint = MaterialTheme.colorScheme.error)
            }
            SelectionContainer(Modifier.weight(1f)) {
                Text(d.subject.ifBlank { "(без темы)" }, style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp))
            }
        }
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val from = d.from
            Avatar(from?.name.orEmpty(), from?.email.orEmpty(), size = 44.dp, modifier = Modifier.clickable {
                from?.email?.let(onContact)
            })
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(from?.display ?: "(неизвестный отправитель)", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (expanded) from?.email.orEmpty() else "кому: " + d.to.joinToString { it.display }.ifBlank { "—" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    Format.listTime(d.received?.toEpochMilli() ?: 0),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "Свернуть" else "Подробнее")
            }
        }
        AnimatedVisibility(expanded, enter = Motion.expand, exit = Motion.collapse) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                RecipientLine("Кому", d.to, onContact)
                if (d.cc.isNotEmpty()) RecipientLine("Копия", d.cc, onContact)
                if (d.bcc.isNotEmpty()) RecipientLine("Скрытая", d.bcc, onContact)
                Text(
                    Format.full(d.received),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        d.meeting?.let { meeting ->
            OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Event, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                meeting.isCancellation -> "Встреча отменена"
                                meeting.isResponse -> "Ответ на приглашение"
                                else -> "Приглашение на встречу"
                            },
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    if (meeting.start != null && meeting.end != null) {
                        Text(Format.eventRange(meeting.start, meeting.end, meeting.isAllDay), modifier = Modifier.padding(top = 8.dp))
                    }
                    if (meeting.location.isNotBlank()) {
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Place, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(4.dp))
                            Text(meeting.location, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (d.isMeetingRequest) {
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onRespond(MeetingResponse.ACCEPT) }, enabled = !busy, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.weight(1f)) {
                                Text("Приду", maxLines = 1, softWrap = false)
                            }
                            FilledTonalButton(onClick = { onRespond(MeetingResponse.TENTATIVE) }, enabled = !busy, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.weight(1f)) {
                                Text("Возможно", maxLines = 1, softWrap = false)
                            }
                            OutlinedButton(onClick = { onRespond(MeetingResponse.DECLINE) }, enabled = !busy, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.weight(1f)) {
                                Text("Не приду", maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }
        }

        // Вложения: файлы — списком; картинки, встроенные в текст, — отдельной свёрнутой группой
        val (inline, files) = remember(d) { d.attachments.partition { it.contentId != null && d.bodyHtml.contains("cid:${it.contentId}", true) } }
        if (files.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AttachFile, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(
                    "Вложения · ${files.size}" + files.sumOf { it.size }.takeIf { it > 0 }?.let { " · " + Format.size(it) }.orEmpty(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                if (files.count { !it.isItem } > 1) {
                    TextButton(onClick = { onSaveAll(files) }, enabled = downloading == null) { Text("Сохранить все", maxLines = 1) }
                }
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                files.forEach { a ->
                    AttachmentRow(a, downloading == a.id, enabled = downloading == null, onOpen = { onAttachment(a) }, onSave = { onSaveAttachment(a) })
                }
            }
        }
        if (inline.isNotEmpty()) {
            TextButton(onClick = { showInline = !showInline }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Icon(Icons.Outlined.Image, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Картинки в тексте · ${inline.size}", maxLines = 1)
                Icon(if (showInline) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            }
            AnimatedVisibility(showInline, enter = Motion.expand, exit = Motion.collapse) {
                Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    inline.forEach { a ->
                        AttachmentRow(a, downloading == a.id, enabled = downloading == null, onOpen = { onAttachment(a) }, onSave = { onSaveAttachment(a) })
                    }
                }
            }
        }

        if (hasRemoteImages && !loadImages) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Image, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text("Картинки из интернета скрыты", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onShowImages) { Text("Показать") }
                }
            }
        }

        AnimatedVisibility(zoomBar, enter = Motion.expand, exit = Motion.collapse) {
            TextZoomBar(textZoom, onTextZoom, onCloseZoomBar)
        }

        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        // Письмо в исходных цветах на тёмной теме — светлой карточкой, чтобы белый фон выглядел намеренно
        val card = style == MailPageStyle.Original && MaterialTheme.colorScheme.surface.luminance() < 0.5f
        if (html == null) {
            // Текст загружается: заголовок уже на месте, ниже — спокойный индикатор вместо пустоты
            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
            }
        } else {
            Box(if (card) Modifier.padding(8.dp).clip(RoundedCornerShape(12.dp)) else Modifier) {
                HtmlView(
                    html,
                    loadRemoteImages = loadImages,
                    onMailto = onMailto,
                    style = style,
                    textZoom = textZoom,
                    onImageLongPress = onImageLongPress,
                    onWebView = onWebView,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onFullscreen) {
                    Icon(Icons.Outlined.Fullscreen, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Открыть на весь экран", maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Обработчик жестов окна письма. */
private interface MessageGestures {
    /** Можно ли перелистнуть: [direction] +1 — к более старому письму (палец влево), −1 — к более новому. */
    fun canSwipe(direction: Int): Boolean
    fun onDrag(dx: Float)
    fun onRelease(dx: Float, velocity: Float)
    val zoom: Int
    fun onPinch(zoom: Float)
    fun onPinchEnd()
}

/**
 * Жесты поверх письма, не мешающие прокрутке и ссылкам: горизонтальный свайп — соседнее письмо,
 * щипок двумя пальцами — размер текста. События читаются раньше WebView и прокрутки (проход Initial);
 * забираются, только когда жест распознан.
 */
private fun Modifier.messageGestures(g: MessageGestures): Modifier = pointerInput(g) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val slop = viewConfiguration.touchSlop
        val velocity = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
        var mode = 0 // 0 — ещё неясно, 1 — свайп, 2 — щипок, -1 — прокрутка (не наше)
        var dx = 0f
        var dy = 0f
        var startDistance = 0f
        var startZoom = 0
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            if (pressed.size >= 2 && mode != 1 && mode != 2) {
                mode = 2
                startDistance = (pressed[0].position - pressed[1].position).getDistance()
                startZoom = g.zoom
            }
            when (mode) {
                2 -> {
                    if (pressed.size >= 2 && startDistance > 0f) {
                        g.onPinch(startZoom * (pressed[0].position - pressed[1].position).getDistance() / startDistance)
                    }
                    event.changes.forEach { it.consume() }
                }
                else -> {
                    val c = event.changes.firstOrNull { it.id == down.id } ?: break
                    val delta = c.positionChangeIgnoreConsumed()
                    dx += delta.x
                    dy += delta.y
                    velocity.addPosition(c.uptimeMillis, c.position)
                    when (mode) {
                        1 -> {
                            g.onDrag(dx)
                            event.changes.forEach { it.consume() }
                        }
                        0 -> when {
                            // Прокрутка уже началась (она стартует при сдвиге по вертикали на slop, в любую сторону)
                            kotlin.math.abs(dy) > slop -> mode = -1
                            // Палец долго стоит — это удержание (выделение текста, меню картинки), не перелистывание
                            c.uptimeMillis - down.uptimeMillis > viewConfiguration.longPressTimeoutMillis -> mode = -1
                            kotlin.math.abs(dx) > slop * 2 && kotlin.math.abs(dx) > 2 * kotlin.math.abs(dy) ->
                                if (g.canSwipe(if (dx < 0) 1 else -1)) {
                                    mode = 1
                                    c.consume()
                                } else {
                                    mode = -1
                                }
                        }
                    }
                }
            }
        }
        when (mode) {
            1 -> g.onRelease(dx, velocity.calculateVelocity().x)
            2 -> g.onPinchEnd()
        }
    }
}

private val REMOTE_IMAGE = Regex("<img[^>]+src=[\"']https?://(?!" + Regex.escape(InlineImages.HOST) + ")", RegexOption.IGNORE_CASE)

/** Размер текста письма: кнопки −/+ и ползунок 70–200 %. */
@Composable
private fun TextZoomBar(zoom: Int, onZoom: (Int) -> Unit, onClose: () -> Unit) {
    var value by remember(zoom) { mutableFloatStateOf(zoom.toFloat()) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onZoom((zoom - 10).coerceAtLeast(70)) }) { Icon(Icons.Outlined.TextDecrease, "Мельче") }
            Slider(
                value = value,
                onValueChange = { value = it },
                onValueChangeFinished = { onZoom((value / 10).toInt() * 10) },
                valueRange = 70f..200f,
                steps = 12,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onZoom((zoom + 10).coerceAtMost(200)) }) { Icon(Icons.Outlined.TextIncrease, "Крупнее") }
            Text(
                "${value.toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                modifier = Modifier.widthIn(min = 40.dp).clickable { onZoom(100) },
            )
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Скрыть") }
        }
    }
}

/** Полноэкранный просмотр письма: приближение жестом и кнопками, прокрутка во все стороны. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullscreenMessage(
    subject: String,
    html: String,
    loadImages: Boolean,
    style: MailPageStyle,
    textZoom: Int,
    onMailto: (String) -> Unit,
    onImageLongPress: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var web by remember { mutableStateOf<WebView?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Закрыть") } },
                    title = { Text(subject.ifBlank { "(без темы)" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    actions = {
                        IconButton(onClick = { web?.zoomOut() }) { Icon(Icons.Outlined.ZoomOut, "Отдалить") }
                        IconButton(onClick = { web?.zoomIn() }) { Icon(Icons.Outlined.ZoomIn, "Приблизить") }
                    },
                )
            },
        ) { padding ->
            HtmlView(
                html,
                loadRemoteImages = loadImages,
                onMailto = onMailto,
                style = style,
                textZoom = textZoom,
                zoomable = true,
                fillHeight = true,
                onImageLongPress = onImageLongPress,
                onWebView = { web = it },
                modifier = Modifier.padding(padding).fillMaxSize(),
            )
        }
    }
}

@Composable
private fun RecipientLine(label: String, list: List<Mailbox>, onContact: (String) -> Unit) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text("$label: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column {
            list.forEach { m ->
                Text(
                    if (m.name.isNotBlank()) "${m.name} <${m.email}>" else m.email,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onContact(m.email) },
                )
            }
        }
    }
}

/** Вложение: касание — открыть, кнопка — сохранить в «Загрузки». */
@Composable
private fun AttachmentRow(a: AttachmentInfo, loading: Boolean, enabled: Boolean, onOpen: () -> Unit, onSave: () -> Unit) {
    OutlinedCard(onClick = onOpen, enabled = enabled || loading, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
            } else {
                Icon(attachmentIcon(a), null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(a.name.ifBlank { "вложение" }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (a.isItem) "вложенное письмо" else Format.size(a.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (!a.isItem) {
                IconButton(onClick = onSave, enabled = enabled) { Icon(Icons.Outlined.Download, "Сохранить «${a.name}»") }
            }
        }
    }
}

private fun attachmentIcon(a: AttachmentInfo): ImageVector {
    val n = a.name.lowercase()
    return when {
        a.contentType.startsWith("image/") -> Icons.Outlined.Image
        n.endsWith(".pdf") -> Icons.Outlined.PictureAsPdf
        n.endsWith(".xls") || n.endsWith(".xlsx") || n.endsWith(".csv") -> Icons.Outlined.TableChart
        n.endsWith(".doc") || n.endsWith(".docx") || n.endsWith(".txt") -> Icons.Outlined.Description
        else -> Icons.Outlined.AttachFile
    }
}

/** Открывает файл во внешнем приложении. Возвращает текст ошибки или null. */
fun openFile(context: Context, file: File, contentType: String): String? {
    val uri = try {
        FileProvider.getUriForFile(context, context.packageName + ".files", file)
    } catch (_: IllegalArgumentException) {
        return "Не удалось открыть файл «${file.name}»"
    }
    val ext = file.extension.lowercase()
    val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        ?: contentType.takeIf { it.isNotBlank() && it != "application/octet-stream" } ?: "*/*"
    return openUri(context, uri, type, file.name)
}

/** Открывает content:// адрес во внешнем приложении. Возвращает текст ошибки или null. */
fun openUri(context: Context, uri: Uri, type: String, name: String): String? {
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        context.startActivity(Intent.createChooser(intent, name))
        null
    } catch (_: ActivityNotFoundException) {
        "Нет приложения, чтобы открыть файл «$name»"
    }
}
