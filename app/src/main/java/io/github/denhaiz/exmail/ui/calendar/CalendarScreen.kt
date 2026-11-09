package io.github.denhaiz.exmail.ui.calendar

import android.os.SystemClock
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarViewMonth
import androidx.compose.material.icons.outlined.CalendarViewWeek
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import io.github.denhaiz.exmail.data.db.EventEntity
import io.github.denhaiz.exmail.data.repo.CalendarRepository
import io.github.denhaiz.exmail.data.userMessage
import io.github.denhaiz.exmail.ui.common.EmptyState
import io.github.denhaiz.exmail.ui.common.ErrorBanner
import io.github.denhaiz.exmail.ui.common.Format
import io.github.denhaiz.exmail.ui.common.keepVisible
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

fun LocalDate.weekStart(): LocalDate = with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
fun LocalDate.startInstant(): Instant = atStartOfDay(Format.zone()).toInstant()

fun EventEntity.overlaps(day: LocalDate): Boolean {
    val from = day.startInstant().toEpochMilli()
    val to = day.plusDays(1).startInstant().toEpochMilli()
    return start < to && end > from || (start == end && start in from until to)
}

/** Число (не отменённых) событий по дням — считается один раз на список, а не для каждой ячейки. */
internal fun eventCountsByDay(events: List<EventEntity>): Map<LocalDate, Int> {
    val zone = Format.zone()
    val result = HashMap<LocalDate, Int>()
    for (e in events) {
        if (e.isCancelled) continue
        var day = Instant.ofEpochMilli(e.start).atZone(zone).toLocalDate()
        val last = Instant.ofEpochMilli(maxOf(e.start, e.end - 1)).atZone(zone).toLocalDate()
        var guard = 0
        while (!day.isAfter(last) && guard++ < 400) {
            result.merge(day, 1, Int::plus)
            day = day.plusDays(1)
        }
    }
    return result
}

