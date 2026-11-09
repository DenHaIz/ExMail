package io.github.denhaiz.exmail.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

data class AccountInfo(
    val login: String,
    val email: String,
    val displayName: String,
    val ewsUrl: String,
    /** Способ проверки подлинности: [AUTH_NTLM] (по умолчанию) или [AUTH_BASIC]. */
    val auth: String = AUTH_NTLM,
) {
    companion object {
        const val AUTH_NTLM = "ntlm"
        const val AUTH_BASIC = "basic"
    }
}

data class AppSettings(
    val notifications: Boolean = true,
    val syncMinutes: Int = 15,
    val signature: String = "",
    val loadImages: Boolean = true,
    /** Порядок разделов приложения (нижняя панель): mail, calendar, contacts. */
    val tabOrder: List<String> = DEFAULT_TABS,
    /** Порядок групп в боковой панели: tabs, main, user. */
    val groupOrder: List<String> = DEFAULT_GROUPS,
    /** Пользовательский порядок папок (id). */
    val folderOrder: List<String> = emptyList(),
    /** Тема: [THEME_SYSTEM] (по умолчанию), [THEME_LIGHT], [THEME_DARK]. */
    val theme: String = THEME_SYSTEM,
    /** В тёмной теме письма показываются затемнёнными (иначе — в исходных цветах). */
    val darkMessages: Boolean = true,
    /** Масштаб текста писем, %. */
    val messageZoom: Int = 100,
    /** Мгновенная доставка: постоянное подключение к серверу (иначе — проверка по расписанию). */
    val instantPush: Boolean = true,
    /** Уже спрашивали про работу в фоне без ограничений. */
    val batteryAsked: Boolean = false,
    /** Свайп по письму в списке слева направо. */
    val swipeRight: SwipeAction = SwipeAction.READ,
    /** Свайп по письму в списке справа налево. */
    val swipeLeft: SwipeAction = SwipeAction.NONE,
    /** Уведомлять о новой версии приложения. */
    val updateNotify: Boolean = true,
    /** «Позже» в окне обновления: до [updatePostponedUntil] о версии [updatePostponedVersion] не напоминаем. */
    val updatePostponedVersion: String = "",
    val updatePostponedUntil: Long = 0L,
) {
    /** Напоминание о версии [version] отложено кнопкой «Позже». */
    fun updatePostponed(version: String, now: Long = System.currentTimeMillis()): Boolean =
        updatePostponedVersion == version && now < updatePostponedUntil

    companion object {
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        val DEFAULT_TABS = listOf("mail", "calendar", "contacts")
        val DEFAULT_GROUPS = listOf("tabs", "main", "user")
    }
}

/** Действие свайпа по письму в списке; [key] хранится в настройках. */
enum class SwipeAction(val key: String) {
    NONE("none"),
    READ("read"),
    FLAG("flag"),
    REPLY("reply"),
    FORWARD("forward"),
    MOVE("move"),
    DELETE("delete"),
    ;

    companion object {
        fun of(key: String?, default: SwipeAction): SwipeAction = entries.firstOrNull { it.key == key } ?: default
    }
}

private val Context.dataStore by preferencesDataStore(name = "exmail")

/** Учётная запись и настройки. Пароль шифруется ключом из Android Keystore (AES-256-GCM). */
@Singleton
class AccountStore @Inject constructor(@ApplicationContext private val context: Context) {

    private object Keys {
        val login = stringPreferencesKey("login")
        val email = stringPreferencesKey("email")
        val name = stringPreferencesKey("display_name")
        val url = stringPreferencesKey("ews_url")
        val secret = stringPreferencesKey("secret")
        val notifications = booleanPreferencesKey("notifications")
        val syncMinutes = intPreferencesKey("sync_minutes")
        val signature = stringPreferencesKey("signature")
        val loadImages = booleanPreferencesKey("load_images")
        val lastNotified = longPreferencesKey("last_notified")
        val tabOrder = stringPreferencesKey("tab_order")
        val groupOrder = stringPreferencesKey("group_order")
        val folderOrder = stringPreferencesKey("folder_order")
        val auth = stringPreferencesKey("auth")
        val theme = stringPreferencesKey("theme")
        val darkMessages = booleanPreferencesKey("dark_messages")
        val messageZoom = intPreferencesKey("message_zoom")
        val instantPush = booleanPreferencesKey("instant_push")
        val batteryAsked = booleanPreferencesKey("battery_asked")
        val swipeRight = stringPreferencesKey("swipe_right")
        val swipeLeft = stringPreferencesKey("swipe_left")
        val updateNotify = booleanPreferencesKey("update_notify")
        val updateNotified = stringPreferencesKey("update_notified")
        val updateNotifiedAt = longPreferencesKey("update_notified_at")
        val updatePostponedVersion = stringPreferencesKey("update_postponed_version")
        val updatePostponedUntil = longPreferencesKey("update_postponed_until")
    }

