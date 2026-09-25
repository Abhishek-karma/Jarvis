package com.jarvis.app.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jarvis.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class UpdateNotifier
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        fun ensureChannel() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel =
                    NotificationChannel(
                        CHANNEL_ID,
                        "App updates",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply {
                        description = "Tells you when a new version of Jarvis is available"
                    }
                context.getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(channel)
            }
        }

        /** Returns true if the notification was posted (false = no permission on API 33+). */
        fun notifyUpdate(version: String, apkUrl: String): Boolean {
            ensureChannel()
            val canPost =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED
                } else {
                    NotificationManagerCompat.from(context).areNotificationsEnabled()
                }
            if (!canPost) return false



            val open = Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl))
            val pending =
                PendingIntent.getActivity(
                    context,
                    0,
                    open,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            val notification =
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("Jarvis $version is available")
                    .setContentText("Tap to download the update")
                    .setAutoCancel(true)
                    .setContentIntent(pending)
                    .build()
            NotificationManagerCompat.from(context).notify(NOTIF_ID_BASE + version.hashCode(), notification)
            return true
        }

        companion object {
            private const val CHANNEL_ID = "app_updates"
            private const val NOTIF_ID_BASE = 4100
        }
    }
