package com.jarvis.core.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

class AssistantNotificationManager(
    private val context: Context,
) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Jarvis Background Tasks & Routines",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Status and alerts for scheduled routines and durable tasks"
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    fun notifyStarted(taskIdOrRoutineId: String, title: String, goal: String) {
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Task Started: $title")
            .setContentText(goal)
            .setOngoing(true)
            .build()
        notificationManager?.notify(taskIdOrRoutineId.hashCode(), notification)
    }

    fun notifyWaitingForConfirmation(taskId: String, title: String, actionDescription: String) {
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Confirmation Needed: $title")
            .setContentText(actionDescription)
            .setOngoing(true)
            .build()
        notificationManager?.notify(taskId.hashCode(), notification)
    }

    fun notifySucceeded(taskIdOrRoutineId: String, title: String, summary: String) {
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Task Completed: $title")
            .setContentText(summary)
            .setAutoCancel(true)
            .build()
        notificationManager?.notify(taskIdOrRoutineId.hashCode(), notification)
    }

    fun notifyFailed(taskIdOrRoutineId: String, title: String, errorReason: String) {
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Task Failed: $title")
            .setContentText(errorReason)
            .setAutoCancel(true)
            .build()
        notificationManager?.notify(taskIdOrRoutineId.hashCode(), notification)
    }

    fun cancel(taskIdOrRoutineId: String) {
        notificationManager?.cancel(taskIdOrRoutineId.hashCode())
    }

    fun notifyCustom(title: String, message: String) {
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .build()
        notificationManager?.notify(System.currentTimeMillis().toInt(), notification)
    }

    companion object {
        const val CHANNEL_ID = "jarvis_tasks_channel"
    }
}
