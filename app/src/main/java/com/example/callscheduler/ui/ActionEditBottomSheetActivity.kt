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
import com.example.callscheduler.data.calendar.CalendarSyncResult
import com.example.callscheduler.data.calendar.GoogleCalendarSyncManager
import com.example.callscheduler.data.local.db.ActionItemEntity
import com.example.callscheduler.data.local.db.AppDatabase
import com.example.callscheduler.data.parser.CallFilenameParser
import com.example.callscheduler.service.ConfirmationNotificationHelper
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        setContent {
            MaterialTheme {
                var itemState by remember { mutableStateOf<ActionItemEntity?>(null) }
                var title by remember { mutableStateOf("") }
                var description by remember { mutableStateOf("") }
                var location by remember { mutableStateOf("") }
                var startTimeText by remember { mutableStateOf("") }
                var durationMinutesText by remember { mutableStateOf("") }
                val scope = rememberCoroutineScope()

                LaunchedEffect(actionId) {
                    val loaded = dao.getActionItemById(actionId)
                    if (loaded != null) {
                        itemState = loaded
                        title = loaded.title
                        description = loaded.description
                        location = loaded.location ?: ""
                        startTimeText = loaded.startEpochMillis?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault()).format(formatter) } ?: ""
                        durationMinutesText = if (loaded.startEpochMillis != null && loaded.endEpochMillis != null) ((loaded.endEpochMillis - loaded.startEpochMillis) / 60_000L).toString() else ""
                    } else {
                        finish()
                    }
                }

                itemState?.let { current ->
                    Surface(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("일정 및 할 일 확인·수정", style = MaterialTheme.typography.titleLarge)
                            Text("통화 상대: ${current.contactName}")
                            OutlinedTextField(
                                value = title,
                                onValueChange = { title = it },
                                label = { Text("제목") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = startTimeText,
                                onValueChange = { startTimeText = it },
                                label = { Text("시작 일시 (yyyy-MM-dd HH:mm)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = durationMinutesText,
                                onValueChange = { durationMinutesText = it },
                                label = { Text("소요 시간 (분)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = location,
                                onValueChange = { location = it },
                                label = { Text("장소") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = description,
                                onValueChange = { description = it },
                                label = { Text("상세 메모") },
                                modifier = Modifier.fillMaxWidth()
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
                                        val parsedStartMillis = runCatching {
                                            LocalDateTime.parse(startTimeText.trim(), formatter)
                                                .atZone(ZoneId.systemDefault())
                                                .toInstant()
                                                .toEpochMilli()
                                        }.getOrNull()
                                        if (parsedStartMillis == null || title.isBlank()) {
                                            Toast.makeText(this@ActionEditBottomSheetActivity, "제목과 올바른 시작 일시를 입력하세요.", Toast.LENGTH_LONG).show()
                                            return@launch
                                        }
                                        val durationMin = durationMinutesText.toLongOrNull()
                                        if (durationMin == null || durationMin !in 1..10080) {
                                            Toast.makeText(this@ActionEditBottomSheetActivity, "소요 시간(1~10080분)을 입력하세요.", Toast.LENGTH_LONG).show()
                                            return@launch
                                        }
                                        val parsedEndMillis = parsedStartMillis + durationMin * 60_000L

                                        val updated = current.copy(
                                            title = CallFilenameParser.stripPhoneNumbers(title).ifBlank { current.title },
                                            description = CallFilenameParser.stripPhoneNumbers(description),
                                            location = location.let { CallFilenameParser.stripPhoneNumbers(it) }.ifBlank { null },
                                            startEpochMillis = parsedStartMillis,
                                            endEpochMillis = parsedEndMillis,
                                            status = "CONFIRMED"
                                        )
                                        when (val syncResult = calendarManager.insertEventOrLaunchFallback(updated)) {
                                            is CalendarSyncResult.InsertedDirectly -> {
                                                dao.updateActionItem(updated.copy(calendarEventId = syncResult.eventId))
                                                Toast.makeText(
                                                    this@ActionEditBottomSheetActivity,
                                                    "수정된 일정이 구글 캘린더에 등록되었습니다.",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                            is CalendarSyncResult.LaunchedCalendarIntent -> {
                                                dao.updateActionItem(updated)
                                                Toast.makeText(
                                                    this@ActionEditBottomSheetActivity,
                                                    "캘린더 앱에서 일정을 저장해 주세요.",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                            is CalendarSyncResult.Failed -> {
                                                Toast.makeText(
                                                    this@ActionEditBottomSheetActivity,
                                                    "캘린더 등록 실패: ${syncResult.reason}",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        }
                                        finish()
                                    }
                                }) {
                                    Text("저장 및 캘린더 등록")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
