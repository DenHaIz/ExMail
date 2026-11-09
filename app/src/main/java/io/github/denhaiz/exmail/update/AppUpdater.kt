package io.github.denhaiz.exmail.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import io.github.denhaiz.exmail.BuildConfig
import io.github.denhaiz.exmail.di.ApplicationScope
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Ошибка обновления с понятным пользователю текстом. */
class UpdateException(message: String) : IOException(message)

private fun Throwable.updateMessage(): String = when (this) {
    is UpdateException -> message.orEmpty()
    is UnknownHostException, is ConnectException -> "Нет подключения к интернету"
    is SocketTimeoutException -> "Сервер обновлений долго не отвечает. Попробуйте ещё раз"
    is IOException -> "Связь прервалась. Попробуйте ещё раз"
    else -> "Сервер обновлений прислал непонятный ответ"
}

/** Выпуск приложения на GitHub. */
data class AppRelease(
    /** Версия без «v»: «1.0.3». */
    val version: String,
    val notes: String,
    val apkUrl: String,
    val size: Long,
    /** Тот же файл через API GitHub (`…/releases/assets/<id>`): доступен и там, где не открывается github.com. */
    val apiUrl: String = "",
)

sealed interface UpdateState {
    /** Ещё не проверяли (с запуска приложения). */
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAt: Long) : UpdateState
    data class Available(val release: AppRelease) : UpdateState

    /** [progress] — 0..1, или −1, если размер неизвестен. */
    data class Downloading(val release: AppRelease, val progress: Float) : UpdateState
    data class Ready(val release: AppRelease, val file: File) : UpdateState
    data class Failed(val message: String, val release: AppRelease?) : UpdateState
}

/**
 * Обновление приложения из GitHub Releases: проверка последнего выпуска, загрузка APK и запуск системной установки.
 * Новая версия ставится поверх (тот же ключ подписи, versionCode больше) — учётная запись и настройки сохраняются.
 * Запросы — отдельным клиентом без учётных данных почты.
 */