/** Первая ячейка сетки месяца (понедельник) — сетка всегда 6 недель. */
fun YearMonth.gridStart(): LocalDate = atDay(1).weekStart()

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CalendarViewModel @Inject constructor(private val repo: CalendarRepository) : ViewModel() {
    val selected = MutableStateFlow(LocalDate.now())
    val monthMode = MutableStateFlow(false)

    /** Загружаемый диапазон: неделя или 6 недель сетки месяца. */
    private val range = combine(selected, monthMode) { d, month ->
        if (month) YearMonth.from(d).gridStart().let { it to it.plusDays(42) }
        else d.weekStart().let { it to it.plusDays(7) }
    }.distinctUntilChanged()

    val events: StateFlow<List<EventEntity>> = range
        .flatMapLatest { (from, to) -> repo.events(from.startInstant(), to.startInstant()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var loads by mutableIntStateOf(0)

    /** Идёт любая загрузка (полоса под календарём). */
    val refreshing: Boolean get() = loads > 0

    /** Обновление жестом «потянуть вниз» (значок обновления). */
    var pullRefreshing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    private val loadedAt = HashMap<Pair<LocalDate, LocalDate>, Long>()
    private val inFlight = HashSet<Pair<LocalDate, LocalDate>>()
    private var current: Pair<LocalDate, LocalDate>? = null

    init {
        viewModelScope.launch {
            range.collect { r ->
                current = r
                if (isStale(r, RANGE_MAX_AGE)) launch { load(r) }
            }
        }
        // Встреча создана, изменена или удалена — кэш остальных периодов устарел, видимый перезагружаем
        viewModelScope.launch {
            repo.changes.drop(1).collect {
                loadedAt.clear()
                current?.let { r -> launch { load(r) } }
            }
        }
    }

    fun select(date: LocalDate) {
        selected.value = date
    }

    fun toggleMonth() {
        monthMode.value = !monthMode.value
    }

    /** Экран снова виден (переключение вкладок) — обновляем, если данные старше минуты. */
    fun onShown() {
        current?.let { r -> if (isStale(r, SHOWN_MAX_AGE)) viewModelScope.launch { load(r) } }
    }

    fun refresh() {
        val r = current ?: return
        if (pullRefreshing) return
        pullRefreshing = true
        viewModelScope.launch {
            try {
                keepVisible { load(r, force = true) }
            } finally {
                pullRefreshing = false
            }
        }
    }

    private fun isStale(r: Pair<LocalDate, LocalDate>, maxAge: Long) =
        loadedAt[r]?.let { SystemClock.elapsedRealtime() - it > maxAge } ?: true

    private suspend fun load(r: Pair<LocalDate, LocalDate>, force: Boolean = false) {
        if (!inFlight.add(r) && !force) return
        loads++
        try {
            repo.refresh(r.first.startInstant(), r.second.startInstant())
            loadedAt[r] = SystemClock.elapsedRealtime()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.userMessage()
        } finally {
            inFlight.remove(r)
            loads--
        }
    }

    private companion object {
        const val RANGE_MAX_AGE = 5 * 60_000L
        const val SHOWN_MAX_AGE = 60_000L
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onOpenEvent: (String) -> Unit,
    onNewEvent: (LocalDate) -> Unit,
    snackbar: SnackbarHostState,
    vm: CalendarViewModel = hiltViewModel(),
) {
    val selected by vm.selected.collectAsState()
    val monthMode by vm.monthMode.collectAsState()
    val events by vm.events.collectAsState()
    val today = LocalDate.now()
    var pickDate by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.onShown() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        Format.monthYear(selected),
                        maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { pickDate = true }.padding(4.dp),
                    )
                },
                actions = {
                    if (selected != today) {
                        IconButton(onClick = { vm.select(today) }) { Icon(Icons.Outlined.Today, "Сегодня") }
                    }
                    IconButton(onClick = vm::toggleMonth) {
                        if (monthMode) Icon(Icons.Outlined.CalendarViewWeek, "Показать неделю")
                        else Icon(Icons.Outlined.CalendarViewMonth, "Показать месяц")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onNewEvent(selected) },
                icon = { Icon(Icons.Outlined.Add, null) },
                text = { Text("Встреча", maxLines = 1) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (monthMode) {
                MonthPager(selected, today, events, vm::select)
            } else {
                WeekPager(selected, today, events, vm::select)
            }
            if (vm.refreshing && !vm.pullRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
            vm.error?.let { ErrorBanner(it, vm::refresh) { vm.error = null } }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                Format.relativeDay(selected),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            val dayEvents = events.filter { it.overlaps(selected) }
            PullToRefreshBox(isRefreshing = vm.pullRefreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
                if (dayEvents.isEmpty()) {
                    EmptyState(
                        Icons.Outlined.EventAvailable,
                        "Свободный день",
                        "На этот день встреч нет",
                        actionLabel = if (monthMode) null else "Запланировать встречу",
                        onAction = { onNewEvent(selected) },
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(dayEvents, key = { it.id }) { e -> EventCard(e) { onOpenEvent(e.id) } }
                    }
                }
            }
        }
    }

    if (pickDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = selected.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { vm.select(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    pickDate = false
                }) { Text("Перейти") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Отмена") } },
        ) { DatePicker(state) }
    }
}

private const val PAGES = 2001
private val fastPage = tween<Float>(220)

@Composable
internal fun WeekPager(selected: LocalDate, today: LocalDate, events: List<EventEntity>, onSelect: (LocalDate) -> Unit) {
    val baseWeek = remember { today.weekStart() }
    val counts = remember(events) { eventCountsByDay(events) }
    val pager = rememberPagerState(initialPage = PAGES / 2 + ChronoUnit.WEEKS.between(baseWeek, selected.weekStart()).toInt()) { PAGES }
    LaunchedEffect(selected) {
        val page = PAGES / 2 + ChronoUnit.WEEKS.between(baseWeek, selected.weekStart()).toInt()
        if (pager.currentPage != page) pager.animateScrollToPage(page, animationSpec = fastPage)
    }
    // Выбор дня меняется только когда листание закончилось: промежуточные страницы при переходе
    // на «Сегодня» или выбранную дату не сбивают анимацию
    val current by rememberUpdatedState(selected)
    val select by rememberUpdatedState(onSelect)
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page ->
            val weekOfPage = baseWeek.plusWeeks((page - PAGES / 2).toLong())
            if (current.weekStart() != weekOfPage) select(weekOfPage.plusDays((current.dayOfWeek.value - 1).toLong()))
        }
    }
    HorizontalPager(pager, modifier = Modifier.fillMaxWidth()) { page ->
        val weekStart = baseWeek.plusWeeks((page - PAGES / 2).toLong())
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            for (i in 0 until 7) {
                val day = weekStart.plusDays(i.toLong())
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable { onSelect(day) }.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    DayOfWeekLabel(day)
                    Spacer(Modifier.height(4.dp))
                    DayNumber(day, day == selected, day == today, inMonth = true, size = 40)
                    Spacer(Modifier.height(4.dp))
                    EventDots(counts[day] ?: 0, max = 1)
                }
            }
        }
    }
}

