package io.github.denhaiz.exmail.ui.calendar

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.automirrored.outlined.Help
import androidx.compose.material.icons.outlined.HighlightOff
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import io.github.denhaiz.exmail.data.repo.AccountRepository
import io.github.denhaiz.exmail.data.repo.CalendarRepository
import io.github.denhaiz.exmail.data.userMessage
import io.github.denhaiz.exmail.ews.Attendee
import io.github.denhaiz.exmail.ews.CalendarEvent
import io.github.denhaiz.exmail.ews.MeetingResponse
import io.github.denhaiz.exmail.ui.common.Avatar
import io.github.denhaiz.exmail.ui.common.ConfirmDialog
import io.github.denhaiz.exmail.ui.common.EmptyState
import io.github.denhaiz.exmail.ui.common.Format
import io.github.denhaiz.exmail.ui.common.LocalAppScope
import io.github.denhaiz.exmail.ui.common.HtmlView
import javax.inject.Inject

@HiltViewModel
class EventViewModel @Inject constructor(
    private val repo: CalendarRepository,
    accounts: AccountRepository,
    saved: SavedStateHandle,
) : ViewModel() {
    private val id: String = checkNotNull(saved["id"])
    private val myEmail = accounts.current?.email.orEmpty()

    var event by mutableStateOf<CalendarEvent?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    private val _events = Channel<String?>(Channel.BUFFERED)
    /** null — закрыть экран. */
    val events = _events.receiveAsFlow()

    val isOrganizer: Boolean
        get() = event?.let { it.isOrganizer ?: (!it.isMeeting || it.organizer?.email.equals(myEmail, true)) } ?: false

    init {
        load()
    }

    fun load() {
        error = null
        viewModelScope.launch {
            try {
                event = repo.getEvent(id)
            } catch (e: Exception) {
                if (e is CancellationException) throw e // закрыли экран или начали новый запрос — это не ошибка
                error = e.userMessage()
            }
        }
    }

    fun respond(r: MeetingResponse) = action {
        val e = event ?: return@action
        repo.respond(e, r)
        if (r == MeetingResponse.DECLINE) {
            _events.send("Вы отклонили встречу")
            _events.send(null)
        } else {
            // Перечитываем встречу: после ответа у неё новый ChangeKey (иначе следующий ответ не пройдёт)
            event = runCatching { repo.getEvent(id) }.getOrNull()
                ?: e.copy(myResponse = if (r == MeetingResponse.ACCEPT) "Accept" else "Tentative")
            _events.send(if (r == MeetingResponse.ACCEPT) "Вы приняли приглашение" else "Ответ «под вопросом» отправлен")
        }
    }

    fun delete(wholeSeries: Boolean = false) = action {
        val e = event ?: return@action
        repo.delete(e.copy(isOrganizer = isOrganizer), wholeSeries)
        _events.send(if (e.isMeeting && isOrganizer) "Встреча отменена, участники уведомлены" else "Удалено из календаря")
        _events.send(null)
    }

    private fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                if (e is CancellationException) throw e // закрыли экран или начали новый запрос — это не ошибка
                _events.send(e.userMessage())
            } finally {
                busy = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onContact: (String) -> Unit,
    onMailto: (String) -> Unit,
    snackbar: SnackbarHostState,
    vm: EventViewModel = hiltViewModel(),
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val screenScope = rememberCoroutineScope()
    val scope = LocalAppScope.current ?: screenScope
    LaunchedEffect(Unit) {
        vm.events.collect { if (it == null) onBack() else scope.launch { snackbar.showSnackbar(it) } }
    }
    val e = vm.event
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад") } },
                title = {},
                actions = {
                    if (e != null) {
                        if (vm.isOrganizer) IconButton(onClick = { onEdit(e.ref.id) }) { Icon(Icons.Outlined.Edit, "Изменить") }
                        IconButton(onClick = { confirmDelete = true }, enabled = !vm.busy) { Icon(Icons.Outlined.Delete, "Удалить") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                vm.error != null -> EmptyState(Icons.Outlined.EventBusy, "Не удалось открыть встречу", vm.error, "Повторить", vm::load)
                e == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        e.subject.ifBlank { "(без названия)" },
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    if (e.isCancelled) {
                        Text(
                            "Встреча отменена организатором",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    InfoRow(Icons.Outlined.Schedule, Format.eventRange(e.start, e.end, e.isAllDay))
                    if (e.isRecurring) InfoRow(Icons.Outlined.Repeat, "Повторяющаяся встреча")
                    if (e.location.isNotBlank()) {
                        InfoRow(Icons.Outlined.Place, e.location, onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(e.location))))
                            }
                        })
                    }
                    if (e.reminderSet && e.reminderMinutes != null) {
                        InfoRow(Icons.Outlined.Notifications, "Напоминание за ${reminderText(e.reminderMinutes)}")
                    }
                    e.organizer?.let { o ->
                        InfoRow(Icons.Outlined.Person, "Организатор: ${o.display}", onClick = { onContact(o.email) })
                    }

                    if (e.isMeeting && !vm.isOrganizer && !e.isCancelled) {
                        Text(
                            "Ваш ответ",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                        )
                        val options = listOf(
                            Triple(MeetingResponse.ACCEPT, "Приду", "Accept"),
                            Triple(MeetingResponse.TENTATIVE, "Возможно", "Tentative"),
                            Triple(MeetingResponse.DECLINE, "Не приду", "Decline"),
                        )
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            options.forEachIndexed { i, (resp, label, code) ->
                                SegmentedButton(
                                    selected = e.myResponse == code,
                                    onClick = { if (e.myResponse != code) vm.respond(resp) },
                                    enabled = !vm.busy,
                                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                                    icon = {},
                                ) { Text(label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                    }

                    val attendees = e.requiredAttendees + e.optionalAttendees
                    if (attendees.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(top = 16.dp))
                        Text(
                            "Участники (${attendees.size})",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                        )
                        e.requiredAttendees.forEach { AttendeeRow(it, false, onContact) }
                        e.optionalAttendees.forEach { AttendeeRow(it, true, onContact) }
                    }

                    val body = e.bodyHtml
                    val hasText = remember(body) { !body.isNullOrBlank() && body.replace(EMPTY_HTML, "").isNotEmpty() }
                    if (hasText && body != null) {
                        HorizontalDivider(Modifier.padding(top = 16.dp))
                        HtmlView(body, loadRemoteImages = false, onMailto = onMailto, modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }

    if (confirmDelete && e != null && e.isRecurring) {
        val cancelMeeting = e.isMeeting && vm.isOrganizer
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (cancelMeeting) "Отменить повторяющуюся встречу?" else "Удалить повторяющееся событие?") },
            text = {
                Text(
                    if (cancelMeeting) "Участники получат уведомление об отмене." else "Выберите, что удалить.",
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { confirmDelete = false; vm.delete() }) {
                        Text("Только это событие", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { confirmDelete = false; vm.delete(wholeSeries = true) }) {
                        Text("Всю серию", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { confirmDelete = false }) { Text("Назад") }
                }
            },
        )
    } else if (confirmDelete && e != null) {
        val cancelMeeting = e.isMeeting && vm.isOrganizer
        ConfirmDialog(
            title = if (cancelMeeting) "Отменить встречу?" else "Удалить из календаря?",
            text = if (cancelMeeting) "Участники получат уведомление об отмене." else "Событие будет удалено из вашего календаря.",
            confirm = if (cancelMeeting) "Отменить встречу" else "Удалить",
            dismiss = "Назад",
            destructive = true,
            onConfirm = vm::delete,
            onDismiss = { confirmDelete = false },
        )
    }
}

fun reminderText(minutes: Int): String = when {
    minutes == 0 -> "0 минут"
    minutes % 1440 == 0 -> Format.plural(minutes / 1440, "день", "дня", "дней")
    minutes % 60 == 0 -> Format.plural(minutes / 60, "час", "часа", "часов")
    else -> Format.plural(minutes, "минуту", "минуты", "минут")
}

@Composable
private fun InfoRow(icon: ImageVector, text: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = if (onClick != null) MaterialTheme.colorScheme.primary else Color.Unspecified)
    }
}

@Composable
private fun AttendeeRow(a: Attendee, optional: Boolean, onContact: (String) -> Unit) {
    val (icon, tint, label) = when (a.response) {
        "Accept", "Organizer" -> Triple(Icons.Outlined.CheckCircle, Color(0xFF2E7D32), "принял(а)")
        "Tentative" -> Triple(Icons.AutoMirrored.Outlined.Help, Color(0xFFE65100), "под вопросом")
        "Decline" -> Triple(Icons.Outlined.HighlightOff, MaterialTheme.colorScheme.error, "отклонил(а)")
        else -> Triple(Icons.Outlined.AccessTime, MaterialTheme.colorScheme.outline, "нет ответа")
    }
    Row(
        Modifier.fillMaxWidth().clickable { onContact(a.mailbox.email) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Avatar(a.mailbox.name, a.mailbox.email, size = 40.dp)
            Icon(icon, label, tint = tint, modifier = Modifier.size(16.dp).align(Alignment.BottomEnd))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.mailbox.display, fontWeight = FontWeight.Medium)
            Text(
                label + if (optional) " · необязательный" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val EMPTY_HTML = Regex("<[^>]*>|&nbsp;|\\s")
