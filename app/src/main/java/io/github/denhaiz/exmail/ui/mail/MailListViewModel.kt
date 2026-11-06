package io.github.denhaiz.exmail.ui.mail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import io.github.denhaiz.exmail.data.AccountInfo
import io.github.denhaiz.exmail.data.AccountStore
import io.github.denhaiz.exmail.data.AppSettings
import io.github.denhaiz.exmail.data.SwipeAction
import io.github.denhaiz.exmail.data.db.FolderEntity
import io.github.denhaiz.exmail.data.db.MessageEntity
import io.github.denhaiz.exmail.data.repo.AccountRepository
import io.github.denhaiz.exmail.data.repo.MailRepository
import io.github.denhaiz.exmail.data.userMessage
import io.github.denhaiz.exmail.ui.common.keepVisible
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** Пункт боковой панели: раздел приложения или папка. */
sealed interface DrawerEntry {
    val key: String

    data class Tab(val route: String, val label: String) : DrawerEntry {
        override val key get() = "tab:$route"
    }

    data class Folder(val folder: FolderEntity, val title: String) : DrawerEntry {
        override val key get() = "folder:${folder.id}"
    }
}

data class DrawerGroup(val key: String, val title: String, val entries: List<DrawerEntry>)

/** Действия свайпа по строке письма: слева направо и справа налево. */
data class Swipes(val right: SwipeAction, val left: SwipeAction)

