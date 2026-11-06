package io.github.denhaiz.exmail.ui.common

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Быстрые и ненавязчивые анимации для всего приложения. */
object Motion {
    const val SHORT = 120
    const val MEDIUM = 180
    private const val TAB = 260
    private const val OPEN = 250
    private const val CLOSE = 200

    /** Material 3: «выразительное» замедление (появление) и ускорение (уход). */
    private val Decelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    private val Accelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    fun <T> fast() = tween<T>(SHORT, easing = FastOutSlowInEasing)

    /** Появление/скрытие блоков (подробности, ошибки, настройки). */
    val expand: EnterTransition = fadeIn(tween(SHORT)) + expandVertically(tween(SHORT, easing = FastOutSlowInEasing))
    val collapse: ExitTransition = fadeOut(tween(90)) + shrinkVertically(tween(SHORT, easing = FastOutSlowInEasing))

    private val tabSlide = tween<IntOffset>(TAB, easing = FastOutSlowInEasing)

    /** Направление перелистывания письма свайпом: +1 — новое письмо приходит справа, −1 — слева; null — не свайп. */
    @Volatile
    private var pendingSibling: Int? = null

    /** Следующий переход к письму — перелистывание свайпом в сторону [direction]. */
    fun openSibling(direction: Int) {
        pendingSibling = direction
    }

    /** Откуда «вырастет» следующий открытый экран (центр нажатой строки, в долях экрана); см. [OpenOrigin]. */
    @Volatile
    internal var pendingOrigin: TransformOrigin? = null

    /**
     * Переходы навигации:
     * - между вкладками — сдвиг всего экрана в сторону выбранной вкладки (как перелистывание);
     * - письмо «вырастает» из нажатой строки списка и при закрытии «сжимается» в неё;
     * - остальные экраны появляются поверх с лёгким сдвигом.
     * Предыдущий экран остаётся под новым до конца анимации — без просвечивания фона и мигания.
     */
    class Nav(private val tabOrder: () -> List<String>, private val zoomRoutes: (String?) -> Boolean) {
        /** Точка «вырастания» для каждого открытого экрана — чтобы закрыть его в то же место. */
        private val origins = HashMap<String, TransformOrigin>()

        /** +1 — новая вкладка правее текущей, -1 — левее, null — это не переход между вкладками. */
        private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabDirection(): Int? {
            val order = tabOrder()
            val from = order.indexOf(initialState.destination.route)
            val to = order.indexOf(targetState.destination.route)
            if (from < 0 || to < 0) return null
            return if (to > from) 1 else -1
        }

        private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabEnter() =
            tabDirection()?.let { d -> slideInHorizontally(tabSlide) { it * d } }

        private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabExit() =
            tabDirection()?.let { d -> slideOutHorizontally(tabSlide) { -it * d } }

        /** Перелистывания: экран письма → направление. Письмо из уведомления поверх письма — обычное открытие. */
        private val siblings = HashMap<String, Int>()

        private fun AnimatedContentTransitionScope<NavBackStackEntry>.siblingDirection(): Int? {
            if (!zoomRoutes(initialState.destination.route) || !zoomRoutes(targetState.destination.route)) return null
            siblings[targetState.id]?.let { return it }
            val d = pendingSibling ?: return null
            pendingSibling = null
            if (siblings.size > 50) siblings.clear()
            siblings[targetState.id] = d
            return d
        }

