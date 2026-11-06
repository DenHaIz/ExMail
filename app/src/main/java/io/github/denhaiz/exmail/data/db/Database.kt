package io.github.denhaiz.exmail.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: String,
    val parentId: String?,
    val name: String,
    val wellKnown: String?,
    val unread: Int,
    val total: Int,
    val depth: Int,
    val position: Int,
)

@Entity(tableName = "messages", indices = [Index(value = ["folderId", "receivedAt"])])
data class MessageEntity(
    @PrimaryKey val id: String,
    val changeKey: String?,
    val folderId: String,
    val subject: String,
    val fromName: String,
    val fromEmail: String,
    val displayTo: String,
    val preview: String,
    val receivedAt: Long,
    val isRead: Boolean,
    val hasAttachments: Boolean,
    val highImportance: Boolean,
    val flagged: Boolean,
    val itemClass: String,
) {
    val isMeetingRequest: Boolean get() = itemClass.startsWith("IPM.Schedule.Meeting.Request", true)
}

@Entity(tableName = "events", indices = [Index("start"), Index("end")])
data class EventEntity(
    @PrimaryKey val id: String,
    val changeKey: String?,
    val subject: String,
    val start: Long,
    val end: Long,
    val location: String,
    val isAllDay: Boolean,
    val organizerName: String,
    val organizerEmail: String,
    val myResponse: String?,
    val isCancelled: Boolean,
    val isMeeting: Boolean,
    val isRecurring: Boolean,
    val freeBusy: String?,
)

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val email: String,
    /** Строки вида `Ключ=номер`, разделённые переводом строки. */
    val phones: String,
    val company: String,
    val department: String,
    val jobTitle: String,
    val office: String,
)

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY position")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders")
    suspend fun all(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE wellKnown = :name LIMIT 1")
    suspend fun byWellKnown(name: String): FolderEntity?

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun byId(id: String): FolderEntity?

    @Query("DELETE FROM folders")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<FolderEntity>)

    @Transaction
    suspend fun replaceAll(list: List<FolderEntity>) {
        clear()
        insertAll(list)
    }

    @Query("UPDATE folders SET unread = :unread WHERE id = :id")
    suspend fun setUnread(id: String, unread: Int)

    @Query("UPDATE folders SET unread = MAX(0, unread + :delta) WHERE id = :id")
    suspend fun adjustUnread(id: String, delta: Int)
}

@Dao
interface MessageDao {
    // Порядок совпадает с перелистыванием письма свайпом (newerThan/olderThan)
    @Query("SELECT * FROM messages WHERE folderId = :folderId ORDER BY receivedAt DESC, id DESC")
    fun observeFolder(folderId: String): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM messages WHERE folderId = :folderId")
    suspend fun count(folderId: String): Int

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun byId(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE folderId = :folderId AND isRead = 0 AND receivedAt > :since ORDER BY receivedAt DESC")
    suspend fun unreadSince(folderId: String, since: Long): List<MessageEntity>

    @Query("SELECT MAX(receivedAt) FROM messages WHERE folderId = :folderId")
    suspend fun newest(folderId: String): Long?

    @Query("SELECT MIN(receivedAt) FROM messages WHERE folderId = :folderId")
    suspend fun oldest(folderId: String): Long?

    @Query(
        "SELECT id FROM messages WHERE folderId = :folderId AND (receivedAt > :at OR (receivedAt = :at AND id > :id)) " +
            "ORDER BY receivedAt ASC, id ASC LIMIT 1",
    )
    suspend fun newerThan(folderId: String, at: Long, id: String): String?

    @Query(
        "SELECT id FROM messages WHERE folderId = :folderId AND (receivedAt < :at OR (receivedAt = :at AND id < :id)) " +
            "ORDER BY receivedAt DESC, id DESC LIMIT 1",
    )
    suspend fun olderThan(folderId: String, at: Long, id: String): String?

    @Upsert
    suspend fun upsert(list: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query("DELETE FROM messages WHERE folderId = :folderId AND receivedAt >= :from AND id NOT IN (:keep)")
    suspend fun deleteMissing(folderId: String, from: Long, keep: List<String>)

    @Query("DELETE FROM messages WHERE folderId = :folderId")
    suspend fun clearFolder(folderId: String)

    @Query("UPDATE messages SET isRead = 1 WHERE folderId = :folderId")
    suspend fun markFolderRead(folderId: String)

    @Query("UPDATE messages SET isRead = :read WHERE id IN (:ids)")
    suspend fun setRead(ids: List<String>, read: Boolean)

    @Query("UPDATE messages SET flagged = :flagged WHERE id IN (:ids)")
    suspend fun setFlagged(ids: List<String>, flagged: Boolean)

    @Query(
        "SELECT * FROM messages WHERE fromEmail != '' AND (fromName LIKE :q OR fromEmail LIKE :q) " +
            "GROUP BY fromEmail ORDER BY MAX(receivedAt) DESC LIMIT 5",
    )
    suspend fun recentSenders(q: String): List<MessageEntity>
}

@Dao
interface EventDao {
    @Query("SELECT * FROM events WHERE start < :to AND `end` > :from ORDER BY isAllDay DESC, start")
    fun observeRange(from: Long, to: Long): Flow<List<EventEntity>>

    @Query("DELETE FROM events WHERE start < :to AND `end` > :from")
    suspend fun deleteRange(from: Long, to: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<EventEntity>)

    @Transaction
    suspend fun replaceRange(from: Long, to: Long, list: List<EventEntity>) {
        deleteRange(from, to)
        insertAll(list)
    }

    @Query("DELETE FROM events WHERE id = :id")
    suspend fun delete(id: String)

    /** Локально убирает вхождения удалённой серии (сервер обновит кэш при следующей загрузке). */
    @Query("DELETE FROM events WHERE isRecurring = 1 AND subject = :subject")
    suspend fun deleteBySubject(subject: String)

    @Query("UPDATE events SET myResponse = :response WHERE id = :id")
    suspend fun setResponse(id: String, response: String)
}

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY displayName COLLATE NOCASE")
    fun observeAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE displayName LIKE :q OR email LIKE :q ORDER BY displayName COLLATE NOCASE LIMIT 200")
    suspend fun search(q: String): List<ContactEntity>

    @Query("SELECT * FROM contacts WHERE id = :id")
    suspend fun byId(id: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE email = :email COLLATE NOCASE LIMIT 1")
    suspend fun byEmail(email: String): ContactEntity?

    @Query("DELETE FROM contacts WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM contacts")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<ContactEntity>)

    @Transaction
    suspend fun replaceAll(list: List<ContactEntity>) {
        clear()
        insertAll(list)
    }
}

@Database(
    entities = [FolderEntity::class, MessageEntity::class, EventEntity::class, ContactEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun folders(): FolderDao
    abstract fun messages(): MessageDao
    abstract fun events(): EventDao
    abstract fun contacts(): ContactDao
}