@Singleton
class AppUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followSslRedirects(false) // переадресация с HTTPS на HTTP не выполняется
        .build()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Пользователь попросил обновить: после загрузки поставить (UpdateInstallHost, когда приложение на экране). */
    private val _installRequested = MutableStateFlow(false)
    val installRequested: StateFlow<Boolean> = _installRequested.asStateFlow()

    /** «Обновить»: загрузка и установка по её окончании. */
    fun start(release: AppRelease) {
        _installRequested.value = true
        download(release)
    }

    /** «Остановить». */
    fun stop() {
        _installRequested.value = false
        cancelDownload()
    }

    /** «Установить» уже загруженное. */
    fun requestInstall() {
        _installRequested.value = true
    }

    fun installHandled() {
        _installRequested.value = false
    }

    private var job: Job? = null

    /** Идущий запрос загрузки — «Остановить» закрывает соединение сразу, даже если сервер не отвечает. */
    @Volatile
    private var call: Call? = null

    /** Время последней проверки (успешной или нет); 0 — не проверяли. */
    @Volatile
    private var checkedAt = 0L

    /** Только для тестов с MockWebServer (HTTP): в приложении загрузка — только по HTTPS. */
    internal var insecureForTests = false

    /** Только для тестов: источники загрузки вместо настоящих. */
    internal var sourcesForTests: List<String>? = null
    private val dir get() = File(context.cacheDir, "updates")

    /** Последний выпуск или null, если в нём нет APK. */
    suspend fun latest(): AppRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "ExMail/${BuildConfig.VERSION_NAME}")
            .build()
        http.newCall(request).execute().use { r ->
            // GitHub без входа отвечает не чаще 60 раз в час с одного адреса
            if (r.code == 403 || r.code == 429) throw UpdateException("Слишком много проверок подряд. Попробуйте через час")
            if (r.code == 404) return@withContext null // выпусков ещё нет
            if (!r.isSuccessful) throw UpdateException("Сервер обновлений ответил ${r.code}")
            parseRelease(r.body?.string().orEmpty())
        }
    }

    /** Проверка по нажатию: результат — в [state]. Идущая загрузка не прерывается. */
    fun check() {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = UpdateState.Checking
            _state.value = try {
                result(latest())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateState.Failed(e.updateMessage(), null)
            } finally {
                checkedAt = System.currentTimeMillis()
            }
        }
    }

    /**
     * Проверка при открытии приложения, если прошлая была давно: процесс может жить днями (мгновенная доставка),
     * и проверка при его запуске устаревает. Загруженное или загружаемое обновление не трогается.
     */
    fun refreshIfStale(maxAgeMillis: Long = STALE_MILLIS) {
        val s = _state.value
        if (s is UpdateState.Downloading || s is UpdateState.Ready) return
        if (System.currentTimeMillis() - checkedAt > maxAgeMillis) check()
    }

    /** Результат фоновой проверки: показывается в настройках, если там не идёт своя работа. */
    fun offer(release: AppRelease?) {
        checkedAt = System.currentTimeMillis()
        if (job?.isActive == true) return
        val s = _state.value
        if (s is UpdateState.Ready || s is UpdateState.Downloading) return
        _state.value = result(release)
    }

    private fun result(release: AppRelease?): UpdateState =
        if (release != null && isNewer(release.version, BuildConfig.VERSION_NAME)) UpdateState.Available(release)
        else UpdateState.UpToDate(System.currentTimeMillis())

    /**
     * Загружает APK выпуска; по готовности — [UpdateState.Ready]. Источники по очереди: API GitHub, зеркало jsDelivr
     * (тег `apk-v<версия>` в репозитории), ссылка выпуска — где-то github.com или его хранилище файлов недоступны.
     * Подлинность файла от источника не зависит: проверяются пакет и versionCode, подпись — сам Android.
     */
    fun download(release: AppRelease) {
        if (job?.isActive == true) return
        // Остановленная загрузка ещё может сворачиваться (закрывается соединение) — новая начнётся после неё
        val previous = job
        val sources = (sourcesForTests ?: downloadUrls(release)).filter { it.startsWith("https://", ignoreCase = true) || insecureForTests }
        if (sources.isEmpty()) {
            _state.value = UpdateState.Failed("Адрес загрузки небезопасен", release)
            return
        }
        job = scope.launch(Dispatchers.IO) {
            previous?.join()
            val file = File(dir, "ExMail-${release.version.replace(Regex("[^0-9A-Za-z.]"), "_")}.apk")
            val part = File(dir, file.name + ".part")
            val failures = mutableListOf<String>()
            try {
                dir.mkdirs()
                var loaded = false
                for (url in sources) {
                    ensureActive()
                    _state.value = UpdateState.Downloading(release, if (release.size > 0) 0f else -1f)
                    try {
                        fetch(url, part, release)
                        loaded = true
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ensureActive() // «Остановить» закрывает соединение — это не ошибка источника
                        part.delete()
                        failures += "${url.toHttpUrlOrNull()?.host ?: url}: ${e.updateMessage()}"
                    }
                }
                if (!loaded) throw UpdateException("Не удалось загрузить (" + failures.joinToString("; ") + ")")
                file.delete()
                if (!part.renameTo(file)) throw UpdateException("Не удалось сохранить файл")
                verify(file)
                ensureActive()
                _state.value = UpdateState.Ready(release, file)
                // Загрузилось, пока приложение свёрнуто, — напоминаем уведомлением (установщик из фона не откроется)
                if (!io.github.denhaiz.exmail.sync.AppVisibility.isVisible()) {
                    io.github.denhaiz.exmail.sync.Notifications.showUpdate(context, release, ready = true)
                }
            } catch (e: CancellationException) {
                part.delete()
                _state.value = UpdateState.Available(release)
                throw e
            } catch (e: Exception) {
                part.delete()
                file.delete()
                _state.value = UpdateState.Failed(e.updateMessage(), release)
            } finally {
                call = null
            }
        }
    }

    private fun CoroutineScope.fetch(url: String, part: File, release: AppRelease) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "ExMail/${BuildConfig.VERSION_NAME}")
            // API GitHub отдаёт сам файл (через переадресацию), а не его описание
            .apply { if (url.startsWith(API_ASSETS, ignoreCase = true)) header("Accept", "application/octet-stream") }
            .build()
        val c = http.newCall(request)
        call = c
        ensureActive() // «Остановить» между источниками: новый запрос не начинаем
        c.execute().use { r ->
            if (!r.isSuccessful) throw UpdateException("ответ ${r.code}")
            val body = r.body ?: throw UpdateException("пустой ответ")
            val total = body.contentLength().takeIf { it > 0 } ?: release.size
            var done = 0L
            var shown = -1
            body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val percent = if (total > 0) (done * 100 / total).toInt() else -1
                        if (percent != shown) { // не чаще раза на процент
                            shown = percent
                            _state.value = UpdateState.Downloading(release, if (total > 0) done.toFloat() / total else -1f)
                        }
                    }
                }
            }
        }
    }

    /** «Остановить»: загрузка прерывается сразу, окно возвращается к предложению обновиться. */
    fun cancelDownload() {
        val s = _state.value as? UpdateState.Downloading ?: return
        job?.cancel()
        call?.cancel()
        _state.value = UpdateState.Available(s.release)
    }

    /** Загруженный файл — это ExMail и новее установленной: иначе установщик выдал бы непонятную ошибку. */
    private fun verify(file: File) {
        val info = context.packageManager.getPackageArchiveInfo(file.path, 0)
            ?: throw UpdateException("Загруженный файл повреждён")
        if (info.packageName != context.packageName) throw UpdateException("Загружено другое приложение")
        if (PackageInfoCompat.getLongVersionCode(info) <= BuildConfig.VERSION_CODE) {
            throw UpdateException("Установлена эта или более новая версия")
        }
    }

    /** Разрешена ли установка приложений из ExMail (Android 8+ спрашивает один раз). */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Экран Android «Установка неизвестных приложений» для ExMail. */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Системный установщик для загруженного APK. */
    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Загрузки прошлых запусков (новая версия уже установлена или отложена) — удаляются при запуске. */
    fun cleanup() {
        if (job?.isActive != true) dir.deleteRecursively()
    }

    companion object {
        private const val REPO = "DenHaIz/ExMail"
        private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
        private const val API_ASSETS = "https://api.github.com/repos/$REPO/releases/assets/"
        private const val STALE_MILLIS = 6 * 60 * 60 * 1000L

        /** Источники загрузки по порядку (см. [download]). */
        fun downloadUrls(r: AppRelease): List<String> = listOf(
            r.apiUrl,
            "https://cdn.jsdelivr.net/gh/$REPO@apk-v${r.version}/ExMail.apk",
            r.apkUrl,
        ).filter { it.isNotBlank() }.distinct()
        const val APK_MIME = "application/vnd.android.package-archive"

        /** Выпуск из ответа GitHub API; null — в выпуске нет APK. */
        fun parseRelease(json: String): AppRelease? {
            val o = JSONObject(json)
            val assets = o.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                return AppRelease(
                    version = o.optString("tag_name").removePrefix("v").trim(),
                    notes = o.optString("body"),
                    apkUrl = a.optString("browser_download_url"),
                    size = a.optLong("size"),
                    apiUrl = a.optString("url").takeIf { it.startsWith(API_ASSETS, ignoreCase = true) }.orEmpty(),
                )
            }
            return null
        }

        /**
         * Новая ли версия [latest] для установленной [installed]. Выпуск «latest» на GitHub всегда самый новый,
         * поэтому любая другая версия — новее. Сравнивать номера нельзя: «1.01» вышла раньше «1.0.2».
         * Что версия действительно новее, проверяется по versionCode загруженного файла.
         */
        fun isNewer(latest: String, installed: String): Boolean =
            latest.isNotBlank() && latest.removePrefix("v") != installed.removePrefix("v")

        /** Описание выпуска без разметки Markdown — для диалога и уведомления. */
        fun plainNotes(md: String): String = md
            .replace("\r\n", "\n")
            .replace("**", "")
            .replace("`", "")
            .lines()
            .filterNot { it.startsWith("Подробности") }
            .joinToString("\n") { line -> if (line.startsWith("- ")) "• " + line.removePrefix("- ") else line }
            .trim()
    }
}
