package com.xmb.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/**
 * Экран блокировки XMB. Показывается поверх системного экрана блокировки.
 * Пароль здесь не вводится: свайп вверх вызывает системное окно PIN-кода / отпечатка —
 * так пароль вводится только один раз, а защита остаётся системной.
 */
class LockActivity : Activity(), LockView.Listener {

    private lateinit var view: LockView
    private lateinit var km: KeyguardManager
    private val handler = Handler(Looper.getMainLooper())
    private val iconCache = HashMap<String, Bitmap?>()
    private var mediaController: MediaController? = null
    private var torchCameraId: String? = null
    private var torchCallback: CameraManager.TorchCallback? = null

    private val notesListener: () -> Unit = { refreshNotes() }

    private val mediaPoll = object : Runnable {
        override fun run() {
            refreshMedia()
            handler.postDelayed(this, 1000)
        }
    }

    /** Телефон разблокирован другим способом (отпечаток, лицо) — убираем наш экран. */
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_USER_PRESENT -> finishUnlocked()
                Intent.ACTION_BATTERY_CHANGED -> readBattery(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        km = getSystemService(KeyguardManager::class.java)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        view = LockView(this)
        view.listener = this
        view.bg.loadFromPrefs(this)
        view.setOnApplyWindowInsetsListener { _, insets ->
            view.applyInsets(insets)
            insets
        }
        setContentView(view)
        hideBars()

        val f = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        LockService.registerSystemReceiver(this, unlockReceiver, f)
        setupTorch()
    }

    override fun onResume() {
        super.onResume()
        getSystemService(NotificationManager::class.java)?.cancel(LockService.ID_LAUNCH)
        view.bg.loadFromPrefs(this)
        view.resetUnlock()
        LockNotificationService.listeners.add(notesListener)
        refreshNotes()
        handler.removeCallbacks(mediaPoll)
        handler.post(mediaPoll)
        hideBars()
    }

    override fun onPause() {
        LockNotificationService.listeners.remove(notesListener)
        handler.removeCallbacks(mediaPoll)
        super.onPause()
    }

    override fun onDestroy() {
        try { unregisterReceiver(unlockReceiver) } catch (_: Exception) { }
        torchCallback?.let { getSystemService(CameraManager::class.java)?.unregisterTorchCallback(it) }
        super.onDestroy()
    }

