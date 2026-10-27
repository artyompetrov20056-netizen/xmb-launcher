package com.xmb.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Звуки меню в духе PS3. Сами звуки синтезируются в коде при запуске
 * (никаких записей из PS3) и проигрываются через SoundPool без задержки.
 */
class XmbSounds(context: Context) {

    var enabled = true

    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val loaded: MutableSet<Int> = Collections.synchronizedSet(HashSet())
    private var tickId = 0
    private var okId = 0
    private var backId = 0
    private var lastTick = 0L

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) loaded.add(id) }
        try {
            tickId = load(context, "xmb_tick.wav", synthTick())
            okId = load(context, "xmb_ok.wav", synthOk())
            backId = load(context, "xmb_back.wav", synthBack())
        } catch (_: Exception) {
        }
    }

    /** Короткий щелчок при переходе на соседний пункт. */
    fun tick() {
        val now = System.currentTimeMillis()
        if (now - lastTick < 35) return
        lastTick = now
        play(tickId, 0.45f)
    }

    fun ok() = play(okId, 0.5f)
    fun back() = play(backId, 0.45f)

    fun release() = pool.release()

    private fun play(id: Int, vol: Float) {
        if (enabled && id != 0 && loaded.contains(id)) pool.play(id, vol, vol, 1, 0, 1f)
    }

    // ---------------- синтез ----------------

    private val rate = 44100

    private fun render(seconds: Float, f: (Float) -> Float): ShortArray {
        val n = (rate * seconds).toInt()
        return ShortArray(n) { i ->
            val t = i.toFloat() / rate
            val fadeOut = ((n - i).toFloat() / (rate * 0.004f)).coerceAtMost(1f)
            (f(t) * fadeOut * 32767f).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    private fun tone(freq: Float, t: Float) = sin(2.0 * PI * freq * t).toFloat()

    /** Мягкий «тик». */
    private fun synthTick() = render(0.045f) { t ->
        val attack = (t / 0.0015f).coerceAtMost(1f)
        attack * (0.55f * tone(2350f, t) * exp(-t / 0.007f) +
            0.25f * tone(4700f, t) * exp(-t / 0.003f))
    }

    /** Подтверждение — короткий двухтоновый звон. */
    private fun synthOk() = render(0.22f) { t ->
        val attack = (t / 0.003f).coerceAtMost(1f)
        val second = if (t > 0.05f) tone(1760f, t) * exp(-(t - 0.05f) / 0.06f) else 0f
        attack * (0.4f * tone(1320f, t) * exp(-t / 0.05f) + 0.35f * second)
    }

    /** Назад / отмена — низкий короткий тон. */
    private fun synthBack() = render(0.12f) { t ->
        val attack = (t / 0.003f).coerceAtMost(1f)
        attack * 0.45f * tone(740f - 900f * t, t) * exp(-t / 0.035f)
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
}
