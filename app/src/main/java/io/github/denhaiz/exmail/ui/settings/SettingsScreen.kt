package io.github.denhaiz.exmail.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.SwipeLeft
import androidx.compose.material.icons.outlined.SwipeRight
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import io.github.denhaiz.exmail.BuildConfig
import io.github.denhaiz.exmail.data.AccountInfo
import io.github.denhaiz.exmail.data.AccountStore
import io.github.denhaiz.exmail.data.AppSettings
import io.github.denhaiz.exmail.data.SwipeAction
import io.github.denhaiz.exmail.data.repo.AccountRepository
import io.github.denhaiz.exmail.sync.Notifications
import io.github.denhaiz.exmail.sync.PushController
import io.github.denhaiz.exmail.update.AppUpdater
import io.github.denhaiz.exmail.update.UpdateState
import io.github.denhaiz.exmail.sync.SyncScheduler
import io.github.denhaiz.exmail.ui.common.Avatar
import io.github.denhaiz.exmail.ui.common.ConfirmDialog
import io.github.denhaiz.exmail.ui.common.Format
import io.github.denhaiz.exmail.ui.calendar.EventEditViewModel
import io.github.denhaiz.exmail.ui.mail.ComposeViewModel
import io.github.denhaiz.exmail.ui.mail.SWIPE_CHOICES
import io.github.denhaiz.exmail.ui.mail.icon
import io.github.denhaiz.exmail.ui.mail.title
import io.github.denhaiz.exmail.ews.OofSettings
import io.github.denhaiz.exmail.data.userMessage
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: AccountStore,
    private val accounts: AccountRepository,
    private val scheduler: SyncScheduler,
    val push: PushController,
    val updater: AppUpdater,
) : ViewModel() {
    val account = accounts.current
    val settings = store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun setNotifications(v: Boolean) = viewModelScope.launch { store.setNotifications(v) }
    fun setLoadImages(v: Boolean) = viewModelScope.launch { store.setLoadImages(v) }
    fun setSignature(v: String) = viewModelScope.launch { store.setSignature(v) }
    fun setTheme(v: String) = viewModelScope.launch { store.setTheme(v) }
    fun setInstantPush(v: Boolean) = viewModelScope.launch { store.setInstantPush(v) }
    fun setDarkMessages(v: Boolean) = viewModelScope.launch { store.setDarkMessages(v) }
    fun setSwipeRight(v: SwipeAction) = viewModelScope.launch { store.setSwipeRight(v) }
    fun setSwipeLeft(v: SwipeAction) = viewModelScope.launch { store.setSwipeLeft(v) }
    fun setUpdateNotify(v: Boolean) = viewModelScope.launch { store.setUpdateNotify(v) }
    fun setSyncMinutes(v: Int) = viewModelScope.launch {
        store.setSyncMinutes(v)
        scheduler.schedule(v)
    }

    fun logout() = viewModelScope.launch { accounts.logout() }

    // Автоответ (Out of Office)
    var oof by mutableStateOf<OofSettings?>(null)
        private set
    var oofBusy by mutableStateOf(false)
        private set
    var oofMessage by mutableStateOf<String?>(null)

    fun loadOof() {
        oofBusy = true
        viewModelScope.launch {
            try {
                oof = accounts.getOof().let {
                    it.copy(
                        internalReply = EventEditViewModel.htmlToText(it.internalReply),
                        externalReply = EventEditViewModel.htmlToText(it.externalReply),
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e // закрыли экран или начали новый запрос — это не ошибка
                oofMessage = e.userMessage()
            } finally {
                oofBusy = false
            }
        }
    }

    fun saveOof(enabled: Boolean, text: String, external: Boolean, from: java.time.LocalDate?, to: java.time.LocalDate?) {
        oofBusy = true
        viewModelScope.launch {
            try {
                val zone = java.time.ZoneId.systemDefault()
                val scheduled = enabled && from != null && to != null
                val settings = OofSettings(
                    state = if (!enabled) "Disabled" else if (scheduled) "Scheduled" else "Enabled",
                    externalAudience = if (external) "All" else "None",
                    start = from?.atStartOfDay(zone)?.toInstant(),
                    end = to?.plusDays(1)?.atStartOfDay(zone)?.toInstant(),
                    internalReply = text,
                    externalReply = if (external) text else "",
                )
                // Exchange хранит автоответ как HTML: переводы строк — в <br>, иначе ответ придёт одной строкой
                val html = ComposeViewModel.textToHtml(text)
                accounts.setOof(settings.copy(internalReply = html, externalReply = if (external) html else ""))
                oof = settings
                oofMessage = if (enabled) "Автоответ включён" else "Автоответ выключен"
            } catch (e: Exception) {
                if (e is CancellationException) throw e // закрыли экран или начали новый запрос — это не ошибка
                oofMessage = "Не сохранено: ${e.userMessage()}"
            } finally {
                oofBusy = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    /** Открыто из уведомления о новой версии — сразу предложить обновиться. */
    offerUpdate: Boolean = false,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val s by vm.settings.collectAsState()
    var confirmLogout by remember { mutableStateOf(false) }
    var editSignature by remember { mutableStateOf(false) }
    var pickInterval by remember { mutableStateOf(false) }
    var editOof by remember { mutableStateOf(false) }
    var pickTheme by remember { mutableStateOf(false) }
    var pickSwipe by remember { mutableStateOf<Boolean?>(null) } // true — вправо, false — влево
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.loadOof() }
    LaunchedEffect(vm.oofMessage) {
        vm.oofMessage?.let { snackbar.showSnackbar(it); vm.oofMessage = null }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.setNotifications(granted)
    }
    val context = LocalContext.current
    // Разрешения меняются в настройках Android — перечитываем при возврате на экран
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose {}
    }
    val systemAllowed = remember(resumed) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val unrestricted = remember(resumed) { vm.push.isUnrestricted() }

    // Обновление приложения
    val update by vm.updater.state.collectAsState()
    var updateDialog by remember { mutableStateOf(offerUpdate) }
    var installWhenReady by remember { mutableStateOf(false) }
    var installAfterPermission by remember { mutableStateOf(false) }
    fun install(file: java.io.File) {
        Notifications.cancelUpdate(context)
        if (!vm.updater.canInstall()) {
            // Android 8+ один раз спрашивает, можно ли ExMail устанавливать приложения; после разрешения установка продолжится сама
            installAfterPermission = true
            runCatching { context.startActivity(vm.updater.permissionIntent()) }
            return
        }
        installAfterPermission = false
        runCatching { context.startActivity(vm.updater.installIntent(file)) }
            .onFailure { vm.oofMessage = "Не удалось открыть установщик Android" }
    }
    LaunchedEffect(Unit) {
        val st = vm.updater.state.value
        if (st is UpdateState.Idle || (offerUpdate && st !is UpdateState.Available && st !is UpdateState.Downloading && st !is UpdateState.Ready)) {
            vm.updater.check()
        }
    }
    LaunchedEffect(update) {
        val st = update
        if (installWhenReady && st is UpdateState.Ready) {
            installWhenReady = false
            updateDialog = false
            install(st.file)
        }
    }
    LaunchedEffect(resumed) {
        val st = vm.updater.state.value
        if (installAfterPermission && st is UpdateState.Ready && vm.updater.canInstall()) install(st.file)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад") } },
                title = { Text("Настройки") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            vm.account?.let { a ->
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(a.displayName, a.email, size = 56.dp)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(a.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(a.email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            Section("Оформление")
            ClickRow(Icons.Outlined.Palette, "Тема", themeLabel(s.theme)) { pickTheme = true }
            SwitchRow(
                Icons.Outlined.DarkMode,
                "Тёмные письма",
                "В тёмной теме показывать письма на тёмном фоне",
                s.darkMessages,
                vm::setDarkMessages,
            )

            Section("Уведомления")
            SwitchRow(Icons.Outlined.Notifications, "Новые письма", "Сообщать о письмах во «Входящих»", s.notifications) { on ->
                if (on && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.setNotifications(on)
            }
            if (s.notifications && !systemAllowed) {
                ClickRow(
                    Icons.Outlined.NotificationsOff,
                    "Уведомления запрещены в Android",
                    "Нажмите, чтобы разрешить уведомления ExMail",
                ) { runCatching { context.startActivity(vm.push.notificationSettingsIntent()) } }
            }
            SwitchRow(
                Icons.Outlined.Bolt,
                "Мгновенная доставка",
                if (s.instantPush) "Письма приходят сразу: постоянное подключение к серверу, значок «Почта на связи» в шторке"
                else "Выключена — почта проверяется по расписанию",
                s.instantPush && s.notifications,
            ) { on -> if (s.notifications) vm.setInstantPush(on) }
            if (s.notifications && s.instantPush) {
                ClickRow(
                    Icons.Outlined.BatteryChargingFull,
                    "Работа в фоне",
                    if (unrestricted) "Без ограничений" else "Ограничена Android — уведомления могут опаздывать. Нажмите, чтобы разрешить",
                ) {
                    val intent = if (unrestricted) Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) else vm.push.unrestrictIntent()
                    runCatching { context.startActivity(intent) }
                }
            }
            ClickRow(
                Icons.Outlined.Sync,
                if (s.instantPush && s.notifications) "Резервная проверка" else "Проверять почту",
                intervalLabel(s.syncMinutes),
            ) { pickInterval = true }

            Section("Письма")
            val oof = vm.oof
            ClickRow(
                Icons.Outlined.EventBusy,
                "Автоответ (нет на месте)",
                when {
                    vm.oofBusy && oof == null -> "Загрузка…"
                    oof == null -> "Недоступно"
                    oof.state == "Disabled" -> "Выключен"
                    oof.state == "Scheduled" && oof.start != null && oof.end != null ->
                        "Включён: ${Format.listTime(oof.start.toEpochMilli())} – ${Format.listTime(oof.end.minusSeconds(1).toEpochMilli())}"
                    else -> "Включён"
                },
            ) { if (oof != null) editOof = true else vm.loadOof() }
            ClickRow(Icons.Outlined.Draw, "Подпись", s.signature.ifBlank { "Не задана" }) { editSignature = true }
            SwitchRow(Icons.Outlined.Image, "Картинки из интернета", "Загружать автоматически", s.loadImages, vm::setLoadImages)

            Section("Жесты в списке писем")
            ClickRow(Icons.Outlined.SwipeRight, "Свайп вправо", s.swipeRight.title) { pickSwipe = true }
            ClickRow(Icons.Outlined.SwipeLeft, "Свайп влево", s.swipeLeft.title) { pickSwipe = false }

            Section("Обновления")
            UpdateRow(update) {
                when (val st = update) {
                    is UpdateState.Ready -> install(st.file)
                    is UpdateState.Available, is UpdateState.Downloading -> updateDialog = true
                    is UpdateState.Failed -> if (st.release != null) updateDialog = true else vm.updater.check()
                    else -> vm.updater.check()
                }
            }
            SwitchRow(
                Icons.Outlined.NewReleases,
                "Сообщать о новых версиях",
                "Уведомление, когда выходит новая версия ExMail",
                s.updateNotify,
                vm::setUpdateNotify,
            )

            Section("Учётная запись")
            vm.account?.let {
                val auth = if (it.auth == AccountInfo.AUTH_BASIC) "Basic" else "NTLM"
                ClickRow(Icons.Outlined.Dns, "Сервер", it.ewsUrl.removePrefix("https://").substringBefore('/') + " · " + auth) {}
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ListItem(
                headlineContent = { Text("Выйти из учётной записи", color = MaterialTheme.colorScheme.error) },
                leadingContent = { Icon(Icons.AutoMirrored.Outlined.Logout, null, tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable { confirmLogout = true },
            )
        }
    }

    if (confirmLogout) {
        ConfirmDialog(
            title = "Выйти?",
            text = "Письма и данные календаря будут удалены с этого телефона. На сервере ничего не изменится.",
            confirm = "Выйти",
            destructive = true,
            onConfirm = { vm.logout() },
            onDismiss = { confirmLogout = false },
        )
    }
    if (editSignature) {
        var text by remember { mutableStateOf(s.signature) }
        AlertDialog(
            onDismissRequest = { editSignature = false },
            title = { Text("Подпись") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("С уважением,\nИван Иванов\nтел. +7 …") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = { TextButton(onClick = { vm.setSignature(text.trim()); editSignature = false }) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { editSignature = false }) { Text("Отмена") } },
        )
    }
    if (editOof) {
        vm.oof?.let { current ->
            OofDialog(current, onSave = { en, text, ext, from, to -> vm.saveOof(en, text, ext, from, to) }, onDismiss = { editOof = false })
        }
    }
    if (pickTheme) {
        AlertDialog(
            onDismissRequest = { pickTheme = false },
            title = { Text("Тема") },
            text = {
                Column {
                    listOf(AppSettings.THEME_SYSTEM, AppSettings.THEME_LIGHT, AppSettings.THEME_DARK).forEach { t ->
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.setTheme(t); pickTheme = false }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = s.theme == t, onClick = { vm.setTheme(t); pickTheme = false })
                            Text(themeLabel(t))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickTheme = false }) { Text("Закрыть") } },
        )
    }
    if (updateDialog) {
        UpdateDialog(
            update,
            onUpdate = { release -> installWhenReady = true; vm.updater.download(release) },
            onInstall = { file -> updateDialog = false; install(file) },
            onRetry = { vm.updater.check() },
            onStop = { installWhenReady = false; vm.updater.cancelDownload() },
            onDismiss = { updateDialog = false },
        )
    }
    pickSwipe?.let { right ->
        val selected = if (right) s.swipeRight else s.swipeLeft
        fun pick(a: SwipeAction) {
            if (right) vm.setSwipeRight(a) else vm.setSwipeLeft(a)
            pickSwipe = null
        }
        AlertDialog(
            onDismissRequest = { pickSwipe = null },
            title = { Text(if (right) "Свайп вправо" else "Свайп влево") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SWIPE_CHOICES.forEach { a ->
                        Row(
                            Modifier.fillMaxWidth().clickable { pick(a) }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected == a, onClick = { pick(a) })
                            Icon(a.icon(), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp))
                            Text(a.title)
                        }
                    }
                    Text(
                        (if (right) "Проведите по письму слева направо" else "Проведите по письму справа налево") +
                            " хотя бы до середины строки. «Ответить» и «Переслать» в «Черновиках» не действуют.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { pickSwipe = null }) { Text("Закрыть") } },
        )
    }
    if (pickInterval) {
        AlertDialog(
            onDismissRequest = { pickInterval = false },
            title = { Text("Проверять почту") },
            text = {
                Column {
                    listOf(15, 30, 60, 180).forEach { m ->
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.setSyncMinutes(m); pickInterval = false }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = s.syncMinutes == m, onClick = { vm.setSyncMinutes(m); pickInterval = false })
                            Text(intervalLabel(m))
                        }
                    }
                    Text(
                        "Пока приложение открыто, почта проверяется каждую минуту. При мгновенной доставке расписание — запасной вариант.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { pickInterval = false }) { Text("Закрыть") } },
        )
    }
}

/** Строка «Обновление приложения»: установленная версия и что можно сделать. */
@Composable
private fun UpdateRow(state: UpdateState, onClick: () -> Unit) {
    val current = BuildConfig.VERSION_NAME
    val subtitle = when (state) {
        UpdateState.Idle -> "Версия $current · нажмите, чтобы проверить"
        UpdateState.Checking -> "Версия $current · проверяем…"
        is UpdateState.UpToDate -> "Установлена последняя версия $current"
        is UpdateState.Available -> "Доступна версия ${state.release.version} — нажмите, чтобы обновить"
        is UpdateState.Downloading ->
            "Загрузка версии ${state.release.version}" + if (state.progress >= 0) " · ${(state.progress * 100).toInt()} %" else "…"
        is UpdateState.Ready -> "Версия ${state.release.version} загружена — нажмите, чтобы установить"
        is UpdateState.Failed -> "${state.message}. Нажмите, чтобы повторить"
    }
    ListItem(
        headlineContent = { Text("Обновление приложения") },
        supportingContent = { Text(subtitle, maxLines = 3) },
        leadingContent = { Icon(Icons.Outlined.SystemUpdate, null) },
        trailingContent = {
            when (state) {
                UpdateState.Checking -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                is UpdateState.Downloading ->
                    if (state.progress >= 0) CircularProgressIndicator({ state.progress }, Modifier.size(24.dp), strokeWidth = 2.dp)
                    else CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                is UpdateState.Available -> FilledTonalButton(onClick = onClick) { Text("Обновить") }
                is UpdateState.Ready -> FilledTonalButton(onClick = onClick) { Text("Установить") }
                else -> Unit
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Новая версия: что изменилось, загрузка с ходом и установка. */
@Composable
internal fun UpdateDialog(
    state: UpdateState,
    onUpdate: (io.github.denhaiz.exmail.update.AppRelease) -> Unit,
    onInstall: (java.io.File) -> Unit,
    onRetry: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    val release = when (state) {
        is UpdateState.Available -> state.release
        is UpdateState.Downloading -> state.release
        is UpdateState.Ready -> state.release
        is UpdateState.Failed -> state.release
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.SystemUpdate, null) },
        title = {
            Text(
                when {
                    release != null -> "ExMail ${release.version}"
                    state is UpdateState.UpToDate -> "Обновлений нет"
                    else -> "Обновление"
                },
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (state) {
                    UpdateState.Idle, UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(16.dp))
                        Text("Проверяем новую версию…")
                    }
                    is UpdateState.UpToDate -> Text("Установлена последняя версия ${BuildConfig.VERSION_NAME}.")
                    is UpdateState.Downloading -> {
                        Text("Загрузка…" + if (state.progress >= 0) " ${(state.progress * 100).toInt()} % из ${Format.size(state.release.size)}" else "")
                        Spacer(Modifier.height(12.dp))
                        if (state.progress >= 0) LinearProgressIndicator({ state.progress }, Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "После загрузки откроется установка Android.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is UpdateState.Failed -> Text("Не получилось: ${state.message}.", color = MaterialTheme.colorScheme.error)
                    else -> Unit
                }
                if (release != null && state !is UpdateState.Downloading) {
                    if (state is UpdateState.Failed) Spacer(Modifier.height(12.dp))
                    val notes = AppUpdater.plainNotes(release.notes)
                    if (notes.isNotBlank()) Text(notes, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        (if (release.size > 0) "Размер ${Format.size(release.size)}. " else "") +
                            "Новая версия ставится поверх: письма, учётная запись и настройки сохранятся.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            when {
                state is UpdateState.Available -> TextButton(onClick = { onUpdate(state.release) }) { Text("Обновить") }
                state is UpdateState.Ready -> TextButton(onClick = { onInstall(state.file) }) { Text("Установить") }
                state is UpdateState.Failed && release != null -> TextButton(onClick = { onUpdate(release) }) { Text("Повторить") }
                state is UpdateState.Failed -> TextButton(onClick = onRetry) { Text("Повторить") }
                state is UpdateState.Downloading -> TextButton(onClick = onDismiss) { Text("Скрыть") }
                else -> TextButton(onClick = onDismiss) { Text("Закрыть") }
            }
        },
        dismissButton = {
            when (state) {
                is UpdateState.Downloading -> TextButton(onClick = onStop) { Text("Остановить") }
                is UpdateState.Available, is UpdateState.Ready, is UpdateState.Failed -> TextButton(onClick = onDismiss) { Text("Позже") }
                else -> Unit
            }
        },
    )
}

private fun themeLabel(t: String) = when (t) {
    AppSettings.THEME_LIGHT -> "Светлая"
    AppSettings.THEME_DARK -> "Тёмная"
    else -> "Как в системе"
}

private fun intervalLabel(m: Int) = when (m) {
    15 -> "Каждые 15 минут"
    30 -> "Каждые 30 минут"
    60 -> "Каждый час"
    else -> "Каждые ${m / 60} часа"
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@Composable
private fun ClickRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle, maxLines = 2) },
        leadingContent = { Icon(icon, null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
