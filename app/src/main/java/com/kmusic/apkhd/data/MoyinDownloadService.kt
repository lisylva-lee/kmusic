package com.kmusic.apkhd.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.kmusic.apkhd.model.DownloadTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 下载前台服务。
 *
 * 存在的唯一理由：让下载在 App 退到后台 / Activity 被销毁 / 系统回收进程后**继续**。
 * 真正的字节搬运在 [DownloadCenter] 的协程里，这里只负责「前台身份 + 进度通知」。
 */
class MoyinDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DownloadCenter.attach(this)

        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "下载",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "KMusic 下载进度" }
            )
        }

        startForegroundCompat(nm, buildNotification(emptyList()))

        observer = scope.launch {
            DownloadCenter.tasks.collectLatest { tasks ->
                val active = tasks.filter { !it.status.startsWith("失败") && it.status != "已完成" }
                if (active.isEmpty()) return@collectLatest
                runCatching { nm.notify(NOTIF_ID, buildNotification(active)) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        observer?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(nm: NotificationManager, n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(active: List<DownloadTask>): Notification {
        val title: String
        val text: String
        val progress: Int
        val indeterminate: Boolean
        if (active.isEmpty()) {
            title = "KMusic 下载"
            text = "准备中…"
            progress = 0
            indeterminate = true
        } else {
            val cur = active.first()
            title = if (active.size > 1) "正在下载 ${active.size} 首" else "正在下载"
            text = "${cur.artist} - ${cur.name}".trim().trimStart('-').trim()
                .ifEmpty { cur.name }
            progress = (cur.progress * 100).toInt().coerceIn(0, 100)
            indeterminate = cur.progress <= 0f
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, progress, indeterminate)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "moyin_download"
        private const val NOTIF_ID = 2001

        fun start(ctx: Context) {
            val i = Intent(ctx, MoyinDownloadService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, MoyinDownloadService::class.java)) }
        }
    }
}
