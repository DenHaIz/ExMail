package io.github.denhaiz.exmail

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
        scope.launch(Dispatchers.IO) { runCatching { mailCache.trim() } }
        // Обновления: старые загрузки удаляются; проверка — сейчас и раз в 12 часов
        scope.launch(Dispatchers.IO) {
            runCatching { updater.cleanup() }
            runCatching { updateChecker.check() }
        }
        runCatching { updateChecker.schedule() }
    }
}
