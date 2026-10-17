package com.example.callscheduler.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.example.callscheduler.data.calendar.GoogleCalendarSyncManager
import com.example.callscheduler.data.local.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val actionId = intent.getLongExtra(ConfirmationNotificationHelper.EXTRA_ACTION_ID, -1L)
        if (actionId == -1L) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getInstance(context).dao()
                val item = dao.getActionItemById(actionId) ?: return@launch

                when (intent.action) {
                    ConfirmationNotificationHelper.ACTION_CONFIRM -> {
                        val calendarManager = GoogleCalendarSyncManager(context)
                        val eventId = calendarManager.insertEventToGoogleCalendar(item)
                        dao.updateActionStatus(actionId, "CONFIRMED", eventId)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "구글 캘린더에 일정이 등록되었습니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                    ConfirmationNotificationHelper.ACTION_DISMISS -> {
                        dao.updateActionStatus(actionId, "DISMISSED", null)
                    }
                }

                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(actionId.toInt())
            } finally {
                pendingResult.finish()
            }
        }
    }
}
