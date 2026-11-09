package io.github.denhaiz.exmail.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import io.github.denhaiz.exmail.data.AccountStore
import io.github.denhaiz.exmail.data.AppSettings
import io.github.denhaiz.exmail.sync.Notifications
import io.github.denhaiz.exmail.sync.PushController
import io.github.denhaiz.exmail.sync.UpdateChecker
import io.github.denhaiz.exmail.ui.settings.UpdateDialog
import io.github.denhaiz.exmail.ui.settings.UpdateInstallHost
import io.github.denhaiz.exmail.update.AppUpdater
import io.github.denhaiz.exmail.update.UpdateState
import androidx.compose.runtime.saveable.rememberSaveable
import io.github.denhaiz.exmail.ui.calendar.CalendarScreen
import io.github.denhaiz.exmail.ui.calendar.EventEditScreen
import io.github.denhaiz.exmail.ui.calendar.EventScreen
import io.github.denhaiz.exmail.ui.common.LocalAppScope
import io.github.denhaiz.exmail.ui.common.Motion
import io.github.denhaiz.exmail.ui.contacts.ContactDetailScreen
import io.github.denhaiz.exmail.ui.contacts.ContactsScreen
import io.github.denhaiz.exmail.ui.mail.ComposeScreen
import io.github.denhaiz.exmail.ui.mail.MailListScreen
import io.github.denhaiz.exmail.ui.mail.MessageScreen
import io.github.denhaiz.exmail.ui.settings.SettingsScreen
import java.time.LocalDate
import javax.inject.Inject

private data class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val allTabs = mapOf(
    "mail" to Tab("mail", "Почта", Icons.Outlined.Mail, Icons.Filled.Mail),
    "calendar" to Tab("calendar", "Календарь", Icons.Outlined.CalendarMonth, Icons.Filled.CalendarMonth),
    "contacts" to Tab("contacts", "Контакты", Icons.Outlined.Contacts, Icons.Filled.Contacts),
)

private fun enc(s: String) = Uri.encode(s)

object Routes {
    /** [browse] = false — письмо из поиска: свайп не листает папку. */
    fun message(id: String, browse: Boolean = true) = "message/${enc(id)}?browse=${if (browse) 1 else 0}"
    fun compose(mode: String = "new", ref: String = "", to: String = "") = "compose?mode=$mode&ref=${enc(ref)}&to=${enc(to)}"
    fun event(id: String) = "event/${enc(id)}"
    fun eventEdit(id: String = "", date: LocalDate? = null, to: String = "") = "eventEdit?id=${enc(id)}&date=${date ?: ""}&to=${enc(to)}"
    /** Контакт без e-mail (только телефон) открывается по id. */
    fun contact(email: String, id: String? = null) = "contact?email=${enc(email)}&id=${enc(id.orEmpty())}"
}

@HiltViewModel
class AppNavViewModel @Inject constructor(
    private val store: AccountStore,
    val push: PushController,
    val updater: AppUpdater,
) : ViewModel() {
    val settings = store.settings

    fun batteryAsked() = viewModelScope.launch { store.setBatteryAsked() }

    /** Ответ на вопрос о мгновенной доставке при первом запуске. */
    fun choosePush(enabled: Boolean) = viewModelScope.launch { store.setInstantPush(enabled) }

    /** «Позже» в окне обновления: следующее напоминание — через 3 дня. */
    fun postponeUpdate(version: String) = viewModelScope.launch {
        store.setUpdatePostponed(version, System.currentTimeMillis() + UpdateChecker.REMIND_MILLIS)
    }
}

