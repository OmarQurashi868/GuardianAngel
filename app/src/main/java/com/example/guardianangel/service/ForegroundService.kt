package com.example.guardianangel.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import android.app.PendingIntent
import com.example.guardianangel.MainActivity
import com.example.guardianangel.core.Constants

/**
 * Foreground service used to keep audio streaming and discovery alive when the app is backgrounded.
 *
 * Expects the following Intent extras:
 * - "title": String for the foreground notification title
 * - "text": String for the foreground notification content text
 *
 * Note:
 * - This service references notification constants defined in Constants:
 *   - Constants.NOTIFICATION_CHANNEL_ID
 *   - Constants.NOTIFICATION_ID
 */
class ForegroundService : Service() {

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): ForegroundService = this@ForegroundService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra("title") ?: "Guardian Angel"
        val text = intent?.getStringExtra("text") ?: "Service running"

        val activityIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            // Use reflection to call startForeground with service type to support older compile targets
            try {
                val method = Service::class.java.getMethod(
                    "startForeground",
                    Int::class.javaPrimitiveType,
                    android.app.Notification::class.java,
                    Int::class.javaPrimitiveType
                )
                // 2 corresponds to FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                method.invoke(this, Constants.NOTIFICATION_ID, notification, 2)
            } catch (e: Exception) {
                startForeground(Constants.NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(Constants.NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (Build.VERSION.SDK_INT >= 24) {
            // Use reflection to call stopForeground(FLAG_REMOVE) for broader compatibility
            try {
                val method = Service::class.java.getMethod(
                    "stopForeground",
                    Int::class.javaPrimitiveType
                )
                // 1 corresponds to STOP_FOREGROUND_REMOVE
                method.invoke(this, 1)
            } catch (e: Exception) {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}
