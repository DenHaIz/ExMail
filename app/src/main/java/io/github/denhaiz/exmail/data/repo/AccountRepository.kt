package io.github.denhaiz.exmail.data.repo

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.denhaiz.exmail.BuildConfig
import io.github.denhaiz.exmail.auth.NtlmCredentials
import io.github.denhaiz.exmail.data.AccountInfo
import io.github.denhaiz.exmail.data.AccountStore
import io.github.denhaiz.exmail.data.db.AppDatabase
import io.github.denhaiz.exmail.di.ApplicationScope
import io.github.denhaiz.exmail.di.PushClient
import io.github.denhaiz.exmail.di.SecondaryClient
import io.github.denhaiz.exmail.ews.EwsAuthException
import io.github.denhaiz.exmail.ews.EwsClient
import io.github.denhaiz.exmail.ews.EwsConfig
import io.github.denhaiz.exmail.ews.EwsSession
import io.github.denhaiz.exmail.ews.OofSettings
import io.github.denhaiz.exmail.ews.getOof
import io.github.denhaiz.exmail.ews.setOof
import io.github.denhaiz.exmail.sync.PushController
import io.github.denhaiz.exmail.sync.SyncScheduler
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Дополнительные параметры входа (скрыты на экране входа под «Дополнительные настройки»). */
data class LoginOptions(
    /** Домен Windows для логина без домена: `ivanov` → `DOMAIN\ivanov`. */
    val domain: String = "",
    /** Адрес почты, если логин с ним не совпадает. */
    val email: String = "",
    val auth: String = AccountInfo.AUTH_NTLM,
)

sealed interface AccountState {
    data object Loading : AccountState
    data object LoggedOut : AccountState
    data class LoggedIn(val info: AccountInfo) : AccountState
}

