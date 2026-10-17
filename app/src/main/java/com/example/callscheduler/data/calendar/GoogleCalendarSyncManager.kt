package com.example.callscheduler.data.calendar

import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import com.example.callscheduler.data.local.db.ActionItemEntity
import java.util.TimeZone

class GoogleCalendarSyncManager(private val context: Context) {

    fun insertEventToGoogleCalendar(item: ActionItemEntity): Long? {
        val calendarId = findPrimaryGoogleCalendarId() ?: return null
        val startMillis = item.startEpochMillis ?: (System.currentTimeMillis() + 3600_000L)
        val endMillis = item.endEpochMillis ?: (startMillis + 1800_000L)

        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.TITLE, item.title)
            put(CalendarContract.Events.DESCRIPTION, "${item.description}
(통화 상대: ${item.contactName} ${item.phoneNumber})")
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            if (!item.location.isNullOrBlank()) {
                put(CalendarContract.Events.EVENT_LOCATION, item.location)
            }
        }

        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        val eventId = uri?.lastPathSegment?.toLongOrNull()

        if (eventId != null) {
            val reminderValues = ContentValues().apply {
                put(CalendarContract.Reminders.MINUTES, 15)
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }
            context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, reminderValues)
        }

        return eventId
    }

    private fun findPrimaryGoogleCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.IS_PRIMARY
        )
        // 구글 계정(com.google) 캘린더 우선 조회, 없을 경우 기기 내 기본 캘린더 사용
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            var googleFallbackId: Long? = null
            var anyCalendarId: Long? = null
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val accountType = cursor.getString(1) ?: ""
                val isPrimary = cursor.getInt(2) == 1
                if (anyCalendarId == null) anyCalendarId = id
                if (accountType == "com.google") {
                    if (isPrimary) return id
                    if (googleFallbackId == null) googleFallbackId = id
                }
            }
            return googleFallbackId ?: anyCalendarId
        }
        return null
    }
}
