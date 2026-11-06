package io.github.denhaiz.exmail.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import io.github.denhaiz.exmail.data.userMessage
import io.github.denhaiz.exmail.ews.ContactInfo

/** Компактная строка поиска (44 dp) вместо громоздкого поля ввода. */
@Composable
fun CompactSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.height(44.dp),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
                )
            }
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Close, "Очистить", Modifier.size(18.dp))
                }
            } else {
                Spacer(Modifier.width(10.dp))
            }
        }
    }
}

/**
 * Окно выбора адресатов из адресной книги организации и личных контактов.
 * Можно отметить несколько человек; «Готово» возвращает итоговый список.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressBookDialog(
    title: String,
    selected: List<Recipient>,
    search: suspend (String) -> List<ContactInfo>,
    onDone: (List<Recipient>) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ContactInfo>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var chosen by remember { mutableStateOf(selected) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(query) {
        if (query.isNotBlank()) delay(300)
        loading = true
        try {
            results = search(query)
            error = null
        } catch (e: Exception) {
            if (e is CancellationException) throw e // закрыли экран или начали новый запрос — это не ошибка
            error = e.userMessage()
        } finally {
            loading = false
        }
    }

    fun toggle(c: ContactInfo) {
        chosen = if (chosen.any { it.email.equals(c.email, true) }) chosen.filterNot { it.email.equals(c.email, true) }
        else chosen + Recipient(c.displayName, c.email)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Закрыть") } },
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    actions = {
                        Button(onClick = { onDone(chosen) }, modifier = Modifier.padding(end = 8.dp)) {
                            Text(if (chosen.isEmpty()) "Готово" else "Готово · ${chosen.size}", maxLines = 1)
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
                CompactSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Поиск в адресной книге",
                    focusRequester = focus,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                )
                if (chosen.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        chosen.forEach { r ->
                            InputChip(
                                selected = true,
                                onClick = { chosen = chosen - r },
                                label = { Text(r.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                trailingIcon = { Icon(Icons.Outlined.Close, "Убрать", Modifier.size(16.dp)) },
                            )
                        }
                    }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
                val list = results
                when {
                    error != null -> EmptyState(Icons.Outlined.SearchOff, "Не удалось выполнить поиск", error)
                    list == null -> Unit
                    list.isEmpty() && query.isBlank() -> EmptyState(
                        Icons.Outlined.PersonSearch,
                        "Поиск по адресной книге",
                        "Введите имя, фамилию или e-mail коллеги",
                    )
                    list.isEmpty() && !loading -> EmptyState(Icons.Outlined.SearchOff, "Никого не нашли", "Проверьте написание")
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(list, key = { it.email.lowercase() }) { c ->
                            val checked = chosen.any { it.email.equals(c.email, true) }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { toggle(c) }
                                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Avatar(c.displayName, c.email, size = 40.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        c.displayName,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        listOf(c.jobTitle, c.department).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { c.email },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Checkbox(checked = checked, onCheckedChange = { toggle(c) })
                            }
                        }
                    }
                }
            }
        }
    }
}
