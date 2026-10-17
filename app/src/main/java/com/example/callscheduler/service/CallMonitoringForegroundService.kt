package com.example.callscheduler.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.FileObserver
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File

class CallMonitoringForegroundService : Service() {

    companion object {
        const val CALL_RECORDING_PATH = "/storage/emulated/0/Recordings/Call/"
        private const val MONITOR_CHANNEL_ID = "call_monitor_service_channel"
        private const val NOTIFICATION_ID = 9001
    }

    private var fileObserver: FileObserver? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification()
        startWatchingCallDirectory()
    }

    private fun startForegroundWithNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            MONITOR_CHANNEL_ID,
            "통화 녹음 자동 감지 서비스",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, MONITOR_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("통화 녹음 자동 일정 감지 중")
            .setContentText("/storage/emulated/0/Recordings/Call/ 경로를 모니터링하고 있습니다.")
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startWatchingCallDirectory() {
        val callDir = File(CALL_RECORDING_PATH)
        if (!callDir.exists()) {
            callDir.mkdirs()
        }

        fileObserver = object : FileObserver(callDir, CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                if (event == CLOSE_WRITE && path != null) {
                    val file = File(callDir, path)
                    if (file.extension.lowercase() in listOf("m4a", "amr", "mp3", "wav")) {
                        val inputData = Data.Builder()
                            .putString("KEY_FILE_PATH", file.absolutePath)
                            .build()
                        val request = OneTimeWorkRequestBuilder<CallProcessWorker>()
                            .setInputData(inputData)
                            .build()
                        WorkManager.getInstance(applicationContext).enqueue(request)
                    }
                }
            }
        }.also { it.startWatching() }
    }

    override fun onDestroy() {
        fileObserver?.stopWatching()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
