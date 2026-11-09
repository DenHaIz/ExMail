package io.github.denhaiz.exmail.sync

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import io.github.denhaiz.exmail.MainActivity
import io.github.denhaiz.exmail.R
import io.github.denhaiz.exmail.data.AccountStore
import io.github.denhaiz.exmail.data.repo.AccountRepository
import io.github.denhaiz.exmail.data.repo.AccountState
import io.github.denhaiz.exmail.di.ApplicationScope
import io.github.denhaiz.exmail.di.PushClient
import io.github.denhaiz.exmail.ews.EwsAuthException
import io.github.denhaiz.exmail.ews.EwsClient
import io.github.denhaiz.exmail.ews.EwsException
import io.github.denhaiz.exmail.ews.listen
import io.github.denhaiz.exmail.ews.subscribeInbox
import javax.inject.Inject
import javax.inject.Singleton

/** Открыто ли приложение на экране (тогда уведомления о новых письмах не нужны — список обновляется сам). */
object AppVisibility {
    fun isVisible(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}

/**
 * Мгновенная доставка: подписка Exchange на новые письма во «Входящих» (EWS streaming).
 * Сервер держит соединение открытым и сам сообщает о новом письме; телефон в это время спит.
 * При обрыве связи — переподключение с нарастающей паузой, при появлении сети — сразу.
 */
@Singleton
class MailPush @Inject constructor(
    @PushClient private val client: EwsClient,
    private val checker: NewMailChecker,
    private val accounts: dagger.Lazy<AccountRepository>,
) {
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Появилась сеть — не ждать окончания паузы. */
    fun kick() {
        wake.trySend(Unit)
    }

    suspend fun run() {
        val state = accounts.get().state.first { it !is AccountState.Loading }
        if (state !is AccountState.LoggedIn) return
        var pause = MIN_PAUSE
        var subscription: String? = null
        while (currentCoroutineContext().isActive) {
            try {
                // Догоняем то, что пришло, пока подписки не было (запуск, обрыв связи)
                checkNow()
                val id = subscription ?: client.subscribeInbox().also { subscription = it }
                val listenStarted = android.os.SystemClock.elapsedRealtime()
                client.listen(id) { checkNow() }
                if (android.os.SystemClock.elapsedRealtime() - listenStarted >= EARLY_CLOSE_MILLIS) {
                    pause = MIN_PAUSE // сервер штатно закрыл соединение через ~30 минут — сразу переподключаемся
                } else {
                    // Соединение закрылось слишком рано (прокси обрывает долгие запросы): без паузы это был бы
                    // бесконечный цикл обновлений и переподключений, разряжающий батарею
                    sleep(pause)
                    pause = (pause * 2).coerceAtMost(MAX_PAUSE)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: EwsAuthException) {
                subscription = null
                sleep(AUTH_PAUSE) // пароль сменили — повторяем редко, чтобы не заблокировать учётную запись
            } catch (_: EwsException) {
                subscription = null // подписка истекла или не найдена — создаём новую
                sleep(pause)
                pause = (pause * 2).coerceAtMost(MAX_PAUSE)
            } catch (_: Exception) {
                sleep(pause) // нет сети: подписка на сервере живёт ещё несколько минут, пробуем с ней
                pause = (pause * 2).coerceAtMost(MAX_PAUSE)
            }
        }
    }

    private suspend fun sleep(millis: Long) {
        withTimeoutOrNull(millis) { wake.receive() }
    }

    private suspend fun checkNow() {
        try {
            checker.check(notify = !AppVisibility.isVisible())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val MIN_PAUSE = 5_000L
        const val MAX_PAUSE = 5 * 60_000L

        /** Подписка держит соединение до ~29 минут; закрылось раньше 5 минут — считаем обрывом. */
        const val EARLY_CLOSE_MILLIS = 5 * 60_000L
        const val AUTH_PAUSE = 30 * 60_000L
    }
}

/** Фоновая служба с постоянным подключением к серверу (значок «Почта на связи» в шторке). */
@AndroidEntryPoint
class PushService : Service() {
    @Inject lateinit var push: MailPush

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var network: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!goForeground()) {
            stopSelf()
            return
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) = push.kick()
        }
        runCatching { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback) }
            .onSuccess { network = callback }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (job?.isActive != true) {
            job = scope.launch {
                push.run()
                stopSelf() // вышли из учётной записи
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        network?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        scope.cancel()
        super.onDestroy()
    }

    /** false — Android не разрешил службу переднего плана (перезапуск из фона на Android 12+): работает резервная проверка. */
    private fun goForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifications.ID_SERVICE, Notifications.serviceNotification(this), type)
        true
    } catch (_: Exception) {
        false
    }
}

/** Запуск и остановка мгновенной доставки по настройкам и состоянию входа. */
@Singleton
class PushController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: AccountStore,
    @ApplicationScope private val scope: CoroutineScope,
) {
    /** Должна ли служба работать сейчас. */
    suspend fun shouldRun(): Boolean {
        val s = store.currentSettings()
        return store.hasAccount() && s.notifications && s.instantPush &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** Запускает или останавливает службу. Из фона Android 12+ может запретить запуск — тогда сработает WorkManager. */
    suspend fun sync() {
        if (shouldRun()) start() else stop()
    }

    fun syncAsync() {
        scope.launch { sync() }
    }

    private fun start() {
        try {
            ContextCompat.startForegroundService(context, Intent(context, PushService::class.java))
        } catch (_: Exception) {
            // ForegroundServiceStartNotAllowedException / IllegalStateException: приложение в фоне
        }
    }

    fun stop() {
        context.stopService(Intent(context, PushService::class.java))
    }

    /** Не ограничивает ли Android работу приложения в фоне (режим энергосбережения для приложения). */
    fun isUnrestricted(): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** Системный запрос «Разрешить работу в фоне без ограничений». */
    fun unrestrictIntent(): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    /** Настройки уведомлений приложения в Android. */
    fun notificationSettingsIntent(): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
}

/** После перезагрузки телефона или обновления приложения снова включаем мгновенную доставку. */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var controller: PushController
    @Inject lateinit var scheduler: SyncScheduler
    @Inject lateinit var store: AccountStore

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (store.hasAccount()) scheduler.schedule(store.currentSettings().syncMinutes)
                controller.sync()
            } finally {
                pending.finish()
            }
        }
    }
}

/** Значок в шторке, пока работает мгновенная доставка. */
internal fun Notifications.serviceNotification(context: Context): Notification {
    val open = PendingIntent.getActivity(
        context, 1, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    return NotificationCompat.Builder(context, CHANNEL_SERVICE)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Почта на связи")
        .setContentText("Новые письма приходят сразу")
        .setOngoing(true)
        .setSilent(true)
        .setShowWhen(false)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(open)
        .build()
}
