package io.github.denhaiz.exmail.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.denhaiz.exmail.BuildConfig
import io.github.denhaiz.exmail.sync.Notifications
import io.github.denhaiz.exmail.ui.common.Format
import io.github.denhaiz.exmail.update.AppRelease
import io.github.denhaiz.exmail.update.AppUpdater
import io.github.denhaiz.exmail.update.UpdateState

/**
 * Установка загруженного обновления — единственное место, где она запускается (в AppNav, пока приложение открыто).
 * Ставит, только когда экран на переднем плане: из фона Android окно установщика не покажет. Если Android ещё
 * не разрешил ExMail устанавливать приложения — открывает это разрешение и после возвращения продолжает.
 */
@Composable
fun UpdateInstallHost(updater: AppUpdater, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val message by rememberUpdatedState(onMessage)
    val state by updater.state.collectAsState()
    val requested by updater.installRequested.collectAsState()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    var askedPermission by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state, requested, lifecycle) {
        val s = state as? UpdateState.Ready ?: return@LaunchedEffect
        if (!requested || !lifecycle.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        when {
            updater.canInstall() -> {
                updater.installHandled()
                askedPermission = false
                Notifications.cancelUpdate(context)
                runCatching { context.startActivity(updater.installIntent(s.file)) }
                    .onFailure { message("Не удалось открыть установщик Android") }
            }
            !askedPermission -> {
                askedPermission = true
                runCatching { context.startActivity(updater.permissionIntent()) }
                    .onFailure {
                        askedPermission = false
                        updater.installHandled()
                        message("Разрешите ExMail установку приложений в настройках Android и нажмите «Установить» ещё раз")
                    }
            }
            else -> {
                // Вернулись из настроек Android без разрешения
                askedPermission = false
                updater.installHandled()
                message("Без разрешения на установку обновление не поставить. Нажмите «Установить», чтобы попробовать снова")
            }
        }
    }
}

/**
 * Окно новой версии: что изменилось, загрузка с ходом и установка.
 * [onLater] — «Позже» (отложить напоминания); [onDismiss] — закрыть окно (загрузка, если идёт, продолжается).
 */
@Composable
fun UpdateDialog(
    state: UpdateState,
    updater: AppUpdater,
    onLater: (AppRelease) -> Unit,
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
                    release != null -> "Новая версия ExMail ${release.version}"
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
                            "После загрузки откроется установка Android. Окно можно скрыть — загрузка продолжится.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is UpdateState.Ready -> Text("Загружено. Нажмите «Установить» — откроется установка Android.")
                    is UpdateState.Failed -> Text("Не получилось: ${state.message}.", color = MaterialTheme.colorScheme.error)
                    else -> Unit
                }
                if (release != null && state !is UpdateState.Downloading) {
                    Spacer(Modifier.height(12.dp))
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
                state is UpdateState.Available -> TextButton(onClick = { updater.start(state.release) }) { Text("Обновить") }
                state is UpdateState.Ready -> TextButton(onClick = { onDismiss(); updater.requestInstall() }) { Text("Установить") }
                state is UpdateState.Failed && release != null -> TextButton(onClick = { updater.start(release) }) { Text("Повторить") }
                state is UpdateState.Failed -> TextButton(onClick = updater::check) { Text("Повторить") }
                state is UpdateState.Downloading -> TextButton(onClick = onDismiss) { Text("Скрыть") }
                else -> TextButton(onClick = onDismiss) { Text("Закрыть") }
            }
        },
        dismissButton = {
            when {
                state is UpdateState.Downloading -> TextButton(onClick = updater::stop) { Text("Остановить") }
                release != null -> TextButton(onClick = { onLater(release) }) { Text("Позже") }
                else -> Unit
            }
        },
    )
}
