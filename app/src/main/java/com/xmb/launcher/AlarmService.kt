package com.xmb.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Звонок будильника / таймера: мелодия с нарастающей громкостью, вибрация, экран звонка. */
class AlarmService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var volume = 0.12f
    private var currentTitle = "Будильник"
    private var currentSound: String? = null
    private var currentId = -1

    private val crescendo = object : Runnable {
        override fun run() {
            volume = (volume + 0.02f).coerceAtMost(1f)
            try { player?.setVolume(volume, volume) } catch (_: Exception) { }
            if (volume < 1f) handler.postDelayed(this, 500)
        }
    }

    /** Через 10 минут звонок выключается сам. */
    private val autoStop = Runnable { stopRing() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RING -> startRing(
                intent.getStringExtra("title") ?: "Будильник",
                intent.getStringExtra("sound"),
                intent.getIntExtra("id", -1)
            )
            ACTION_SNOOZE -> {
                if (currentId >= 0) AlarmStore.snooze(this, currentId, currentSound)
                stopRing()
            }
            ACTION_STOP -> stopRing()
            else -> stopRing()
        }
        return START_NOT_STICKY
    }

    private fun startRing(title: String, sound: String?, id: Int) {
        stopPlayback()
        currentTitle = title
        currentSound = sound
        currentId = id
        ringing = RingInfo(title, id >= 0)

        createChannel(this)
        val full = PendingIntent.getActivity(
            this, 10, Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date()))
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
        if (id >= 0) {
            b.addAction(Notification.Action.Builder(null, "Отложить ${AlarmStore.SNOOZE_MINUTES} мин", action(ACTION_SNOOZE, 11)).build())
        }
        b.addAction(Notification.Action.Builder(null, "Выключить", action(ACTION_STOP, 12)).build())
        val n = b.build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, n)
        }

        play(sound)
        vibrate()
        try { startActivity(Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, 10 * 60_000L)
    }

    private fun action(act: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, AlarmService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE
    )

    private fun play(sound: String?) {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val sources = ArrayList<Any>()
        if (sound != null && File(sound).exists()) sources += sound
        RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)?.let { sources += it }
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)?.let { sources += it }
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)?.let { sources += it }
        for (src in sources) {
            try {
                val mp = MediaPlayer()
                mp.setAudioAttributes(attrs)
                if (src is String) mp.setDataSource(src) else mp.setDataSource(this, src as Uri)
                mp.isLooping = true
                mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
                mp.prepare()
                volume = 0.12f
                mp.setVolume(volume, volume)
                mp.start()
                player = mp
                handler.removeCallbacks(crescendo)
                handler.postDelayed(crescendo, 500)
                return
            } catch (_: Exception) {
            }
        }
    }

    private fun vibrate() {
        val v = getSystemService(Vibrator::class.java) ?: return
        vibrator = v
        try {
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 800), 1))
        } catch (_: Exception) {
        }
    }

    private fun stopPlayback() {
        handler.removeCallbacks(crescendo)
        try { player?.stop() } catch (_: Exception) { }
        try { player?.release() } catch (_: Exception) { }
        player = null
        try { vibrator?.cancel() } catch (_: Exception) { }
    }

    private fun stopRing() {
        handler.removeCallbacks(autoStop)
        stopPlayback()
        ringing = null
        listeners.forEach { it() }
        if (Build.VERSION.SDK_INT >= 33) stopForeground(STOP_FOREGROUND_REMOVE)
        else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        stopPlayback()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    class RingInfo(val title: String, val canSnooze: Boolean)

    companion object {
        const val ACTION_RING = "com.xmb.launcher.RING"
        const val ACTION_STOP = "com.xmb.launcher.RING_STOP"
        const val ACTION_SNOOZE = "com.xmb.launcher.RING_SNOOZE"
        private const val CHANNEL = "xmb_alarm"
        private const val ID = 7

        /** Что сейчас звонит (для экрана звонка); null — ничего. */
        @Volatile
        var ringing: RingInfo? = null
            private set

        /** Звонок закончился — экраны звонка закрываются. */
        val listeners = CopyOnWriteArrayList<() -> Unit>()

        fun ring(ctx: Context, title: String, sound: String?, id: Int) {
            val i = Intent(ctx, AlarmService::class.java).setAction(ACTION_RING)
                .putExtra("title", title).putExtra("sound", sound).putExtra("id", id)
            try { ctx.startForegroundService(i) } catch (_: Exception) { }
        }

        fun send(ctx: Context, action: String) {
            try { ctx.startService(Intent(ctx, AlarmService::class.java).setAction(action)) } catch (_: Exception) { }
        }

        fun createChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Будильник и таймер", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setBypassDnd(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }
    }
}
