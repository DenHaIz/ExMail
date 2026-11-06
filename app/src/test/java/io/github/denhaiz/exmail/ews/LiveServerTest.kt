package io.github.denhaiz.exmail.ews

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import io.github.denhaiz.exmail.auth.NtlmCredentials
import java.time.Duration
import java.time.Instant

/**
 * Проверка против реального сервера. Пропускается, если не заданы переменные окружения:
 *   EXMAIL_USER (user@example.com или DOMAIN\user), EXMAIL_PASSWORD,
 *   EXMAIL_URL (адрес EWS), EXMAIL_SEND_TEST=1 — отправить письмо самому себе.
 */
class LiveServerTest {
    private val user = System.getenv("EXMAIL_USER").orEmpty()
    private val password = System.getenv("EXMAIL_PASSWORD").orEmpty()

    @Test
    fun liveSmokeTest(): Unit = runBlocking {
        val url = System.getenv("EXMAIL_URL").orEmpty()
        assumeTrue(
            "EXMAIL_USER/EXMAIL_PASSWORD/EXMAIL_URL не заданы — пропуск",
            user.isNotBlank() && password.isNotBlank() && url.isNotBlank(),
        )
        val session = EwsSession().apply { config = EwsConfig(url, NtlmCredentials.fromLogin(user, password)) }
        val client = EwsClient(EwsClient.createHttpClient(session), session, { "Russian Standard Time" })

        val folders = client.getWellKnownFolders()
        println("Стандартные папки: ${folders.keys}")
        assertTrue("inbox" in folders)

        val all = client.findMailFolders()
        println("Почтовых папок: ${all.size}: ${all.take(10).map { it.displayName }}")

        val page = client.findMessages("inbox", 0, 5)
        println("Входящие: всего ${page.total}")
        page.items.forEach { println("  ${it.received} | ${it.from?.display} | ${it.subject}") }
        page.items.firstOrNull()?.let {
            val d = client.getMessage(it.ref.id)
            println("Первое письмо: тело ${d.bodyHtml.length} симв., вложений ${d.attachments.size}")
        }

        val now = Instant.now()
        val events = client.findEvents(now, now.plus(Duration.ofDays(7)))
        println("Событий на неделю: ${events.size}")
        events.take(5).forEach { println("  ${it.start} ${it.subject} @ ${it.location}") }

        val login = user.substringAfter('\\').substringBefore('@')
        val me = client.resolveNames(login)
        println("ResolveNames('$login'): ${me.take(3).map { "${it.displayName} <${it.email}>" }}")

        val contacts = client.findContacts(0, 20)
        println("Личных контактов (первая страница): ${contacts.first.size}")

        // Мгновенная доставка: сервер должен принять streaming-подписку и открыть поток (ждём 1 минуту)
        val pushClient = EwsClient(EwsClient.createHttpClient(session, readTimeoutSeconds = 120), session, affinity = true)
        val subscription = pushClient.subscribeInbox()
        println("Подписка на новые письма: ${subscription.take(16)}…")
        var newMail = 0
        pushClient.listen(subscription, minutes = 1) { newMail++ }
        println("Поток событий закрыт сервером штатно, новых писем за минуту: $newMail")

        me.firstOrNull()?.email?.let { email ->
            val avail = client.getAvailability(listOf(email), now, now.plus(Duration.ofDays(1)))
            println("Занятость $email: ${avail.firstOrNull()?.mergedFreeBusy}")
            println("Фото: ${client.getUserPhoto(email)?.size ?: 0} байт")
            if (System.getenv("EXMAIL_SEND_TEST") == "1") {
                client.sendMessage(OutgoingMessage(listOf(email), subject = "ExMail: тестовое письмо", bodyHtml = "<p>Проверка отправки из ExMail</p>"))
                println("Тестовое письмо отправлено на $email")
            }
        }
    }
}
