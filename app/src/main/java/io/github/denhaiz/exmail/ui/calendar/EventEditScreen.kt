package io.github.denhaiz.exmail.ui.calendar

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import io.github.denhaiz.exmail.data.repo.AccountRepository
import io.github.denhaiz.exmail.data.repo.CalendarRepository
import io.github.denhaiz.exmail.data.repo.ContactsRepository
import io.github.denhaiz.exmail.data.userMessage
import io.github.denhaiz.exmail.ews.Availability
import io.github.denhaiz.exmail.ews.CalendarEvent
import io.github.denhaiz.exmail.ews.ContactInfo
import io.github.denhaiz.exmail.ews.EventDraft
import io.github.denhaiz.exmail.ews.Recurrence
import io.github.denhaiz.exmail.ews.Room
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.InputChip
import io.github.denhaiz.exmail.ui.common.Format
import io.github.denhaiz.exmail.ui.common.Recipient
import io.github.denhaiz.exmail.ui.common.RecipientField
import io.github.denhaiz.exmail.ui.common.withTyped
import io.github.denhaiz.exmail.ui.mail.ComposeViewModel
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class EventEditViewModel @Inject constructor(
    private val calendar: CalendarRepository,
    private val contacts: ContactsRepository,
    accounts: AccountRepository,
    saved: SavedStateHandle,
) : ViewModel() {
    private val id: String? = saved.get<String>("id")?.takeIf { it.isNotBlank() }
    val isNew = id == null
    private val myEmail = accounts.current?.email.orEmpty()
    private var original: CalendarEvent? = null
    private var originalDescription = ""
    private var originalOptional: Set<String> = emptySet()

    var subject by mutableStateOf("")
    var location by mutableStateOf("")
    var description by mutableStateOf("")
    var allDay by mutableStateOf(false)
    var date by mutableStateOf(LocalDate.now())
        private set
    var startTime by mutableStateOf(LocalTime.of(10, 0))
        private set
    var duration by mutableStateOf(Duration.ofMinutes(60))
        private set
    var attendees by mutableStateOf<List<Recipient>>(emptyList())
        private set
    /** Набрано в поле участников, но ещё не стало «чипсой». */
    var typedAttendees = ""
    /** Сообщение для календаря после сохранения. */
    var doneMessage = ""
        private set
    var reminder by mutableStateOf<Int?>(15)
    var recurrence by mutableStateOf<Recurrence.Kind?>(null)
    var room by mutableStateOf<Room?>(null)

    /** Повторение можно задать для новой встречи или одиночного события (не для вхождения серии). */
    val canRecur: Boolean get() = isNew || original?.isRecurring == false

    var rooms by mutableStateOf<List<Room>?>(null)
        private set
    var roomsBusy by mutableStateOf<Set<String>>(emptySet())
        private set
    var roomsLoading by mutableStateOf(false)
        private set
    var roomsError by mutableStateOf<String?>(null)
        private set

    var loading by mutableStateOf(!isNew)
        private set
    var saving by mutableStateOf(false)
        private set
    var availability by mutableStateOf<List<Availability>>(emptyList())
        private set
    var availabilityLoading by mutableStateOf(false)
        private set
    private var availabilityJob: Job? = null

    private val _events = Channel<String?>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        saved.get<String>("date")?.takeIf { it.isNotBlank() }?.let { date = LocalDate.parse(it) }
        if (isNew) {
            // Ближайшее «круглое» время: следующий час, если выбран сегодняшний день
            val now = LocalDateTime.now()
            startTime = if (date == now.toLocalDate()) LocalTime.of((now.hour + 1).coerceAtMost(23), 0) else LocalTime.of(10, 0)
            saved.get<String>("to")?.takeIf { it.isNotBlank() }?.let { attendees = listOf(Recipient("", it)) }
            refreshAvailability()
        } else {
            viewModelScope.launch {
                try {
                    val e = calendar.getEvent(id!!)
                    original = e
                    subject = e.subject
                    location = e.location
                    originalDescription = htmlToText(e.bodyHtml.orEmpty())
                    description = originalDescription
                    allDay = e.isAllDay
                    val start = e.start.atZone(Format.zone())
                    date = start.toLocalDate()
                    startTime = start.toLocalTime()
                    duration = Duration.between(e.start, e.end)
                    originalOptional = e.optionalAttendees.map { it.mailbox.email.lowercase() }.toSet()
                    attendees = (e.requiredAttendees + e.optionalAttendees)
                        .filter { !it.mailbox.email.equals(myEmail, true) }
                        .map { Recipient(it.mailbox.name, it.mailbox.email) }
                    reminder = if (e.reminderSet) e.reminderMinutes else null
                    room = e.resources.firstOrNull()?.let { Room(it.mailbox.display, it.mailbox.email) }
                    refreshAvailability()
                } catch (ex: Exception) {
                    _events.send(ex.userMessage())
                } finally {
                    loading = false
                }
            }
        }
    }

    val start: Instant get() = LocalDateTime.of(date, if (allDay) LocalTime.MIDNIGHT else startTime).atZone(Format.zone()).toInstant()
    val end: Instant
        get() = if (allDay) {
            date.plusDays(maxOf(1, duration.toDays())).atStartOfDay(Format.zone()).toInstant()
        } else {
            start.plus(duration)
        }

    fun selectDate(d: LocalDate) {
        date = d
        refreshAvailability()
    }

    fun setStart(t: LocalTime) {
        startTime = t
    }

    fun setEnd(t: LocalTime) {
        var d = Duration.between(startTime, t)
        if (d.isNegative || d.isZero) d = d.plusDays(1)
        duration = d
    }

    fun setDurationMinutes(m: Long) {
        duration = Duration.ofMinutes(m)
    }

    fun updateAttendees(list: List<Recipient>) {
        attendees = list
        refreshAvailability()
    }

    suspend fun suggest(q: String): List<ContactInfo> = contacts.suggest(q)

    suspend fun addressBook(q: String): List<ContactInfo> = contacts.pickerSearch(q)

    /** Загружает переговорные и отмечает занятые на выбранное время. */
    fun loadRooms() {
        if (roomsLoading) return
        roomsLoading = true
        roomsError = null
        viewModelScope.launch {
            try {
                val list = calendar.rooms()
                rooms = list
                if (list.isNotEmpty()) {
                    val dayStart = date.atStartOfDay(Format.zone()).toInstant()
                    val slots = calendar.availability(list.take(100).map { it.email }, dayStart, dayStart.plus(Duration.ofDays(1)))
                    val from = if (allDay) 0 else startTime.toSecondOfDay() / 1800
                    val to = if (allDay) 48 else from + maxOf(1, (duration.toMinutes() / 30).toInt())
                    roomsBusy = slots.filter { a ->
                        (from until to).any { i -> a.mergedFreeBusy.getOrNull(i) in setOf('1', '2', '3') }
                    }.map { it.email.lowercase() }.toSet()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e // закрыли экран или начали новый запрос — это не ошибка
                roomsError = e.userMessage()
            } finally {
                roomsLoading = false
            }
        }
    }

    fun chooseRoom(r: Room?) {
        val previous = room
        room = r
        if (r != null && (location.isBlank() || location == previous?.name)) location = r.name
        if (r == null && previous != null && location == previous.name) location = ""
    }

    private fun refreshAvailability() {
        availabilityJob?.cancel()
        val emails = (listOf(myEmail) + attendees.map { it.email }).filter { it.isNotBlank() }.distinct()
        if (emails.isEmpty()) return
        availabilityJob = viewModelScope.launch {
            delay(300)
            availabilityLoading = true
            try {
                val dayStart = date.atStartOfDay(Format.zone()).toInstant()
                availability = calendar.availability(emails, dayStart, dayStart.plus(Duration.ofDays(1)))
            } catch (_: Exception) {
                availability = emptyList()
            } finally {
                availabilityLoading = false
            }
        }
    }

    fun save() {
        if (saving) return
        if (subject.isBlank()) {
            viewModelScope.launch { _events.send("Укажите название встречи") }
            return
        }
        val typed = withTyped(attendees, typedAttendees)
        if (typed == null) {
            viewModelScope.launch { _events.send("Проверьте адрес участника: он введён не полностью или с ошибкой") }
            return
        }
        if (typed != attendees) updateAttendees(typed)
        typedAttendees = ""
        saving = true
        viewModelScope.launch {
            try {
                val body = if (!isNew && description == originalDescription) original?.bodyHtml.orEmpty()
                else ComposeViewModel.textToHtml(description)
                val draft = EventDraft(
                    subject = subject.trim(),
                    bodyHtml = body,
                    start = start,
                    end = end,
                    isAllDay = allDay,
                    location = location.trim(),
                    required = attendees.map { it.email }.filter { it.lowercase() !in originalOptional },
                    optional = attendees.map { it.email }.filter { it.lowercase() in originalOptional },
                    reminderMinutes = reminder,
                    resources = listOfNotNull(room?.email),
                    recurrence = recurrence?.takeIf { canRecur }?.let { Recurrence(it) },
                )
                val o = original
                if (o == null) {
                    calendar.create(draft)
                    doneMessage = if (attendees.isEmpty()) "Событие создано" else "Приглашения отправлены"
                } else {
                    val hadAttendees = o.requiredAttendees.isNotEmpty() || o.optionalAttendees.isNotEmpty() || o.resources.isNotEmpty()
                    calendar.update(o.ref, draft, notifyAttendees = attendees.isNotEmpty() || room != null || hadAttendees)
                    doneMessage = "Изменения сохранены"
                }
                // Экран закрывается сразу; кнопка остаётся недоступной — встреча не создастся дважды
                _events.send(null)
            } catch (e: CancellationException) {
                saving = false
                throw e
            } catch (e: Exception) {
                saving = false
                _events.send("Не сохранено: ${e.userMessage()}")
            }
        }
    }

    companion object {
        fun htmlToText(html: String): String = html
            .replace(Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>"), "")
            .replace(Regex("(?i)<br\\s*/?>|</p>|</div>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
            .lines().joinToString("\n") { it.trimEnd() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
private val dateFmt = DateTimeFormatter.ofPattern("EEE, d MMMM yyyy", Format.ru)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEditScreen(onClose: () -> Unit, onSaved: (String) -> Unit, vm: EventEditViewModel = hiltViewModel()) {
    val snackbar = remember { SnackbarHostState() }
    var pickDate by remember { mutableStateOf(false) }
    var pickStart by remember { mutableStateOf(false) }
    var pickEnd by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var pickRoom by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.events.collect { msg ->
            if (msg == null) onSaved(vm.doneMessage) else scope.launch { snackbar.showSnackbar(msg) }
        }
    }
    BackHandler(onBack = onClose)

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Закрыть") } },
                title = { Text(if (vm.isNew) "Новая встреча" else "Изменение", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    Button(onClick = vm::save, enabled = !vm.saving && !vm.loading, modifier = Modifier.padding(end = 8.dp)) {
                        if (vm.saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        else Text(if (vm.attendees.isNotEmpty() && vm.isNew) "Пригласить" else "Сохранить", maxLines = 1)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()),
        ) {
            if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedTextField(
                value = vm.subject,
                onValueChange = { vm.subject = it },
                label = { Text("Название") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )

            // Дата и время
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Schedule, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text("Весь день", modifier = Modifier.weight(1f))
                Switch(checked = vm.allDay, onCheckedChange = { vm.allDay = it })
            }
            Row(Modifier.padding(start = 52.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { pickDate = true }) { Text(vm.date.format(dateFmt).replaceFirstChar { it.titlecase(Format.ru) }) }
            }
            if (!vm.allDay) {
                Row(Modifier.padding(start = 52.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { pickStart = true }) { Text(vm.startTime.format(timeFmt), style = MaterialTheme.typography.titleMedium) }
                    Text("—")
                    TextButton(onClick = { pickEnd = true }) {
                        Text(vm.startTime.plus(vm.duration).format(timeFmt), style = MaterialTheme.typography.titleMedium)
                    }
                }
                Row(
                    Modifier.padding(start = 52.dp, end = 16.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(15L to "15 мин", 30L to "30 мин", 60L to "1 час", 90L to "1,5 часа", 120L to "2 часа").forEach { (m, label) ->
                        FilterChip(
                            selected = vm.duration.toMinutes() == m,
                            onClick = { vm.setDurationMinutes(m) },
                            label = { Text(label) },
                        )
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text(
                "Участники",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            RecipientField("Кого", vm.attendees, vm::updateAttendees, vm::suggest, addressBook = vm::addressBook, onTyping = { vm.typedAttendees = it })
            AvailabilityGrid(vm.availability, vm.availabilityLoading, vm.date, vm.startTime, vm.duration, vm.allDay)

            OutlinedTextField(
                value = vm.location,
                onValueChange = { vm.location = it },
                label = { Text("Место") },
                leadingIcon = { Icon(Icons.Outlined.Place, null) },
                trailingIcon = {
                    IconButton(onClick = { pickRoom = true; vm.loadRooms() }) {
                        Icon(Icons.Outlined.MeetingRoom, "Выбрать переговорную", tint = MaterialTheme.colorScheme.primary)
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            vm.room?.let { r ->
                InputChip(
                    selected = true,
                    onClick = { vm.chooseRoom(null) },
                    label = { Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Outlined.MeetingRoom, null, Modifier.size(18.dp)) },
                    trailingIcon = { Icon(Icons.Outlined.Close, "Убрать переговорную", Modifier.size(16.dp)) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (vm.canRecur) RecurrencePicker(vm.recurrence) { vm.recurrence = it }
            ReminderPicker(vm.reminder) { vm.reminder = it }
            OutlinedTextField(
                value = vm.description,
                onValueChange = { vm.description = it },
                label = { Text("Описание") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Spacer(Modifier.height(32.dp))
        }
    }

    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = vm.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { vm.selectDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    pickDate = false
                }) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Отмена") } },
        ) { DatePicker(state) }
    }
    if (pickStart) TimeDialog("Начало", vm.startTime, { vm.setStart(it) }) { pickStart = false }
    if (pickEnd) TimeDialog("Окончание", vm.startTime.plus(vm.duration), { vm.setEnd(it) }) { pickEnd = false }
    if (pickRoom) {
        RoomDialog(
            rooms = vm.rooms,
            busy = vm.roomsBusy,
            loading = vm.roomsLoading,
            error = vm.roomsError,
            selected = vm.room,
            onPick = { vm.chooseRoom(it); pickRoom = false },
            onDismiss = { pickRoom = false },
        )
    }
}

@Composable
private fun RoomDialog(
    rooms: List<Room>?,
    busy: Set<String>,
    loading: Boolean,
    error: String?,
    selected: Room?,
    onPick: (Room) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Переговорные") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                when {
                    loading && rooms == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    error != null -> Text(error, color = MaterialTheme.colorScheme.error)
                    rooms.isNullOrEmpty() -> Text(
                        "Список переговорных на сервере не настроен. Укажите место вручную или добавьте переговорную как участника.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> {
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        rooms.forEach { r ->
                            val isBusy = r.email.lowercase() in busy
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onPick(r) }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Outlined.MeetingRoom,
                                    null,
                                    tint = if (r.email == selected?.email) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(
                                    if (isBusy) "занята" else "свободна",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isBusy) MaterialTheme.colorScheme.error else Color(0xFF2E7D32),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecurrencePicker(value: Recurrence.Kind?, onChange: (Recurrence.Kind?) -> Unit) {
    val options = listOf(null) + Recurrence.Kind.entries
    fun label(v: Recurrence.Kind?) = when (v) {
        null -> "Не повторять"
        Recurrence.Kind.DAILY -> "Каждый день"
        Recurrence.Kind.WEEKDAYS -> "По будням"
        Recurrence.Kind.WEEKLY -> "Каждую неделю"
        Recurrence.Kind.MONTHLY -> "Каждый месяц"
        Recurrence.Kind.YEARLY -> "Каждый год"
    }
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { expanded = it }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = label(value),
            onValueChange = {},
            readOnly = true,
            label = { Text("Повтор") },
            leadingIcon = { Icon(Icons.Outlined.Repeat, null) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded, { expanded = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(label(o)) }, onClick = { onChange(o); expanded = false })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(title: String, initial: LocalTime, onPick: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state) },
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)); onDismiss() }) { Text("Готово") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderPicker(value: Int?, onChange: (Int?) -> Unit) {
    val options = listOf(null, 0, 5, 15, 30, 60, 1440)
    fun label(v: Int?) = when (v) {
        null -> "Без напоминания"
        0 -> "В момент начала"
        else -> "За " + reminderText(v)
    }
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { expanded = it }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = label(value),
            onValueChange = {},
            readOnly = true,
            label = { Text("Напоминание") },
            leadingIcon = { Icon(Icons.Outlined.Notifications, null) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded, { expanded = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(label(o)) }, onClick = { onChange(o); expanded = false })
            }
        }
    }
}

/** Шкала занятости участников с 8:00 до 20:00; выбранное время подсвечено. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AvailabilityGrid(
    data: List<Availability>,
    loading: Boolean,
    date: LocalDate,
    start: LocalTime,
    duration: Duration,
    allDay: Boolean,
) {
    if (data.isEmpty() && !loading) return
    val firstSlot = 16 // 8:00 при шаге 30 минут
    val slots = 24 // до 20:00
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Занятость на ${date.format(DateTimeFormatter.ofPattern("d MMMM", Format.ru))}", style = MaterialTheme.typography.labelLarge)
            if (loading) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.padding(start = 84.dp)) {
            listOf("8", "10", "12", "14", "16", "18").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
        }
        val selFrom = if (allDay) 0 else start.toSecondOfDay() / 1800 - firstSlot
        val selTo = if (allDay) slots else selFrom + maxOf(1, (duration.toMinutes() / 30).toInt())
        data.forEach { a ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    a.email.substringBefore('@'),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(84.dp).padding(end = 6.dp),
                )
                Row(Modifier.weight(1f).height(18.dp).clip(RoundedCornerShape(4.dp))) {
                    for (i in 0 until slots) {
                        val c = a.mergedFreeBusy.getOrNull(firstSlot + i)
                        val color = when (c) {
                            '1' -> Color(0xFF90CAF9)
                            '2' -> MaterialTheme.colorScheme.primary
                            '3' -> Color(0xFF8E24AA)
                            '4', null -> MaterialTheme.colorScheme.surfaceVariant
                            else -> MaterialTheme.colorScheme.surfaceContainerHigh
                        }
                        Box(
                            Modifier.weight(1f).height(18.dp).background(color)
                                .then(if (i in selFrom until selTo) Modifier.background(Color(0x5543A047)) else Modifier),
                        )
                    }
                }
            }
        }
        FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Legend(MaterialTheme.colorScheme.primary, "занят")
            Legend(Color(0xFF90CAF9), "возможно")
            Legend(Color(0xFF8E24AA), "отсутствует")
            Legend(Color(0x5543A047), "выбрано")
        }
        if (data.any { it.error != null }) {
            Text(
                "Для некоторых участников данные о занятости недоступны",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

