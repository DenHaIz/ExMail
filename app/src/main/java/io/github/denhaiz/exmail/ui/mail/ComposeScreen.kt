package io.github.denhaiz.exmail.ui.mail

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PriorityHigh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.denhaiz.exmail.data.AccountStore
import io.github.denhaiz.exmail.data.repo.AccountRepository
import io.github.denhaiz.exmail.data.repo.ContactsRepository
import io.github.denhaiz.exmail.data.repo.MailRepository
import io.github.denhaiz.exmail.data.userMessage
import io.github.denhaiz.exmail.ews.AttachmentInfo
import io.github.denhaiz.exmail.ews.ContactInfo
import io.github.denhaiz.exmail.ews.MessageDetail
import io.github.denhaiz.exmail.ews.NewAttachment
import io.github.denhaiz.exmail.ews.OutgoingMessage
import io.github.denhaiz.exmail.ews.ReplyKind
import io.github.denhaiz.exmail.ews.Xml
import io.github.denhaiz.exmail.ui.calendar.EventEditViewModel
import io.github.denhaiz.exmail.ui.common.ConfirmDialog
import io.github.denhaiz.exmail.ui.common.Format
import io.github.denhaiz.exmail.ui.common.Recipient
import io.github.denhaiz.exmail.ui.common.RecipientField
import io.github.denhaiz.exmail.ui.common.withTyped
import javax.inject.Inject

data class PickedFile(val uri: Uri, val name: String, val size: Long, val type: String)