/** Период поиска по дате получения письма, дни включительно. */
data class DateRange(val from: LocalDate, val to: LocalDate)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MailListViewModel @Inject constructor(
    private val repo: MailRepository,
    private val store: AccountStore,
    accounts: AccountRepository,
) : ViewModel() {

    val account: AccountInfo? = accounts.current

    val folders: StateFlow<List<FolderEntity>> =
        repo.folders().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val settings = store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val swipes: StateFlow<Swipes> = settings.map { Swipes(it.swipeRight, it.swipeLeft) }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, Swipes(SwipeAction.READ, SwipeAction.NONE))

    /** Группы боковой панели с учётом пользовательского порядка. */
    val drawer: StateFlow<List<DrawerGroup>> = combine(folders, settings) { list, s -> buildDrawer(list, s) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val folderId = MutableStateFlow<String?>(null)
    private val pendingDeletes = MutableStateFlow<Set<String>>(emptySet())

    val currentFolder: StateFlow<FolderEntity?> = combine(folders, folderId) { list, id ->
        list.firstOrNull { it.id == id } ?: list.firstOrNull { it.wellKnown == "inbox" }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val messages: StateFlow<List<MessageEntity>> = currentFolder.filterNotNull()
        .distinctUntilChangedBy { it.id }
        .flatMapLatest { f -> repo.messages(f.id) }
        .combine(pendingDeletes) { list, pending -> if (pending.isEmpty()) list else list.filter { it.id !in pending } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    var refreshing by mutableStateOf(false)
        private set

    /** Обновление жестом «потянуть вниз» (значок обновления). */
    var pullRefreshing by mutableStateOf(false)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var hasMore by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
    var initialLoadDone by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set

    // Поиск
    var searchActive by mutableStateOf(false)
        private set
    var searchQuery by mutableStateOf("")
        private set
    /** Период поиска; null — любая дата. */
    var searchDates by mutableStateOf<DateRange?>(null)
        private set
    var searchResults by mutableStateOf<List<MessageEntity>?>(null)
        private set
    var searching by mutableStateOf(false)
        private set
    private var searchJob: Job? = null

    private val _messagesToUser = Channel<String>(Channel.BUFFERED)
    val messagesToUser = _messagesToUser.receiveAsFlow()

    private var lastLoaded: String? = null

    init {
        viewModelScope.launch {
            try {
                if (folders.value.isEmpty()) repo.refreshFolders()
            } catch (e: Exception) {
                if (e is CancellationException) throw e // закрыли экран или начали новый запрос — это не ошибка
                error = e.userMessage()
                initialLoadDone = true // вместо бесконечной «загрузки» — ошибка и кнопка «Повторить»
            }
            currentFolder.filterNotNull().collect { f ->
                if (lastLoaded != f.id) {
                    lastLoaded = f.id
                    refresh()
                }
            }
        }
    }

    fun selectFolder(folder: FolderEntity) {
        closeSearch()
        hasMore = true
        initialLoadDone = false
        folderId.value = folder.id
    }

    private var refreshJob: Job? = null
    private var refreshFolderId: String? = null

    /**
     * Обновляет текущую папку. Если в это время идёт обновление другой папки (пользователь переключился),
     * оно отменяется — иначе новая папка осталась бы без загрузки.
     */
    fun refresh(): Job? {
        // Папки ещё не загружены (первый вход без связи) — сначала они; затем папка обновится сама
        val f = currentFolder.value ?: return loadFolders()
        refreshJob?.takeIf { it.isActive }?.let { active ->
            if (refreshFolderId == f.id) return active
            active.cancel()
        }
        refreshFolderId = f.id
        refreshing = true
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                repo.refresh(f.id)
                runCatching { repo.refreshFolders() }
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.userMessage()
            } finally {
                if (refreshJob === coroutineContext[Job]) {
                    refreshing = false
                    initialLoadDone = true
                }
            }
        }
        refreshJob = job
        job.start()
        return job
    }

    private fun loadFolders(): Job = viewModelScope.launch {
        refreshing = true
        try {
            repo.refreshFolders()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.userMessage()
            initialLoadDone = true
        } finally {
            refreshing = false
        }
    }

    fun pullRefresh() {
        if (pullRefreshing) return
        pullRefreshing = true
        viewModelScope.launch {
            try {
                keepVisible { refresh()?.join() }
            } finally {
                pullRefreshing = false
            }
        }
    }

    fun loadMore() {
        val f = currentFolder.value ?: return
        if (loadingMore || !hasMore || refreshing) return
        loadingMore = true
        viewModelScope.launch {
            try {
                val more = repo.loadMore(f.id)
                if (currentFolder.value?.id == f.id) hasMore = more
            } catch (e: Exception) {
                if (e is CancellationException) throw e // закрыли экран или начали новый запрос — это не ошибка
                error = e.userMessage()
            } finally {
                loadingMore = false
            }
        }
    }

    fun toggleRead(m: MessageEntity) = launchAction { repo.markRead(listOf(m.id), !m.isRead) }

    fun toggleFlag(m: MessageEntity) = launchAction { repo.setFlag(listOf(m.id), !m.flagged) }

    fun move(m: MessageEntity, to: FolderEntity) = launchAction {
        repo.move(listOf(m.id), to.id)
        _messagesToUser.send("Перемещено в «${to.name}»")
    }

    private val deleteTimers = HashMap<String, Job>()

    /**
     * Скрывает письмо сразу; на сервере удаляет через [UNDO_MILLIS], если пользователь не нажал «Отменить».
     * Таймер живёт во ViewModel, поэтому переход на другой экран удаление не теряет.
     */
    fun hide(m: MessageEntity) {
        pendingDeletes.value = pendingDeletes.value + m.id
        deleteTimers.remove(m.id)?.cancel()
        deleteTimers[m.id] = viewModelScope.launch {
            delay(UNDO_MILLIS)
            deleteTimers.remove(m.id)
            commitDelete(m)
        }
    }

    fun undoDelete(m: MessageEntity) {
        deleteTimers.remove(m.id)?.cancel()
        pendingDeletes.value = pendingDeletes.value - m.id
    }

    /** Удалить сразу (снэкбар закрыт без «Отменить»). */
    fun deleteNow(m: MessageEntity) {
        if (deleteTimers.remove(m.id)?.also { it.cancel() } != null) commitDelete(m)
    }

    override fun onCleared() {
        // Экран закрыт насовсем — удаляем то, что ждало отмены
        val pending = deleteTimers.keys.toList()
        deleteTimers.values.forEach { it.cancel() }
        deleteTimers.clear()
        if (pending.isNotEmpty()) repo.deleteInBackground(pending)
    }

    private fun commitDelete(m: MessageEntity) {
        viewModelScope.launch {
            try {
                repo.delete(listOf(m.id))
            } catch (e: Exception) {
                if (e is CancellationException) throw e // закрыли экран или начали новый запрос — это не ошибка
                _messagesToUser.send("Не удалось удалить: ${e.userMessage()}")
            } finally {
                pendingDeletes.value = pendingDeletes.value - m.id
            }
        }
    }

    // region Действия с папкой

    fun markAllRead() = folderAction("Все письма отмечены прочитанными") { repo.markAllRead(it.id) }

    fun emptyCurrentFolder() = folderAction("Папка очищена") { repo.emptyFolder(it) }

    fun createFolder(name: String) = folderAction("Папка «$name» создана") { repo.createFolder(name) }

    fun renameCurrentFolder(name: String) = folderAction("Папка переименована") { repo.renameFolder(it, name) }

    fun deleteCurrentFolder() = folderAction("Папка перемещена в «Удалённые»") {
        repo.deleteFolder(it)
        folderId.value = null
    }

    private fun folderAction(done: String, block: suspend (FolderEntity) -> Unit) {
        val f = currentFolder.value ?: return
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                block(f)
                _messagesToUser.send(done)
            } catch (e: Exception) {
                if (e is CancellationException) throw e // закрыли экран или начали новый запрос — это не ошибка
                _messagesToUser.send(e.userMessage())
            } finally {
                busy = false
            }
        }
    }

    // endregion

    // region Порядок в боковой панели

    fun reorderGroups(groups: List<DrawerGroup>) {
        viewModelScope.launch { store.setGroupOrder(groups.map { it.key }) }
    }

    fun reorderEntries(group: DrawerGroup, entries: List<DrawerEntry>) {
        viewModelScope.launch {
            if (group.key == "tabs") {
                store.setTabOrder(entries.filterIsInstance<DrawerEntry.Tab>().map { it.route })
            } else {
                val current = drawer.value
                val ids = current.filter { it.key != "tabs" }.flatMap { g ->
                    (if (g.key == group.key) entries else g.entries).filterIsInstance<DrawerEntry.Folder>().map { it.folder.id }
                }
                store.setFolderOrder(ids)
            }
        }
    }

    // endregion

    fun openSearch() {
        searchActive = true
    }

    fun closeSearch() {
        searchJob?.cancel()
        searchActive = false
        searchQuery = ""
        searchDates = null
        searchResults = null
        searching = false
    }

    fun onSearchQuery(q: String) {
        searchQuery = q
        runSearch(debounce = true)
    }

    fun onSearchDates(range: DateRange?) {
        searchDates = range
        runSearch(debounce = false)
    }

    /** Ищет по тексту (от 2 символов), по дате или по обоим; без условий — подсказка вместо результатов. */
    private fun runSearch(debounce: Boolean) {
        searchJob?.cancel()
        val q = searchQuery.trim().takeIf { it.length >= 2 }
        val dates = searchDates
        if (q == null && dates == null) {
            searchResults = null
            searching = false
            return
        }
        val f = currentFolder.value ?: return
        val zone = ZoneId.systemDefault()
        searchJob = viewModelScope.launch {
            if (debounce) delay(400)
            searching = true
            try {
                searchResults = repo.search(
                    f.id,
                    q,
                    from = dates?.from?.atStartOfDay(zone)?.toInstant(),
                    until = dates?.to?.plusDays(1)?.atStartOfDay(zone)?.toInstant(),
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e // закрыли экран или начали новый запрос — это не ошибка
                error = e.userMessage()
            } finally {
                // Отменённый поиск (новый запрос, другая дата) не гасит индикатор нового
                if (searchJob === coroutineContext[Job]) searching = false
            }
        }
    }

    private fun launchAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                if (e is CancellationException) throw e // закрыли экран или начали новый запрос — это не ошибка
                _messagesToUser.send(e.userMessage())
            }
        }
    }

    companion object {
        const val UNDO_MILLIS = 10_000L
        val TAB_LABELS = mapOf("mail" to "Почта", "calendar" to "Календарь", "contacts" to "Контакты")

        fun buildDrawer(folders: List<FolderEntity>, s: AppSettings): List<DrawerGroup> {
            val byId = folders.associateBy { it.id }
            fun title(f: FolderEntity): String {
                if (f.wellKnown != null || f.depth == 0) return f.name
                val parts = mutableListOf(f.name)
                var p = f.parentId?.let(byId::get)
                var guard = 0
                while (p != null && guard++ < 10) {
                    parts.add(0, p.name)
                    p = p.parentId?.let(byId::get)
                }
                return parts.joinToString(" › ")
            }
            val rank = s.folderOrder.withIndex().associate { it.value to it.index }
            fun sorted(list: List<FolderEntity>) =
                list.sortedWith(compareBy<FolderEntity>({ rank[it.id] ?: Int.MAX_VALUE }, { it.position }))

            val groups = mapOf(
                "tabs" to DrawerGroup("tabs", "Разделы", s.tabOrder.map { DrawerEntry.Tab(it, TAB_LABELS[it] ?: it) }),
                "main" to DrawerGroup("main", "Почта", sorted(folders.filter { it.wellKnown != null }).map { DrawerEntry.Folder(it, title(it)) }),
                "user" to DrawerGroup("user", "Мои папки", sorted(folders.filter { it.wellKnown == null }).map { DrawerEntry.Folder(it, title(it)) }),
            )
            return s.groupOrder.mapNotNull { groups[it] }.filter { it.entries.isNotEmpty() || it.key == "user" }
        }
    }
}
