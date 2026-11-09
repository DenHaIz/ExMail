package io.github.denhaiz.exmail.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.denhaiz.exmail.update.AppRelease
import io.github.denhaiz.exmail.update.AppUpdater
import io.github.denhaiz.exmail.update.UpdateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Имена файлов из писем и адрес загрузки обновления. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SecurityTest {

    @Test
    fun safeName_neverLeavesFolder() {
        assertEquals("file", FileSaver.safeName(".."))
        assertEquals("file", FileSaver.safeName(" . "))
        assertEquals(".._.._etc_passwd", FileSaver.safeName("../../etc/passwd"))
        assertEquals("a_b.pdf", FileSaver.safeName("a\\b.pdf"))
        assertEquals("Отчёт.docx", FileSaver.safeName("Отчёт.docx"))
        // Длинное имя укорачивается, расширение остаётся
        val long = FileSaver.safeName("а".repeat(300) + ".pdf")
        assertEquals(120, long.length)
        assertTrue(long.endsWith(".pdf"))
    }

    @Test
    fun update_refusesInsecureDownloadUrl() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val updater = AppUpdater(ApplicationProvider.getApplicationContext(), scope)
            val release = AppRelease("9.9.9", "", "http://example.com/ExMail.apk", 1)
            updater.sourcesForTests = listOf(release.apkUrl)
            updater.download(release)
            val s = updater.state.value
            assertTrue(s is UpdateState.Failed)
            assertEquals("Адрес загрузки небезопасен", (s as UpdateState.Failed).message)
        } finally {
            scope.cancel()
        }
    }
}