        val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
            val sibling = siblingDirection()
            if (sibling != null) {
                val d = sibling
                slideInHorizontally(tween(OPEN, easing = Decelerate)) { it * d } + fadeIn(tween(OPEN / 2))
            } else tabEnter() ?: if (zoomRoutes(targetState.destination.route)) {
                if (origins.size > 50) origins.clear()
                // Точка запоминается за экраном один раз — повторный расчёт перехода даёт ту же
                val origin = origins.getOrPut(targetState.id) { pendingOrigin ?: TransformOrigin.Center }
                pendingOrigin = null
                scaleIn(tween(OPEN, easing = Decelerate), initialScale = 0.82f, transformOrigin = origin) +
                    fadeIn(tween(OPEN / 2))
            } else {
                pendingOrigin = null // нажатая строка открыла не письмо (например, черновик)
                fadeIn(tween(MEDIUM)) + slideInHorizontally(tween(OPEN, easing = Decelerate)) { it / 12 }
            }
        }
        val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
            val sibling = siblingDirection()
            if (sibling != null) {
                val d = sibling
                slideOutHorizontally(tween(OPEN, easing = Decelerate)) { -it * d / 3 } + fadeOut(tween(OPEN / 2))
            } else tabExit() ?: ExitTransition.KeepUntilTransitionsFinished
        }
        val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
            tabEnter() ?: EnterTransition.None
        }
        val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
            tabExit() ?: if (zoomRoutes(initialState.destination.route)) {
                val origin = origins[initialState.id] ?: TransformOrigin.Center
                scaleOut(tween(CLOSE, easing = Accelerate), targetScale = 0.82f, transformOrigin = origin) +
                    fadeOut(tween(CLOSE, easing = Accelerate))
            } else {
                fadeOut(tween(MEDIUM, easing = Accelerate)) + slideOutHorizontally(tween(CLOSE, easing = Accelerate)) { it / 12 }
            }
        }
    }
}

/**
 * Запоминает положение элемента на экране; [mark] перед навигацией — и новый экран «вырастет» из этого места.
 * Координаты читаются только в момент нажатия, во время прокрутки ничего не вычисляется.
 */
class OpenOrigin {
    private var coords: LayoutCoordinates? = null

    val modifier: Modifier = Modifier.onPlaced { coords = it }

    fun mark() {
        val c = coords?.takeIf { it.isAttached } ?: return
        val root = c.findRootCoordinates().size
        if (root.width == 0 || root.height == 0) return
        val center = c.boundsInRoot().center
        Motion.pendingOrigin = TransformOrigin(
            (center.x / root.width).coerceIn(0f, 1f),
            (center.y / root.height).coerceIn(0f, 1f),
        )
    }
}

/**
 * Выполняет [block] не быстрее [minMillis]: индикатор обновления жестом прячется только после того,
 * как флаг «обновляется» побыл true хотя бы один кадр.
 */
suspend fun <T> keepVisible(minMillis: Long = 400, block: suspend () -> T): T {
    val started = System.currentTimeMillis()
    return try {
        block()
    } finally {
        val left = minMillis - (System.currentTimeMillis() - started)
        if (left > 0) withContext(NonCancellable) { delay(left) }
    }
}

/**
 * Область корутин уровня приложения (её задаёт AppNav): сообщение внизу экрана, показанное перед закрытием
 * экрана, остаётся видимым на следующем экране, а закрытие не ждёт, пока сообщение скроется.
 */
val LocalAppScope = staticCompositionLocalOf<CoroutineScope?> { null }

/** Время удержания письма до появления меню. */
const val HOLD_MILLIS = 500L
private const val HOLD_FILL_DELAY = 100L

/**
 * Строка, реагирующая на касание (onTap) и на удержание [holdMillis] (onHold).
 * Во время удержания строка плавно «заполняется» цветом — видно, сколько ещё держать.
 * Если палец сдвинулся (прокрутка списка, свайп), удержание отменяется.
 */
@Composable
fun HoldableBox(
    onTap: () -> Unit,
    onHold: () -> Unit,
    modifier: Modifier = Modifier,
    holdMillis: Long = HOLD_MILLIS,
    content: @Composable () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onHold)
    val fill = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)

    Box(
        modifier
            .indication(interaction, ripple())
            .pointerInput(holdMillis) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val press = PressInteraction.Press(down.position)
                    scope.launch { interaction.emit(press) }
                    val anim = scope.launch {
                        delay(HOLD_FILL_DELAY) // короткие касания без «заливки»
                        progress.snapTo(0f)
                        progress.animateTo(1f, tween((holdMillis - HOLD_FILL_DELAY).toInt(), easing = LinearEasing))
                    }
                    var up: PointerInputChange? = null
                    val released = withTimeoutOrNull(holdMillis) {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                up = change
                                break
                            }
                            if (change.isConsumed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                        }
                        true
                    }
                    anim.cancel()
                    scope.launch { progress.snapTo(0f) }
                    when {
                        released == null -> {
                            scope.launch { interaction.emit(PressInteraction.Release(press)) }
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            hold()
                            // Поглощаем остаток жеста, чтобы не сработало касание
                            do {
                                val e = awaitPointerEvent()
                                e.changes.forEach { it.consume() }
                            } while (e.changes.any { it.pressed })
                        }
                        up != null && up?.isConsumed == false -> {
                            up?.consume()
                            scope.launch { interaction.emit(PressInteraction.Release(press)) }
                            tap()
                        }
                        else -> scope.launch { interaction.emit(PressInteraction.Cancel(press)) }
                    }
                }
            }
            .drawWithContent {
                drawContent()
                val p = progress.value
                if (p > 0f) drawRect(fill, size = Size(size.width * p, size.height))
            },
    ) { content() }
}

