package io.github.denhaiz.exmail.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.denhaiz.exmail.ews.OofSettings
import io.github.denhaiz.exmail.ui.common.Format
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Настройка автоответа «Нет на месте». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OofDialog(
    current: OofSettings,
    onSave: (enabled: Boolean, text: String, external: Boolean, from: LocalDate?, to: LocalDate?) -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = Format.zone()
    var enabled by remember { mutableStateOf(current.state != "Disabled") }
    var text by remember {
        mutableStateOf(current.internalReply.ifBlank { "Здравствуйте! Сейчас меня нет на месте, отвечу после возвращения." })
    }
    var external by remember { mutableStateOf(current.externalAudience != "None") }
    var scheduled by remember { mutableStateOf(current.state == "Scheduled") }
    var from by remember { mutableStateOf(current.start?.atZone(zone)?.toLocalDate() ?: LocalDate.now()) }
    var to by remember { mutableStateOf(current.end?.minusSeconds(1)?.atZone(zone)?.toLocalDate() ?: LocalDate.now().plusDays(7)) }
    var pick by remember { mutableStateOf<String?>(null) }
    val fmt = DateTimeFormatter.ofPattern("d MMM yyyy", Format.ru)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Автоответ") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Отвечать автоматически", modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Текст ответа") },
                    minLines = 3,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { external = !external }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = external, onCheckedChange = { external = it }, enabled = enabled)
                    Text("Отвечать и внешним адресатам", style = MaterialTheme.typography.bodyMedium)
                }
                Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { scheduled = !scheduled }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = scheduled, onCheckedChange = { scheduled = it }, enabled = enabled)
                    Text("Только в указанные дни", style = MaterialTheme.typography.bodyMedium)
                }
                if (scheduled && enabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { pick = "from" }, modifier = Modifier.weight(1f)) { Text(from.format(fmt), maxLines = 1) }
                        Text("—")
                        OutlinedButton(onClick = { pick = "to" }, modifier = Modifier.weight(1f)) { Text(to.format(fmt), maxLines = 1) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(enabled, text.trim(), external, from.takeIf { scheduled }, to.takeIf { scheduled }?.let { maxOf(it, from) })
                onDismiss()
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )

    pick?.let { which ->
        val initial = if (which == "from") from else to
        val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pick = null },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        val d = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        if (which == "from") from = d else to = d
                    }
                    pick = null
                }) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { pick = null }) { Text("Отмена") } },
        ) { DatePicker(state) }
    }
}
