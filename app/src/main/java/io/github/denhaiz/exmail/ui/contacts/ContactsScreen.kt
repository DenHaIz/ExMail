package io.github.denhaiz.exmail.ui.contacts

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContactPage
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import io.github.denhaiz.exmail.data.db.ContactEntity
import io.github.denhaiz.exmail.data.repo.ContactsRepository
import io.github.denhaiz.exmail.data.userMessage
import io.github.denhaiz.exmail.ews.ContactInfo
import io.github.denhaiz.exmail.ui.common.Avatar
import io.github.denhaiz.exmail.ui.common.CompactSearchField
import io.github.denhaiz.exmail.ui.common.ConfirmDialog
import io.github.denhaiz.exmail.ui.common.EmptyState
import io.github.denhaiz.exmail.ui.common.keepVisible
import io.github.denhaiz.exmail.ui.common.ErrorBanner
import io.github.denhaiz.exmail.ui.common.Format
import javax.inject.Inject

@HiltViewModel
class ContactsViewModel @Inject constructor(private val repo: ContactsRepository) : ViewModel() {
    val personal = repo.contacts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    var tab by mutableIntStateOf(0)
    var query by mutableStateOf("")
        private set
    var directory by mutableStateOf<List<ContactInfo>?>(null)
        private set
    var searching by mutableStateOf(false)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    private var job: Job? = null

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Обновление жестом «потянуть вниз» (значок обновления). */
    var pullRefreshing by mutableStateOf(false)
        private set

    private var loadJob: Job? = null

    init {
        load()
    }

    fun refresh() {
        if (pullRefreshing) return
        pullRefreshing = true
        viewModelScope.launch {
            try {
                // Значок должен быть виден хотя бы мгновение, иначе он не успевает спрятаться
                keepVisible { (loadJob?.takeIf { it.isActive } ?: load()).join() }
            } finally {
                pullRefreshing = false
            }
        }
    }

    private fun load(): Job = viewModelScope.launch {
        refreshing = true
        try {
            repo.refresh()
            error = null
        } catch (e: Exception) {
            if (e is CancellationException) throw e // закрыли экран или начали новый запрос — это не ошибка
            error = e.userMessage()
        } finally {
            refreshing = false
        }
    }.also { loadJob = it }

    fun onQuery(q: String) {
        query = q
        job?.cancel()
        if (q.trim().length < 2) {
            directory = null
            searching = false
            return
        }
        job = viewModelScope.launch {
            delay(350)
            searching = true
            try {
                directory = repo.searchDirectory(q)
                error = null
            } catch (e: Exception) {
                if (e is CancellationException) throw e // закрыли экран или начали новый запрос — это не ошибка
                error = e.userMessage()
            } finally {
                searching = false
            }
        }
    }