/**
 * Вертикальный список с перестановкой элементов: долгое нажатие — «поднять» элемент, затем тянуть.
 * Высоты элементов могут различаться. [onReorder] вызывается после отпускания; до прихода нового
 * порядка из [items] список показывает порядок, выбранный пользователем (без возврата назад).
 */
@Composable
fun <T> ReorderableColumn(
    items: List<T>,
    key: (T) -> String,
    onReorder: (List<T>) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (item: T, isDragging: Boolean, dragHandle: Modifier) -> Unit,
) {
    val itemKeys = items.map(key)
    val order = remember { mutableStateListOf<String>().apply { addAll(itemKeys) } }
    // Порядок источника, который мы уже приняли: меняется только при реальном обновлении данных
    val accepted = remember { arrayOf(itemKeys) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val heights = remember { HashMap<String, Int>() }
    val shifts = remember { mutableStateMapOf<String, Animatable<Float, AnimationVector1D>>() }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val reorder by rememberUpdatedState(onReorder)
    val latestItems by rememberUpdatedState(items)
    val byKey = items.associateBy(key)

    if (dragging == null && itemKeys != accepted[0]) {
        accepted[0] = itemKeys
        if (order.toList() != itemKeys) {
            order.clear()
            order.addAll(itemKeys)
        }
    }

    /** Соседний элемент плавно «уступает» место перетаскиваемому. */
    fun slide(k: String, from: Float) {
        val a = shifts.getOrPut(k) { Animatable(0f) }
        scope.launch {
            a.snapTo(a.value + from)
            a.animateTo(0f, tween(Motion.SHORT, easing = FastOutSlowInEasing))
        }
    }

    Column(modifier) {
        order.toList().forEach { k ->
            val item = byKey[k] ?: return@forEach
            // key(): при перестановке элемент сохраняет своё состояние и жест не прерывается
            key(k) {
                val isDragging = k == dragging
                val handle = Modifier.pointerInput(k) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            dragging = k
                            offset = 0f
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            offset += amount.y
                            val idx = order.indexOf(k)
                            val own = (heights[k] ?: 0).toFloat()
                            if (offset > 0 && idx in 0 until order.lastIndex) {
                                val next = order[idx + 1]
                                val h = heights[next] ?: 0
                                if (offset > h / 2f) {
                                    order.add(idx + 1, order.removeAt(idx))
                                    offset -= h
                                    slide(next, own)
                                }
                            } else if (offset < 0 && idx > 0) {
                                val prev = order[idx - 1]
                                val h = heights[prev] ?: 0
                                if (-offset > h / 2f) {
                                    order.add(idx - 1, order.removeAt(idx))
                                    offset += h
                                    slide(prev, -own)
                                }
                            }
                        },
                        onDragEnd = {
                            dragging = null
                            offset = 0f
                            val current = latestItems.associateBy(key)
                            reorder(order.mapNotNull { current[it] })
                        },
                        onDragCancel = {
                            dragging = null
                            offset = 0f
                        },
                    )
                }
                Box(
                    Modifier
                        .onSizeChanged { heights[k] = it.height }
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (isDragging) offset else shifts[k]?.value ?: 0f
                            val scale = if (isDragging) 1.02f else 1f
                            scaleX = scale
                            scaleY = scale
                        }
                        .then(if (isDragging) Modifier.shadow(6.dp) else Modifier),
                ) {
                    content(item, isDragging, handle)
                }
            }
        }
    }
}