@Singleton
class AccountRepository @Inject constructor(
    private val store: AccountStore,
    private val session: EwsSession,
    private val client: EwsClient,
    private val db: AppDatabase,
    private val scheduler: SyncScheduler,
    private val push: PushController,
    private val mail: MailRepository,
    private val calendar: CalendarRepository,
    private val contacts: ContactsRepository,
    @SecondaryClient private val photoClient: EwsClient,
    @PushClient private val pushClient: EwsClient,
    @ApplicationContext private val context: Context,
    @ApplicationScope scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<AccountState>(AccountState.Loading)
    val state: StateFlow<AccountState> = _state.asStateFlow()

    val current: AccountInfo? get() = (state.value as? AccountState.LoggedIn)?.info

    init {
        scope.launch {
            val saved = store.load()
            if (saved == null) {
                _state.value = AccountState.LoggedOut
            } else {
                val (info, password) = saved
                session.config = EwsConfig(info.ewsUrl, NtlmCredentials.fromLogin(info.login, password), info.auth == AccountInfo.AUTH_BASIC)
                session.mailbox = info.email
                _state.value = AccountState.LoggedIn(info)
                scheduler.schedule(store.currentSettings().syncMinutes)
            }
        }
    }

    /**
     * Вход: проверяет логин/пароль запросом к серверу, определяет имя и адрес пользователя.
     * Логин можно ввести как `ivanov@company.ru`, `DOMAIN\ivanov` или `ivanov` (если задан домен почты по умолчанию).
     * Адрес сервера не задан — он ищется по e-mail ([findServers]).
     */
    suspend fun login(
        rawLogin: String,
        password: String,
        url: String = BuildConfig.DEFAULT_EWS_URL,
        options: LoginOptions = LoginOptions(),
    ) {
        if (url.isNotBlank()) return loginAt(rawLogin, password, url, options)
        val login = normalizeLogin(rawLogin, options.domain)
        val email = emailOf(login, options)
            ?: throw IllegalArgumentException("Введите рабочий e-mail, например ivanov@company.ru: по нему найдётся почтовый сервер")
        val servers = findServers(login, password, email, options)
        if (servers.isEmpty()) {
            throw IllegalArgumentException(
                "Почтовый сервер для «${email.substringAfter('@')}» не найден. " +
                    "Укажите его адрес в «Дополнительных настройках» — его подскажет ИТ-отдел",
            )
        }
        var last: Exception? = null
        for (server in servers) {
            try {
                return loginAt(rawLogin, password, server, options)
            } catch (e: CancellationException) {
                throw e
            } catch (e: EwsAuthException) {
                throw e // неверный пароль: не перебираем серверы дальше, чтобы не заблокировать учётную запись
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("Не удалось войти")
    }

    private suspend fun loginAt(rawLogin: String, password: String, url: String, options: LoginOptions) {
        val login = normalizeLogin(rawLogin, options.domain)
        val ewsUrl = normalizeUrl(url)
        val basic = options.auth == AccountInfo.AUTH_BASIC
        if (basic && !ewsUrl.startsWith("https://", true)) throw IllegalArgumentException("Вход по Basic возможен только по HTTPS")
        val previous = session.config
        dropConnections()
        session.config = EwsConfig(ewsUrl, NtlmCredentials.fromLogin(login, password), basic)
        try {
            client.getWellKnownFolders()
            val shortName = login.substringAfter('\\').substringBefore('@')
            val knownEmail = options.email.trim().takeIf { '@' in it }
            val found = try {
                client.resolveNames(knownEmail ?: if ('@' in login) login else shortName).filter { !it.isGroup }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            // Поиск по «ivanov» может вернуть и «ivanova» — берём точное совпадение
            val me = found.firstOrNull { it.email.equals(knownEmail ?: login, true) }
                ?: found.firstOrNull { it.email.substringBefore('@').equals(shortName, true) }
                ?: found.singleOrNull()
            val email = knownEmail ?: me?.email ?: when {
                '@' in login -> login
                BuildConfig.DEFAULT_MAIL_DOMAIN.isNotBlank() -> "$shortName@${BuildConfig.DEFAULT_MAIL_DOMAIN}"
                else -> login
            }
            val info = AccountInfo(login, email, me?.displayName ?: email, ewsUrl, options.auth)
            store.save(info, password)
            withContext(Dispatchers.IO) {
                db.clearAllTables()
                mail.clearCache() // письма и картинки прежней учётной записи
                listOf("attachments", "images", "photos").forEach { File(context.cacheDir, it).deleteRecursively() }
            }
            contacts.clearCache()
            session.mailbox = info.email
            _state.value = AccountState.LoggedIn(info)
            scheduler.schedule(store.currentSettings().syncMinutes)
            push.sync()
        } catch (e: Exception) {
            session.config = previous
            dropConnections()
            throw e
        }
    }

    private fun dropConnections() {
        client.evictConnections()
        photoClient.evictConnections()
        pushClient.evictConnections()
    }

    /** Адрес сервера для кнопки «Найти по e-mail»: первый найденный или null. */
    suspend fun discover(rawLogin: String, password: String, options: LoginOptions = LoginOptions()): String? {
        val login = normalizeLogin(rawLogin, options.domain)
        val email = emailOf(login, options) ?: return null
        return findServers(login, password, email, options).firstOrNull()
    }

    private fun emailOf(login: String, options: LoginOptions): String? =
        options.email.trim().takeIf { '@' in it } ?: login.takeIf { '@' in it }

    /**
     * Серверы Exchange для почты [email]. Сначала — типовые адреса домена (`exchange.`, `mail.` …): им отправляется
     * только запрос без учётных данных, и подходят те, где действительно отвечает EWS. Ничего не нашлось — Autodiscover
     * (как Outlook: с введёнными логином и паролем, только на адреса домена из e-mail).
     */
    private suspend fun findServers(login: String, password: String, email: String, options: LoginOptions): List<String> {
        val probed = coroutineScope {
            serverCandidates(email).map { url -> async { url.takeIf { EwsClient.isEwsEndpoint(it) } } }.awaitAll()
        }.filterNotNull()
        if (probed.isNotEmpty()) return probed
        val found = try {
            autodiscover(login, password, email, options)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return listOfNotNull(found?.let(::normalizeUrl))
    }

    /**
     * Ищет адрес EWS через Autodiscover по e-mail.
     * Временно использует введённые учётные данные; после поиска прежние параметры сессии восстанавливаются.
     */
    private suspend fun autodiscover(login: String, password: String, email: String, options: LoginOptions): String? {
        val previous = session.config
        dropConnections()
        session.config = EwsConfig("https://${email.substringAfter('@')}/EWS/Exchange.asmx", NtlmCredentials.fromLogin(login, password), options.auth == AccountInfo.AUTH_BASIC)
        return try {
            client.autodiscover(email)
        } finally {
            session.config = previous
            dropConnections()
        }
    }

    suspend fun logout() {
        push.stop()
        scheduler.cancel()
        store.clear()
        session.config = null
        session.mailbox = null
        dropConnections()
        calendar.clearCache()
        contacts.clearCache()
        withContext(Dispatchers.IO) {
            mail.clearCache() // письма и картинки на диске
            db.clearAllTables()
            // Скачанные вложения, картинки и фото прежней учётной записи
            listOf("attachments", "images", "photos").forEach { File(context.cacheDir, it).deleteRecursively() }
        }
        _state.value = AccountState.LoggedOut
    }

    suspend fun getOof(): OofSettings = client.getOof(current?.email.orEmpty())

    suspend fun setOof(settings: OofSettings) = client.setOof(current?.email.orEmpty(), settings)

    companion object {
        /**
         * `ivanov` → `ivanov@домен-почты` (если он задан в сборке), с [domain] → `DOMAIN\ivanov`;
         * `user@x` и `DOM\user` не меняются.
         */
        fun normalizeLogin(raw: String, domain: String = "", mailDomain: String = BuildConfig.DEFAULT_MAIL_DOMAIN): String {
            val s = raw.trim()
            val d = domain.trim().trimEnd('\\')
            return when {
                '@' in s || '\\' in s -> s
                d.isNotEmpty() -> "$d\\$s"
                mailDomain.isNotBlank() -> "$s@$mailDomain"
                else -> s
            }
        }

        /** Типовые адреса EWS для домена почты: так называют серверы Exchange чаще всего. */
        fun serverCandidates(email: String): List<String> {
            val d = email.substringAfter('@', "").trim().trimEnd('.').lowercase()
            if (d.isEmpty() || d.any { it.isWhitespace() || it == '/' || it == ':' }) return emptyList()
            return listOf("exchange.$d", "mail.$d", "owa.$d", "webmail.$d", "outlook.$d", "ex.$d", d)
                .map { "https://$it/EWS/Exchange.asmx" }
        }

        fun normalizeUrl(raw: String): String {
            var s = raw.trim().ifBlank { BuildConfig.DEFAULT_EWS_URL }
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "https://$s"
            if (!s.contains("/EWS/", true)) s = s.trimEnd('/') + "/EWS/Exchange.asmx"
            return s
        }
    }
}
