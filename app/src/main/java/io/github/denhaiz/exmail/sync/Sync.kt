package io.github.denhaiz.exmail.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.denhaiz.exmail.MainActivity
import io.github.denhaiz.exmail.R
import io.github.denhaiz.exmail.data.AccountStore
import io.github.denhaiz.exmail.data.db.MessageDao
import kotlinx.coroutines.flow.first
import io.github.denhaiz.exmail.data.repo.AccountRepository
import io.github.denhaiz.exmail.data.repo.AccountState
import io.github.denhaiz.exmail.data.repo.MailRepository
import io.github.denhaiz.exmail.ews.EwsAuthException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    fun schedule(minutes: Int) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(minutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
        NotificationManagerCompat.from(context).cancelAll()
    }

    private companion object {
        const val WORK = "mail-sync"
    }
}

/** Проверка новых писем во «Входящих» и показ уведомлений. */
@Singleton
class NewMailChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mail: MailRepository,
    private val messages: MessageDao,
    private val store: AccountStore,
    private val accounts: dagger.Lazy<AccountRepository>,
) {
    private val lock = Mutex()

    /** @param notify false — только обновить список и отметить письма как «уже увиденные» (приложение открыто). */
    suspend fun check(notify: Boolean) = lock.withLock { checkLocked(notify) }

    private suspend fun checkLocked(notify: Boolean) {
        // WorkManager может запустить проверку в «холодном» процессе: сначала загружаем учётную запись
        val state = accounts.get().state.first { it !is AccountState.Loading }
        if (state !is AccountState.LoggedIn) return
        val inbox = mail.wellKnownFolder("inbox") ?: return
        mail.refresh(inbox.id)
        val last = store.lastNotified()
        val newest = messages.newest(inbox.id) ?: return
        if (last == 0L || !notify) {
            store.setLastNotified(maxOf(last, newest))
            return
        }
        val fresh = messages.unreadSince(inbox.id, last)
        if (fresh.isNotEmpty() && store.currentSettings().notifications) Notifications.showNewMail(context, fresh)
        store.setLastNotified(maxOf(last, newest))
    }
}

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val checker: NewMailChecker,
    private val push: PushController,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        checker.check(notify = !AppVisibility.isVisible())
        // Если система остановила мгновенную доставку — пробуем запустить снова (из фона Android может не разрешить)
        push.sync()
        Result.success()
    } catch (_: EwsAuthException) {
        Result.failure()
    } catch (_: Exception) {
        Result.retry()
    }
}

/**
 * Проверка новой версии приложения: при запуске процесса и раз в 12 часов в фоне. Уведомление — не чаще раза в 3 дня
 * и не во время отсрочки «Позже»; пока приложение на экране, вместо уведомления показывается окно (AppNav).
 */
@Singleton
class UpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val updater: io.github.denhaiz.exmail.update.AppUpdater,
    private val store: AccountStore,
) {
    private val lock = Mutex()

    fun schedule() {
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(UPDATE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** [notify] = false — только обновить состояние (при запуске приложения окно покажет AppNav). */
    suspend fun check(notify: Boolean) = lock.withLock {
        val release = updater.latest()
        updater.offer(release)
        if (!notify || AppVisibility.isVisible()) return@withLock
        // Уже загружается или загружено — «доступна новая версия» не к месту
        val st = updater.state.value
        if (st is io.github.denhaiz.exmail.update.UpdateState.Downloading || st is io.github.denhaiz.exmail.update.UpdateState.Ready) return@withLock
        if (release == null || !io.github.denhaiz.exmail.update.AppUpdater.isNewer(release.version, io.github.denhaiz.exmail.BuildConfig.VERSION_NAME)) return@withLock
        val now = System.currentTimeMillis()
        val s = store.currentSettings()
        if (!s.updateNotify || s.updatePostponed(release.version, now)) return@withLock
        val (version, at) = store.updateNotified()
        if (version == release.version && now - at < REMIND_MILLIS) return@withLock
        Notifications.showUpdate(context, release)
        store.setUpdateNotified(release.version, now)
    }

    companion object {
        private const val UPDATE_WORK = "app-update"

        /** Повторное напоминание и отсрочка «Позже» — 3 дня. */
        const val REMIND_MILLIS = 3 * 24 * 60 * 60 * 1000L
    }
}

@HiltWorker
class UpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val checker: UpdateChecker,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        checker.check(notify = true)
        Result.success()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: io.github.denhaiz.exmail.update.UpdateException) {
        Result.success() // ответ сервера (в т.ч. лимит запросов GitHub): повтор через 30 с не поможет — ждём следующей проверки
    } catch (_: Exception) {
        Result.retry()
    }
}

