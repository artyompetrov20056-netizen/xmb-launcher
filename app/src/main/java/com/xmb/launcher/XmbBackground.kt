package com.xmb.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Фон XMB: градиент цвета темы, волны и искры.
 * Общий для рабочего стола и экрана блокировки.
 */
class XmbBackground {

    /** Режим «Переливающийся»: раз в 2 минуты фон плавно (1 минуту) переходит к следующему цвету. */
    var autoTheme = false

    private val targetColors = IntArray(3)
    private val curColors = FloatArray(9)
    private var shaderColors = IntArray(3)
    private var shaderH = -1

    private val bgPaint = Paint()
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private val waveTop = Array(3) { Path() }
    private val waveBand = Array(3) { Path() }
    private val waveSegs = 72
    private val waveYs = FloatArray(waveSegs + 1)
    private val sparkN = 70
    private val rnd = Random(7)
    private val sparkX = FloatArray(sparkN) { rnd.nextFloat() }
    private val sparkY = FloatArray(sparkN) { rnd.nextFloat() - 0.5f }
    private val sparkSpeed = FloatArray(sparkN) { 0.004f + rnd.nextFloat() * 0.018f }
    private val sparkPhase = FloatArray(sparkN) { rnd.nextFloat() * 6.28f }

    init {
        setTheme(XmbTheme.ALL[0], animate = false)
    }

    fun setTheme(theme: XmbTheme, animate: Boolean) {
        targetColors[0] = theme.top
        targetColors[1] = theme.mid
        targetColors[2] = theme.bottom
        if (!animate) {
            for (i in 0 until 3) for (ch in 0 until 3) curColors[i * 3 + ch] = channel(targetColors[i], ch).toFloat()
        }
    }

    /** Загрузить сохранённую тему (общие настройки рабочего стола). */
    fun loadFromPrefs(context: Context) {
        val prefs = context.getSharedPreferences("xmb", Context.MODE_PRIVATE)
        val idx = prefs.getInt("theme", 0).coerceIn(0, XmbTheme.ALL.lastIndex)
        autoTheme = prefs.getBoolean("theme_auto", false)
        setTheme(XmbTheme.ALL[idx], animate = false)
        if (autoTheme) {
            applyAutoColors()
            for (i in 0 until 3) for (ch in 0 until 3) curColors[i * 3 + ch] = channel(targetColors[i], ch).toFloat()
        }
    }

    /** Плавное изменение цвета. Возвращает true, пока цвет меняется. */
    fun step(dt: Float): Boolean {
        var moving = false
        if (autoTheme) applyAutoColors()
        val kc = 1f - exp(-dt * 3f)
        for (i in 0 until 3) for (ch in 0 until 3) {
            val idx = i * 3 + ch
            val d = channel(targetColors[i], ch) - curColors[idx]
            if (abs(d) > 0.5f) { curColors[idx] += d * kc; moving = true } else curColors[idx] = channel(targetColors[i], ch).toFloat()
        }
        return moving
    }

    /**
     * Рисует фон. orient: 0 — горизонтально (волны поперёк, как на PS3),
     * 1 — вертикально (волны вдоль экрана, наклон ~20° от вертикали, не сплюснуты).
     */
    fun draw(c: Canvas, width: Int, height: Int, unit: Float, orient: Float, time: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cols = IntArray(3) { Color.rgb(curColors[it * 3].toInt(), curColors[it * 3 + 1].toInt(), curColors[it * 3 + 2].toInt()) }
        if (!cols.contentEquals(shaderColors) || shaderH != height) {
            shaderColors = cols
            shaderH = height
            bgPaint.shader = LinearGradient(0f, 0f, 0f, h, cols, floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w, h, bgPaint)

        val cross = lerp(h, w, orient)
        val len = lerp(w, h, orient)
        val span = hypot(w, h) * 1.15f
        val ampScale = lerp(1f, 1.7f, orient)
        val cx = w * 0.5f
        val cy = lerp(h * 0.60f, h * 0.50f, orient)

        c.save()
        c.translate(cx, cy)
        c.rotate(-70f * orient)

        linePaint.strokeWidth = max(1.5f, unit * 0.0025f)
        for (k in 0 until 3) {
            val top = waveTop[k]
            val band = waveBand[k]
            top.rewind(); band.rewind()
            val amp = cross * (0.045f + 0.018f * k) * ampScale
            val f1 = (1.1f + 0.4f * k) * 2f * PI.toFloat() / len
            val ph = time * (0.22f + 0.07f * k) + k * 1.9f
            for (s in 0..waveSegs) {
                val x = -span / 2f + span * s / waveSegs
                val y = k * cross * 0.012f + amp * sin(x * f1 + ph) + amp * 0.35f * sin(x * f1 * 0.47f - ph * 1.4f)
                waveYs[s] = y
                if (s == 0) { top.moveTo(x, y); band.moveTo(x, y) } else { top.lineTo(x, y); band.lineTo(x, y) }
            }
            for (s in waveSegs downTo 0) {
                val x = -span / 2f + span * s / waveSegs
                val th = cross * 0.022f * ampScale * (1.2f + sin(x * f1 * 0.8f + ph * 0.7f + k))
                band.lineTo(x, waveYs[s] + th)
            }
            band.close()
            bandPaint.alpha = 24 - k * 5
            c.drawPath(band, bandPaint)
            linePaint.alpha = 80 - k * 18
            c.drawPath(top, linePaint)
        }

        for (i in 0 until sparkN) {
            val x = ((sparkX[i] + time * sparkSpeed[i]) % 1f) * span - span / 2f
            val y = sparkY[i] * cross * 0.30f * ampScale
            val a = 0.5f + 0.5f * sin(time * 2.2f + sparkPhase[i])
            sparkPaint.alpha = (25 + 120 * a).toInt()
            c.drawCircle(x, y, unit * (0.0015f + 0.0012f * (i % 3)), sparkPaint)
        }
        c.restore()
    }

    private fun applyAutoColors() {
        val list = XmbTheme.CYCLE
        val period = 120_000L
        val now = System.currentTimeMillis()
        val idx = now / period
        val sec = (now % period) / 1000f
        val n = list.size
        val from = list[(((idx - 1) % n + n) % n).toInt()]
        val to = list[(idx % n).toInt()]
        val x = (sec / 60f).coerceIn(0f, 1f)
        val f = x * x * (3f - 2f * x)
        targetColors[0] = lerpColor(from.top, to.top, f)
        targetColors[1] = lerpColor(from.mid, to.mid, f)
        targetColors[2] = lerpColor(from.bottom, to.bottom, f)
    }

    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

    private fun lerpColor(a: Int, b: Int, f: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt(),
    )

    private fun channel(color: Int, ch: Int): Int = when (ch) {
        0 -> Color.red(color)
        1 -> Color.green(color)
        else -> Color.blue(color)
    }
}
