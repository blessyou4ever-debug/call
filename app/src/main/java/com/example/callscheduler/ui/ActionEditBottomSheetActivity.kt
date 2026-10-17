package com.example.callscheduler.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.callscheduler.data.calendar.GoogleCalendarSyncManager
import com.example.callscheduler.data.local.db.ActionItemEntity
import com.example.callscheduler.data.local.db.AppDatabase
import com.example.callscheduler.service.ConfirmationNotificationHelper
import kotlinx.coroutines.launch

class ActionEditBottomSheetActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val actionId = intent.getLongExtra(ConfirmationNotificationHelper.EXTRA_ACTION_ID, -1L)
        if (actionId == -1L) {
            finish()
            return
        }

        val dao = AppDatabase.getInstance(this).dao()
        val calendarManager = GoogleCalendarSyncManager(this)

        setContent {
            MaterialTheme {
                var itemState by remember { mutableStateOf<ActionItemEntity?>(null) }
                var title by remember { mutableStateOf("") }
                var description by remember { mutableStateOf("") }
                var location by remember { mutableStateOf("") }
                val scope = rememberCoroutineScope()

                LaunchedEffect(actionId) {
                    val loaded = dao.getActionItemById(actionId)
                    if (loaded != null) {
                        itemState = loaded
                        title = loaded.title
                        description = loaded.description
                        location = loaded.location ?: ""
                    } else {
                        finish()
                    }
                }

                itemState?.let { current ->
                    Surface(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("일정 및 할 일 확인·수정", style = MaterialTheme.typography.titleLarge)
                            Text("통화 상대: ${current.contactName} (${current.phoneNumber})")
                            OutlinedTextField(
                                value = title,
                                onValueChange = { title = it },
                                label = { Text("제목") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = location,
                                onValueChange = { location = it },
                                label = { Text("장소 또는 연락처") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = description,
                                onValueChange = { description = it },
                                label = { Text("상세 메모") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 3
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { finish() }) {
                                    Text("취소")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(onClick = {
                                    scope.launch {
                                        val updated = current.copy(
                                            title = title,
                                            description = description,
                                            location = location.ifBlank { null },
                                            status = "CONFIRMED"
                                        )
                                        val eventId = calendarManager.insertEventToGoogleCalendar(updated)
                                        dao.updateActionItem(updated.copy(calendarEventId = eventId))
                                        Toast.makeText(
                                            this@ActionEditBottomSheetActivity,
                                            "수정된 일정이 구글 캘린더에 등록되었습니다.",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        finish()
                                    }
                                }) {
                                    Text("구글 캘린더 등록")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
