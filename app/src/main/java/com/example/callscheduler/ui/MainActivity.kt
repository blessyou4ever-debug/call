package com.example.callscheduler.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.callscheduler.data.calendar.GoogleCalendarSyncManager
import com.example.callscheduler.data.local.datastore.AiSettingsDataStore
import com.example.callscheduler.data.local.datastore.EngineMode
import com.example.callscheduler.data.local.db.ActionItemEntity
import com.example.callscheduler.data.local.db.AppDatabase
import com.example.callscheduler.service.CallMonitoringForegroundService
import com.example.callscheduler.service.CallProcessWorker
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val settingsDataStore = AiSettingsDataStore(this)
        val dao = AppDatabase.getInstance(this).dao()
        val calendarManager = GoogleCalendarSyncManager(this)

        setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                val currentMode by settingsDataStore.engineModeFlow.collectAsState(initial = EngineMode.AUTO_FALLBACK)
                val apiKey by settingsDataStore.apiKeyFlow.collectAsState(initial = "")
                val actionItems by dao.observeAllActionItems().collectAsState(initial = emptyList())
                var apiKeyInput by remember(apiKey) { mutableStateOf(apiKey) }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    val serviceIntent = Intent(this, CallMonitoringForegroundService::class.java)
                    ContextCompat.startForegroundService(this, serviceIntent)
                    Toast.makeText(this, "통화 녹음 폴더 감시 서비스가 시작되었습니다.", Toast.LENGTH_SHORT).show()
                }

                Scaffold(
                    topBar = {
                        Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("통화 녹음 자동 일정·할 일 관리", style = MaterialTheme.typography.titleLarge)
                                Text(
                                    "감시 경로: /storage/emulated/0/Recordings/Call/",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("1. 권한 승인 및 폴더 감시 시작", style = MaterialTheme.typography.titleMedium)
                                    Button(
                                        onClick = {
                                            val perms = mutableListOf(
                                                Manifest.permission.READ_CALENDAR,
                                                Manifest.permission.WRITE_CALENDAR,
                                                Manifest.permission.READ_PHONE_STATE
                                            )
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                perms.add(Manifest.permission.READ_MEDIA_AUDIO)
                                                perms.add(Manifest.permission.POST_NOTIFICATIONS)
                                            } else {
                                                perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                                            }
                                            permissionLauncher.launch(perms.toTypedArray())
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("권한 허용 및 자동 감시 활성화")
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            val samplePath = "/storage/emulated/0/Recordings/Call/통화 녹음_김대리_010-1234-5678_261017_174500.m4a"
                                            val sampleText = "김대리님, 내일 오후 3시에 강남역 카페에서 미팅 진행하시죠. 요청하신 견적서 자료도 확인해서 오늘 저녁에 다시 연락드리겠습니다."
                                            val input = Data.Builder()
                                                .putString("KEY_FILE_PATH", samplePath)
                                                .putString("KEY_SIMULATED_TRANSCRIPT", sampleText)
                                                .build()
                                            WorkManager.getInstance(this@MainActivity).enqueue(
                                                OneTimeWorkRequestBuilder<CallProcessWorker>()
                                                    .setInputData(input)
                                                    .build()
                                            )
                                            Toast.makeText(this@MainActivity, "샘플 통화 분석 및 푸시 알림을 실행했습니다.", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("테스트 통화 녹음 분석 실행 (푸시 알림 체험)")
                                    }
                                }
                            }
                        }

                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("2. AI 음성 인식(STT) 및 일정 추출 모드 선택", style = MaterialTheme.typography.titleMedium)
                                    EngineMode.entries.forEach { mode ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            RadioButton(
                                                selected = (currentMode == mode),
                                                onClick = { scope.launch { settingsDataStore.setEngineMode(mode) } }
                                            )
                                            Text(text = mode.label)
                                        }
                                    }

                                    if (currentMode != EngineMode.ON_DEVICE) {
                                        OutlinedTextField(
                                            value = apiKeyInput,
                                            onValueChange = { apiKeyInput = it },
                                            label = { Text("Gemini API Key (클라우드 전환 시 사용)") },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true
                                        )
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    settingsDataStore.setGeminiApiKey(apiKeyInput)
                                                    Toast.makeText(this@MainActivity, "API 키가 저장되었습니다.", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        ) {
                                            Text("API 키 저장")
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Text("3. 감지된 약속·할 일·콜백 목록 (${actionItems.size}건)", style = MaterialTheme.typography.titleMedium)
                        }

                        items(actionItems) { item ->
                            ActionItemCard(
                                item = item,
                                onConfirm = {
                                    scope.launch {
                                        val eventId = calendarManager.insertEventToGoogleCalendar(item)
                                        dao.updateActionStatus(item.id, "CONFIRMED", eventId)
                                        Toast.makeText(this@MainActivity, "구글 캘린더에 등록되었습니다.", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onDismiss = {
                                    scope.launch {
                                        dao.updateActionStatus(item.id, "DISMISSED", null)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ActionItemCard(
    item: ActionItemEntity,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA) }
    val timeText = item.startEpochMillis?.let { dateFormat.format(Date(it)) } ?: "시간 미지정"

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("[${item.actionType}] ${item.title}", style = MaterialTheme.typography.titleMedium)
            Text("통화 상대: ${item.contactName} (${item.phoneNumber})", style = MaterialTheme.typography.bodySmall)
            Text("예정 시각: $timeText | 장소: ${item.location ?: "없음"}", style = MaterialTheme.typography.bodySmall)
            Text(item.description, style = MaterialTheme.typography.bodyMedium)
            Text("상태: ${item.status}", style = MaterialTheme.typography.labelMedium)

            if (item.status == "PENDING") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onConfirm) {
                        Text("구글 캘린더 등록")
                    }
                    OutlinedButton(onClick = onDismiss) {
                        Text("무시")
                    }
                }
            }
        }
    }
}
