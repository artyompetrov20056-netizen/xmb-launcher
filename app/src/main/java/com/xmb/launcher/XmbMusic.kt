package com.xmb.launcher

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Музыка XMB: общая фоновая мелодия и мелодии приложений (как SND0 на PS3).
 * Когда задерживаешься на приложении со своей мелодией, общая плавно затихает,
 * а мелодия приложения плавно появляется. Уходишь — всё возвращается.
 */
class XmbMusic {

    /** Громкость: 0 — выключена, 1 — минимальная, 2 — средняя, 3 — высокая. */
    var level = 2
        set(v) { field = v.coerceIn(0, LEVELS.lastIndex); update() }

    private var global: File? = null
    private var hover: File? = null
    private var paused = false

    private class Track(val file: File, val mp: MediaPlayer) {
        var vol = 0f
        var target = 0f
    }

    private val tracks = ArrayList<Track>()
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (step()) handler.postDelayed(this, 40)
        }
    }

    fun setGlobal(f: File?) {
        global = f
        // файл мог смениться при том же пути — начинаем заново
        tracks.filter { it.file == f }.forEach { it.target = 0f; it.vol = 0f }
        cleanup()
        update()
    }

    fun setHover(f: File?) {
        if (hover == f) return
        hover = f
        update()
    }

    fun pause() {
        paused = true
        for (t in tracks) try { if (t.mp.isPlaying) t.mp.pause() } catch (_: Exception) { }
    }

    fun resume() {
        paused = false
        for (t in tracks) if (t.target > 0f) try { t.mp.start() } catch (_: Exception) { }
        update()
    }

    fun release() {
        handler.removeCallbacks(tick)
        for (t in tracks) try { t.mp.release() } catch (_: Exception) { }
        tracks.clear()
    }

    private fun update() {
        val vol = LEVELS[level]
        val want = if (vol <= 0f) null else (hover ?: global)
        for (t in tracks) t.target = if (want != null && t.file == want) vol else 0f
        if (want != null && tracks.none { it.file == want && it.target > 0f }) {
            try {
                val mp = MediaPlayer()
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                mp.setDataSource(want.path)
                mp.isLooping = true
                mp.prepare()
                mp.setVolume(0f, 0f)
                if (!paused) mp.start()
                tracks.add(Track(want, mp).also { it.target = vol })
            } catch (_: Exception) {
            }
        }
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    /** Шаг плавного изменения громкости (около секунды на полный переход). */
    private fun step(): Boolean {
        var active = false
        for (t in tracks) {
            val d = t.target - t.vol
            if (kotlin.math.abs(d) > 0.001f) {
                t.vol += d.coerceIn(-0.045f, 0.045f)
                try { t.mp.setVolume(t.vol, t.vol) } catch (_: Exception) { }
                active = true
            }
        }
        cleanup()
        return active
    }

    private fun cleanup() {
        val it = tracks.iterator()
        while (it.hasNext()) {
            val t = it.next()
            if (t.target <= 0f && t.vol <= 0.001f) {
                try { t.mp.release() } catch (_: Exception) { }
                it.remove()
            }
        }
    }

    companion object {
        val LEVELS = floatArrayOf(0f, 0.25f, 0.55f, 1f)
        val LEVEL_NAMES = listOf("Выключена", "Минимальная", "Средняя", "Высокая")
    }
}
