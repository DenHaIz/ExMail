package io.github.denhaiz.exmail.ui.common

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import io.github.denhaiz.exmail.ews.ContactInfo
import io.github.denhaiz.exmail.ui.theme.avatarColor

/** Загрузчик фото сотрудников (из ContactsRepository), передаётся через CompositionLocal. */
interface PhotoLoader {
    fun cached(email: String): Bitmap?
    suspend fun load(email: String): Bitmap?
}

val LocalPhotoLoader = staticCompositionLocalOf<PhotoLoader?> { null }

@Composable
fun Avatar(name: String, email: String, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val loader = LocalPhotoLoader.current
    var photo by remember(email) { mutableStateOf(loader?.cached(email)) }
    LaunchedEffect(email) {
        if (photo == null && email.isNotBlank()) photo = loader?.load(email)
    }
    val key = name.ifBlank { email }
    // Цвет и инициалы считаются один раз для имени, а не при каждой перерисовке строки списка
    val color = remember(key) { avatarColor(key) }
    val initials = remember(key) { Format.initials(key) }
    val image = remember(photo) { photo?.asImageBitmap() }
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                initials,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                // В dp, а не sp: крупный системный шрифт не выталкивает инициалы за круг
                fontSize = with(LocalDensity.current) { (size * 0.4f).toSp() },
            )
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    text: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // Прокручиваемый контейнер — чтобы «потянуть вниз для обновления» работало и на пустом экране
    BoxWithConstraints(modifier.fillMaxSize()) {
        EmptyStateContent(icon, title, text, actionLabel, onAction, Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight))
    }
}

@Composable
private fun EmptyStateContent(
    icon: ImageVector,
    title: String,
    text: String?,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    modifier: Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(88.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (text != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** Плашка «нет связи» / ошибка обновления над списком. */
@Composable
fun ErrorBanner(text: String, onRetry: (() -> Unit)?, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CloudOff, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(12.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (onRetry != null) TextButton(onClick = onRetry) { Text("Повторить", maxLines = 1) }
            IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Close, "Скрыть", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismiss: String = "Отмена",
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismiss) } },
    )
}

data class Recipient(val name: String, val email: String) {
    val label: String get() = name.ifBlank { email }
}

/**
 * Получатели вместе с адресами, набранными в поле, но ещё не ставшими «чипсами» (пользователь сразу нажал
 * «Отправить»). null — в набранном тексте есть неверный адрес.
 */
fun withTyped(list: List<Recipient>, typed: String): List<Recipient>? {
    val parts = typed.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.any { !Format.EMAIL.matches(it) }) return null
    return list + parts.distinctBy { it.lowercase() }.filter { p -> list.none { it.email.equals(p, true) } }.map { Recipient("", it) }
}

/**
 * Поле получателей: адреса превращаются в «чипсы», при вводе — подсказки
 * из контактов, недавних отправителей и адресной книги организации.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecipientField(
    label: String,
    recipients: List<Recipient>,
    onChange: (List<Recipient>) -> Unit,
    suggest: suspend (String) -> List<ContactInfo>,
    modifier: Modifier = Modifier,
    /** Поиск для окна адресной книги, открываемого кнопкой-подписью поля. */
    addressBook: (suspend (String) -> List<ContactInfo>)? = null,
    trailing: (@Composable () -> Unit)? = null,
    /** Текст, набранный в поле (ещё не «чипса»): экран учитывает его при отправке, см. [withTyped]. */
    onTyping: (String) -> Unit = {},
) {
    var showBook by remember { mutableStateOf(false) }
    if (showBook && addressBook != null) {
        AddressBookDialog(
            title = label,
            selected = recipients,
            search = addressBook,
            onDone = { onChange(it); showBook = false },
            onDismiss = { showBook = false },
        )
    }
    var text by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<ContactInfo>>(emptyList()) }
    var focused by remember { mutableStateOf(false) }

    fun commit(raw: String): Boolean {
        val email = raw.trim().trim(',', ';').trim()
        if (email.isEmpty()) return true
        if (!Format.EMAIL.matches(email)) return false
        if (recipients.none { it.email.equals(email, true) }) onChange(recipients + Recipient("", email))
        text = ""
        return true
    }

    val typing by rememberUpdatedState(onTyping)
    // Поле скрыли (свернули «Копию») — набранный в нём текст больше не учитывается при отправке
    DisposableEffect(Unit) { onDispose { typing("") } }
    // Набранный адрес уже добавлен в получатели (например, при отправке) — поле очищается
    LaunchedEffect(recipients) {
        if (text.isNotBlank() && withTyped(emptyList(), text)?.all { t -> recipients.any { it.email.equals(t.email, true) } } == true) text = ""
    }
    LaunchedEffect(text) {
        typing(text)
        val q = text.trim()
        if (q.length < 2) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        suggestions = runCatching { suggest(q) }.getOrDefault(emptyList())
            .filter { s -> recipients.none { it.email.equals(s.email, true) } }
    }

    Column(modifier) {
        Row(Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.Top) {
            if (addressBook != null) {
                // Кнопка «Кому» открывает адресную книгу
                Surface(
                    onClick = { showBook = true },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.padding(top = 8.dp).semantics { contentDescription = "$label: выбрать из адресной книги" },
                ) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Outlined.PersonAdd, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Spacer(Modifier.width(8.dp))
            } else {
                Text(
                    label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp, top = 12.dp).widthIn(min = 56.dp),
                )
            }
            FlowRow(
                Modifier.weight(1f).heightIn(min = 48.dp),
                verticalArrangement = Arrangement.Center,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                recipients.forEach { r ->
                    // Получатель убирается только крестиком: касание самой «чипсы» (посмотреть адрес) его не удаляет
                    InputChip(
                        selected = false,
                        onClick = {},
                        label = { Text(r.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        avatar = { Avatar(r.name, r.email, size = InputChipDefaults.AvatarSize) },
                        trailingIcon = {
                            Icon(
                                Icons.Default.Close,
                                "Убрать ${r.label}",
                                Modifier.size(24.dp).clip(CircleShape).clickable { onChange(recipients - r) }.padding(4.dp),
                            )
                        },
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { v ->
                        if (v.endsWith(",") || v.endsWith(";") || (v.endsWith(" ") && Format.EMAIL.matches(v.trim()))) {
                            if (!commit(v)) text = v
                        } else {
                            text = v
                        }
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { commit(text) }),
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .weight(1f)
                        .widthIn(min = 96.dp)
                        .padding(vertical = 12.dp)
                        .semantics { contentDescription = label }
                        .onFocusChanged {
                            if (focused && !it.isFocused) commit(text)
                            focused = it.isFocused
                        },
                )
            }
            Box(Modifier.padding(top = 2.dp)) { trailing?.invoke() }
        }
        if (focused && suggestions.isNotEmpty()) {
            Surface(tonalElevation = 3.dp, shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Column {
                    suggestions.forEach { s ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onChange(recipients + Recipient(s.displayName, s.email))
                                    text = ""
                                    suggestions = emptyList()
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(s.displayName, s.email, size = 36.dp)
                            Spacer(Modifier.size(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOf(s.email, s.jobTitle).filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