    /** «Назад» на экране блокировки ничего не делает. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.dispatchKeyEvent(event)
    }

    @Suppress("DEPRECATION")
    private fun hideBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
    }

    // =====================================================================
    // Разблокировка
    // =====================================================================

    /** Вызывает системное окно PIN-кода / отпечатка. after — что сделать после разблокировки. */
    private fun requestUnlock(after: (() -> Unit)?) {
        if (km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    after?.invoke()
                    finishUnlocked()
                }

                override fun onDismissCancelled() = view.resetUnlock()
                override fun onDismissError() = view.resetUnlock()
            })
        } else {
            after?.invoke()
            finishUnlocked()
        }
    }

    @Suppress("DEPRECATION")
    private fun finishUnlocked() {
        if (isFinishing) return
        finish()
        overridePendingTransition(0, android.R.anim.fade_out)
    }

    override fun onUnlockSwipe() = requestUnlock(null)

    override fun onNoteTap(note: LockNote) {
        requestUnlock {
            val pi = note.intent ?: return@requestUnlock
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    val opts = ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    pi.send(this, 0, null, null, null, null, opts.toBundle())
                } else {
                    pi.send()
                }
            } catch (_: PendingIntent.CanceledException) {
            }
            if (note.autoCancel) {
                try { LockNotificationService.instance?.cancelNotification(note.key) } catch (_: Exception) { }
            }
        }
    }

    // =====================================================================
    // Камера и фонарик
    // =====================================================================

    override fun onCamera() {
        val intents = listOf(
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
        )
        for (i in intents) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
            }
        }
    }

    private fun setupTorch() {
        val cm = getSystemService(CameraManager::class.java) ?: return
        try {
            torchCameraId = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (_: Exception) {
        }
        val cb = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId == torchCameraId) view.torchOn = enabled
            }
        }
        torchCallback = cb
        try { cm.registerTorchCallback(cb, handler) } catch (_: Exception) { }
    }

    override fun onTorch() {
        val id = torchCameraId ?: return
        try {
            getSystemService(CameraManager::class.java)?.setTorchMode(id, !view.torchOn)
        } catch (_: Exception) {
        }
    }

    // =====================================================================
    // Батарея, уведомления, музыка
    // =====================================================================

    private fun readBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level >= 0 && scale > 0) view.battery = level * 100 / scale
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        view.charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun secureSetting(name: String, def: Int): Int =
        try { Settings.Secure.getInt(contentResolver, name, def) } catch (_: Exception) { def }

    private fun refreshNotes() {
        val svc = LockNotificationService.instance
        if (svc == null) { view.notes = emptyList(); return }
        val locked = km.isDeviceSecure && km.isKeyguardLocked
        // Учитываем системные настройки уведомлений на экране блокировки
        if (locked && secureSetting("lock_screen_show_notifications", 1) == 0) { view.notes = emptyList(); return }
        val allowPrivate = secureSetting("lock_screen_allow_private_notifications", 1) != 0

        val all = try { svc.activeNotifications ?: emptyArray() } catch (_: Exception) { emptyArray() }
        view.notes = all
            .filter {
                it.packageName != packageName && !it.isOngoing &&
                    (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0
            }
            .sortedByDescending { it.postTime }
            .mapNotNull { sbn ->
                val n = sbn.notification
                val ex = n.extras
                var title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                var text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString()
                if (title.isNullOrBlank() && text.isNullOrBlank()) return@mapNotNull null
                if (locked && n.visibility == Notification.VISIBILITY_SECRET) return@mapNotNull null
                if (locked && !allowPrivate && n.visibility != Notification.VISIBILITY_PUBLIC) {
                    val pub = n.publicVersion?.extras
                    title = pub?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: appLabel(sbn.packageName)
                    text = pub?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "Содержимое скрыто"
                }
                LockNote(
                    key = sbn.key,
                    packageName = sbn.packageName,
                    icon = appIcon(sbn.packageName),
                    title = if (title.isNullOrBlank()) appLabel(sbn.packageName) else title,
                    text = text ?: "",
                    time = sbn.postTime,
                    intent = n.contentIntent,
                    autoCancel = (n.flags and Notification.FLAG_AUTO_CANCEL) != 0,
                )
            }
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    private fun appIcon(pkg: String): Bitmap? = iconCache.getOrPut(pkg) {
        try {
            val d = packageManager.getApplicationIcon(pkg)
            val size = (resources.displayMetrics.density * 48f).toInt().coerceAtLeast(48)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, size, size)
            d.draw(Canvas(bmp))
            bmp
        } catch (_: Exception) {
            null
        }
    }

    private fun refreshMedia() {
        val msm = getSystemService(MediaSessionManager::class.java)
        val controllers: List<MediaController> = try {
            msm?.getActiveSessions(LockNotificationService.component(this)) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val c = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull { it.metadata != null }
        mediaController = c
        if (c == null) {
            if (view.media != null) view.media = null
            return
        }
        val md = c.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: appLabel(c.packageName)
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        val cur = view.media
        if (cur == null || cur.title != title || cur.artist != artist || cur.playing != playing) {
            view.media = LockMedia(title, artist, playing)
        }
    }

    override fun onMedia(action: Int) {
        val tc = mediaController?.transportControls ?: return
        when (action) {
            0 -> tc.skipToPrevious()
            1 -> if (view.media?.playing == true) tc.pause() else tc.play()
            2 -> tc.skipToNext()
        }
        handler.postDelayed({ refreshMedia() }, 300)
    }
}
