package io.github.denhaiz.exmail.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import io.github.denhaiz.exmail.data.db.EventDao
import io.github.denhaiz.exmail.data.db.EventEntity
import io.github.denhaiz.exmail.ews.Availability
import io.github.denhaiz.exmail.ews.CalendarEvent
import io.github.denhaiz.exmail.ews.EventDraft
import io.github.denhaiz.exmail.ews.EwsClient
import io.github.denhaiz.exmail.ews.ItemRef
import io.github.denhaiz.exmail.ews.MeetingResponse
import io.github.denhaiz.exmail.ews.Room
import io.github.denhaiz.exmail.ews.getRooms
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CalendarRepository @Inject constructor(
    private val client: EwsClient,
    private val dao: EventDao,
) {
    private val _changes = MutableStateFlow(0)

    /** Растёт при каждом изменении календаря из приложения — экран календаря перезагружает видимый период. */
    val changes: StateFlow<Int> = _changes

    fun invalidate() = _changes.update { it + 1 }

    fun events(from: Instant, to: Instant): Flow<List<EventEntity>> =
        dao.observeRange(from.toEpochMilli(), to.toEpochMilli())

    suspend fun refresh(from: Instant, to: Instant) {
        val events = client.findEvents(from, to)
        dao.replaceRange(from.toEpochMilli(), to.toEpochMilli(), events.map { it.toEntity() })
    }

    suspend fun getEvent(id: String): CalendarEvent = client.getEvent(id)

    suspend fun create(draft: EventDraft): ItemRef = client.createEvent(draft).also { invalidate() }

    suspend fun update(ref: ItemRef, draft: EventDraft, notifyAttendees: Boolean) {
        client.updateEvent(ref, draft, notifyAttendees)
        invalidate()
    }

    /** Удаление; организатор встречи рассылает участникам отмену. */
    suspend fun delete(event: CalendarEvent, wholeSeries: Boolean = false) {
        val organizerOfMeeting = event.isMeeting && event.isOrganizer == true
        client.deleteItems(listOf(ItemRef(event.ref.id)), sendCancellations = organizerOfMeeting, wholeSeries = wholeSeries)
        if (wholeSeries) dao.deleteBySubject(event.subject) else dao.delete(event.ref.id)
        invalidate()
    }

    @Volatile private var roomsCache: List<Room>? = null

    fun clearCache() {
        roomsCache = null
    }

    /** Переговорные организации (кэшируются на время работы приложения). */
    suspend fun rooms(): List<Room> = roomsCache ?: client.getRooms().also { roomsCache = it }

    suspend fun respond(event: CalendarEvent, response: MeetingResponse) {
        client.respondToMeeting(event.ref, response)
        if (response == MeetingResponse.DECLINE) {
            dao.delete(event.ref.id)
        } else {
            dao.setResponse(event.ref.id, if (response == MeetingResponse.ACCEPT) "Accept" else "Tentative")
        }
        invalidate()
    }

    suspend fun availability(emails: List<String>, from: Instant, to: Instant): List<Availability> =
        client.getAvailability(emails, from, to)

    private fun CalendarEvent.toEntity() = EventEntity(
        id = ref.id,
        changeKey = ref.changeKey,
        subject = subject,
        start = start.toEpochMilli(),
        end = end.toEpochMilli(),
        location = location,
        isAllDay = isAllDay,
        organizerName = organizer?.name.orEmpty(),
        organizerEmail = organizer?.email.orEmpty(),
        myResponse = myResponse,
        isCancelled = isCancelled,
        isMeeting = isMeeting,
        isRecurring = isRecurring,
        freeBusy = freeBusy,
    )
}
