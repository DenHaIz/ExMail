package io.github.denhaiz.exmail.ews

import java.time.Instant

open class EwsException(val code: String, message: String) : Exception(message)

/** Сервер отклонил логин/пароль (повторный 401 после NTLM Type3). */
class EwsAuthException : EwsException("Unauthorized", "Неверный логин или пароль")

class EwsHttpException(val httpCode: Int, message: String) : EwsException("HTTP$httpCode", message)

data class ItemRef(val id: String, val changeKey: String? = null)

data class Mailbox(val name: String, val email: String) {
    val display: String get() = name.ifBlank { email }
}

data class EwsFolder(
    val id: String,
    val changeKey: String?,
    val parentId: String?,
    val displayName: String,
    val folderClass: String?,
    val totalCount: Int,
    val unreadCount: Int,
    val childCount: Int,
    val hidden: Boolean = false,
)

data class MessageSummary(
    val ref: ItemRef,
    val subject: String,
    val from: Mailbox?,
    val displayTo: String,
    val preview: String,
    val received: Instant?,
    val isRead: Boolean,
    val hasAttachments: Boolean,
    val importance: String,
    val flagged: Boolean,
    val itemClass: String,
)

data class MessagePage(val items: List<MessageSummary>, val total: Int, val isLast: Boolean)

data class AttachmentInfo(
    val id: String,
    val name: String,
    val contentType: String,
    val size: Long,
    val isInline: Boolean,
    val contentId: String?,
    val isItem: Boolean,
)

data class MeetingInfo(
    val start: Instant?,
    val end: Instant?,
    val location: String,
    val isAllDay: Boolean,
    val isCancellation: Boolean,
    val isResponse: Boolean,
    val responseType: String?,
)

data class MessageDetail(
    val ref: ItemRef,
    val subject: String,
    val from: Mailbox?,
    val to: List<Mailbox>,
    val cc: List<Mailbox>,
    val bcc: List<Mailbox>,
    val received: Instant?,
    val bodyHtml: String,
    val isRead: Boolean,
    val flagged: Boolean,
    val importance: String,
    val itemClass: String,
    val attachments: List<AttachmentInfo>,
    val meeting: MeetingInfo?,
    val parentFolderId: String?,
) {
    val isMeetingRequest: Boolean get() = itemClass.startsWith("IPM.Schedule.Meeting.Request", true)
}

data class FileAttachmentData(val name: String, val contentType: String, val bytes: ByteArray)

data class NewAttachment(val name: String, val contentType: String, val bytes: ByteArray)

data class OutgoingMessage(
    val to: List<String>,
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String,
    val bodyHtml: String,
    val highImportance: Boolean = false,
    val attachments: List<NewAttachment> = emptyList(),
)

enum class ReplyKind(val element: String) {
    REPLY("ReplyToItem"),
    REPLY_ALL("ReplyAllToItem"),
    FORWARD("ForwardItem"),
}

enum class MeetingResponse(val element: String) {
    ACCEPT("AcceptItem"),
    TENTATIVE("TentativelyAcceptItem"),
    DECLINE("DeclineItem"),
}

data class Attendee(val mailbox: Mailbox, val response: String?)

data class CalendarEvent(
    val ref: ItemRef,
    val subject: String,
    val start: Instant,
    val end: Instant,
    val location: String,
    val isAllDay: Boolean,
    val organizer: Mailbox?,
    val myResponse: String?,
    val isCancelled: Boolean,
    val isMeeting: Boolean,
    val isRecurring: Boolean,
    val freeBusy: String?,
    val isOrganizer: Boolean? = null,
    val bodyHtml: String? = null,
    val requiredAttendees: List<Attendee> = emptyList(),
    val optionalAttendees: List<Attendee> = emptyList(),
    val reminderSet: Boolean = false,
    val reminderMinutes: Int? = null,
    val resources: List<Attendee> = emptyList(),
    /** Single, Occurrence, Exception, RecurringMaster */
    val itemType: String? = null,
) {
    val isOccurrence: Boolean get() = itemType == "Occurrence" || itemType == "Exception"
}

data class EventDraft(
    val subject: String,
    val bodyHtml: String,
    val start: Instant,
    val end: Instant,
    val isAllDay: Boolean,
    val location: String,
    val required: List<String>,
    val optional: List<String>,
    val reminderMinutes: Int?,
    val resources: List<String> = emptyList(),
    val recurrence: Recurrence? = null,
)

/** Правило повторения встречи. [until] — последний день (включительно) или null — без окончания. */
data class Recurrence(val kind: Kind, val until: java.time.LocalDate? = null) {
    enum class Kind { DAILY, WEEKDAYS, WEEKLY, MONTHLY, YEARLY }
}

data class OofSettings(
    val state: String, // Disabled, Enabled, Scheduled
    val externalAudience: String, // None, Known, All
    val start: Instant?,
    val end: Instant?,
    val internalReply: String,
    val externalReply: String,
)

data class Room(val name: String, val email: String)

data class ContactInfo(
    val id: String?,
    val displayName: String,
    val email: String,
    val phones: List<Pair<String, String>> = emptyList(),
    val company: String = "",
    val department: String = "",
    val jobTitle: String = "",
    val office: String = "",
    val isGroup: Boolean = false,
)

/** Занятость по слотам: символы MergedFreeBusy (0 свободен, 1 под вопросом, 2 занят, 3 нет на месте, 4 нет данных). */
data class Availability(val email: String, val mergedFreeBusy: String, val error: String? = null)
