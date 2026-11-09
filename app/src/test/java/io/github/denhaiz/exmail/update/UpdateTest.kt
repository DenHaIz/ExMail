package io.github.denhaiz.exmail.update

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Обновление приложения: ответ GitHub, сравнение версий, загрузка и проверка файла. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class UpdateTest {

    private val json = """
        {"tag_name":"v1.0.3","name":"ExMail 1.0.3","body":"**Обновления**\r\n- Кнопка `Обновить`.\r\n\r\nПодробности — в HANDOFF.md.",
         "assets":[
           {"name":"notes.txt","size":10,"browser_download_url":"https://example.com/notes.txt"},
           {"name":"ExMail-v1.0.3.apk","size":2600000,"browser_download_url":"https://github.com/DenHaIz/ExMail/releases/download/v1.0.3/ExMail-v1.0.3.apk"}
         ]}
    """.trimIndent()

    @Test
    fun parseRelease_takesApkAsset() {
        val r = AppUpdater.parseRelease(json)!!
        assertEquals("1.0.3", r.version)
        assertEquals(2_600_000L, r.size)
        assertTrue(r.apkUrl.endsWith("/ExMail-v1.0.3.apk"))
        assertNull(AppUpdater.parseRelease("""{"tag_name":"v2","assets":[]}"""))
    }

    @Test
    fun isNewer_anyOtherLatestVersion() {
        assertTrue(AppUpdater.isNewer("1.0.3", "1.0.2"))
        // «1.01» вышла раньше «1.0.2» — номера не сравниваются, решает versionCode загруженного файла
        assertTrue(AppUpdater.isNewer("1.0.2", "1.01"))
        assertFalse(AppUpdater.isNewer("v1.0.2", "1.0.2"))
        assertFalse(AppUpdater.isNewer("", "1.0.2"))
    }

    @Test
    fun plainNotes_withoutMarkdown() {
        assertEquals("Обновления\n• Кнопка Обновить.", AppUpdater.plainNotes(AppUpdater.parseRelease(json)!!.notes))
    }

    @Test
    fun download_rejectsFileThatIsNotTheApp() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(300_000) { 7 })))
        server.start()
        val context = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob())
        try {
            val updater = AppUpdater(context, scope).apply { insecureForTests = true }
            val release = AppRelease("9.9.9", "", server.url("/ExMail.apk").toString(), 300_000)
            updater.sourcesForTests = listOf(release.apkUrl)
            updater.download(release)
            val end = withTimeout(10_000) {
                updater.state.first { it is UpdateState.Failed || it is UpdateState.Ready }
            }
            // Не APK (или чужое приложение) до установщика не доходит: понятная ошибка, файл удалён
            assertTrue(end is UpdateState.Failed)
            assertEquals("Загруженный файл повреждён", (end as UpdateState.Failed).message)
            assertEquals(release, end.release)
            val dir = File(context.cacheDir, "updates")
            assertTrue(dir.listFiles().orEmpty().isEmpty())
            assertEquals("ExMail/", server.takeRequest().getHeader("User-Agent")!!.substringBefore('/') + "/")
        } finally {
            scope.cancel()
            server.shutdown()
        }
    }
}
