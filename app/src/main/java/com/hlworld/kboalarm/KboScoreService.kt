package com.hlworld.kboalarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

class KboScoreService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var updateJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (updateJob == null) {
            startInForeground(buildNotification("경기 정보를 불러오는 중"))
            updateJob = scope.launch {
                while (isActive) {
                    val (title, lines) = buildCurrentSnapshot()
                    updateNotification(title, lines)
                    delay(30_000)
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        updateJob?.cancel()
        scope.cancel()
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(title: String, lines: List<String>) {
        val notification = buildNotification(title, lines)
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
    }

    private fun buildCurrentSnapshot(): Pair<String, List<String>> {
        return runCatching {
            val date = LocalDate.now(ZoneId.of("Asia/Seoul"))
            val games = KboRepository.fetchTodayGames(date)
            games.forEach { game ->
                game.gameId?.let { id ->
                    game.weatherLabel()?.let { weather ->
                        getSharedPreferences("kbo_alarm_prefs", MODE_PRIVATE)
                            .edit().putString("weather_$id", weather).apply()
                    }
                }
            }

            val canceled = games.count { it.canceled }

            val title = "KBO 오늘 경기 ${games.size}경기"
            val lines = buildList {
                add("총 ${games.size}경기 · 취소 $canceled")
                // Keep each game on one line so all five games fit in the expanded notification.
                games.forEach { add("${it.titleLine()} ${it.scoreLine()}") }
                if (games.size > 7) add("외 ${games.size - 7}경기")
            }

            title to lines
        }.getOrElse {
            "KBO 오늘 경기" to listOf("경기 정보를 불러오지 못했습니다.", it.message ?: "알 수 없는 오류")
        }
    }

    private fun buildNotification(title: String, lines: List<String> = emptyList()): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = Intent(this, KboScoreService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val bigText = lines.joinToString("\n")

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(title)
            .setContentText(lines.firstOrNull() ?: "오늘 KBO 경기")
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_view, "열기", openPendingIntent)
            .addAction(android.R.drawable.ic_delete, "중지", stopPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = CHANNEL_DESCRIPTION
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "kbo_scoreboard"
        const val CHANNEL_NAME = "KBO 경기 알림"
        const val CHANNEL_DESCRIPTION = "KBO 경기 점수를 계속 보여주는 알림"
        const val NOTIFICATION_ID = 20260820
        const val ACTION_STOP = "com.hlworld.kboalarm.action.STOP"
    }
}