/** Сетка месяца: точками отмечены дни со встречами, свайп — соседний месяц. */
@Composable
internal fun MonthPager(selected: LocalDate, today: LocalDate, events: List<EventEntity>, onSelect: (LocalDate) -> Unit) {
    val baseMonth = remember { YearMonth.from(today) }
    val selectedMonth = YearMonth.from(selected)
    val counts = remember(events) { eventCountsByDay(events) }
    val pager = rememberPagerState(initialPage = PAGES / 2 + ChronoUnit.MONTHS.between(baseMonth, selectedMonth).toInt()) { PAGES }
    LaunchedEffect(selectedMonth) {
        val page = PAGES / 2 + ChronoUnit.MONTHS.between(baseMonth, selectedMonth).toInt()
        if (pager.currentPage != page) pager.animateScrollToPage(page, animationSpec = fastPage)
    }
    val current by rememberUpdatedState(selected)
    val select by rememberUpdatedState(onSelect)
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page ->
            val month = baseMonth.plusMonths((page - PAGES / 2).toLong())
            if (YearMonth.from(current) != month) select(month.atDay(current.dayOfMonth.coerceAtMost(month.lengthOfMonth())))
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            val monday = today.weekStart()
            for (i in 0 until 7) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { DayOfWeekLabel(monday.plusDays(i.toLong())) }
            }
        }
        HorizontalPager(pager, modifier = Modifier.fillMaxWidth()) { page ->
            val month = baseMonth.plusMonths((page - PAGES / 2).toLong())
            val start = month.gridStart()
            Column {
                for (w in 0 until 6) {
                    Row(Modifier.fillMaxWidth()) {
                        for (d in 0 until 7) {
                            val day = start.plusDays((w * 7 + d).toLong())
                            Column(
                                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { onSelect(day) }.padding(vertical = 2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                DayNumber(day, day == selected, day == today, inMonth = YearMonth.from(day) == month, size = 34)
                                Spacer(Modifier.height(3.dp))
                                EventDots(counts[day] ?: 0, max = 3)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayOfWeekLabel(day: LocalDate) {
    Text(
        Format.dayName(day),
        style = MaterialTheme.typography.labelMedium,
        color = if (day.dayOfWeek.value >= 6) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

@Composable
private fun DayNumber(day: LocalDate, isSelected: Boolean, isToday: Boolean, inMonth: Boolean, size: Int) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .then(if (isToday && !isSelected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            day.dayOfMonth.toString(),
            fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            color = when {
                isSelected -> MaterialTheme.colorScheme.onPrimary
                !inMonth -> MaterialTheme.colorScheme.outline
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
private fun EventDots(count: Int, max: Int) {
    Row(Modifier.height(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(count.coerceAtMost(max)) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        }
    }
}

@Composable
fun EventCard(e: EventEntity, onClick: () -> Unit) {
    val accent = when {
        e.isCancelled -> MaterialTheme.colorScheme.outline
        e.myResponse == "Tentative" -> Color(0xFFFFA000)
        e.isMeeting && e.myResponse in setOf("NoResponseReceived", "Unknown") -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(accent))
            Column(Modifier.widthIn(min = 76.dp).padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp)) {
                if (e.isAllDay) {
                    Text("весь день", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                } else {
                    Text(Format.time(e.start), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(Format.time(e.end), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            Column(Modifier.weight(1f).padding(top = 12.dp, bottom = 12.dp, end = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        e.subject.ifBlank { "(без названия)" },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        textDecoration = if (e.isCancelled) TextDecoration.LineThrough else null,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (e.isRecurring) Icon(Icons.Outlined.Repeat, "Повторяется", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (e.location.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Place, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(2.dp))
                        Text(
                            e.location,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                responseLabel(e)?.let { (text, color) ->
                    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                        Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun responseLabel(e: EventEntity): Pair<String, Color>? = when {
    e.isCancelled -> "Отменена" to MaterialTheme.colorScheme.error
    !e.isMeeting -> null
    e.myResponse == "Tentative" -> "Под вопросом" to io.github.denhaiz.exmail.ui.theme.warningColor()
    e.myResponse == "NoResponseReceived" || e.myResponse == "Unknown" -> "Ожидает ответа" to MaterialTheme.colorScheme.primary
    else -> null
}
