package io.github.denhaiz.exmail.data.repo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import io.github.denhaiz.exmail.data.db.ContactDao
import io.github.denhaiz.exmail.data.db.ContactEntity
import io.github.denhaiz.exmail.data.db.MessageDao
import io.github.denhaiz.exmail.ews.ContactInfo
import io.github.denhaiz.exmail.di.SecondaryClient
import io.github.denhaiz.exmail.ews.EwsClient
import io.github.denhaiz.exmail.ews.createContact
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactsRepository @Inject constructor(
    private val client: EwsClient,
    @SecondaryClient private val photoClient: EwsClient,
    private val dao: ContactDao,
    private val messageDao: MessageDao,
    @ApplicationContext private val context: Context,
) {
    private val photoMemory = LruCache<String, Bitmap>(120)
    private val missingPhotos = HashSet<String>()
    private val photoMutex = Mutex()
    private val photoDir = File(context.cacheDir, "photos")

    fun contacts(): Flow<List<ContactEntity>> = dao.observeAll()

    suspend fun personalById(id: String): ContactEntity? = dao.byId(id)

    suspend fun personalByEmail(email: String): ContactEntity? = dao.byEmail(email)

    suspend fun create(contact: ContactInfo) {
        client.createContact(contact)
        refresh()
    }

    suspend fun delete(id: String) {
        client.deleteItems(listOf(io.github.denhaiz.exmail.ews.ItemRef(id)))
        dao.delete(id)
    }

    /** Поиск для окна адресной книги: пустой запрос — личные контакты, иначе адресная книга + личные. */
    suspend fun pickerSearch(query: String): List<ContactInfo> {
        val q = query.trim()
        if (q.isEmpty()) {
            return dao.search("%").map { ContactInfo(it.id, it.displayName, it.email, jobTitle = it.jobTitle, company = it.company) }
                .filter { it.email.isNotBlank() }
        }
        val local = dao.search("%$q%").map { ContactInfo(it.id, it.displayName, it.email, jobTitle = it.jobTitle, company = it.company) }
        val remote = if (q.length >= 2) client.resolveNames(q, includePersonal = false) else emptyList()
        return (remote + local).filter { it.email.isNotBlank() }.distinctBy { it.email.lowercase() }
    }

    suspend fun refresh() {
        val all = mutableListOf<ContactInfo>()
        var offset = 0
        while (true) {
            val (page, isLast) = client.findContacts(offset, 500)
            all += page
            offset += page.size
            if (isLast || page.isEmpty() || offset > 5000) break
        }
        dao.replaceAll(
            all.filter { it.id != null }.map {
                ContactEntity(
                    id = it.id!!,
                    displayName = it.displayName.ifBlank { it.email },
                    email = it.email,
                    phones = it.phones.joinToString("\n") { (k, v) -> "$k=$v" },
                    company = it.company,
                    department = it.department,
                    jobTitle = it.jobTitle,
                    office = it.office,
                )
            },
        )
    }

    /** Поиск сотрудников в адресной книге организации. */
    /** Поиск в адресной книге; записи без адреса и повторы убираются (адрес — ключ строки в списке). */
    suspend fun searchDirectory(query: String): List<ContactInfo> =
        if (query.trim().length < 2) emptyList()
        else client.resolveNames(query).filter { it.email.isNotBlank() }.distinctBy { it.email.lowercase() }

    /** Карточка сотрудника по e-mail (из адресной книги). */
    suspend fun lookup(email: String): ContactInfo? {
        val found = client.resolveNames(email, includePersonal = true)
        return found.firstOrNull { it.email.equals(email, true) } ?: found.firstOrNull()
    }

    /** Подсказки получателей: личные контакты, недавние отправители, затем адресная книга. */
    suspend fun suggest(query: String): List<ContactInfo> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val like = "%$q%"
        val local = dao.search(like).map { ContactInfo(it.id, it.displayName, it.email) } +
            messageDao.recentSenders(like).map { ContactInfo(null, it.fromName.ifBlank { it.fromEmail }, it.fromEmail) }
        val remote = if (q.length >= 2) runCatching { client.resolveNames(q, includePersonal = true) }.getOrDefault(emptyList()) else emptyList()
        return (local + remote).filter { it.email.isNotBlank() }.distinctBy { it.email.lowercase() }.take(8)
    }

    fun cachedPhoto(email: String): Bitmap? = photoMemory.get(email.lowercase())

    fun clearCache() {
        photoMemory.evictAll()
        synchronized(missingPhotos) { missingPhotos.clear() }
    }

    /**
     * Фото сотрудника: память → диск → сервер. С диска читается сразу, без очереди к серверу, поэтому
     * при прокрутке списка уже известные фото появляются мгновенно. «Фото нет» тоже запоминается на диске
     * (на [NO_PHOTO_DAYS] дней), чтобы не спрашивать сервер о каждом внешнем отправителе при каждом запуске.
     */
    suspend fun photo(email: String): Bitmap? {
        val key = email.lowercase()
        if (key.isBlank()) return null
        photoMemory.get(key)?.let { return it }
        if (synchronized(missingPhotos) { key in missingPhotos }) return null
        val file = File(photoDir, sha1(key))
        val none = File(photoDir, sha1(key) + ".none")
        withContext(Dispatchers.IO) {
            when {
                file.exists() -> decode(file.readBytes()).also { if (it === NO_PHOTO) file.delete() } // испорченный файл — загрузим заново
                none.exists() && System.currentTimeMillis() - none.lastModified() < NO_PHOTO_MILLIS -> NO_PHOTO
                else -> null
            }
        }?.let { return memo(key, it) }
        return photoMutex.withLock {
            photoMemory.get(key)?.let { return@withLock it }
            val bytes = try {
                photoClient.getUserPhoto(email)
            } catch (e: CancellationException) {
                throw e // экран закрыт или список прокручен — это не «фото нет»
            } catch (_: IOException) {
                return@withLock null // нет связи — попробуем в следующий раз, не запоминаем «фото нет»
            } catch (_: Exception) {
                // Сервер занят, ошибка входа — не «фото нет»: до перезапуска не спрашиваем, на диск не пишем
                synchronized(missingPhotos) { missingPhotos += key }
                return@withLock null
            }
            val bmp = bytes?.let { decode(it) }
            withContext(Dispatchers.IO) {
                runCatching {
                    photoDir.mkdirs()
                    if (bmp != null && bmp !== NO_PHOTO && bytes != null) {
                        val tmp = File.createTempFile(file.name, ".tmp", photoDir)
                        tmp.writeBytes(bytes)
                        if (!tmp.renameTo(file)) tmp.delete()
                    } else {
                        none.writeBytes(ByteArray(0))
                    }
                }
            }
            memo(key, bmp ?: NO_PHOTO)
        }
    }

    private fun memo(key: String, bmp: Bitmap): Bitmap? {
        if (bmp === NO_PHOTO) {
            synchronized(missingPhotos) { missingPhotos += key }
            return null
        }
        photoMemory.put(key, bmp)
        return bmp
    }

    private suspend fun decode(bytes: ByteArray): Bitmap? =
        withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } ?: NO_PHOTO

    private companion object {
        const val NO_PHOTO_DAYS = 7L
        val NO_PHOTO_MILLIS = NO_PHOTO_DAYS * 24 * 3600 * 1000

        /** Отметка «у сотрудника нет фото». */
        val NO_PHOTO: Bitmap by lazy { Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8) }
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
