package io.github.denhaiz.exmail.ui.mail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.Forward
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderDelete
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Outbox
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.denhaiz.exmail.data.SwipeAction
import io.github.denhaiz.exmail.data.db.FolderEntity
import io.github.denhaiz.exmail.data.db.MessageEntity
import io.github.denhaiz.exmail.ui.common.Avatar
import io.github.denhaiz.exmail.ui.common.ConfirmDialog
import io.github.denhaiz.exmail.ui.common.EmptyState
import io.github.denhaiz.exmail.ui.common.ErrorBanner
import io.github.denhaiz.exmail.ui.common.Format
import io.github.denhaiz.exmail.ui.common.HoldableBox
import io.github.denhaiz.exmail.ui.common.Motion
import io.github.denhaiz.exmail.ui.common.OpenOrigin
import io.github.denhaiz.exmail.ui.common.ReorderableColumn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailListScreen(
    /** [browse] — открыто из списка папки (соседние письма листаются), false — из поиска. */
    onOpenMessage: (id: String, browse: Boolean) -> Unit,
    onOpenDraft: (String) -> Unit,
    onCompose: () -> Unit,
    onReply: (id: String, mode: String) -> Unit,
    onSettings: () -> Unit,
    onOpenTab: (String) -> Unit,
    snackbar: SnackbarHostState,
    vm: MailListViewModel = hiltViewModel(),
) {
    val folders by vm.folders.collectAsState()
    val drawerGroups by vm.drawer.collectAsState()
    val folder by vm.currentFolder.collectAsState()
    val messages by vm.messages.collectAsState()
    val swipes by vm.swipes.collectAsState()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var folderMenu by remember { mutableStateOf(false) }
    var nameDialog by remember { mutableStateOf<String?>(null) } // "create" | "rename"
    var confirm by remember { mutableStateOf<String?>(null) } // "empty" | "delete"
    var moveTarget by remember { mutableStateOf<MessageEntity?>(null) }

    LaunchedEffect(Unit) { vm.messagesToUser.collect { snackbar.showSnackbar(it) } }
    // Системное «Назад» (кнопка или жест) закрывает поиск, а не уходит со вкладки
    BackHandler(enabled = vm.searchActive) { vm.closeSearch() }

    fun deleteWithUndo(m: MessageEntity) {
        vm.hide(m)
        // Предыдущее «Письмо удалено» закрывается сразу (и удаляет своё письмо), а не ждёт в очереди:
        // кнопка «Отменить» всегда относится к показанному письму
        snackbar.currentSnackbarData?.dismiss()
        scope.launch {
            val r = snackbar.showSnackbar("Письмо удалено", actionLabel = "Отменить", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) vm.undoDelete(m) else vm.deleteNow(m)
        }
    }

    val isDrafts = folder?.wellKnown == "drafts"
    fun open(m: MessageEntity) = if (isDrafts) onOpenDraft(m.id) else onOpenMessage(m.id, !vm.searchActive)

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            FolderDrawer(
                accountName = vm.account?.displayName.orEmpty(),
                accountEmail = vm.account?.email.orEmpty(),
                groups = drawerGroups,
                current = folder,
                onSelectFolder = {
                    vm.selectFolder(it)
                    scope.launch { drawer.close(); listState.scrollToItem(0) }
                },
                onSelectTab = { route ->
                    scope.launch { drawer.close() }
                    if (route != "mail") onOpenTab(route)
                },
                onReorderGroups = vm::reorderGroups,
                onReorderEntries = vm::reorderEntries,
                onNewFolder = { scope.launch { drawer.close() }; nameDialog = "create" },
                onSettings = { scope.launch { drawer.close() }; onSettings() },
            )
        },
    ) {
        Scaffold(
            topBar = {
                if (vm.searchActive) {
                    SearchTopBar(vm.searchQuery, vm::onSearchQuery, vm::closeSearch, folder?.name.orEmpty())
                } else {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Outlined.Menu, "Папки") }
                        },
                        title = {
                            Column {
                                Text(folder?.name ?: "Почта", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val unread = folder?.unread ?: 0
                                if (unread > 0) {
                                    Text(
                                        Format.plural(unread, "непрочитанное", "непрочитанных", "непрочитанных"),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                        },
                        actions = {
                            IconButton(onClick = vm::openSearch) { Icon(Icons.Outlined.Search, "Поиск") }
                            Box {
                                IconButton(onClick = { folderMenu = true }) { Icon(Icons.Outlined.MoreVert, "Действия с папкой") }
                                FolderMenu(
                                    expanded = folderMenu,
                                    folder = folder,
                                    onDismiss = { folderMenu = false },
                                    onMarkAllRead = vm::markAllRead,
                                    onEmpty = { confirm = "empty" },
                                    onNew = { nameDialog = "create" },
                                    onRename = { nameDialog = "rename" },
                                    onDelete = { confirm = "delete" },
                                )
                            }
                        },
                    )
                }
            },
            floatingActionButton = {
                if (!vm.searchActive) {
                    ExtendedFloatingActionButton(
                        onClick = onCompose,
                        expanded = listState.isScrollingUp(),
                        icon = { Icon(Icons.Outlined.Edit, null) },
                        text = { Text("Написать", maxLines = 1) },
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                vm.error?.let { ErrorBanner(it, onRetry = { vm.refresh() }, onDismiss = { vm.error = null }) }
                // Один объект на папку: строки списка не перерисовываются из-за новых лямбд при каждой перекомпоновке
                val actions = remember(isDrafts, swipes, onReply, onOpenMessage, onOpenDraft) {
                    MessageActions(
                        onOpen = ::open,
                        onReply = { onReply(it.id, "reply") },
                        onForward = { onReply(it.id, "forward") },
                        onToggleRead = vm::toggleRead,
                        onToggleFlag = vm::toggleFlag,
                        onMove = { moveTarget = it },
                        onDelete = ::deleteWithUndo,
                        canReply = !isDrafts,
                        swipes = swipes,
                    )
                }
                if (vm.searchActive) {
                    SearchResults(vm, actions)
                } else {
                    PullToRefreshBox(
                        isRefreshing = vm.pullRefreshing,
                        onRefresh = vm::pullRefresh,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        when {
                            messages.isEmpty() && (vm.refreshing || !vm.initialLoadDone) -> LoadingList()
                            messages.isEmpty() -> EmptyState(
                                Icons.Outlined.Inbox,
                                "Здесь пока пусто",
                                "В папке «${folder?.name.orEmpty()}» нет писем. Потяните вниз, чтобы обновить.",
                            )
                            else -> MessageList(messages, listState, vm.loadingMore, vm::loadMore, actions, retryKey = vm.refreshing)
                        }
                    }
                }
            }
        }
    }

    nameDialog?.let { mode ->
        FolderNameDialog(
            title = if (mode == "create") "Новая папка" else "Переименовать папку",
            initial = if (mode == "rename") folder?.name.orEmpty() else "",
            onConfirm = { if (mode == "create") vm.createFolder(it) else vm.renameCurrentFolder(it) },
            onDismiss = { nameDialog = null },
        )
    }
    when (confirm) {
        "empty" -> ConfirmDialog(
            title = "Очистить папку?",
            text = if (folder?.wellKnown == "deleteditems") "Все письма в «Удалённых» будут удалены окончательно."
            else "Все письма из папки «${folder?.name}» будут перемещены в «Удалённые».",
            confirm = "Очистить",
            destructive = true,
            onConfirm = vm::emptyCurrentFolder,
            onDismiss = { confirm = null },
        )
        "delete" -> ConfirmDialog(
            title = "Удалить папку?",
            text = "Папка «${folder?.name}» вместе с письмами будет перемещена в «Удалённые».",
            confirm = "Удалить",
            destructive = true,
            onConfirm = vm::deleteCurrentFolder,
            onDismiss = { confirm = null },
        )
    }
    moveTarget?.let { m ->
        MoveDialog(folders.filter { it.id != m.folderId }, onPick = { vm.move(m, it) }, onDismiss = { moveTarget = null })
    }
}

/** Действия над письмом из списка. */
@Stable
class MessageActions(
    val onOpen: (MessageEntity) -> Unit,
    val onReply: (MessageEntity) -> Unit,
    val onForward: (MessageEntity) -> Unit,
    val onToggleRead: (MessageEntity) -> Unit,
    val onToggleFlag: (MessageEntity) -> Unit,
    val onMove: (MessageEntity) -> Unit,
    val onDelete: (MessageEntity) -> Unit,
    val canReply: Boolean,
    swipes: Swipes = Swipes(SwipeAction.READ, SwipeAction.NONE),
) {
    /** Свайп слева направо; в «Черновиках» ответ и пересылка недоступны — такой свайп выключен. */
    val swipeRight: SwipeAction = available(swipes.right)
    val swipeLeft: SwipeAction = available(swipes.left)

    private fun available(a: SwipeAction) =
        if (!canReply && (a == SwipeAction.REPLY || a == SwipeAction.FORWARD)) SwipeAction.NONE else a

    fun perform(a: SwipeAction, m: MessageEntity) = when (a) {
        SwipeAction.NONE -> Unit
        SwipeAction.READ -> onToggleRead(m)
        SwipeAction.FLAG -> onToggleFlag(m)
        SwipeAction.REPLY -> onReply(m)
        SwipeAction.FORWARD -> onForward(m)
        SwipeAction.MOVE -> onMove(m)
        SwipeAction.DELETE -> onDelete(m)
    }
}

@Composable
private fun FolderMenu(
    expanded: Boolean,
    folder: FolderEntity?,
    onDismiss: () -> Unit,
    onMarkAllRead: () -> Unit,
    onEmpty: () -> Unit,
    onNew: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        fun item(text: String, icon: ImageVector, action: () -> Unit) = @Composable {
            DropdownMenuItem(text = { Text(text, maxLines = 1) }, leadingIcon = { Icon(icon, null) }, onClick = { onDismiss(); action() })
        }
        item("Отметить все прочитанными", Icons.Outlined.DoneAll, onMarkAllRead)()
        if (folder?.wellKnown == "deleteditems" || folder?.wellKnown == "junkemail") {
            item("Очистить папку", Icons.Outlined.DeleteSweep, onEmpty)()
        }
        HorizontalDivider()
        item("Новая папка", Icons.Outlined.CreateNewFolder, onNew)()
        if (folder != null && folder.wellKnown == null) {
            item("Переименовать папку", Icons.Outlined.DriveFileRenameOutline, onRename)()
            item("Удалить папку", Icons.Outlined.FolderDelete, onDelete)()
        }
    }
}

