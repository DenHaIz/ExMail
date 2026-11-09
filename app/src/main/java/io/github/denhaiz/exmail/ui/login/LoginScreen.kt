package io.github.denhaiz.exmail.ui.login

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Domain
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import io.github.denhaiz.exmail.BuildConfig
import io.github.denhaiz.exmail.data.AccountInfo
import io.github.denhaiz.exmail.data.repo.AccountRepository
import io.github.denhaiz.exmail.data.repo.LoginOptions
import io.github.denhaiz.exmail.data.userMessage
import io.github.denhaiz.exmail.ui.common.Motion
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(private val accounts: AccountRepository) : ViewModel() {
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Техническая причина ошибки (мелким шрифтом): по ней ИТ-отдел поймёт, что случилось. */
    var errorDetail by mutableStateOf<String?>(null)
        private set

    private fun fail(e: Exception) {
        error = e.userMessage()
        errorDetail = when (e) {
            is io.github.denhaiz.exmail.ews.EwsAuthException, is IllegalArgumentException -> null
            is io.github.denhaiz.exmail.ews.EwsException -> "${e.code}: ${e.message.orEmpty()}".take(300)
            else -> (e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")).take(300)
        }
    }

    var discovering by mutableStateOf(false)
        private set
    var hint by mutableStateOf<String?>(null)
        private set

    fun login(login: String, password: String, server: String, options: LoginOptions) {
        if (login.isBlank() || password.isEmpty()) {
            error = if (BuildConfig.DEFAULT_MAIL_DOMAIN.isBlank()) "Введите рабочий e-mail и пароль" else "Введите логин и пароль"
            return
        }
        loading = true
        error = null
        errorDetail = null
        viewModelScope.launch {
            try {
                accounts.login(login, password, server, options)
            } catch (e: Exception) {
                if (e is CancellationException) throw e // закрыли экран или начали новый запрос — это не ошибка
                fail(e)
            } finally {
                loading = false
            }
        }
    }

    /** Поиск адреса сервера по e-mail (Autodiscover); найденный адрес передаётся в [onFound]. */
    fun discover(login: String, password: String, options: LoginOptions, onFound: (String) -> Unit) {
        if (login.isBlank() || password.isEmpty()) {
            error = "Для поиска сервера введите логин (e-mail) и пароль"
            return
        }
        discovering = true
        error = null
        errorDetail = null
        hint = null
        viewModelScope.launch {
            try {
                val url = accounts.discover(login, password, options)
                if (url == null) {
                    error = "Сервер не найден автоматически. Укажите адрес вручную или уточните его в ИТ-отделе."
                } else {
                    onFound(url)
                    hint = "Сервер найден"
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e // закрыли экран или начали новый запрос — это не ошибка
                fail(e)
            } finally {
                discovering = false
            }
        }
    }
}

@Composable
fun LoginScreen(vm: LoginViewModel = hiltViewModel()) {
    var login by rememberSaveable { mutableStateOf("") }
    // Пароль не сохраняется в состоянии экрана: Android может записать его на диск при выгрузке приложения
    var password by remember { mutableStateOf("") }
    var server by rememberSaveable { mutableStateOf(BuildConfig.DEFAULT_EWS_URL) }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var domain by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var auth by rememberSaveable { mutableStateOf(AccountInfo.AUTH_NTLM) }
    val focus = LocalFocusManager.current
    val busy = vm.loading || vm.discovering

    fun options() = LoginOptions(domain = domain, email = email, auth = auth)
    // Сборка без сервера по умолчанию: входят по рабочему e-mail, сервер находится по нему
    val byEmail = BuildConfig.DEFAULT_MAIL_DOMAIN.isBlank()

    fun submit() {
        focus.clearFocus()
        vm.login(login, password, server, options())
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().systemBarsPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(84.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Mail, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(44.dp))
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text("Рабочая почта", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Почта, календарь и адресная книга компании",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(32.dp))

                OutlinedTextField(
                    value = login,
                    onValueChange = { login = it },
                    label = { Text(if (byEmail) "Рабочий e-mail" else "Логин или рабочий e-mail") },
                    placeholder = { Text("ivanov@${BuildConfig.DEFAULT_MAIL_DOMAIN.ifBlank { "company.ru" }}") },
                    leadingIcon = { Icon(Icons.Outlined.Person, null) },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Пароль") },
                    leadingIcon = { Icon(Icons.Outlined.Lock, null) },
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                if (showPassword) "Скрыть пароль" else "Показать пароль",
                            )
                        }
                    },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )

                AnimatedVisibility(vm.error != null, enter = Motion.expand, exit = Motion.collapse) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.size(10.dp))
                            Column {
                                Text(vm.error.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                                vm.errorDetail?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f),
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = ::submit,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    if (vm.loading) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.size(12.dp))
                        Text(if (server.isBlank()) "Ищем почтовый сервер…" else "Подключаемся…")
                    } else {
                        Text("Войти", style = MaterialTheme.typography.titleMedium)
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    if (byEmail) "Используйте рабочий e-mail и тот же пароль, что и для входа на рабочий компьютер или в Outlook. Сервер найдётся сам."
                    else "Используйте те же логин и пароль, что и для входа на рабочий компьютер или в Outlook.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(24.dp))
                TextButton(onClick = { advanced = !advanced }) {
                    Icon(if (advanced) Icons.Outlined.ExpandLess else Icons.Outlined.Tune, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(if (advanced) "Скрыть дополнительные настройки" else "Дополнительные настройки")
                }
                AnimatedVisibility(advanced, enter = Motion.expand, exit = Motion.collapse) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Менять не нужно, если ИТ-отдел не просил об этом.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = server,
                            onValueChange = { server = it },
                            label = { Text("Адрес сервера (EWS)") },
                            placeholder = { Text("найдётся по e-mail") },
                            leadingIcon = { Icon(Icons.Outlined.Dns, null) },
                            singleLine = true,
                            enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(
                                onClick = { focus.clearFocus(); vm.discover(login, password, options()) { server = it } },
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                            ) {
                                if (vm.discovering) {
                                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                                } else {
                                    Icon(Icons.Outlined.TravelExplore, null, Modifier.size(18.dp))
                                }
                                Spacer(Modifier.size(8.dp))
                                Text("Найти по e-mail", maxLines = 1)
                            }
                            Spacer(Modifier.size(8.dp))
                            TextButton(
                                onClick = {
                                    server = BuildConfig.DEFAULT_EWS_URL
                                    domain = ""
                                    email = ""
                                    auth = AccountInfo.AUTH_NTLM
                                },
                                enabled = !busy,
                            ) { Text("Сбросить", maxLines = 1) }
                        }
                        vm.hint?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        OutlinedTextField(
                            value = domain,
                            onValueChange = { domain = it.trim() },
                            label = { Text("Домен (необязательно)") },
                            placeholder = { Text("например, CORP") },
                            leadingIcon = { Icon(Icons.Outlined.Domain, null) },
                            singleLine = true,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it.trim() },
                            label = { Text("E-mail, если отличается от логина") },
                            leadingIcon = { Icon(Icons.Outlined.AlternateEmail, null) },
                            singleLine = true,
                            enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("Проверка подлинности", style = MaterialTheme.typography.labelLarge)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            val modes = listOf(AccountInfo.AUTH_NTLM to "NTLM", AccountInfo.AUTH_BASIC to "Basic")
                            modes.forEachIndexed { i, (value, label) ->
                                SegmentedButton(
                                    selected = auth == value,
                                    onClick = { auth = value },
                                    shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                                    enabled = !busy,
                                    icon = {},
                                ) { Text(label, maxLines = 1) }
                            }
                        }
                        Text(
                            if (auth == AccountInfo.AUTH_NTLM) "NTLM — как в Outlook на рабочем компьютере (рекомендуется)."
                            else "Basic — только если NTLM на сервере выключен. Пароль передаётся внутри защищённого HTTPS-соединения.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (byEmail) "С указанным адресом сервера можно входить и как ДОМЕН\\ivanov."
                            else "Логин можно указать как ivanov, ivanov@${BuildConfig.DEFAULT_MAIL_DOMAIN} или ДОМЕН\\ivanov.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