object Notifications {
    const val CHANNEL_MAIL = "new_mail"
    const val CHANNEL_SERVICE = "connection"
    const val CHANNEL_UPDATE = "app_update"
    private const val ID_UPDATE = 0x5E8
    const val ID_SERVICE = 0x5E7
    const val EXTRA_MESSAGE_ID = "message_id"
    /** Открыть настройки с предложением обновиться (уведомление о новой версии). */
    const val EXTRA_OPEN_UPDATE = "open_update"
    private const val GROUP = "io.github.denhaiz.exmail.NEW_MAIL"

    fun createChannels(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_MAIL,
            context.getString(R.string.channel_new_mail),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.channel_new_mail_desc) }
        val service = NotificationChannel(
            CHANNEL_SERVICE,
            "Подключение к почте",
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = "Значок, пока включена мгновенная доставка писем. Можно скрыть — доставка продолжит работать."
            setShowBadge(false)
        }
        val update = NotificationChannel(
            CHANNEL_UPDATE,
            "Обновления приложения",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "Сообщение о новой версии ExMail" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(channel, service, update))
    }

    /** «Доступна новая версия»: нажатие открывает настройки с предложением обновиться. */
    fun showUpdate(context: Context, release: io.github.denhaiz.exmail.update.AppRelease, ready: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_UPDATE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(
            context, ID_UPDATE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notes = io.github.denhaiz.exmail.update.AppUpdater.plainNotes(release.notes)
        val n = NotificationCompat.Builder(context, CHANNEL_UPDATE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (ready) "ExMail ${release.version} загружена" else "Доступна новая версия ExMail ${release.version}")
            .setContentText(if (ready) "Нажмите, чтобы установить" else "Нажмите, чтобы обновить")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(((if (ready) "Нажмите, чтобы установить." else "Нажмите, чтобы обновить.") + "\n\n" + notes).take(600)),
            )
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID_UPDATE, n) }
    }

    fun cancelUpdate(context: Context) = NotificationManagerCompat.from(context).cancel(ID_UPDATE)

    fun showNewMail(context: Context, items: List<io.github.denhaiz.exmail.data.db.MessageEntity>) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = NotificationManagerCompat.from(context)
        items.take(5).forEach { m ->
            val intent = Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_MESSAGE_ID, m.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(
                context, m.id.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n = NotificationCompat.Builder(context, CHANNEL_MAIL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(m.fromName.ifBlank { m.fromEmail })
                .setContentText(m.subject.ifBlank { "(без темы)" })
                .setStyle(NotificationCompat.BigTextStyle().bigText(m.subject + "\n" + m.preview))
                .setWhen(m.receivedAt)
                .setShowWhen(true)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setGroup(GROUP)
                .setCategory(NotificationCompat.CATEGORY_EMAIL)
                .build()
            runCatching { nm.notify(m.id.hashCode(), n) }
        }
        if (items.size > 1) {
            val summary = NotificationCompat.Builder(context, CHANNEL_MAIL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Новых писем: ${items.size}")
                .setGroup(GROUP)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .build()
            runCatching { nm.notify(0, summary) }
        }
    }
}
