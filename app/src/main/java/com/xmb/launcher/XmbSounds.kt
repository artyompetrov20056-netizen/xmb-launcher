package com.xmb.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Звуковое оформление меню в духе PS3. Все звуки синтезируются в коде при запуске
 * (никаких записей из PS3) и проигрываются через SoundPool без задержки.
 */
class XmbSounds(context: Context) {

    /** Громкость: 0 — выключен, 1 — минимальная, 2 — средняя, 3 — высокая. */
    var level = 2
        set(v) { field = v.coerceIn(0, LEVELS.lastIndex) }

    private val pool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val loaded: MutableSet<Int> = Collections.synchronizedSet(HashSet())
    private var tickId = 0
    private var okId = 0
    private var backId = 0
    private var keyId = 0
    private var holdId = 0
    private var launchId = 0
    private var lastTick = 0L
    private var lastKey = 0L

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) loaded.add(id) }
        try {
            tickId = load(context, "xmb_tick.wav", synthTick())
            okId = load(context, "xmb_ok.wav", synthOk())
            backId = load(context, "xmb_back.wav", synthBack())
            keyId = load(context, "xmb_key.wav", synthKey())
            holdId = load(context, "xmb_hold.wav", synthHold())
            launchId = load(context, "xmb_launch.wav", synthLaunch())
        } catch (_: Exception) {
        }
    }

    /** Прокрутка: переход на соседний пункт или категорию. */
    fun tick() {
        val now = System.currentTimeMillis()
        if (now - lastTick < 35) return
        lastTick = now
        play(tickId, 0.5f)
    }

    /** Нажатие клавиши на экранной клавиатуре. */
    fun key() {
        val now = System.currentTimeMillis()
        if (now - lastKey < 20) return
        lastKey = now
        play(keyId, 0.55f)
    }

    /** Выбор пункта / подтверждение. */
    fun ok() = play(okId, 0.55f)

    /** Назад / отмена. */
    fun back() = play(backId, 0.5f)

    /** Удержание приложения (открылось меню). */
    fun hold() = play(holdId, 0.6f)

    /** Запуск приложения. */
    fun launch() = play(launchId, 0.6f)

    fun release() = pool.release()

    private fun play(id: Int, vol: Float) {
        val v = vol * LEVELS[level]
        if (v > 0f && id != 0 && loaded.contains(id)) pool.play(id, v, v, 1, 0, 1f)
    }

    // ---------------- синтез ----------------

    private val rate = 44100
    private val noise = Random(3)

    private fun render(seconds: Float, f: (Float) -> Float): ShortArray {
        val n = (rate * seconds).toInt()
        return ShortArray(n) { i ->
            val t = i.toFloat() / rate
            val fadeOut = ((n - i).toFloat() / (rate * 0.004f)).coerceAtMost(1f)
            (f(t) * fadeOut * 32767f).toInt().coerceIn(-32767, 32767).toShort()
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

    private fun load(ctx: Context, name: String, pcm: ShortArray): Int {
        val file = File(ctx.cacheDir, name)
        writeWav(file, pcm)
        return pool.load(file.path, 1)
    }

    private fun writeWav(file: File, pcm: ShortArray) {
        val dataLen = pcm.size * 2
        val buf = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()); buf.putInt(36 + dataLen); buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()); buf.putInt(16); buf.putShort(1.toShort()); buf.putShort(1.toShort())
        buf.putInt(rate); buf.putInt(rate * 2); buf.putShort(2.toShort()); buf.putShort(16.toShort())
        buf.put("data".toByteArray()); buf.putInt(dataLen)
        for (s in pcm) buf.putShort(s)
        FileOutputStream(file).use { it.write(buf.array()) }
    }

    companion object {
        val LEVELS = floatArrayOf(0f, 0.2f, 0.5f, 1f)
        val LEVEL_NAMES = listOf("Выключен", "Минимальная", "Средняя", "Высокая")
    }
}
