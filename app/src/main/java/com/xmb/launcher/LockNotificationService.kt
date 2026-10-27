package com.xmb.launcher

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Доступ к уведомлениям — чтобы показывать их и управление музыкой на экране блокировки.
 * Пользователь включает его сам в настройках «Доступ к уведомлениям».
 */
class LockNotificationService : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
        changed()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        changed()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = changed()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = changed()

    companion object {
        @Volatile
        var instance: LockNotificationService? = null
            private set

        val listeners = CopyOnWriteArrayList<() -> Unit>()
        private val main = Handler(Looper.getMainLooper())

        fun changed() {
            main.post { listeners.forEach { it() } }
        }

        fun component(ctx: Context) = ComponentName(ctx, LockNotificationService::class.java)

        fun isEnabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            return flat.split(':').any { ComponentName.unflattenFromString(it)?.packageName == ctx.packageName }
        }
    }
}
