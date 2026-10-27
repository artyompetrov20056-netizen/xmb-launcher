package com.xmb.launcher

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Звуковое оформление меню в духе PS3. Все звуки синтезируются в коде
 * (никаких записей из PS3).
 *
 * Пока рабочий стол открыт, держим аудиоканал открытым и непрерывно пишем в него тишину,
 * подмешивая звуки меню. Иначе Bluetooth-наушники «засыпают» и короткие щелчки
 * (30–200 мс) теряются целиком, пока канал просыпается.
 */
class XmbSounds {

    /** Громкость: 0 — выключен, 1 — минимальная, 2 — средняя, 3 — высокая. */
    var level = 2
        set(v) { field = v.coerceIn(0, LEVELS.lastIndex) }

    private val rate = 44100
    private val noise = Random(3)

    private val tickClip = synthTick()
    private val okClip = synthOk()
    private val backClip = synthBack()
    private val keyClip = synthKey()
    private val holdClip = synthHold()
    private val launchClip = synthLaunch()

    private class Voice(val data: FloatArray, val vol: Float) { var pos = 0 }

    private val voices = ArrayList<Voice>()
    @Volatile private var running = false
    @Volatile private var generation = 0
    private val handler = Handler(Looper.getMainLooper())
    private val stopRunnable = Runnable { running = false }

    private var lastTick = 0L
    private var lastKey = 0L

    // ---------------- управление каналом ----------------

    /** Рабочий стол на экране — открываем канал. */
    fun start() {
        handler.removeCallbacks(stopRunnable)
        if (running || level == 0) return
        running = true
        val gen = ++generation
        Thread({ loop(gen) }, "xmb-audio").start()
    }

    /** Ушли с рабочего стола — закрываем канал чуть позже, чтобы доиграл звук запуска. */
    fun stopSoon() {
        handler.removeCallbacks(stopRunnable)
        handler.postDelayed(stopRunnable, 1500)
    }

    fun release() {
        handler.removeCallbacks(stopRunnable)
        running = false
    }

    private fun loop(gen: Int) {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Exception) { }
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(max(minBuf, 4096))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (_: Exception) {
            return
        }

        try {
            track.play()
            val chunk = 256
            val mix = FloatArray(chunk)
            val out = ShortArray(chunk)
            while (running && gen == generation) {
                java.util.Arrays.fill(mix, 0f)
                synchronized(voices) {
                    val it = voices.iterator()
                    while (it.hasNext()) {
                        val v = it.next()
                        val n = min(chunk, v.data.size - v.pos)
                        for (i in 0 until n) mix[i] += v.data[v.pos + i] * v.vol
                        v.pos += n
                        if (v.pos >= v.data.size) it.remove()
                    }
                }
                for (i in 0 until chunk) out[i] = (mix[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                if (track.write(out, 0, chunk) < 0) break
            }
        } catch (_: Exception) {
        } finally {
            try { track.stop() } catch (_: Exception) { }
            track.release()
        }
    }

    // ---------------- звуки ----------------

    /** Прокрутка: переход на соседний пункт или категорию. */
    fun tick() {
        val now = System.currentTimeMillis()
        if (now - lastTick < 35) return
        lastTick = now
        play(tickClip, 0.5f)
    }

    /** Нажатие клавиши на экранной клавиатуре. */
    fun key() {
        val now = System.currentTimeMillis()
        if (now - lastKey < 20) return
        lastKey = now
        play(keyClip, 0.55f)
    }

    /** Выбор пункта / подтверждение. */
    fun ok() = play(okClip, 0.55f)

    /** Назад / отмена. */
    fun back() = play(backClip, 0.5f)

    /** Удержание приложения (открылось меню). */
    fun hold() = play(holdClip, 0.6f)

    /** Запуск приложения. */
    fun launch() = play(launchClip, 0.6f)

    private fun play(clip: FloatArray, vol: Float) {
        val v = vol * LEVELS[level]
        if (v <= 0f) return
        synchronized(voices) {
            if (voices.size < 8) voices.add(Voice(clip, v))
        }
        if (!running) start()
    }

    // ---------------- синтез ----------------

    private fun render(seconds: Float, f: (Float) -> Float): FloatArray {
        val n = (rate * seconds).toInt()
        return FloatArray(n) { i ->
            val t = i.toFloat() / rate
            val fadeOut = ((n - i).toFloat() / (rate * 0.004f)).coerceAtMost(1f)
            f(t) * fadeOut
        }
    }

    private fun tone(freq: Float, t: Float) = sin(2.0 * PI * freq * t).toFloat()

    /** Тон с плавно меняющейся частотой: f0 + k·t. */
    private fun chirp(f0: Float, k: Float, t: Float) = sin(2.0 * PI * (f0 * t + 0.5f * k * t * t)).toFloat()

    private fun attack(t: Float, a: Float) = (t / a).coerceAtMost(1f)

    /** Мягкий «тик» прокрутки. */
    private fun synthTick() = render(0.045f) { t ->
        attack(t, 0.0015f) * (0.55f * tone(2350f, t) * exp(-t / 0.007f) +
            0.25f * tone(4700f, t) * exp(-t / 0.003f))
    }

    /** Подтверждение — короткий двухтоновый звон. */
    private fun synthOk() = render(0.22f) { t ->
        val second = if (t > 0.05f) tone(1760f, t) * exp(-(t - 0.05f) / 0.06f) else 0f
        attack(t, 0.003f) * (0.4f * tone(1320f, t) * exp(-t / 0.05f) + 0.35f * second)
    }

    /** Назад — короткий нисходящий тон. */
    private fun synthBack() = render(0.12f) { t ->
        attack(t, 0.003f) * 0.45f * chirp(740f, -1800f, t) * exp(-t / 0.035f)
    }

    /** Клавиша — сухой короткий щелчок. */
    private fun synthKey() = render(0.03f) { t ->
        val n = noise.nextFloat() * 2f - 1f
        attack(t, 0.0008f) * (0.35f * n * exp(-t / 0.0035f) + 0.35f * tone(1500f, t) * exp(-t / 0.006f))
    }

    /** Удержание — мягкий восходящий «вжух». */
    private fun synthHold() = render(0.24f) { t ->
        attack(t, 0.01f) * (0.45f * chirp(330f, 1500f, t) * exp(-t / 0.09f) +
            0.15f * chirp(660f, 3000f, t) * exp(-t / 0.06f))
    }

    /** Запуск приложения — восходящий звон с «искрами». */
    private fun synthLaunch() = render(0.5f) { t ->
        val s1 = if (t > 0.12f) tone(2093f, t) * exp(-(t - 0.12f) / 0.12f) else 0f
        val s2 = if (t > 0.20f) tone(3136f, t) * exp(-(t - 0.20f) / 0.10f) else 0f
        attack(t, 0.006f) * (0.35f * chirp(520f, 2300f, t) * exp(-t / 0.16f) + 0.2f * s1 + 0.12f * s2)
    }

    companion object {
        val LEVELS = floatArrayOf(0f, 0.2f, 0.5f, 1f)
        val LEVEL_NAMES = listOf("Выключен", "Минимальная", "Средняя", "Высокая")
    }
}
