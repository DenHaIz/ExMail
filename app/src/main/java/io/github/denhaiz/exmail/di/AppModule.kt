package io.github.denhaiz.exmail.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import io.github.denhaiz.exmail.data.db.AppDatabase
import io.github.denhaiz.exmail.ews.EwsClient
import io.github.denhaiz.exmail.ews.EwsSession
import io.github.denhaiz.exmail.ews.WindowsZones
import java.util.TimeZone
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/** Отдельный EWS-клиент (своё соединение) для фоновых мелочей вроде фото, чтобы не задерживать почту. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SecondaryClient

/** EWS-клиент мгновенной доставки: своё соединение, ответ сервера ждёт до получаса. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PushClient

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun session(): EwsSession = EwsSession()

    @Provides
    @Singleton
    fun httpClient(session: EwsSession): OkHttpClient = EwsClient.createHttpClient(session)

    @Provides
    @Singleton
    fun ewsClient(http: OkHttpClient, session: EwsSession): EwsClient =
        EwsClient(http, session, windowsTimeZoneId = ::windowsTimeZone)

    private var cachedZone: Pair<String, String?>? = null

    /** Пояс Windows для текущего пояса телефона (пересчитывается, если пользователь сменил пояс). */
    @Synchronized
    private fun windowsTimeZone(): String? {
        val tz = TimeZone.getDefault()
        cachedZone?.takeIf { it.first == tz.id }?.let { return it.second }
        val resolved = WindowsZones.resolve(
            ianaId = tz.id,
            toWindows = { android.icu.util.TimeZone.getWindowsID(it) },
            canonical = { android.icu.util.TimeZone.getCanonicalID(it) },
            offsetMinutes = tz.getOffset(System.currentTimeMillis()) / 60_000,
            observesDst = tz.useDaylightTime(),
        )
        cachedZone = tz.id to resolved
        return resolved
    }

    @Provides
    @Singleton
    @SecondaryClient
    fun secondaryEwsClient(session: EwsSession): EwsClient =
        EwsClient(EwsClient.createHttpClient(session), session)

    @Provides
    @Singleton
    @PushClient
    fun pushEwsClient(session: EwsSession): EwsClient =
        EwsClient(EwsClient.createHttpClient(session, readTimeoutSeconds = 40 * 60), session, affinity = true)

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "exmail.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun folderDao(db: AppDatabase) = db.folders()
    @Provides fun messageDao(db: AppDatabase) = db.messages()
    @Provides fun eventDao(db: AppDatabase) = db.events()
    @Provides fun contactDao(db: AppDatabase) = db.contacts()
}
