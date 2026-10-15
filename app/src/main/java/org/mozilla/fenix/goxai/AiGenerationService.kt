package org.mozilla.fenix.goxai

import org.mozilla.fenix.R

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** Keeps an active AI stream alive when the chat Fragment or app is no longer visible. */
class AiGenerationService : Service() {
    private var removeObserver: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "AI 生成", NotificationManager.IMPORTANCE_LOW))
        }
        startForeground(NOTIFICATION_ID, notification())
        removeObserver = aiConversation.observe {
            if (aiConversation.isGenerating) manager.notify(NOTIFICATION_ID, notification())
            else stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) aiConversation.stop()
        return START_NOT_STICKY
    }

    private fun notification(): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, AiChatActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AiGenerationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Gox AI 正在生成")
            .setContentText("可返回聊天查看进度")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "停止", stop)
            .build()
    }

    override fun onDestroy() {
        removeObserver?.invoke()
        removeObserver = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.gox.ai.START"
        const val ACTION_STOP = "com.gox.ai.STOP"
        private const val CHANNEL = "gox_ai_generation"
        private const val NOTIFICATION_ID = 2401
    }
}