@HiltViewModel
class ComposeViewModel @Inject constructor(
    private val mail: MailRepository,
    private val contacts: ContactsRepository,
    private val store: AccountStore,
    accounts: AccountRepository,
    @ApplicationContext private val context: Context,
    saved: SavedStateHandle,
) : ViewModel() {
    /** new | reply | replyAll | forward | draft */
    val mode: String = saved["mode"] ?: "new"
    private val refId: String? = saved.get<String>("ref")?.takeIf { it.isNotBlank() }
    private val draftId: String? = refId.takeIf { mode == "draft" }
    private val myEmail = accounts.current?.email.orEmpty()

    var to by mutableStateOf<List<Recipient>>(emptyList())
    var cc by mutableStateOf<List<Recipient>>(emptyList())
    var bcc by mutableStateOf<List<Recipient>>(emptyList())
    /** Набрано в полях получателей, но ещё не стало «чипсой». */
    var typedTo = ""
    var typedCc = ""
    var typedBcc = ""
    var subject by mutableStateOf("")
    var body by mutableStateOf("")
    var highImportance by mutableStateOf(false)
    var attachments by mutableStateOf<List<PickedFile>>(emptyList())
    /** Вложения, уже сохранённые в черновике на сервере. */
    var draftAttachments by mutableStateOf<List<AttachmentInfo>>(emptyList())
        private set
    var showCc by mutableStateOf(false)
    var original by mutableStateOf<MessageDetail?>(null)
        private set
    var loading by mutableStateOf(refId != null)
        private set

    /** Черновик не загрузился: сохранить или отправить — значит затереть его на сервере пустым. */
    var draftLoadFailed by mutableStateOf(false)
        private set
    var sending by mutableStateOf(false)
        private set
    private var initialBody = ""
    private var edited = false

    /** Набранное письмо после того, как Android выгрузил приложение из памяти (например, во время выбора файла). */
    private val restored: Bundle? = saved.get<Bundle>(STATE)?.takeIf { it.containsKey("subject") }

    sealed interface Event {
        data object Sent : Event
        data object DraftSaved : Event
        data class Message(val text: String) : Event
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val title: String
        get() = when (mode) {
            "reply", "replyAll" -> "Ответ"
            "forward" -> "Пересылка"
            "draft" -> "Черновик"
            else -> "Новое письмо"
        }

    init {
        saved.setSavedStateProvider(STATE) { saveState() }
        saved.get<String>("to")?.takeIf { it.isNotBlank() }?.let { addr ->
            to = addr.split(',').filter { it.isNotBlank() }.map { Recipient("", it.trim()) }
        }
        restored?.let(::restoreState)
        viewModelScope.launch {
            val signature = store.currentSettings().signature
            if (mode != "draft" && restored == null) {
                body = if (signature.isNotBlank()) "\n\n--\n$signature" else ""
                initialBody = body
            }
            if (refId != null) {
                try {
                    val o = mail.getMessage(refId, forceRefresh = mode == "draft")
                    when {
                        // Восстановленный текст не затираем исходным письмом
                        restored != null -> if (mode == "draft") draftAttachments = o.attachments.filter { !it.isInline } else original = o
                        mode == "draft" -> loadDraft(o)
                        else -> {
                            original = o
                            prefill(o)
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e // закрыли экран или начали новый запрос — это не ошибка
                    if (mode == "draft" && restored == null) {
                        draftLoadFailed = true
                        _events.send(Event.Message("Черновик не загрузился: ${e.userMessage()} Закройте его и откройте снова"))
                    } else {
                        _events.send(Event.Message(e.userMessage()))
                    }
                } finally {
                    loading = false
                }
            }
        }
    }

    private fun saveState(): Bundle = if (body.length > MAX_STATE_CHARS) Bundle() else Bundle().apply {
        fun put(key: String, list: List<Recipient>) {
            putStringArray("$key.names", list.map { it.name }.toTypedArray())
            putStringArray("$key.emails", list.map { it.email }.toTypedArray())
        }
        put("to", to)
        put("cc", cc)
        put("bcc", bcc)
        putString("subject", subject)
        putString("body", body)
        putString("initialBody", initialBody)
        putBoolean("important", highImportance)
        putBoolean("edited", edited)
    }

    private fun restoreState(b: Bundle) {
        fun get(key: String): List<Recipient> {
            val names = b.getStringArray("$key.names").orEmpty()
            val emails = b.getStringArray("$key.emails").orEmpty()
            return emails.mapIndexed { i, e -> Recipient(names.getOrElse(i) { "" }, e) }
        }
        to = get("to")
        cc = get("cc")
        bcc = get("bcc")
        showCc = cc.isNotEmpty() || bcc.isNotEmpty()
        subject = b.getString("subject").orEmpty()
        body = b.getString("body").orEmpty()
        initialBody = b.getString("initialBody").orEmpty()
        highImportance = b.getBoolean("important")
        edited = b.getBoolean("edited")
    }

    private fun loadDraft(d: MessageDetail) {
        to = d.to.map { Recipient(it.name, it.email) }
        cc = d.cc.map { Recipient(it.name, it.email) }
        bcc = d.bcc.map { Recipient(it.name, it.email) }
        showCc = cc.isNotEmpty() || bcc.isNotEmpty()
        subject = d.subject
        highImportance = d.importance == "High"
        body = EventEditViewModel.htmlToText(d.bodyHtml)
        initialBody = body
        draftAttachments = d.attachments.filter { !it.isInline }
    }

    private fun prefill(o: MessageDetail) {
        fun prefixed(p: String) = if (o.subject.startsWith(p, true)) o.subject else "$p ${o.subject}"
        when (mode) {
            "reply" -> {
                to = listOfNotNull(o.from).map { Recipient(it.name, it.email) }
                subject = prefixed("RE:")
            }
            "replyAll" -> {
                to = (listOfNotNull(o.from) + o.to).filter { !it.email.equals(myEmail, true) }
                    .distinctBy { it.email.lowercase() }.map { Recipient(it.name, it.email) }
                cc = o.cc.filter { !it.email.equals(myEmail, true) }.map { Recipient(it.name, it.email) }
                showCc = cc.isNotEmpty()
                subject = prefixed("RE:")
            }
            "forward" -> subject = prefixed("FW:")
        }
    }

    fun markEdited() {
        edited = true
    }

    val hasChanges: Boolean
        get() = edited || body != initialBody || attachments.isNotEmpty()

    suspend fun suggest(q: String): List<ContactInfo> = contacts.suggest(q)

    suspend fun addressBook(q: String): List<ContactInfo> = contacts.pickerSearch(q)

    fun addFiles(uris: List<Uri>) {
        val added = uris.mapNotNull { uri ->
            runCatching {
                var name = "файл"
                var size = 0L
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                        c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
                    }
                }
                PickedFile(uri, name, size, context.contentResolver.getType(uri) ?: "application/octet-stream")
            }.getOrNull()
        }
        attachments = attachments + added
    }

    val totalSize: Long get() = attachments.sumOf { it.size } + draftAttachments.sumOf { it.size }

    private suspend fun buildMessage(): OutgoingMessage {
        val files = withContext(Dispatchers.IO) {
            attachments.map { f ->
                val bytes = context.contentResolver.openInputStream(f.uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Не удалось прочитать файл «${f.name}»")
                NewAttachment(f.name, f.type, bytes)
            }
        }
        return OutgoingMessage(
            to = to.map { it.email },
            cc = cc.map { it.email },
            bcc = bcc.map { it.email },
            subject = subject,
            bodyHtml = textToHtml(body),
            highImportance = highImportance,
            attachments = files,
        )
    }

    private val replyKind: ReplyKind?
        get() = when (mode) {
            "reply" -> ReplyKind.REPLY
            "replyAll" -> ReplyKind.REPLY_ALL
            "forward" -> ReplyKind.FORWARD
            else -> null
        }

    /** Добавляет набранные адреса к получателям; false — какой-то из них неверный. */
    private fun commitTyped(): Boolean {
        val t = withTyped(to, typedTo)
        val c = withTyped(cc, typedCc)
        val b = withTyped(bcc, typedBcc)
        if (t == null || c == null || b == null) return false
        to = t
        cc = c
        bcc = b
        typedTo = ""
        typedCc = ""
        typedBcc = ""
        return true
    }

    fun send() {
        if (loading || draftLoadFailed) return
        if (sending) return
        if (!commitTyped()) {
            viewModelScope.launch { _events.send(Event.Message("Проверьте адрес получателя: он введён не полностью или с ошибкой")) }
            return
        }
        if (to.isEmpty() && cc.isEmpty() && bcc.isEmpty()) {
            viewModelScope.launch { _events.send(Event.Message("Добавьте хотя бы одного получателя")) }
            return
        }
        sending = true
        viewModelScope.launch {
            try {
                val m = buildMessage()
                val kind = replyKind
                when {
                    draftId != null -> mail.sendDraft(draftId, m)
                    kind != null && refId != null ->
                        mail.reply(kind, refId, m.bodyHtml, m.to, m.cc, m.bcc, m.subject, m.attachments)
                    else -> mail.send(m)
                }
                _events.send(Event.Sent)
            } catch (_: OutOfMemoryError) {
                _events.send(Event.Message("Не отправлено: вложения слишком большие. Уменьшите их размер или отправьте частями."))
            } catch (e: Exception) {
                if (e is CancellationException) throw e // закрыли экран или начали новый запрос — это не ошибка
                _events.send(Event.Message("Не отправлено: ${e.userMessage()}"))
            } finally {
                sending = false
            }
        }
    }

    fun saveDraft() {
        if (sending) return
        if (loading || draftLoadFailed) {
            viewModelScope.launch { _events.send(Event.Message("Черновик ещё не загружен — сохранение затёрло бы его")) }
            return
        }
        commitTyped() // неверный адрес в черновике не мешает его сохранить
        sending = true
        viewModelScope.launch {
            try {
                mail.saveDraft(buildMessage(), draftId, replyKind, refId.takeIf { mode != "draft" })
                _events.send(Event.DraftSaved)
            } catch (_: OutOfMemoryError) {
                _events.send(Event.Message("Черновик не сохранён: вложения слишком большие"))
            } catch (e: Exception) {
                if (e is CancellationException) throw e // закрыли экран или начали новый запрос — это не ошибка
                _events.send(Event.Message("Черновик не сохранён: ${e.userMessage()}"))
            } finally {
                sending = false
            }
        }
    }

    companion object {
        private const val STATE = "compose"
        /** Длинная переписка в черновике не сохраняется: системный предел на сохранённое состояние ~1 МБ. */
        private const val MAX_STATE_CHARS = 100_000

        fun textToHtml(text: String): String =
            "<div style=\"font-family:Calibri,Arial,sans-serif;font-size:11pt\">" +
                Xml.esc(text).replace("\r\n", "\n").replace("\n", "<br>") + "</div>"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(
    onClose: () -> Unit,
    onSent: () -> Unit,
    onDraftSaved: () -> Unit,
    vm: ComposeViewModel = hiltViewModel(),
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmClose by remember { mutableStateOf(false) }
    var confirmNoSubject by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.addFiles(uris)
    }

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                ComposeViewModel.Event.Sent -> onSent()
                ComposeViewModel.Event.DraftSaved -> onDraftSaved()
                is ComposeViewModel.Event.Message -> scope.launch { snackbar.showSnackbar(e.text) }
            }
        }
    }

    fun tryClose() {
        // Пока письмо отправляется, закрыть экран нельзя: отправка прервалась бы без предупреждения
        if (vm.sending) return
        if (vm.hasChanges) confirmClose = true else onClose()
    }
    BackHandler(onBack = ::tryClose)

    fun trySend() {
        if (vm.subject.isBlank()) confirmNoSubject = true else vm.send()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = ::tryClose) { Icon(Icons.Outlined.Close, "Закрыть") } },
                title = { Text(vm.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !vm.sending) {
                        Icon(Icons.Outlined.AttachFile, "Прикрепить файл")
                    }
                    if (vm.mode == "new" || vm.mode == "draft") {
                        IconToggleButton(checked = vm.highImportance, onCheckedChange = { vm.highImportance = it; vm.markEdited() }) {
                            Icon(
                                Icons.Outlined.PriorityHigh,
                                if (vm.highImportance) "Важное (вкл.)" else "Пометить как важное",
                                tint = if (vm.highImportance) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = ::trySend, enabled = !vm.sending && !vm.loading && !vm.draftLoadFailed) {
                        if (vm.sending) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        else Icon(Icons.AutoMirrored.Filled.Send, "Отправить", tint = MaterialTheme.colorScheme.primary)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            if (vm.loading || vm.sending) LinearProgressIndicator(Modifier.fillMaxWidth())
            RecipientField(
                label = "Кому",
                recipients = vm.to,
                onChange = { vm.to = it; vm.markEdited() },
                suggest = vm::suggest,
                addressBook = vm::addressBook,
                onTyping = { vm.typedTo = it },
                trailing = {
                    IconButton(onClick = { vm.showCc = !vm.showCc }) {
                        Icon(if (vm.showCc) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, "Копия и скрытая копия")
                    }
                },
            )
            if (vm.showCc) {
                RecipientField("Копия", vm.cc, { vm.cc = it; vm.markEdited() }, vm::suggest, addressBook = vm::addressBook, onTyping = { vm.typedCc = it })
                RecipientField("Скрытая", vm.bcc, { vm.bcc = it; vm.markEdited() }, vm::suggest, addressBook = vm::addressBook, onTyping = { vm.typedBcc = it })
            }
            TextField(
                value = vm.subject,
                onValueChange = { vm.subject = it; vm.markEdited() },
                placeholder = { Text("Тема") },
                singleLine = true,
                colors = plainFieldColors(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (vm.attachments.isNotEmpty() || vm.draftAttachments.isNotEmpty()) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    vm.draftAttachments.forEach { a ->
                        AssistChip(
                            onClick = {},
                            label = { Text("${a.name}  ${Format.size(a.size)}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(Icons.Outlined.AttachFile, null, Modifier.size(18.dp)) },
                        )
                    }
                    vm.attachments.forEach { f ->
                        InputChip(
                            selected = false,
                            onClick = { vm.attachments = vm.attachments - f },
                            label = { Text("${f.name}  ${Format.size(f.size)}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(Icons.Outlined.AttachFile, null, Modifier.size(18.dp)) },
                            trailingIcon = { Icon(Icons.Outlined.Close, "Убрать вложение", Modifier.size(18.dp)) },
                        )
                    }
                    if (vm.totalSize > 25L * 1024 * 1024) {
                        Text(
                            "Общий размер вложений ${Format.size(vm.totalSize)} — сервер может не принять такое письмо.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            TextField(
                value = vm.body,
                onValueChange = { vm.body = it },
                placeholder = { Text("Текст письма") },
                colors = plainFieldColors(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
            )

            vm.original?.let { o ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Outlined.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                if (vm.mode == "forward") "Исходное письмо и его вложения будут добавлены автоматически"
                                else "Исходное письмо будет добавлено ниже вашего ответа",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "${o.from?.display.orEmpty()}: ${o.subject}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("Сохранить черновик?") },
            text = { Text("Письмо можно будет дописать позже из папки «Черновики».") },
            confirmButton = {
                TextButton(onClick = { confirmClose = false; vm.saveDraft() }) { Text("Сохранить") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmClose = false; onClose() }) {
                        Text("Не сохранять", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { confirmClose = false }) { Text("Отмена") }
                }
            },
        )
    }
    if (confirmNoSubject) {
        ConfirmDialog(
            title = "Отправить без темы?",
            text = "У письма не указана тема.",
            confirm = "Отправить",
            onConfirm = vm::send,
            onDismiss = { confirmNoSubject = false },
        )
    }
}

@Composable
private fun plainFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
)
