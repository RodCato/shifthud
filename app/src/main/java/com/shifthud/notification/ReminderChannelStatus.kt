package com.shifthud.notification

import android.app.NotificationManager

/** A fresh snapshot of Android's channel settings, never an app-owned copy of user choices. */
data class ReminderChannelStatus(val notificationsAllowed: Boolean, val importance: Int?,
                                 val soundConfigured: Boolean, val vibrationEnabled: Boolean) {
    val channelEnabled get() = importance != null && importance != NotificationManager.IMPORTANCE_NONE
    val importanceLabel get() = when (importance) {
        null -> "Not created"
        NotificationManager.IMPORTANCE_NONE -> "Disabled"
        NotificationManager.IMPORTANCE_MIN -> "Min"
        NotificationManager.IMPORTANCE_LOW -> "Low"
        NotificationManager.IMPORTANCE_DEFAULT -> "Default"
        NotificationManager.IMPORTANCE_HIGH, NotificationManager.IMPORTANCE_MAX -> "High"
        else -> "Unknown"
    }
    val description get() = "Notifications: ${if (notificationsAllowed) "Allowed" else "Blocked"}\n" +
        "Channel: ${if (channelEnabled) "Enabled" else "Disabled"}\nChannel importance: $importanceLabel\n" +
        "Sound: ${if (soundConfigured) "Configured" else "Silent"}\n" +
        "Vibration: ${if (vibrationEnabled) "Enabled" else "Disabled"}"
    val advice get() = when {
        !notificationsAllowed || !channelEnabled -> "Lunch reminders are disabled in Android Settings."
        !soundConfigured -> "No sound is configured for Lunch reminders."
        importance!! < NotificationManager.IMPORTANCE_HIGH -> "Lunch reminders may be silent. Channel priority is not High. Use Android Settings to choose your preferred alert behavior."
        else -> null
    }
}
