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
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
            // Ошибка чтения хранилища не должна оставить приложение на экране загрузки — тогда показываем вход
            val saved = try {
                store.load()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (saved == null) {
                _state.value = AccountState.LoggedOut
            } else {
                val (info, password) = saved
                session.config = EwsConfig(info.ewsUrl, NtlmCredentials.fromLogin(info.login, password), info.auth == AccountInfo.AUTH_BASIC)
                session.mailbox = info.email
                _state.value = AccountState.LoggedIn(info)
                runCatching { scheduler.schedule(store.currentSettings().syncMinutes) }
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
        // Поиск и вход целиком — не дольше LOGIN_TIMEOUT: иначе на плохой сети экран «Подключаемся…» висит минутами
        withTimeoutOrNull(LOGIN_TIMEOUT_MILLIS) { loginOrFind(rawLogin, password, url, options); true }
            ?: throw java.net.SocketTimeoutException("Вход занял больше ${LOGIN_TIMEOUT_MILLIS / 1000} с")
    }

    private suspend fun loginOrFind(rawLogin: String, password: String, url: String, options: LoginOptions) {
        if (url.isNotBlank()) return loginWithRetry(rawLogin, password, url, options)
        // Basic передаёт пароль как есть — только на адрес, который пользователь указал сам
        if (options.auth == AccountInfo.AUTH_BASIC) {
            throw IllegalArgumentException("Для входа по Basic укажите адрес сервера в «Дополнительных настройках»")
        }
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
        // Показываем самую содержательную ошибку: ответ сервера важнее обрыва связи с последним из адресов
        var best: Exception? = null
        for (server in servers) {
            try {
                return loginWithRetry(rawLogin, password, server, options)
            } catch (e: CancellationException) {
                throw e
            } catch (e: EwsAuthException) {
                throw e // неверный пароль: не перебираем серверы дальше, чтобы не заблокировать учётную запись
            } catch (e: Exception) {
                if (best == null || (e is io.github.denhaiz.exmail.ews.EwsException && best !is io.github.denhaiz.exmail.ews.EwsException)) best = e
            }
        }
        throw best ?: IllegalStateException("Не удалось войти")
    }

    /**
     * Вход с одной повторной попыткой при сбое связи: первое соединение (TLS + три шага NTLM) на медленной сети
     * или через прокси иногда обрывается, а второе проходит. Неверный пароль не повторяется.
     */
    private suspend fun loginWithRetry(rawLogin: String, password: String, url: String, options: LoginOptions) {
        try {
            loginAt(rawLogin, password, url, options)
        } catch (e: CancellationException) {
            throw e
        } catch (e: EwsAuthException) {
            throw e
        } catch (e: Exception) {
            // Повторяем сбой связи и временную недоступность сервера (502–504, «сервер занят»)
            val transient = e is java.io.IOException ||
                (e is io.github.denhaiz.exmail.ews.EwsHttpException && e.httpCode in 500..504) ||
                (e is io.github.denhaiz.exmail.ews.EwsException && e.code == "ErrorServerBusy")
            if (!transient) throw e
            kotlinx.coroutines.delay(RETRY_DELAY_MILLIS)
            loginAt(rawLogin, password, url, options)
        }
    }

    private suspend fun loginAt(rawLogin: String, password: String, url: String, options: LoginOptions) {
        val login = normalizeLogin(rawLogin, options.domain)
        val ewsUrl = normalizeUrl(url)
        val basic = options.auth == AccountInfo.AUTH_BASIC
        if (basic && !ewsUrl.startsWith("https://", true)) throw IllegalArgumentException("Вход по Basic возможен только по HTTPS")
        val previous = session.config
        dropConnections()
        session.config = EwsConfig(ewsUrl, NtlmCredentials.fromLogin(login, password), basic)
        val info: AccountInfo
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
            info = AccountInfo(login, email, me?.displayName ?: email, ewsUrl, options.auth)
            store.save(info, password)
        } catch (e: Exception) {
            session.config = previous
            dropConnections()
            throw e
        }
        // Вход проверен и сохранён: дальше ошибки (очистка кэша, расписание) не должны оставить «полувход»
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
            runCatching { db.clearAllTables() }
            runCatching { mail.clearCache() } // письма и картинки прежней учётной записи
            listOf("attachments", "images", "photos").forEach { runCatching { File(context.cacheDir, it).deleteRecursively() } }
        }
        contacts.clearCache()
        session.mailbox = info.email
        _state.value = AccountState.LoggedIn(info)
        runCatching { scheduler.schedule(store.currentSettings().syncMinutes) }
        runCatching { push.sync() }
    }

    /** Отменяет запросы и закрывает соединения всех клиентов (в фоне: закрытие TLS — сетевая операция). */
    private suspend fun dropConnections() = withContext(Dispatchers.IO) {
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
        suspend fun probe() = coroutineScope {
            serverCandidates(email).map { url -> async { url.takeIf { EwsClient.isEwsEndpoint(it) } } }.awaitAll()
        }.filterNotNull()
        // Второй проход: на холодной сети первая проверка может не успеть (у сервера есть недоступный адрес —
        // OkHttp запоминает его и во второй раз сразу идёт по рабочему)
        val probed = probe().ifEmpty { probe() }
        // Отвечает только узел Autodiscover — сначала спрашиваем у него основной адрес сервера
        if (probed.any { !isAutodiscoverHost(it) }) return orderServers(probed, null)
        val found = try {
            autodiscover(login, password, email, options)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }?.let(::normalizeUrl)
        // Autodiscover может вернуть внутренний адрес (доступный только из офиса): недоступный пробуем последним
        if (found != null && !EwsClient.isEwsEndpoint(found)) return orderServers(probed, null) + found
        return orderServers(probed, found)
    }

    /**
     * Ищет адрес EWS через Autodiscover по e-mail.
     * Временно использует введённые учётные данные; после поиска прежние параметры сессии восстанавливаются.
     */
    private suspend fun autodiscover(login: String, password: String, email: String, options: LoginOptions): String? {
        val previous = session.config
        dropConnections()
        // Только NTLM: при поиске запросы идут на сайт домена, пароль открытым текстом (Basic) туда не отправляется
        session.config = EwsConfig("https://${email.substringAfter('@')}/EWS/Exchange.asmx", NtlmCredentials.fromLogin(login, password), false)
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

        private const val RETRY_DELAY_MILLIS = 1_500L
        private const val LOGIN_TIMEOUT_MILLIS = 150_000L

        /** Типовые адреса EWS для домена почты: так называют серверы Exchange чаще всего. */
        fun serverCandidates(email: String): List<String> {
            val d = email.substringAfter('@', "").trim().trimEnd('.').lowercase()
            if (d.isEmpty() || d.any { it.isWhitespace() || it == '/' || it == ':' }) return emptyList()
            // autodiscover.домен — обычно тот же сервер Exchange (его EWS отвечает и по этому имени)
            return listOf("exchange.$d", "mail.$d", "owa.$d", "webmail.$d", "outlook.$d", "ex.$d", "autodiscover.$d", d)
                .map { "https://$it/EWS/Exchange.asmx" }
        }

        private fun isAutodiscoverHost(url: String) = url.substringAfter("://").startsWith("autodiscover.", ignoreCase = true)

        /**
         * Порядок попыток входа: серверы, найденные по типовым именам; адрес из Autodiscover ([discovered]);
         * в последнюю очередь — EWS на узле Autodiscover (если ответ Autodiscover не получен или его адрес недоступен).
         */
        fun orderServers(probed: List<String>, discovered: String?): List<String> {
            val (viaAutodiscover, direct) = probed.partition(::isAutodiscoverHost)
            return (direct + listOfNotNull(discovered) + viaAutodiscover).distinctBy { it.lowercase() }
        }

        /** «mail.corp.ru», «https://mail.corp.ru/owa», «mail.corp.ru/EWS» → `https://mail.corp.ru/EWS/Exchange.asmx`. */
        fun normalizeUrl(raw: String): String {
            var s = raw.trim().ifBlank { BuildConfig.DEFAULT_EWS_URL }
            // HTTP запрещён (пароль и письма — только по защищённому соединению)
            if (s.startsWith("http://", true)) s = "https://" + s.substring(7)
            if (!s.startsWith("https://", true)) s = "https://$s"
            val url = s.toHttpUrlOrNull() ?: return s
            if (url.encodedPath.endsWith(".asmx", ignoreCase = true)) return url.toString()
            return url.newBuilder().encodedPath("/EWS/Exchange.asmx").query(null).fragment(null).build().toString()
        }
    }
}