@Composable
private fun FolderNameDialog(title: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Название") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()); onDismiss() }, enabled = name.isNotBlank()) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun MoveDialog(folders: List<FolderEntity>, onPick: (FolderEntity) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Переместить в…") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                folders.forEach { f ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onDismiss(); onPick(f) }
                            .padding(start = (f.depth * 16).dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(folderIcon(f.wellKnown), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun LoadingList() {
    Column(Modifier.fillMaxSize()) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Загружаем письма…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit, folderName: String) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Закрыть поиск") } },
        title = {
            TextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text("Поиск в «$folderName»", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Outlined.Close, "Очистить") }
                },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
    )
}

@Composable
private fun SearchResults(vm: MailListViewModel, actions: MessageActions) {
    val results = vm.searchResults
    Column(Modifier.fillMaxSize()) {
        SearchDateFilter(vm.searchDates, vm::onSearchDates)
        if (vm.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
        when {
            results == null -> EmptyState(
                Icons.Outlined.Search,
                "Поиск по письмам",
                "Введите имя отправителя, тему или слово из текста письма — или выберите дату",
            )
            results.isEmpty() && !vm.searching -> EmptyState(
                Icons.Outlined.SearchOff,
                "Ничего не найдено",
                if (vm.searchDates != null) "Попробуйте изменить запрос или период" else "Попробуйте изменить запрос",
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(results, key = { it.id }) { m ->
                    MessageRowWithMenu(m, actions)
                    HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

/** Фильтр поиска по дате получения: быстрые периоды, день или период из календаря. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchDateFilter(range: DateRange?, onChange: (DateRange?) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<String?>(null) } // "day" | "range"
    val today = Format.today()
    Box(Modifier.padding(horizontal = 16.dp)) {
        FilterChip(
            selected = range != null,
            onClick = { menu = true },
            label = { Text(range?.let { dateRangeLabel(it, today) } ?: "Любая дата", maxLines = 1) },
            leadingIcon = { Icon(Icons.Outlined.DateRange, null, Modifier.size(FilterChipDefaults.IconSize)) },
            trailingIcon = if (range == null) null else {
                {
                    Icon(
                        Icons.Outlined.Close,
                        "Сбросить дату",
                        Modifier.size(FilterChipDefaults.IconSize).clickable { onChange(null) },
                    )
                }
            },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            @Composable
            fun item(text: String, action: () -> Unit) =
                DropdownMenuItem(text = { Text(text, maxLines = 1) }, onClick = { menu = false; action() })
            item("Любая дата") { onChange(null) }
            item("Сегодня") { onChange(DateRange(today, today)) }
            item("Вчера") { today.minusDays(1).let { onChange(DateRange(it, it)) } }
            item("Последние 7 дней") { onChange(DateRange(today.minusDays(6), today)) }
            item("Последние 30 дней") { onChange(DateRange(today.minusDays(29), today)) }
            HorizontalDivider()
            item("Выбрать день…") { picker = "day" }
            item("Выбрать период…") { picker = "range" }
        }
    }

    // Календарь выбора работает в UTC-полуночах; дни после сегодняшнего недоступны
    val todayUtc = remember(today) { today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
    val selectable = remember(todayUtc) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayUtc
            override fun isSelectableYear(year: Int) = year <= today.year
        }
    }
    fun utc(d: LocalDate) = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    fun day(utcMillis: Long) = Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()
    when (picker) {
        "day" -> {
            val state = rememberDatePickerState(
                initialSelectedDateMillis = utc(range?.from ?: today),
                selectableDates = selectable,
            )
            DatePickerDialog(
                onDismissRequest = { picker = null },
                confirmButton = {
                    TextButton(
                        onClick = {
                            state.selectedDateMillis?.let { day(it) }?.let { onChange(DateRange(it, it)) }
                            picker = null
                        },
                        enabled = state.selectedDateMillis != null,
                    ) { Text("Готово") }
                },
                dismissButton = { TextButton(onClick = { picker = null }) { Text("Отмена") } },
            ) {
                DatePicker(state)
            }
        }
        "range" -> {
            val state = rememberDateRangePickerState(
                initialSelectedStartDateMillis = range?.let { utc(it.from) },
                initialSelectedEndDateMillis = range?.let { utc(it.to) },
                selectableDates = selectable,
            )
            DatePickerDialog(
                onDismissRequest = { picker = null },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val start = state.selectedStartDateMillis?.let { day(it) }
                            // Отмечен только первый день — период из одного дня
                            if (start != null) onChange(DateRange(start, state.selectedEndDateMillis?.let { day(it) } ?: start))
                            picker = null
                        },
                        enabled = state.selectedStartDateMillis != null,
                    ) { Text("Готово") }
                },
                dismissButton = { TextButton(onClick = { picker = null }) { Text("Отмена") } },
            ) {
                DateRangePicker(
                    state,
                    title = { Text("Выберите период", Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)) },
                    showModeToggle = false,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private val RU = Locale.forLanguageTag("ru")
private val DAY = DateTimeFormatter.ofPattern("d MMM", RU)
private val DAY_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy", RU)

/** «Сегодня», «Вчера», «5 нояб.», «Последние 7 дней», «1 нояб. – 4 нояб.»; год — если не текущий. */
internal fun dateRangeLabel(r: DateRange, today: LocalDate): String {
    fun day(d: LocalDate) = when (d) {
        today -> "Сегодня"
        today.minusDays(1) -> "Вчера"
        else -> d.format(if (d.year == today.year) DAY else DAY_YEAR)
    }
    return when {
        r.from == r.to -> day(r.from)
        r.to == today && r.from == today.minusDays(6) -> "Последние 7 дней"
        r.to == today && r.from == today.minusDays(29) -> "Последние 30 дней"
        else -> day(r.from) + " – " + day(r.to).replaceFirstChar { it.lowercase() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageList(
    messages: List<MessageEntity>,
    state: LazyListState,
    loadingMore: Boolean,
    onEndReached: () -> Unit,
    actions: MessageActions,
    /** Меняется после обновления папки (в т.ч. «Повторить» после ошибки) — подгрузка в конце списка повторяется. */
    retryKey: Any? = null,
) {
    val nearEnd by remember {
        derivedStateOf {
            val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= state.layoutInfo.totalItemsCount - 5
        }
    }
    // И после ошибки или окончания обновления: иначе внизу списка «загрузить ещё» больше не повторяется
    LaunchedEffect(nearEnd, messages.size, retryKey) { if (nearEnd && messages.size >= 20) onEndReached() }

    LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
        items(messages, key = { it.id }, contentType = { "message" }) { m ->
            SwipeRow(
                m,
                actions,
                Modifier.animateItem(fadeInSpec = Motion.fast(), placementSpec = Motion.fast(), fadeOutSpec = Motion.fast()),
            )
            HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
        if (loadingMore) {
            item(contentType = "loading") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

/** Доля ширины строки, до которой её нужно дотянуть, чтобы сработало действие свайпа. */
private const val SWIPE_FRACTION = 0.5f

/** Через сколько уехавшая за край строка возвращается на место, если письмо осталось в списке. */
private const val DISMISS_RESET_MILLIS = 700L

/**
 * Строка письма со свайпами; действия в обе стороны выбираются в настройках (по умолчанию вправо —
 * прочитано/непрочитано, влево — ничего). Срабатывает, только если строку дотянули хотя бы до середины:
 * быстрый короткий взмах при прокрутке не считается. Пройденный порог видно по цвету фона и слышно по лёгкой вибрации.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRow(m: MessageEntity, actions: MessageActions, modifier: Modifier) {
    if (actions.swipeRight == SwipeAction.NONE && actions.swipeLeft == SwipeAction.NONE) {
        Box(modifier) { MessageRowWithMenu(m, actions) }
        return
    }
    // Состояние свайпа живёт дольше строки — берём актуальное письмо, а не то, что было при первом показе
    val current by rememberUpdatedState(m)
    val currentActions by rememberUpdatedState(actions)
    val width = remember { mutableIntStateOf(0) }
    val holder = remember { arrayOfNulls<SwipeToDismissBoxState>(1) }
    fun offset(): Float = holder[0]?.let { runCatching { it.requireOffset() }.getOrDefault(0f) } ?: 0f
    fun passed(): Boolean = width.intValue > 0 && kotlin.math.abs(offset()) >= width.intValue * SWIPE_FRACTION
    val swipe = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            val action = when (v) {
                SwipeToDismissBoxValue.StartToEnd -> currentActions.swipeRight
                SwipeToDismissBoxValue.EndToStart -> currentActions.swipeLeft
                SwipeToDismissBoxValue.Settled -> SwipeAction.NONE
            }
            // Порог по расстоянию, а не по скорости: Material засчитывает и короткий быстрый взмах
            if (action != SwipeAction.NONE && passed()) {
                currentActions.perform(action, current)
                action == SwipeAction.DELETE // удаляемая строка уезжает за край, остальные возвращаются
            } else {
                false
            }
        },
        positionalThreshold = { it * SWIPE_FRACTION },
    )
    holder[0] = swipe
    // Уехавшая за край строка возвращается: сразу — если такой её восстановил список (письмо вернули «Отменить»),
    // с задержкой — если письмо почему-то осталось в списке
    // (корутина запускается только для такой строки, а не для каждой появившейся при прокрутке)
    val restored = remember { booleanArrayOf(swipe.currentValue != SwipeToDismissBoxValue.Settled) }
    if (swipe.currentValue != SwipeToDismissBoxValue.Settled) {
        LaunchedEffect(Unit) {
            if (!restored[0]) delay(DISMISS_RESET_MILLIS)
            restored[0] = false
            swipe.snapTo(SwipeToDismissBoxValue.Settled)
        }
    }
    val armed by remember { derivedStateOf { passed() } }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(armed) { if (armed) haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = actions.swipeRight != SwipeAction.NONE,
        enableDismissFromEndToStart = actions.swipeLeft != SwipeAction.NONE,
        backgroundContent = {
            when (swipe.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> SwipeBackground(actions.swipeRight, fromEnd = false, m, armed)
                SwipeToDismissBoxValue.EndToStart -> SwipeBackground(actions.swipeLeft, fromEnd = true, m, armed)
                SwipeToDismissBoxValue.Settled -> Unit // строку не тянут — ничего не рисуется
            }
        },
        modifier = modifier.onSizeChanged { width.intValue = it.width },
    ) {
        MessageRowWithMenu(m, actions)
    }
}

/** Фон под строкой при свайпе: что произойдёт; после середины — цветной (удаление — красный). */
@Composable
internal fun SwipeBackground(action: SwipeAction, fromEnd: Boolean, m: MessageEntity, armed: Boolean) {
    val c = MaterialTheme.colorScheme
    val destructive = action == SwipeAction.DELETE
    val bg = when {
        !armed -> c.surfaceVariant
        destructive -> c.error
        else -> c.primary
    }
    val on = when {
        !armed -> c.onSurfaceVariant
        destructive -> c.onError
        else -> c.onPrimary
    }
    Row(
        Modifier.fillMaxSize().background(bg).padding(horizontal = 24.dp),
        horizontalArrangement = if (fromEnd) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (fromEnd) {
            Text(action.label(m), color = on, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            Icon(action.icon(m), null, tint = on)
        } else {
            Icon(action.icon(m), null, tint = on)
            Spacer(Modifier.width(8.dp))
            Text(action.label(m), color = on, maxLines = 1)
        }
    }
}

/** Строка письма: касание — открыть, удержание [io.github.denhaiz.exmail.ui.common.HOLD_MILLIS] — меню действий. */
@Composable
private fun MessageRowWithMenu(m: MessageEntity, actions: MessageActions) {
    var menu by remember { mutableStateOf(false) }
    val origin = remember { OpenOrigin() }
    Box(origin.modifier) {
        HoldableBox(onTap = { origin.mark(); actions.onOpen(m) }, onHold = { menu = true }) {
            MessageRow(m)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            @Composable
            fun item(text: String, icon: ImageVector, tint: Color = MaterialTheme.colorScheme.onSurface, action: () -> Unit) {
                DropdownMenuItem(
                    text = { Text(text, color = tint, maxLines = 1) },
                    leadingIcon = { Icon(icon, null, tint = tint) },
                    onClick = { menu = false; action() },
                )
            }
            if (actions.canReply) {
                item("Ответить", Icons.AutoMirrored.Outlined.Reply) { actions.onReply(m) }
                item("Переслать", Icons.AutoMirrored.Outlined.Forward) { actions.onForward(m) }
            }
            item(
                if (m.isRead) "Отметить непрочитанным" else "Отметить прочитанным",
                if (m.isRead) Icons.Outlined.MarkEmailUnread else Icons.Outlined.MarkEmailRead,
            ) { actions.onToggleRead(m) }
            item(if (m.flagged) "Снять флажок" else "Отметить флажком", Icons.Outlined.Flag) { actions.onToggleFlag(m) }
            item("Переместить…", Icons.AutoMirrored.Outlined.DriveFileMove) { actions.onMove(m) }
            HorizontalDivider()
            item("Удалить", Icons.Outlined.Delete, MaterialTheme.colorScheme.error) { actions.onDelete(m) }
        }
    }
}

@Composable
fun MessageRow(m: MessageEntity) {
    val unread = !m.isRead
    // Пересчитывается при смене дня, а не при каждой перерисовке строки
    val time = remember(m.receivedAt, Format.today()) { Format.listTime(m.receivedAt) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Box {
            Avatar(m.fromName, m.fromEmail, size = 44.dp)
            if (unread) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .size(12.dp)
                        .background(MaterialTheme.colorScheme.surface, CircleShape)
                        .padding(2.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    m.fromName.ifBlank { m.fromEmail.ifBlank { m.displayTo.ifBlank { "(без отправителя)" } } },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    time,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (m.isMeetingRequest) {
                    Icon(Icons.Outlined.Event, "Приглашение", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                }
                if (m.highImportance) {
                    Icon(Icons.Filled.PriorityHigh, "Важное", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                }
                Text(
                    m.subject.ifBlank { "(без темы)" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (m.hasAttachments) {
                    Icon(Icons.Outlined.AttachFile, "Есть вложения", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (m.flagged) {
                    Icon(Icons.Filled.Flag, "С флажком", Modifier.size(16.dp), tint = Color(0xFFD32F2F))
                }
            }
            if (m.preview.isNotBlank()) {
                Text(
                    m.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun FolderDrawer(
    accountName: String,
    accountEmail: String,
    groups: List<DrawerGroup>,
    current: FolderEntity?,
    onSelectFolder: (FolderEntity) -> Unit,
    onSelectTab: (String) -> Unit,
    onReorderGroups: (List<DrawerGroup>) -> Unit,
    onReorderEntries: (DrawerGroup, List<DrawerEntry>) -> Unit,
    onNewFolder: () -> Unit,
    onSettings: () -> Unit,
) {
    // Отпускание после перетаскивания не должно открывать пункт
    var lastDrop by remember { mutableLongStateOf(0L) }
    fun clickAllowed() = System.currentTimeMillis() - lastDrop > 400

    ModalDrawerSheet {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            // Имя и адрес — полностью: длинное ФИО переносится на следующую строку, а не обрезается.
            // Настройки — значок справа от имени: видны сразу, без прокрутки списка папок
            Row(
                Modifier.padding(start = 20.dp, end = 4.dp, top = 20.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(accountName, accountEmail, size = 48.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        accountName.ifBlank { accountEmail },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (accountName.isNotBlank()) {
                        Text(
                            accountEmail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onSettings, modifier = Modifier.align(Alignment.Top)) {
                    Icon(Icons.Outlined.Settings, "Настройки", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()

            ReorderableColumn(
                items = groups,
                key = { it.key },
                onReorder = { lastDrop = System.currentTimeMillis(); onReorderGroups(it) },
            ) { group, _, groupHandle ->
                Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Row(
                        groupHandle.fillMaxWidth().padding(start = 28.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            group.title,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(Icons.Outlined.DragIndicator, "Удерживайте, чтобы переместить группу", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
                    }
                    if (group.entries.isEmpty()) {
                        Text(
                            "Папок пока нет",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 28.dp, bottom = 8.dp),
                        )
                    }
                    ReorderableColumn(
                        items = group.entries,
                        key = { it.key },
                        onReorder = { lastDrop = System.currentTimeMillis(); onReorderEntries(group, it) },
                    ) { entry, dragging, handle ->
                        val (label, icon, selected, badge) = when (entry) {
                            is DrawerEntry.Tab -> Quad(entry.label, tabIcon(entry.route), false, 0)
                            is DrawerEntry.Folder -> Quad(entry.title, folderIcon(entry.folder.wellKnown), entry.folder.id == current?.id, entry.folder.unread)
                        }
                        NavigationDrawerItem(
                            label = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            icon = { Icon(icon, null) },
                            badge = { if (badge > 0) Badge { Text(if (badge > 999) "999+" else badge.toString(), maxLines = 1) } },
                            selected = selected || dragging,
                            onClick = {
                                if (clickAllowed()) when (entry) {
                                    is DrawerEntry.Tab -> onSelectTab(entry.route)
                                    is DrawerEntry.Folder -> onSelectFolder(entry.folder)
                                }
                            },
                            modifier = handle.padding(NavigationDrawerItemDefaults.ItemPadding),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }

            HorizontalDivider()
            NavigationDrawerItem(
                label = { Text("Новая папка") },
                icon = { Icon(Icons.Outlined.CreateNewFolder, null) },
                selected = false,
                onClick = onNewFolder,
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding).padding(top = 6.dp),
            )
            Text(
                "Удерживайте пункт или заголовок группы и перетащите, чтобы изменить порядок",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
            )
        }
    }
}

private data class Quad(val label: String, val icon: ImageVector, val selected: Boolean, val badge: Int)

private fun tabIcon(route: String): ImageVector = when (route) {
    "calendar" -> Icons.Outlined.CalendarMonth
    "contacts" -> Icons.Outlined.Contacts
    else -> Icons.Outlined.Mail
}

fun folderIcon(wellKnown: String?): ImageVector = when (wellKnown) {
    "inbox" -> Icons.Outlined.Inbox
    "drafts" -> Icons.Outlined.Drafts
    "sentitems" -> Icons.AutoMirrored.Outlined.Send
    "deleteditems" -> Icons.Outlined.Delete
    "junkemail" -> Icons.Outlined.Report
    "outbox" -> Icons.Outlined.Outbox
    null -> Icons.Outlined.Folder
    else -> Icons.Outlined.MailOutline
}

/**
 * true, когда список прокручивают вверх или он в самом начале (кнопка «Написать» развёрнута).
 * Меняется только после заметного сдвига в одну сторону — кнопка не дёргается от мелких движений пальца,
 * а экран не перекомпонуется на каждом кадре прокрутки.
 */
@Composable
fun LazyListState.isScrollingUp(): Boolean {
    var up by remember(this) { mutableStateOf(true) }
    val threshold = with(LocalDensity.current) { 24.dp.toPx() }
    LaunchedEffect(this, threshold) {
        var index = firstVisibleItemIndex
        var offset = firstVisibleItemScrollOffset
        var travelled = 0f
        snapshotFlow { firstVisibleItemIndex to firstVisibleItemScrollOffset }.collect { (i, o) ->
            val delta = when {
                i == index -> (o - offset).toFloat()
                i > index -> threshold // следующая строка — точно вниз
                else -> -threshold
            }
            index = i
            offset = o
            // Путь в одну сторону накапливается; при смене направления отсчёт начинается заново
            travelled = if ((travelled >= 0f) == (delta >= 0f)) travelled + delta else delta
            when {
                i == 0 && o == 0 -> up = true
                travelled >= threshold -> up = false
                travelled <= -threshold -> up = true
            }
        }
    }
    return up
}
