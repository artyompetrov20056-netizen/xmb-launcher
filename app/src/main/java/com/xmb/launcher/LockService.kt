package com.xmb.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Фоновая служба: при выключении экрана заранее открывает экран блокировки XMB,
 * чтобы при включении он уже был на месте поверх системного.
 */
class LockService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var pixel: View? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) showLock()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Экран блокировки XMB включён")
            .setContentText("Нужен, чтобы показывать экран блокировки в стиле XMB")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID_SERVICE, n)
        }
        registerSystemReceiver(this, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        addPixel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!LockController.isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) { }
        removePixel()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun showLock() {
        if (!LockController.isEnabled(this)) return
        // Во время звонка экран гаснет от датчика приближения — не мешаем
        val am = getSystemService(AudioManager::class.java)
        if (am != null && (am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION)) return

        val intent = Intent(this, LockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        if (pixel == null) addPixel()
        try { startActivity(intent) } catch (_: Exception) { }

        // Запасной путь, если нет разрешения «поверх других приложений»
        if (!Settings.canDrawOverlays(this)) postFullScreen(intent)
    }

    private fun postFullScreen(intent: Intent) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()) return
        val pi = PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, CHANNEL_LAUNCH)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Экран блокировки XMB")
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .build()
        try { nm.notify(ID_LAUNCH, n) } catch (_: Exception) { }
        handler.postDelayed({ nm.cancel(ID_LAUNCH) }, 4000)
    }

    /**
     * Невидимое окно в 1 пиксель. Начиная с Android 15 приложение с разрешением
     * «поверх других приложений» может открывать экраны из фона, только пока у него есть такое окно.
     */
    private fun addPixel() {
        if (pixel != null || !Settings.canDrawOverlays(this)) return
        try {
            val wm = getSystemService(WindowManager::class.java) ?: return
            val v = View(this)
            val lp = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            wm.addView(v, lp)
            pixel = v
        } catch (_: Exception) {
            pixel = null
        }
    }

    private fun removePixel() {
        val v = pixel ?: return
        try { getSystemService(WindowManager::class.java)?.removeView(v) } catch (_: Exception) { }
        pixel = null
    }

    companion object {
        const val CHANNEL_SERVICE = "xmb_lock_service"
        const val CHANNEL_LAUNCH = "xmb_lock_launch"
        const val ID_SERVICE = 1
        const val ID_LAUNCH = 2

        fun createChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_SERVICE, "Служба экрана блокировки", NotificationManager.IMPORTANCE_MIN).apply {
                    setShowBadge(false)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_LAUNCH, "Показ экрана блокировки", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
            )
        }

        /** Регистрация приёмника системных событий (экран, батарея, разблокировка). */
        fun registerSystemReceiver(ctx: Context, r: BroadcastReceiver, f: IntentFilter) {
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED)
            else ctx.registerReceiver(r, f)
        }
    }
}

/** Включение / выключение экрана блокировки XMB. */
object LockController {
    private const val PREF = "lock_enabled"

    fun isEnabled(ctx: Context) = ctx.getSharedPreferences("xmb", Context.MODE_PRIVATE).getBoolean(PREF, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences("xmb", Context.MODE_PRIVATE).edit().putBoolean(PREF, on).apply()
        if (on) start(ctx) else ctx.stopService(Intent(ctx, LockService::class.java))
    }

    fun start(ctx: Context) {
        if (!isEnabled(ctx)) return
        try { ctx.startForegroundService(Intent(ctx, LockService::class.java)) } catch (_: Exception) { }
    }
}

/** Запуск службы после перезагрузки телефона и после обновления приложения. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> LockController.start(context)
        }
    }
}