@Composable
fun AppNav(
    openMessageId: String?,
    onMessageOpened: () -> Unit,
    /** Нажато уведомление о новой версии — открыть настройки с предложением обновиться. */
    openUpdate: Boolean = false,
    onUpdateOpened: () -> Unit = {},
    navVm: AppNavViewModel = hiltViewModel(),
) {
    val settings by navVm.settings.collectAsState(initial = AppSettings())
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val tabs = settings.tabOrder.mapNotNull { allTabs[it] }
    val tabOrder by rememberUpdatedState(tabs.map { it.route })
    val motion = remember { Motion.Nav(tabOrder = { tabOrder }, zoomRoutes = { it?.startsWith("message/") == true }) }
    val context = LocalContext.current

    // Первый запуск, по очереди: разрешение на уведомления (Android 13+) → «Включить мгновенную доставку?» →
    // если включили — «Разрешить работу в фоне без ограничений?». Вопросы — только по уже прочитанным настройкам:
    // по значениям по умолчанию окно появилось бы и сразу пропало
    var permissionDone by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionDone = true
        navVm.push.syncAsync()
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissionDone = true
        }
    }
    val loadedSettings by navVm.settings.collectAsState(initial = null)
    var batteryAnswered by rememberSaveable { mutableStateOf(false) }
    val askPush = loadedSettings?.let { s ->
        permissionDone && !s.pushAsked && s.notifications && NotificationManagerCompat.from(context).areNotificationsEnabled()
    } == true
    val askBattery = loadedSettings?.let { s ->
        permissionDone && !batteryAnswered && s.pushAsked && s.instantPush && s.notifications && !s.batteryAsked &&
            !navVm.push.isUnrestricted()
    } == true
    LaunchedEffect(openMessageId) {
        if (openMessageId != null) {
            nav.navigate(Routes.message(openMessageId))
            onMessageOpened()
        }
    }
    // Новая версия — окно при открытии приложения (раз за запуск), если её не отложили кнопкой «Позже»
    val update by navVm.updater.state.collectAsState()
    var updatePromptShown by rememberSaveable { mutableStateOf(false) }
    var updatePrompt by rememberSaveable { mutableStateOf(false) }
    // Единственное место установки обновления (из настроек и из этого окна)
    UpdateInstallHost(navVm.updater) { msg -> scope.launch { snackbar.showSnackbar(msg) } }
    LaunchedEffect(Unit) { navVm.updater.refreshIfStale() }
    // Настройки ещё не прочитаны — не знаем, отложено ли обновление: ждём их
    val storedSettings by navVm.settings.collectAsState(initial = null)
    LaunchedEffect(update, storedSettings) {
        val u = update
        val s = storedSettings ?: return@LaunchedEffect
        val release = (u as? UpdateState.Available)?.release ?: (u as? UpdateState.Ready)?.release ?: return@LaunchedEffect
        // Открыто уведомлением — окно покажут настройки; уже обновляется по просьбе пользователя — окно не нужно
        if (openUpdate || route == "settings?update={update}" || navVm.updater.installRequested.value) return@LaunchedEffect
        if (askPush || askBattery) return@LaunchedEffect // сначала вопросы первого запуска — окна не накладываются
        if (!updatePromptShown && !s.updatePostponed(release.version)) {
            updatePromptShown = true
            updatePrompt = true
        }
    }
    if (updatePrompt) {
        UpdateDialog(
            update,
            navVm.updater,
            onLater = { release ->
                updatePrompt = false
                navVm.postponeUpdate(release.version)
                Notifications.cancelUpdate(context)
            },
            onDismiss = { updatePrompt = false },
        )
    }
    LaunchedEffect(openUpdate) {
        if (openUpdate) {
            nav.navigate("settings?update=1")
            onUpdateOpened()
        }
    }

    if (askPush) {
        AlertDialog(
            onDismissRequest = {}, // нужен ответ: от него зависит, как приходит почта
            icon = { Icon(Icons.Outlined.NotificationsActive, null) },
            title = { Text("Мгновенная доставка писем") },
            text = {
                Text(
                    "Включить? Письма будут приходить сразу: ExMail держит подключение к почтовому серверу, " +
                        "в шторке появится значок «Почта на связи». Без неё почта проверяется по расписанию, " +
                        "и уведомления могут приходить с опозданием до 15 минут. Изменить можно в настройках.",
                )
            },
            confirmButton = { TextButton(onClick = { navVm.choosePush(true) }) { Text("Включить") } },
            dismissButton = { TextButton(onClick = { navVm.choosePush(false) }) { Text("Не нужно") } },
        )
    } else if (askBattery) {
        AlertDialog(
            onDismissRequest = { batteryAnswered = true; navVm.batteryAsked() },
            icon = { Icon(Icons.Outlined.NotificationsActive, null) },
            title = { Text("Стабильная доставка") },
            text = {
                Text(
                    "Чтобы Android не отключал подключение к почте для экономии заряда, разрешите ExMail работать в фоне " +
                        "без ограничений. Изменить можно в настройках приложения.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    batteryAnswered = true
                    navVm.batteryAsked()
                    runCatching { context.startActivity(navVm.push.unrestrictIntent()) }
                }) { Text("Разрешить") }
            },
            dismissButton = { TextButton(onClick = { batteryAnswered = true; navVm.batteryAsked() }) { Text("Не сейчас") } },
        )
    }

    fun toast(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    // Нижняя панель лежит поверх экранов и уезжает вниз при открытии письма. Экраны вкладок всегда оставляют
    // под неё место, поэтому при переходах ничего не меняет размер (не «прыгают» кнопки и списки).
    val barPadding = PaddingValues(bottom = NAV_BAR_HEIGHT + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
    val tabPage = Modifier.padding(barPadding).consumeWindowInsets(barPadding)
    val showBar = route == null || tabs.any { it.route == route }

    Scaffold(contentWindowInsets = WindowInsets(0)) { padding ->
        CompositionLocalProvider(LocalAppScope provides scope) {
            Box(Modifier.padding(padding).fillMaxSize()) {
                NavHost(
                    nav,
                    startDestination = "mail",
                    modifier = Modifier.fillMaxSize(),
                    enterTransition = motion.enter,
                    exitTransition = motion.exit,
                    popEnterTransition = motion.popEnter,
                    popExitTransition = motion.popExit,
                ) {
                    composable("mail") {
                        Box(tabPage) {
                            MailListScreen(
                                onOpenMessage = { id, browse -> nav.go(Routes.message(id, browse)) },
                                onOpenDraft = { nav.go(Routes.compose("draft", it)) },
                                onCompose = { nav.go(Routes.compose()) },
                                onReply = { id, mode -> nav.go(Routes.compose(mode, id)) },
                                onSettings = { nav.go("settings") },
                                onOpenTab = { nav.switchTab(it) },
                                snackbar = snackbar,
                            )
                        }
                    }
                    composable("calendar") {
                        Box(tabPage) {
                            CalendarScreen(
                                onOpenEvent = { nav.go(Routes.event(it)) },
                                onNewEvent = { nav.go(Routes.eventEdit(date = it)) },
                                snackbar = snackbar,
                            )
                        }
                    }
                    composable("contacts") {
                        Box(tabPage) {
                            ContactsScreen(onOpen = { email, id -> nav.go(Routes.contact(email, id)) })
                        }
                    }
                    composable(
                        MESSAGE_ROUTE,
                        arguments = listOf(
                            navArgument("id") { type = NavType.StringType },
                            navArgument("browse") { type = NavType.StringType; defaultValue = "1" },
                        ),
                    ) {
                        MessageScreen(
                            onBack = { nav.back() },
                            onReply = { id, mode -> nav.go(Routes.compose(mode, id)) },
                            onComposeTo = { nav.go(Routes.compose(to = it)) },
                            onOpenContact = { nav.go(Routes.contact(it)) },
                            snackbar = snackbar,
                            onOpenSibling = { id, direction ->
                                Motion.openSibling(direction)
                                // Соседнее письмо заменяет текущее: «Назад» возвращает в список
                                nav.navigate(Routes.message(id)) { popUpTo(MESSAGE_ROUTE) { inclusive = true } }
                            },
                        )
                    }
                    composable(
                        "compose?mode={mode}&ref={ref}&to={to}",
                        arguments = listOf(
                            navArgument("mode") { type = NavType.StringType; defaultValue = "new" },
                            navArgument("ref") { type = NavType.StringType; defaultValue = "" },
                            navArgument("to") { type = NavType.StringType; defaultValue = "" },
                        ),
                    ) {
                        ComposeScreen(
                            onClose = { nav.back() },
                            onSent = {
                                nav.popBackStack()
                                toast("Письмо отправлено")
                            },
                            onDraftSaved = {
                                nav.popBackStack()
                                toast("Черновик сохранён")
                            },
                        )
                    }
                    composable("event/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                        EventScreen(
                            onBack = { nav.back() },
                            onEdit = { nav.go(Routes.eventEdit(id = it)) },
                            onContact = { nav.go(Routes.contact(it)) },
                            onMailto = { nav.go(Routes.compose(to = it)) },
                            snackbar = snackbar,
                        )
                    }
                    composable(
                        "eventEdit?id={id}&date={date}&to={to}",
                        arguments = listOf(
                            navArgument("id") { type = NavType.StringType; defaultValue = "" },
                            navArgument("date") { type = NavType.StringType; defaultValue = "" },
                            navArgument("to") { type = NavType.StringType; defaultValue = "" },
                        ),
                    ) {
                        EventEditScreen(
                            onClose = { nav.back() },
                            onSaved = { msg ->
                                // После изменения возвращаемся в календарь (карточка события могла устареть)
                                if (!nav.popBackStack("calendar", inclusive = false)) {
                                    // Встреча из карточки контакта или письма: форму убираем из стека этой вкладки,
                                    // иначе она вернётся при следующем открытии вкладки
                                    nav.popBackStack()
                                    nav.switchTab("calendar")
                                }
                                if (msg.isNotBlank()) toast(msg)
                            },
                        )
                    }
                    composable(
                        "contact?email={email}&id={id}",
                        arguments = listOf(
                            navArgument("email") { type = NavType.StringType; defaultValue = "" },
                            navArgument("id") { type = NavType.StringType; defaultValue = "" },
                        ),
                    ) {
                        ContactDetailScreen(
                            onBack = { nav.back() },
                            onWrite = { nav.go(Routes.compose(to = it)) },
                            onMeeting = { nav.go(Routes.eventEdit(date = LocalDate.now(), to = it)) },
                        )
                    }
                    composable(
                        "settings?update={update}",
                        arguments = listOf(navArgument("update") { type = NavType.StringType; defaultValue = "0" }),
                    ) { entry ->
                        SettingsScreen(onBack = { nav.back() }, offerUpdate = entry.arguments?.getString("update") == "1")
                    }
                }
                AnimatedVisibility(
                    visible = showBar,
                    enter = slideInVertically(tween(Motion.MEDIUM)) { it },
                    exit = slideOutVertically(tween(Motion.MEDIUM)) { it },
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    NavigationBar {
                        tabs.forEach { t ->
                            val selected = route == t.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = { nav.switchTab(t.route) },
                                icon = { Icon(if (selected) t.selectedIcon else t.icon, null) },
                                label = { Text(t.label, maxLines = 1) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val MESSAGE_ROUTE = "message/{id}?browse={browse}"

/** Высота нижней панели вкладок Material 3 (без системной полосы навигации). */
private val NAV_BAR_HEIGHT = 80.dp

/**
 * Переход по нажатию: только с полностью открытого экрана — двойное нажатие или нажатие во время анимации
 * не открывает вторую копию экрана.
 */
private fun NavHostController.go(route: String) {
    if (currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) navigate(route)
}

/** «Назад» по стрелке: один раз (не во время анимации закрытия) и никогда не убирает первый экран вкладки. */
private fun NavHostController.back() {
    val current = currentBackStackEntry ?: return
    if (current.lifecycle.currentState != Lifecycle.State.RESUMED || previousBackStackEntry == null) return
    popBackStack()
}

private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
