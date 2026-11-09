package io.github.denhaiz.exmail

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Looper
import android.webkit.WebSettings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import io.github.denhaiz.exmail.data.AccountStore
import io.github.denhaiz.exmail.data.AppSettings
import io.github.denhaiz.exmail.data.MailImages
import io.github.denhaiz.exmail.data.repo.AccountRepository
import io.github.denhaiz.exmail.data.repo.AccountState
import io.github.denhaiz.exmail.data.repo.ContactsRepository
import io.github.denhaiz.exmail.sync.NewMailChecker
import io.github.denhaiz.exmail.sync.PushController
import io.github.denhaiz.exmail.sync.Notifications
import io.github.denhaiz.exmail.ui.AppNav
import io.github.denhaiz.exmail.ui.common.LocalPhotoLoader
import io.github.denhaiz.exmail.ui.common.LocalWebImages
import io.github.denhaiz.exmail.ui.common.PhotoLoader
import io.github.denhaiz.exmail.ui.login.LoginScreen
import io.github.denhaiz.exmail.ui.theme.ExMailTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var accounts: AccountRepository
    @Inject lateinit var contacts: ContactsRepository
    @Inject lateinit var checker: NewMailChecker
    @Inject lateinit var store: AccountStore
    @Inject lateinit var push: PushController
    @Inject lateinit var mailImages: MailImages

    private var openMessageId by mutableStateOf<String?>(null)
    private var openUpdate by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Письмо из уведомления открываем один раз: при повороте экрана или смене темы — не повторно
        if (savedInstanceState == null) {
            openMessageId = intent?.takeMessageId()
            openUpdate = intent?.takeOpenUpdate() == true
        }

        // Движок WebView загружается при первом открытом письме (до полсекунды) — загружаем заранее, когда приложение простаивает
        Looper.myQueue().addIdleHandler {
            runCatching { WebSettings.getDefaultUserAgent(this) }
            false
        }

        val photos = object : PhotoLoader {
            override fun cached(email: String): Bitmap? = contacts.cachedPhoto(email)
            override suspend fun load(email: String): Bitmap? = contacts.photo(email)
        }

        setContent {
            val settings by store.settings.collectAsState(initial = null)
            val dark = when (settings?.theme) {
                AppSettings.THEME_LIGHT -> false
                AppSettings.THEME_DARK -> true
                else -> isSystemInDarkTheme()
            }
            // Цвет значков строки состояния и навигации — по теме приложения, а не системы
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose {}
            }
            ExMailTheme(dark = dark) {
                CompositionLocalProvider(LocalPhotoLoader provides photos, LocalWebImages provides mailImages) {
                    val state by accounts.state.collectAsState()
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        when (state) {
                            AccountState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                            AccountState.LoggedOut -> LoginScreen()
                            is AccountState.LoggedIn -> AppNav(
                                openMessageId,
                                onMessageOpened = { openMessageId = null },
                                openUpdate = openUpdate,
                                onUpdateOpened = { openUpdate = false },
                            )
                        }
                    }
                }
            }
        }

        // Приложение на экране: запускаем мгновенную доставку (из фона Android 12+ запуск запрещён),
        // а при смене настроек или входе/выходе — включаем или выключаем её
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(store.settings, accounts.state) { s, a -> listOf(s.notifications, s.instantPush, s.pushAsked, a is AccountState.LoggedIn) }
                    .distinctUntilChanged()
                    .collect { push.sync() }
            }
        }

        // Пока приложение на экране — проверяем «Входящие» каждую минуту
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    if (accounts.state.value is AccountState.LoggedIn) runCatching { checker.check(notify = false) }
                    delay(60_000)
                }
            }
        }
    }

    private companion object {
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.takeMessageId()?.let { openMessageId = it }
        if (intent.takeOpenUpdate()) openUpdate = true
    }

    /** Нажато уведомление о новой версии; флаг убирается из intent, чтобы не открыть настройки ещё раз. */
    private fun Intent.takeOpenUpdate(): Boolean =
        !fromRecents() && getBooleanExtra(Notifications.EXTRA_OPEN_UPDATE, false).also { removeExtra(Notifications.EXTRA_OPEN_UPDATE) }

    /** Открыто из «Недавних»: Android повторяет исходный intent уведомления — его действие уже выполнено. */
    private fun Intent.fromRecents() = flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0

    /** id письма из уведомления; убирается из intent, чтобы не открыть письмо ещё раз. */
    private fun Intent.takeMessageId(): String? =
        getStringExtra(Notifications.EXTRA_MESSAGE_ID)?.also { removeExtra(Notifications.EXTRA_MESSAGE_ID) }?.takeUnless { fromRecents() }
}