    suspend fun load(): Pair<AccountInfo, String>? {
        val p = context.dataStore.data.first()
        val login = p[Keys.login] ?: return null
        val secret = p[Keys.secret] ?: return null
        val password = runCatching { decrypt(secret) }.getOrNull() ?: return null
        val info = AccountInfo(login, p[Keys.email].orEmpty(), p[Keys.name].orEmpty(), p[Keys.url].orEmpty(), p[Keys.auth] ?: AccountInfo.AUTH_NTLM)
        return info to password
    }

    suspend fun save(info: AccountInfo, password: String) {
        val secret = encrypt(password)
        context.dataStore.edit {
            it[Keys.login] = info.login
            it[Keys.email] = info.email
            it[Keys.name] = info.displayName
            it[Keys.url] = info.ewsUrl
            it[Keys.auth] = info.auth
            it[Keys.secret] = secret
        }
    }

    /** Удаляет учётную запись и данные; оформление (тема, масштаб, жесты) и настройки обновлений остаются. */
    suspend fun clear() {
        context.dataStore.edit { p ->
            val theme = p[Keys.theme]
            val zoom = p[Keys.messageZoom]
            val dark = p[Keys.darkMessages]
            val right = p[Keys.swipeRight]
            val left = p[Keys.swipeLeft]
            val updateNotify = p[Keys.updateNotify]
            val updateNotified = p[Keys.updateNotified]
            val updateNotifiedAt = p[Keys.updateNotifiedAt]
            val postponedVersion = p[Keys.updatePostponedVersion]
            val postponedUntil = p[Keys.updatePostponedUntil]
            p.clear()
            theme?.let { p[Keys.theme] = it }
            zoom?.let { p[Keys.messageZoom] = it }
            dark?.let { p[Keys.darkMessages] = it }
            right?.let { p[Keys.swipeRight] = it }
            left?.let { p[Keys.swipeLeft] = it }
            updateNotify?.let { p[Keys.updateNotify] = it }
            updateNotified?.let { p[Keys.updateNotified] = it }
            updateNotifiedAt?.let { p[Keys.updateNotifiedAt] = it }
            postponedVersion?.let { p[Keys.updatePostponedVersion] = it }
            postponedUntil?.let { p[Keys.updatePostponedUntil] = it }
        }
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map(::toSettings)

    suspend fun currentSettings(): AppSettings = toSettings(context.dataStore.data.first())

    private fun toSettings(p: Preferences) = AppSettings(
        notifications = p[Keys.notifications] ?: true,
        syncMinutes = p[Keys.syncMinutes] ?: 15,
        signature = p[Keys.signature] ?: "",
        loadImages = p[Keys.loadImages] ?: true,
        tabOrder = normalize(p[Keys.tabOrder], AppSettings.DEFAULT_TABS),
        groupOrder = normalize(p[Keys.groupOrder], AppSettings.DEFAULT_GROUPS),
        folderOrder = p[Keys.folderOrder]?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
        theme = p[Keys.theme]?.takeIf { it in THEMES } ?: AppSettings.THEME_SYSTEM,
        darkMessages = p[Keys.darkMessages] ?: true,
        messageZoom = (p[Keys.messageZoom] ?: 100).coerceIn(MIN_ZOOM, MAX_ZOOM),
        instantPush = p[Keys.instantPush] ?: true,
        batteryAsked = p[Keys.batteryAsked] ?: false,
        swipeRight = SwipeAction.of(p[Keys.swipeRight], SwipeAction.READ),
        swipeLeft = SwipeAction.of(p[Keys.swipeLeft], SwipeAction.NONE),
        updateNotify = p[Keys.updateNotify] ?: true,
        updatePostponedVersion = p[Keys.updatePostponedVersion].orEmpty(),
        updatePostponedUntil = p[Keys.updatePostponedUntil] ?: 0L,
    )

    /** Сохранённый порядок + новые элементы по умолчанию, без неизвестных. */
    private fun normalize(saved: String?, defaults: List<String>): List<String> {
        val list = saved?.split(',')?.filter { it in defaults }.orEmpty()
        return list + defaults.filter { it !in list }
    }

    suspend fun setTabOrder(order: List<String>) = context.dataStore.edit { it[Keys.tabOrder] = order.joinToString(",") }
    suspend fun setGroupOrder(order: List<String>) = context.dataStore.edit { it[Keys.groupOrder] = order.joinToString(",") }
    suspend fun setFolderOrder(order: List<String>) = context.dataStore.edit { it[Keys.folderOrder] = order.joinToString(",") } // id папок — base64, запятых не содержат

    suspend fun setNotifications(enabled: Boolean) = context.dataStore.edit { it[Keys.notifications] = enabled }
    suspend fun setSyncMinutes(minutes: Int) = context.dataStore.edit { it[Keys.syncMinutes] = minutes }
    suspend fun setSignature(text: String) = context.dataStore.edit { it[Keys.signature] = text }
    suspend fun setLoadImages(enabled: Boolean) = context.dataStore.edit { it[Keys.loadImages] = enabled }
    suspend fun setInstantPush(enabled: Boolean) = context.dataStore.edit { it[Keys.instantPush] = enabled }
    suspend fun setBatteryAsked() = context.dataStore.edit { it[Keys.batteryAsked] = true }
    suspend fun setSwipeRight(action: SwipeAction) = context.dataStore.edit { it[Keys.swipeRight] = action.key }
    suspend fun setSwipeLeft(action: SwipeAction) = context.dataStore.edit { it[Keys.swipeLeft] = action.key }
    suspend fun setUpdateNotify(enabled: Boolean) = context.dataStore.edit { it[Keys.updateNotify] = enabled }

    /** Версия, о которой последний раз сообщали уведомлением, и когда. */
    suspend fun updateNotified(): Pair<String?, Long> =
        context.dataStore.data.first().let { it[Keys.updateNotified] to (it[Keys.updateNotifiedAt] ?: 0L) }
    suspend fun setUpdateNotified(version: String, at: Long) = context.dataStore.edit {
        it[Keys.updateNotified] = version
        it[Keys.updateNotifiedAt] = at
    }

    /** «Позже»: не напоминать о версии [version] до [until]. */
    suspend fun setUpdatePostponed(version: String, until: Long) = context.dataStore.edit {
        it[Keys.updatePostponedVersion] = version
        it[Keys.updatePostponedUntil] = until
    }

    /** Есть ли сохранённая учётная запись (без расшифровки пароля). */
    suspend fun hasAccount(): Boolean = context.dataStore.data.first().let { it[Keys.login] != null && it[Keys.secret] != null }

    suspend fun setTheme(theme: String) = context.dataStore.edit { it[Keys.theme] = theme }
    suspend fun setDarkMessages(enabled: Boolean) = context.dataStore.edit { it[Keys.darkMessages] = enabled }
    suspend fun setMessageZoom(percent: Int) = context.dataStore.edit { it[Keys.messageZoom] = percent.coerceIn(MIN_ZOOM, MAX_ZOOM) }

    suspend fun lastNotified(): Long = context.dataStore.data.first()[Keys.lastNotified] ?: 0L
    suspend fun setLastNotified(value: Long) = context.dataStore.edit { it[Keys.lastNotified] = value }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey {
        (keyStore().getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(cipher.iv + ct)
    }

    private fun decrypt(encoded: String): String {
        val bytes = Base64.getDecoder().decode(encoded)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        }
        return String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }

    companion object {
        const val MIN_ZOOM = 70
        const val MAX_ZOOM = 200
        private val THEMES = setOf(AppSettings.THEME_SYSTEM, AppSettings.THEME_LIGHT, AppSettings.THEME_DARK)
        private const val KEY_ALIAS = "exmail_credentials"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
