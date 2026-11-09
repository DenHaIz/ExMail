package io.github.denhaiz.exmail

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import io.github.denhaiz.exmail.data.MailCache
import io.github.denhaiz.exmail.di.ApplicationScope
import io.github.denhaiz.exmail.sync.Notifications
import io.github.denhaiz.exmail.sync.UpdateChecker
import io.github.denhaiz.exmail.update.AppUpdater
import javax.inject.Inject

@HiltAndroidApp
class ExMailApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var mailCache: MailCache

    @Inject
    lateinit var store: io.github.denhaiz.exmail.data.AccountStore

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    @Inject
    lateinit var updater: AppUpdater

    @Inject
    lateinit var updateChecker: UpdateChecker

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        // Письма и картинки старше двух недель — из кэша
        // Срок хранения из настроек; первое значение — заодно удаление устаревшего при запуске
        scope.launch(Dispatchers.IO) {
            store.settings.map { it.cacheDays }.distinctUntilChanged().collect { days ->
                runCatching { mailCache.setRetention(days) }
                runCatching { mailCache.trim() }
            }
        }
        // Обновления: старые загрузки удаляются; проверка — при открытии приложения (AppNav) и раз в 12 часов в фоне.
        // Не при каждом запуске процесса: его запускают и фоновые задачи, а GitHub без входа отвечает 60 раз в час на адрес
        scope.launch(Dispatchers.IO) { runCatching { updater.cleanup() } }
        runCatching { updateChecker.schedule() }
    }
}