    fun create(c: ContactInfo, onDone: () -> Unit) {
        if (saving) return
        saving = true
        viewModelScope.launch {
            try {
                repo.create(c)
                _events.send("Контакт «${c.displayName}» сохранён")
                onDone()
            } catch (e: Exception) {
                if (e is CancellationException) throw e // закрыли экран или начали новый запрос — это не ошибка
                _events.send("Не сохранено: ${e.userMessage()}")
            } finally {
                saving = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(onOpen: (email: String, personalId: String?) -> Unit, vm: ContactsViewModel = hiltViewModel()) {
    val personal by vm.personal.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var newContact by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.events.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Контакты") }) },
        floatingActionButton = {
            if (vm.tab == 1) {
                ExtendedFloatingActionButton(
                    onClick = { newContact = true },
                    icon = { Icon(Icons.Outlined.PersonAdd, null) },
                    text = { Text("Контакт", maxLines = 1) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            CompactSearchField(
                value = vm.query,
                onValueChange = vm::onQuery,
                placeholder = "Имя, фамилия или e-mail",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            PrimaryTabRow(selectedTabIndex = vm.tab) {
                Tab(vm.tab == 0, { vm.tab = 0 }, text = { Text("Сотрудники", maxLines = 1) })
                Tab(vm.tab == 1, { vm.tab = 1 }, text = { Text("Мои контакты", maxLines = 1) })
            }
            if (vm.searching || (vm.refreshing && !vm.pullRefreshing)) LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.error?.let { ErrorBanner(it, null) { vm.error = null } }

            if (vm.tab == 0) {
                val results = vm.directory
                when {
                    results == null -> EmptyState(
                        Icons.Outlined.PersonSearch,
                        "Адресная книга компании",
                        "Начните вводить имя, фамилию или e-mail коллеги",
                    )
                    results.isEmpty() && !vm.searching -> EmptyState(Icons.Outlined.SearchOff, "Никого не нашли", "Проверьте написание или попробуйте фамилию")
                    else -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                        items(results, key = { it.email }) { c ->
                            ContactRow(c.displayName, c.email, listOf(c.jobTitle, c.department).filter { it.isNotBlank() }.joinToString(" · ")) {
                                onOpen(c.email, null)
                            }
                        }
                    }
                }
            } else {
                val q = vm.query.trim()
                val filtered = if (q.isEmpty()) personal else personal.filter {
                    it.displayName.contains(q, true) || it.email.contains(q, true) || it.company.contains(q, true)
                }
                PullToRefreshBox(isRefreshing = vm.pullRefreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
                    if (filtered.isEmpty()) {
                        EmptyState(
                            Icons.Outlined.ContactPage,
                            if (q.isEmpty()) "Личных контактов нет" else "Ничего не найдено",
                            if (q.isEmpty()) "Добавьте контакт кнопкой «Контакт» или из карточки сотрудника" else null,
                        )
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                            items(filtered, key = { it.id }) { c: ContactEntity ->
                                ContactRow(c.displayName, c.email, listOf(c.jobTitle, c.company).filter { it.isNotBlank() }.joinToString(" · ")) {
                                    onOpen(c.email, c.id)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (newContact) {
        NewContactDialog(saving = vm.saving, onSave = { vm.create(it) { newContact = false } }, onDismiss = { newContact = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewContactDialog(saving: Boolean, onSave: (ContactInfo) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var mobile by remember { mutableStateOf("") }
    var work by remember { mutableStateOf("") }
    var company by remember { mutableStateOf("") }
    var job by remember { mutableStateOf("") }
    val emailOk = email.isBlank() || Format.EMAIL.matches(email.trim())

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Закрыть") } },
                    title = { Text("Новый контакт", maxLines = 1) },
                    actions = {
                        Button(
                            onClick = {
                                onSave(
                                    ContactInfo(
                                        id = null,
                                        displayName = name.trim(),
                                        email = email.trim(),
                                        phones = listOf("MobilePhone" to mobile.trim(), "BusinessPhone" to work.trim()),
                                        company = company.trim(),
                                        jobTitle = job.trim(),
                                    ),
                                )
                            },
                            enabled = name.isNotBlank() && emailOk && !saving,
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            if (saving) CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                            else Text("Сохранить", maxLines = 1)
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(name, { name = it }, label = { Text("Имя и фамилия") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    email, { email = it },
                    label = { Text("E-mail") },
                    isError = !emailOk,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    mobile, { mobile = it },
                    label = { Text("Мобильный телефон") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    work, { work = it },
                    label = { Text("Рабочий телефон") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(company, { company = it }, label = { Text("Организация") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(job, { job = it }, label = { Text("Должность") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
internal fun ContactRow(name: String, email: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name, email, size = 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle.ifBlank { email },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@HiltViewModel
class ContactDetailViewModel @Inject constructor(
    private val repo: ContactsRepository,
    saved: SavedStateHandle,
) : ViewModel() {
    val email: String = Uri.decode(checkNotNull(saved.get<String>("email")))
    private val personalId: String? = saved.get<String>("id")?.takeIf { it.isNotBlank() }

    var contact by mutableStateOf<ContactInfo?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    /** id личного контакта с этим адресом (если есть). */
    var personal by mutableStateOf<String?>(personalId)
        private set
    var busy by mutableStateOf(false)
        private set

    private val _events = Channel<String?>(Channel.BUFFERED)
    /** null — закрыть экран. */
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            val local = personalId?.let { repo.personalById(it) } ?: repo.personalByEmail(email)
            personal = local?.id
            contact = if (local != null && personalId != null) {
                ContactInfo(
                    local.id, local.displayName, local.email,
                    phones = local.phones.lines().filter { '=' in it }.map { it.substringBefore('=') to it.substringAfter('=') },
                    company = local.company, department = local.department, jobTitle = local.jobTitle, office = local.office,
                )
            } else {
                runCatching { repo.lookup(email) }.getOrNull() ?: ContactInfo(null, email.substringBefore('@'), email)
            }
            loading = false
        }
    }

    fun addToContacts() {
        val c = contact ?: return
        busy = true
        viewModelScope.launch {
            try {
                repo.create(c.copy(id = null))
                personal = repo.personalByEmail(c.email)?.id
                _events.send("Добавлено в «Мои контакты»")
            } catch (e: Exception) {
                if (e is CancellationException) throw e // закрыли экран или начали новый запрос — это не ошибка
                _events.send(e.userMessage())
            } finally {
                busy = false
            }
        }
    }

    fun deletePersonal() {
        val id = personal ?: return
        busy = true
        viewModelScope.launch {
            try {
                repo.delete(id)
                _events.send("Контакт удалён")
                _events.send(null)
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
fun ContactDetailScreen(
    onBack: () -> Unit,
    onWrite: (String) -> Unit,
    onMeeting: (String) -> Unit,
    vm: ContactDetailViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.events.collect { if (it == null) onBack() else scope.launch { snackbar.showSnackbar(it) } } }
    val c = vm.contact
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад") } },
                title = {},
                actions = {
                    if (vm.personal != null) {
                        IconButton(onClick = { confirmDelete = true }, enabled = !vm.busy) { Icon(Icons.Outlined.Delete, "Удалить контакт") }
                    } else if (c != null && !c.isGroup) {
                        IconButton(onClick = vm::addToContacts, enabled = !vm.busy) { Icon(Icons.Outlined.PersonAdd, "Добавить в мои контакты") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            if (vm.loading || vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(c?.displayName.orEmpty(), vm.email, size = 96.dp)
                Spacer(Modifier.height(12.dp))
                Text(c?.displayName ?: vm.email, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                val sub = listOfNotNull(c?.jobTitle, c?.department).filter { it.isNotBlank() }.joinToString(" · ")
                if (sub.isNotBlank()) {
                    Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(20.dp))
                val phone = c?.phones?.firstOrNull { it.first == "MobilePhone" }?.second ?: c?.phones?.firstOrNull()?.second
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    QuickAction(Icons.Outlined.Mail, "Написать") { onWrite(vm.email) }
                    if (phone != null) QuickAction(Icons.Outlined.Call, "Позвонить") { dial(context, phone) }
                    QuickAction(Icons.Outlined.Event, "Встреча") { onMeeting(vm.email) }
                }
            }
            HorizontalDivider()
            DetailRow(Icons.Outlined.Email, vm.email, "e-mail", onClick = { onWrite(vm.email) }, onLongClick = {
                copy(context, vm.email); scope.launch { snackbar.showSnackbar("Адрес скопирован") }
            })
            c?.phones?.forEach { (key, number) ->
                DetailRow(
                    if (key == "MobilePhone") Icons.Outlined.Smartphone else Icons.Outlined.Phone,
                    number,
                    phoneLabel(key),
                    onClick = { dial(context, number) },
                    onLongClick = { copy(context, number); scope.launch { snackbar.showSnackbar("Номер скопирован") } },
                )
            }
            if (!c?.company.isNullOrBlank()) DetailRow(Icons.Outlined.Business, c!!.company, "организация")
            if (!c?.department.isNullOrBlank()) DetailRow(Icons.Outlined.Badge, c!!.department, "отдел")
            if (!c?.office.isNullOrBlank()) DetailRow(Icons.Outlined.Place, c!!.office, "офис")
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Удалить контакт?",
            text = "Контакт будет удалён из «Моих контактов». В адресной книге компании сотрудник останется.",
            confirm = "Удалить",
            destructive = true,
            onConfirm = vm::deletePersonal,
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(onClick = onClick, modifier = Modifier.padding(4.dp)) { Icon(icon, label) }
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailRow(icon: ImageVector, value: String, label: String, onClick: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null || onLongClick != null) {
                    Modifier.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun phoneLabel(key: String) = when (key) {
    "MobilePhone" -> "мобильный"
    "BusinessPhone", "BusinessPhone2" -> "рабочий"
    "HomePhone", "HomePhone2" -> "домашний"
    "BusinessFax" -> "факс"
    else -> "телефон"
}

private fun dial(context: Context, number: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number.filter { it.isDigit() || it == '+' }))) }
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("ExMail", text))
}
