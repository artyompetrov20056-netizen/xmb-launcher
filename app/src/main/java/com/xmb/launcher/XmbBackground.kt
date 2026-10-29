package com.xmb.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Фон: градиент + анимированная сцена выбранного стиля.
 * Общий для рабочего стола, экрана блокировки, будильника и часов.
 */
class XmbBackground {

    /** Режим «Переливающийся»: раз в 2 минуты фон плавно (1 минуту) переходит к следующему цвету. */
    var autoTheme = false
    var style: VisualStyle = VisualStyles.ALL[0]

    private val targetColors = IntArray(3)
    private val themeColors = IntArray(3)
    private val curColors = FloatArray(9)
    private var shaderColors = IntArray(3)
    private var shaderH = -1

    private val bgPaint = Paint()
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeJoin = Paint.Join.ROUND }

    private val waveTop = Array(3) { Path() }
    private val waveBand = Array(3) { Path() }
    private val waveSegs = 72
    private val waveYs = FloatArray(waveSegs + 1)
    private val path = Path()

    private val rnd = Random(7)
    private val lips = LipsArt()
    private val sparkN = 70
    private val sparkX = FloatArray(sparkN) { rnd.nextFloat() }
    private val sparkY = FloatArray(sparkN) { rnd.nextFloat() - 0.5f }
    private val sparkSpeed = FloatArray(sparkN) { 0.004f + rnd.nextFloat() * 0.018f }
    private val sparkPhase = FloatArray(sparkN) { rnd.nextFloat() * 6.28f }

    // сферы
    private val sphereShader = RadialGradient(
        -0.35f, -0.4f, 1.45f,
        intArrayOf(Color.argb(235, 235, 235, 255), Color.argb(230, 130, 130, 240), Color.argb(220, 55, 55, 170), Color.argb(200, 20, 18, 80)),
        floatArrayOf(0f, 0.22f, 0.62f, 1f), Shader.TileMode.CLAMP
    )
    private val spherePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = sphereShader }

    // мягкое свечение (частицы)
    private val warmGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 1f,
            intArrayOf(Color.argb(255, 255, 236, 200), Color.argb(110, 255, 200, 140), Color.TRANSPARENT),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
    }
    private val coolGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 1f,
            intArrayOf(Color.argb(160, 120, 170, 255), Color.argb(50, 80, 120, 230), Color.TRANSPARENT),
            floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
    }
    // лучи света
    private val sheetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, -0.5f, 0f, 0.5f,
            intArrayOf(Color.TRANSPARENT, Color.argb(46, 160, 200, 255), Color.argb(70, 220, 235, 255), Color.argb(46, 160, 200, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f), Shader.TileMode.CLAMP)
    }

    // кубы: x, y, z, размер, углы, скорости вращения
    private val cubeN = 16
    private val cube = Array(cubeN) { FloatArray(8) }
    private val cubeOrder = Array(cubeN) { it }
    private val vx = FloatArray(8)
    private val vy = FloatArray(8)
    private val vz = FloatArray(8)
    private val px = FloatArray(8)
    private val py = FloatArray(8)
    private val faces = arrayOf(
        intArrayOf(0, 1, 3, 2), intArrayOf(4, 6, 7, 5), intArrayOf(0, 4, 5, 1),
        intArrayOf(2, 3, 7, 6), intArrayOf(0, 2, 6, 4), intArrayOf(1, 5, 7, 3),
    )
    private var lastTime = 0f

    // пузырьки «Виты» и частицы
    private val bubN = 26
    private val bubX = FloatArray(bubN) { rnd.nextFloat() }
    private val bubY = FloatArray(bubN) { rnd.nextFloat() }
    private val bubR = FloatArray(bubN) { 0.008f + rnd.nextFloat() * 0.035f }
    private val bubS = FloatArray(bubN) { 0.01f + rnd.nextFloat() * 0.025f }
    private val partN = 60
    private val partX = FloatArray(partN) { rnd.nextFloat() }
    private val partY = FloatArray(partN) { rnd.nextFloat() }
    private val partR = FloatArray(partN) { rnd.nextFloat() }
    private val partS = FloatArray(partN) { 0.004f + rnd.nextFloat() * 0.012f }
    private val partP = FloatArray(partN) { rnd.nextFloat() * 6.28f }

    init {
        setTheme(XmbTheme.ALL[0], animate = false)
        for (i in 0 until cubeN) resetCube(i, true)
    }

    fun setTheme(theme: XmbTheme, animate: Boolean) {
        themeColors[0] = theme.top
        themeColors[1] = theme.mid
        themeColors[2] = theme.bottom
        updateTargets()
        if (!animate) snapColors()
    }

    fun setStyle(s: VisualStyle, animate: Boolean) {
        style = s
        Glyphs.weight = s.weight
        Glyphs.tint = s.glyphInk
        Glyphs.boneMode = s.frame == FrameKind.CHROME
        updateTargets()
        if (!animate) snapColors()
    }

    private fun snapColors() {
        for (i in 0 until 3) for (ch in 0 until 3) curColors[i * 3 + ch] = channel(targetColors[i], ch).toFloat()
    }

    private fun updateTargets() {
        val p = style.palette
        if (p != null) { for (i in 0 until 3) targetColors[i] = p[i]; return }
        if (autoTheme) applyAutoColors() else for (i in 0 until 3) targetColors[i] = themeColors[i]
    }

    /** Загрузить сохранённые тему и стиль (общие настройки рабочего стола). */
    fun loadFromPrefs(context: Context) {
        val prefs = context.getSharedPreferences("xmb", Context.MODE_PRIVATE)
        val idx = prefs.getInt("theme", 0).coerceIn(0, XmbTheme.ALL.lastIndex)
        autoTheme = prefs.getBoolean("theme_auto", false)
        style = VisualStyles.current(context)
        Glyphs.weight = style.weight
        Glyphs.tint = style.glyphInk
        Glyphs.boneMode = style.frame == FrameKind.CHROME
        setTheme(XmbTheme.ALL[idx], animate = false)
    }

    /** Основной цвет темы сейчас (с учётом плавных переходов). */
    fun accent(): Int = Color.rgb(curColors[3].toInt(), curColors[4].toInt(), curColors[5].toInt())

    /** Плавное изменение цвета. Возвращает true, пока цвет меняется. */
    fun step(dt: Float): Boolean {
        var moving = false
        updateTargets()
        val kc = 1f - exp(-dt * 3f)
        for (i in 0 until 3) for (ch in 0 until 3) {
            val idx = i * 3 + ch
            val d = channel(targetColors[i], ch) - curColors[idx]
            if (abs(d) > 0.5f) { curColors[idx] += d * kc; moving = true } else curColors[idx] = channel(targetColors[i], ch).toFloat()
        }
        return moving
    }

    // =====================================================================

    fun draw(c: Canvas, width: Int, height: Int, unit: Float, orient: Float, time: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val dt = (time - lastTime).coerceIn(0f, 0.05f)
        lastTime = time

        val cols = IntArray(3) { Color.rgb(curColors[it * 3].toInt(), curColors[it * 3 + 1].toInt(), curColors[it * 3 + 2].toInt()) }
        if (!cols.contentEquals(shaderColors) || shaderH != height) {
            shaderColors = cols
            shaderH = height
            bgPaint.shader = LinearGradient(0f, 0f, 0f, h, cols, floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w, h, bgPaint)

        when (style.scene) {
            Scene.WAVES -> drawWaves(c, w, h, unit, orient, time)
            Scene.SPHERES -> drawSpheres(c, w, h, unit, time)
            Scene.TILES -> drawTiles(c, w, h, unit, time)
            Scene.CUBES -> drawCubes(c, w, h, unit, dt)
            Scene.SOFT_WAVE -> drawSoftWave(c, w, h, unit, orient, time)
            Scene.LINES -> drawLines(c, w, h, unit, orient, time)
            Scene.RIBBONS -> drawRibbons(c, w, h, unit, orient, time)
            Scene.LIGHT -> drawLight(c, w, h, unit, time)
            Scene.PARTICLES -> drawParticles(c, w, h, unit, time)
            Scene.CHROME -> drawChrome(c, w, h, unit, time)
            Scene.WEBCORE -> drawWebcore(c, w, h, unit, time)
            Scene.SNAKE -> drawSnake(c, w, h, unit, time)
            Scene.CRYSTAL -> drawCrystalWorld(c, w, h, unit, time)
            Scene.LIPS -> lips.draw(c, w, h, unit, time, accent())
        }
    }

    // ---------------- XMB: волны (как было) ----------------

    private fun beginWaveFrame(c: Canvas, w: Float, h: Float, orient: Float) {
        c.save()
        c.translate(w * 0.5f, lerp(h * 0.60f, h * 0.50f, orient))
        c.rotate(-70f * orient)
    }

    private fun drawWaves(c: Canvas, w: Float, h: Float, unit: Float, orient: Float, time: Float) {
        val cross = lerp(h, w, orient)
        val len = lerp(w, h, orient)
        val span = hypot(w, h) * 1.15f
        val ampScale = lerp(1f, 1.7f, orient)
        beginWaveFrame(c, w, h, orient)
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

    // ---------------- Мягкая волна (PSX) ----------------

    private fun drawSoftWave(c: Canvas, w: Float, h: Float, unit: Float, orient: Float, time: Float) {
        val cross = lerp(h, w, orient)
        val len = lerp(w, h, orient)
        val span = hypot(w, h) * 1.2f
        beginWaveFrame(c, w, h, orient)
        for (k in 0 until 2) {
            path.rewind()
            val amp = cross * (0.07f + 0.03f * k)
            val f1 = (0.7f + 0.25f * k) * 2f * PI.toFloat() / len
            val ph = time * (0.12f + 0.05f * k) + k * 2.3f
            for (s in 0..waveSegs) {
                val x = -span / 2f + span * s / waveSegs
                val y = amp * sin(x * f1 + ph)
                waveYs[s] = y
                if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            for (s in waveSegs downTo 0) {
                val x = -span / 2f + span * s / waveSegs
                path.lineTo(x, waveYs[s] + cross * (0.10f + 0.04f * sin(x * f1 * 0.6f + ph)))
            }
            path.close()
            bandPaint.alpha = 26 - k * 8
            c.drawPath(path, bandPaint)
        }
        // мягкий блик по гребню
        path.rewind()
        val amp = cross * 0.07f
        val f1 = 0.7f * 2f * PI.toFloat() / len
        val ph = time * 0.12f
        for (s in 0..waveSegs) {
            val x = -span / 2f + span * s / waveSegs
            val y = amp * sin(x * f1 + ph)
            if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        linePaint.strokeWidth = max(2f, unit * 0.006f)
        linePaint.alpha = 34
        c.drawPath(path, linePaint)
        c.restore()
    }

    // ---------------- Портатив (PSP): пучок тонких линий ----------------

    private fun drawLines(c: Canvas, w: Float, h: Float, unit: Float, orient: Float, time: Float) {
        val cross = lerp(h, w, orient)
        val len = lerp(w, h, orient)
        val span = hypot(w, h) * 1.15f
        beginWaveFrame(c, w, h, orient)
        linePaint.strokeWidth = max(1.2f, unit * 0.0018f)
        val n = 16
        for (j in 0 until n) {
            path.rewind()
            val t = j / (n - 1f)
            val amp = cross * (0.05f + 0.035f * sin(t * PI.toFloat()))
            val f1 = 0.95f * 2f * PI.toFloat() / len
            val ph = time * 0.2f + t * 1.1f
            val off = (t - 0.5f) * cross * 0.06f
            for (s in 0..waveSegs) {
                val x = -span / 2f + span * s / waveSegs
                val y = off + amp * sin(x * f1 + ph) + cross * 0.02f * sin(x * f1 * 2.1f - time * 0.3f + t * 3f)
                if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            linePaint.alpha = (30 + 55 * sin(t * PI.toFloat())).toInt()
            c.drawPath(path, linePaint)
        }
        c.restore()
    }

    // ---------------- Вита: широкие ленты и пузырьки ----------------

    private fun drawRibbons(c: Canvas, w: Float, h: Float, unit: Float, orient: Float, time: Float) {
        val cross = lerp(h, w, orient)
        val len = lerp(w, h, orient)
        val span = hypot(w, h) * 1.2f
        beginWaveFrame(c, w, h, orient)
        for (k in 0 until 3) {
            path.rewind()
            val amp = cross * (0.10f - 0.02f * k)
            val f1 = (0.8f + 0.35f * k) * 2f * PI.toFloat() / len
            val ph = time * (0.16f + 0.06f * k) + k * 2.1f
            for (s in 0..waveSegs) {
                val x = -span / 2f + span * s / waveSegs
                val y = (k - 1) * cross * 0.04f + amp * sin(x * f1 + ph)
                waveYs[s] = y
                if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            for (s in waveSegs downTo 0) {
                val x = -span / 2f + span * s / waveSegs
                path.lineTo(x, waveYs[s] + cross * (0.06f + 0.03f * sin(x * f1 * 1.3f - ph)))
            }
            path.close()
            bandPaint.alpha = 30 - k * 6
            c.drawPath(path, bandPaint)
        }
        c.restore()
        // пузырьки поднимаются вверх
        stroke.strokeWidth = max(1f, unit * 0.0022f)
        for (i in 0 until bubN) {
            val y = 1.1f - ((bubY[i] + time * bubS[i]) % 1.2f)
            val x = bubX[i] + 0.02f * sin(time * 0.8f + i)
            val r = bubR[i] * unit
            stroke.alpha = (40 + 50 * (1f - abs(y - 0.5f) * 1.6f).coerceIn(0f, 1f)).toInt()
            c.drawCircle(x * w, y * h, r, stroke)
            fill.alpha = stroke.alpha / 3
            c.drawCircle(x * w - r * 0.35f, y * h - r * 0.35f, r * 0.22f, fill)
        }
    }

    // ---------------- Сферы (PS1) ----------------

    private fun drawSpheres(c: Canvas, w: Float, h: Float, unit: Float, time: Float) {
        for (i in 0 until 7) {
            val r = unit * (0.07f + 0.035f * i)
            val sp = 0.05f + 0.02f * (i % 3)
            val x = w * (0.5f + 0.38f * sin(time * sp + i * 1.7f))
            val y = h * (0.5f + 0.38f * cos(time * sp * 0.8f + i * 2.3f))
            spherePaint.alpha = 150 + (i % 3) * 30
            c.save(); c.translate(x, y); c.scale(r, r)
            c.drawCircle(0f, 0f, 1f, spherePaint)
            c.restore()
        }
        // тонкая орбита
        stroke.strokeWidth = max(1f, unit * 0.002f)
        stroke.alpha = 30
        val rr = unit * (0.42f + 0.02f * sin(time * 0.4f))
        c.drawCircle(w * 0.5f, h * 0.5f, rr, stroke)
    }

    // ---------------- Плитка (PS one): блик бежит по плиткам ----------------

    private fun drawTiles(c: Canvas, w: Float, h: Float, unit: Float, time: Float) {
        val ts = unit * 0.15f
        val gap = ts * 0.09f
        val cols = (w / ts).toInt() + 2
        val rows = (h / ts).toInt() + 2
        val sweep = ((time * 0.09f) % 1.6f) - 0.3f
        val lw = max(1f, unit * 0.002f)
        for (iy in 0 until rows) for (ix in 0 until cols) {
            val l = ix * ts - ts * 0.3f
            val t = iy * ts - ts * 0.3f
            val d = (l + t) / (w + h)
            val glowA = exp(-((d - sweep) * (d - sweep)) / 0.004f)
            val pulse = 0.5f + 0.5f * sin(time * 0.7f + ix * 0.9f + iy * 1.3f)
            fill.color = Color.WHITE
            fill.alpha = (14 + 10 * pulse + 70 * glowA).toInt()
            c.drawRect(l + gap, t + gap, l + ts - gap, t + ts - gap, fill)
            stroke.strokeWidth = lw
            stroke.color = Color.WHITE
            stroke.alpha = (55 + 80 * glowA).toInt()
            c.drawLine(l + gap, t + ts - gap, l + gap, t + gap, stroke)
            c.drawLine(l + gap, t + gap, l + ts - gap, t + gap, stroke)
            stroke.color = Color.BLACK
            stroke.alpha = 55
            c.drawLine(l + ts - gap, t + gap, l + ts - gap, t + ts - gap, stroke)
            c.drawLine(l + ts - gap, t + ts - gap, l + gap, t + ts - gap, stroke)
        }
        stroke.color = Color.WHITE
    }

    // ---------------- Кубы (PS2): летят из глубины ----------------

    private fun resetCube(i: Int, anyZ: Boolean) {
        val q = cube[i]
        q[0] = rnd.nextFloat() * 2f - 1f
        q[1] = rnd.nextFloat() * 2f - 1f
        q[2] = if (anyZ) 1.5f + rnd.nextFloat() * 7f else 8f + rnd.nextFloat() * 1.5f
        q[3] = 0.12f + rnd.nextFloat() * 0.22f
        q[4] = rnd.nextFloat() * 6.28f
        q[5] = rnd.nextFloat() * 6.28f
        q[6] = (rnd.nextFloat() - 0.5f) * 0.8f
        q[7] = (rnd.nextFloat() - 0.5f) * 0.8f
    }

    private fun drawCubes(c: Canvas, w: Float, h: Float, unit: Float, dt: Float) {
        // голубая дымка в центре
        val gr = unit * 0.9f
        coolGlow.alpha = 120
        c.save(); c.translate(w * 0.5f, h * 0.5f); c.scale(gr, gr); c.drawCircle(0f, 0f, 1f, coolGlow); c.restore()

        for (i in 0 until cubeN) {
            val q = cube[i]
            q[2] -= dt * 0.45f
            q[4] += q[6] * dt
            q[5] += q[7] * dt
            if (q[2] < 1.1f) resetCube(i, false)
        }
        cubeOrder.sortByDescending { cube[it][2] }
        val f = unit * 0.9f
        val sx = w / unit * 1.6f
        val sy = h / unit * 1.6f
        // свет сверху-слева-спереди
        val lx = -0.4f; val ly = -0.6f; val lz = -0.7f
        val ln = sqrt(lx * lx + ly * ly + lz * lz)
        for (i in cubeOrder) {
            val q = cube[i]
            val cx = q[0] * sx
            val cy = q[1] * sy
            val cz = q[2]
            val s = q[3]
            val ca = cos(q[4]); val sa = sin(q[4])
            val cb = cos(q[5]); val sb = sin(q[5])
            for (v in 0 until 8) {
                val x0 = if (v and 1 == 0) -s else s
                val y0 = if (v and 2 == 0) -s else s
                val z0 = if (v and 4 == 0) -s else s
                // вращение вокруг Y, затем X
                val x1 = x0 * ca + z0 * sa
                val z1 = -x0 * sa + z0 * ca
                val y2 = y0 * cb - z1 * sb
                val z2 = y0 * sb + z1 * cb
                vx[v] = x1 + cx; vy[v] = y2 + cy; vz[v] = z2 + cz
                px[v] = w / 2f + vx[v] * f / vz[v]
                py[v] = h / 2f + vy[v] * f / vz[v]
            }
            val fog = (1f - (cz - 1.1f) / 8f).coerceIn(0f, 1f)
            for (fc in faces) {
                val a = fc[0]; val b = fc[1]; val d = fc[3]
                val ux = vx[b] - vx[a]; val uy = vy[b] - vy[a]; val uz = vz[b] - vz[a]
                val wx = vx[d] - vx[a]; val wy = vy[d] - vy[a]; val wz = vz[d] - vz[a]
                var nx = uy * wz - uz * wy
                var ny = uz * wx - ux * wz
                var nz = ux * wy - uy * wx
                val nl = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
                nx /= nl; ny /= nl; nz /= nl
                // смотрим из начала координат
                if (nx * vx[a] + ny * vy[a] + nz * vz[a] >= 0f) continue
                val lit = 0.30f + 0.70f * max(0f, -(nx * lx + ny * ly + nz * lz) / ln)
                fill.color = Color.rgb((150 * lit + 20).toInt(), (160 * lit + 22).toInt(), (185 * lit + 30).toInt())
                fill.alpha = (235 * fog).toInt()
                path.rewind()
                path.moveTo(px[fc[0]], py[fc[0]])
                path.lineTo(px[fc[1]], py[fc[1]])
                path.lineTo(px[fc[2]], py[fc[2]])
                path.lineTo(px[fc[3]], py[fc[3]])
                path.close()
                c.drawPath(path, fill)
            }
        }
        fill.color = Color.WHITE
    }

    // ---------------- Глубина (PS4): медленные лучи света ----------------

    private fun drawLight(c: Canvas, w: Float, h: Float, unit: Float, time: Float) {
        val diag = hypot(w, h)
        for (k in 0 until 3) {
            val t = time * (0.05f + 0.02f * k) + k * 2.1f
            val cx = w * (0.5f + 0.35f * sin(t))
            val cy = h * (0.5f + 0.25f * cos(t * 0.7f))
            val thick = unit * (0.35f + 0.15f * k)
            sheetPaint.alpha = 255 - k * 60
            c.save()
            c.translate(cx, cy)
            c.rotate(-35f + 8f * sin(t * 0.6f) + k * 12f)
            c.scale(diag * 1.4f, thick)
            c.drawRect(-0.5f, -0.5f, 0.5f, 0.5f, sheetPaint)
            c.restore()
        }
        for (i in 0 until 30) {
            val x = ((sparkX[i] + time * sparkSpeed[i] * 0.6f) % 1f) * w
            val y = (sparkY[i] + 0.5f) * h
            sparkPaint.alpha = (20 + 60 * (0.5f + 0.5f * sin(time * 1.5f + sparkPhase[i]))).toInt()
            c.drawCircle(x, y, unit * 0.002f, sparkPaint)
        }
    }

    // ---------------- Частицы (PS5): тёплые светящиеся частицы ----------------

    private fun drawParticles(c: Canvas, w: Float, h: Float, unit: Float, time: Float) {
        val gr = unit * 0.8f
        warmGlow.alpha = 22
        c.save(); c.translate(w * 0.5f, h * 0.95f); c.scale(gr * 1.6f, gr); c.drawCircle(0f, 0f, 1f, warmGlow); c.restore()
        for (i in 0 until partN) {
            val y = 1.05f - ((partY[i] + time * partS[i]) % 1.1f)
            val x = partX[i] + 0.015f * sin(time * 0.5f + partP[i])
            val depth = partR[i]
            val r = unit * (0.003f + 0.018f * depth * depth)
            val tw = 0.55f + 0.45f * sin(time * (1.2f + depth) + partP[i])
            warmGlow.alpha = (70 + 150 * tw * (1f - depth * 0.5f)).toInt().coerceIn(0, 255)
            c.save(); c.translate(x * w, y * h); c.scale(r, r); c.drawCircle(0f, 0f, 1f, warmGlow); c.restore()
        }
    }

    // ---------------- Хром: планета, сетка-пол, рамка из бусин ----------------

    private val starN = 90
    private val starX = FloatArray(starN) { rnd.nextFloat() }
    private val starY = FloatArray(starN) { rnd.nextFloat() * 0.75f }
    private val starP = FloatArray(starN) { rnd.nextFloat() * 6.28f }
    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(-0.3f, -0.35f, 1.25f,
            intArrayOf(Color.rgb(250, 248, 255), Color.rgb(196, 190, 236), Color.rgb(110, 100, 190), Color.rgb(40, 30, 96)),
            floatArrayOf(0f, 0.3f, 0.7f, 1f), Shader.TileMode.CLAMP)
    }
    private val rainbow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = android.graphics.SweepGradient(0f, 0f,
            intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED), null)
    }
    private val beadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(-0.35f, -0.4f, 1.3f,
            intArrayOf(Color.WHITE, Color.rgb(170, 172, 200), Color.rgb(60, 58, 90)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
    }

    private fun drawChrome(c: Canvas, w: Float, h: Float, unit: Float, time: Float) {
        // звёзды
        for (i in 0 until starN) {
            sparkPaint.alpha = (60 + 150 * (0.5f + 0.5f * sin(time * 1.8f + starP[i]))).toInt()
            c.drawCircle(starX[i] * w, starY[i] * h, unit * (0.0015f + 0.0015f * (i % 3)), sparkPaint)
        }
        // хромовая планета с радужным переливом
        val ox = w * 0.66f
        val oy = h * 0.26f
        val orr = unit * 0.30f
        orbPaint.alpha = 235
        c.save(); c.translate(ox, oy); c.scale(orr, orr); c.drawCircle(0f, 0f, 1f, orbPaint)
        c.rotate(time * 14f)
        rainbow.alpha = 55
        c.drawCircle(0f, 0f, 1f, rainbow)
        c.restore()
        fill.color = Color.WHITE
        fill.alpha = 170
        c.drawOval(ox - orr * 0.55f, oy - orr * 0.78f, ox - orr * 0.05f, oy - orr * 0.52f, fill)
        // тонкий полумесяц-кольцо
        stroke.color = Color.WHITE
        stroke.strokeWidth = max(1.5f, unit * 0.004f)
        stroke.alpha = 120
        c.drawArc(ox - orr * 1.18f, oy - orr * 1.18f, ox + orr * 1.18f, oy + orr * 1.18f, 120f + 10f * sin(time * 0.3f), 140f, false, stroke)

        // сетка-пол в перспективе, едет на нас
        val horizon = h * 0.70f
        stroke.strokeWidth = max(1f, unit * 0.0022f)
        stroke.color = Color.rgb(255, 120, 230)
        val scroll = (time * 0.35f) % 1f
        for (k in 0 until 14) {
            val z = (k + 1f - scroll)
            val y = horizon + (h - horizon) * (1f / z) * 0.9f
            if (y <= horizon + 1f || y > h) continue
            stroke.alpha = (40 + 120 * (1f / z)).toInt().coerceAtMost(200)
            c.drawLine(0f, y, w, y, stroke)
        }
        stroke.alpha = 70
        for (k in -12..12) {
            val xb = w / 2f + k * w * 0.16f
            c.drawLine(w / 2f + k * w * 0.012f, horizon, xb, h, stroke)
        }
        stroke.color = Color.WHITE

        drawSpine(c, w, h, unit, time)

        // монолитная рёберная хромовая рамка (рисуется один раз), по ней бежит блик
        val fb = chromeFrame(width(w), height(h), unit)
        if (fb != null) c.drawBitmap(fb, 0f, 0f, null)
        val m = unit * 0.04f
        val perim = 2f * (w + h - 4f * m)
        val glint = (time * 0.12f % 1f) * perim
        val pxy = perimeterPoint(glint, w, h, m)
        warmGlowWhite.alpha = 200
        c.save(); c.translate(pxy[0], pxy[1]); c.scale(unit * 0.09f, unit * 0.09f); c.drawCircle(0f, 0f, 1f, warmGlowWhite); c.restore()
    }

    private val warmGlowWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 1f,
            intArrayOf(Color.argb(220, 255, 255, 255), Color.argb(60, 220, 220, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP)
    }
    private var frameBmp: android.graphics.Bitmap? = null
    private var frameKey = 0L

    private fun width(w: Float) = w.toInt()

    /** Рамка: сплошная рёберная хромовая полоса с шипами внутрь и узлами-шарами в углах. */
    private fun chromeFrame(w: Int, h: Int, unit: Float): android.graphics.Bitmap? {
        if (w <= 0 || h <= 0) return null
        val key = w.toLong() * 100000L + h
        frameBmp?.let { if (frameKey == key) return it }
        val bmp = try { android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888) } catch (_: Throwable) { return null }
        val c = Canvas(bmp)
        val t = unit * 0.052f
        val m = unit * 0.04f
        val p = Path()
        p.addRoundRect(m, m, w - m, h - m, unit * 0.06f, unit * 0.06f, Path.Direction.CW)
        ChromeArt.tube(c, p, t, 3)
        // узлы по серединам сторон и в углах
        val r = t * 0.95f
        for ((x, y) in listOf(m to m, w - m to m, m to h - m, w - m to h - m)) {
            ChromeArt.boss(c, x, y, r * 1.35f, 7, 255, 20f)
            ChromeArt.jewel(c, x + r * 0.2f, y + r * 0.25f, r * 0.38f)
        }
        for ((x, y) in listOf(w / 2f to m, w / 2f to h - m, m to h / 2f, w - m to h / 2f)) {
            ChromeArt.boss(c, x, y, r, 6)
        }
        frameBmp?.recycle()
        frameBmp = bmp
        frameKey = key
        return bmp
    }

    private val boneShader = LinearGradient(0f, -0.5f, 0f, 0.5f,
        intArrayOf(Color.WHITE, Color.rgb(185, 190, 216), Color.rgb(85, 90, 118), Color.rgb(246, 246, 255), Color.rgb(140, 145, 176), Color.rgb(38, 41, 64)),
        floatArrayOf(0f, 0.2f, 0.45f, 0.56f, 0.8f, 1f), Shader.TileMode.CLAMP)
    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = boneShader }

    private val spinePath = Path()
    private val ribPath = Path()

    /** Массивный рёберный хромовый позвоночник с рёбрами, медленно извивается через экран. */
    private fun drawSpine(c: Canvas, w: Float, h: Float, unit: Float, time: Float) {
        val n = 40
        spinePath.rewind()
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        for (i in 0 until n) {
            val t = i / (n - 1f)
            ys[i] = h * (1.08f - t * 1.16f)
            xs[i] = w * 0.30f + w * 0.15f * sin(t * 2.2f * PI.toFloat() + time * 0.3f)
            if (i == 0) spinePath.moveTo(xs[i], ys[i]) else spinePath.lineTo(xs[i], ys[i])
        }
        // рёбра — изогнутые рёберные трубки по обе стороны
        for (i in 4 until n - 3 step 4) {
            for (side in intArrayOf(-1, 1)) {
                ribPath.rewind()
                ribPath.moveTo(xs[i], ys[i])
                val sw = sin(time * 0.8f + i) * unit * 0.02f
                ribPath.quadTo(
                    xs[i] + side * unit * 0.20f, ys[i] - unit * 0.14f,
                    xs[i] + side * unit * 0.30f, ys[i] + unit * 0.05f + sw
                )
                ChromeArt.tube(c, ribPath, unit * 0.022f, if (side < 0) 2 else 3, 170)
            }
        }
        ChromeArt.tube(c, spinePath, unit * 0.06f, 1, 200)
    }

    private val ppt = FloatArray(2)
    private fun perimeterPoint(d0: Float, w: Float, h: Float, m: Float): FloatArray {
        var d = d0
        val tw = w - 2 * m
        val th = h - 2 * m
        when {
            d < tw -> { ppt[0] = m + d; ppt[1] = m }
            d < tw + th -> { d -= tw; ppt[0] = w - m; ppt[1] = m + d }
            d < 2 * tw + th -> { d -= tw + th; ppt[0] = w - m - d; ppt[1] = h - m }
            else -> { d -= 2 * tw + th; ppt[0] = m; ppt[1] = h - m - d }
        }
        return ppt
    }

    // ---------------- Веб-кор: точечная матрица, сетка, ползунки ----------------

    private var dotBuf = Array(4) { FloatArray(0) }
    private val dotCount = IntArray(4)
    private val dotPaint = Paint().apply { strokeCap = Paint.Cap.SQUARE }
    private val mono = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.MONOSPACE }
    private val scan = Path()
    private var scanH = -1
    private val inkOlive = Color.rgb(196, 206, 90)
    private val inkRed = Color.rgb(150, 52, 40)

    private fun drawWebcore(c: Canvas, w: Float, h: Float, unit: Float, time: Float) {
        // бордовая сетка
        stroke.color = inkRed
        stroke.strokeWidth = max(1f, unit * 0.003f)
        stroke.alpha = 70
        val g = unit * 0.2f
        val gx = (time * unit * 0.01f) % g
        var x = -g + gx
        while (x < w) { c.drawLine(x, 0f, x, h, stroke); x += g }
        var y = 0f
        while (y < h) { c.drawLine(0f, y, w, y, stroke); y += g }

        // точечная матрица с «дизерингом»: 4 уровня яркости
        val stepD = unit * 0.018f
        val cols = (w / stepD).toInt() + 1
        val rows = (h / stepD).toInt() + 1
        val need = cols * rows * 2
        for (l in 0 until 4) { if (dotBuf[l].size < need) dotBuf[l] = FloatArray(need); dotCount[l] = 0 }
        for (iy in 0 until rows) {
            val yy = iy * stepD
            for (ix in 0 until cols) {
                val xx = ix * stepD
                val v = sin(xx * 0.004f + time * 0.6f) + sin(yy * 0.003f - time * 0.45f) + sin((xx + yy) * 0.0025f + time * 0.3f)
                val dith = (((ix and 1) * 2 + (iy and 1)) / 4f) * 0.6f
                val lv = (((v + 3f) / 6f) * 4f + dith - 0.9f).toInt()
                if (lv < 0) continue
                val l = min(lv, 3)
                val buf = dotBuf[l]
                val n = dotCount[l]
                buf[n] = xx; buf[n + 1] = yy
                dotCount[l] = n + 2
            }
        }
        dotPaint.strokeWidth = stepD * 0.55f
        dotPaint.color = inkOlive
        for (l in 0 until 4) {
            dotPaint.alpha = 18 + l * 26
            c.drawPoints(dotBuf[l], 0, dotCount[l], dotPaint)
        }

        // бегущие ползунки справа
        val bx = w - unit * 0.30f
        stroke.color = inkOlive
        fill.color = inkOlive
        for (k in 0 until 5) {
            val yy = h * 0.12f + k * unit * 0.07f
            val len = unit * 0.24f
            stroke.alpha = 90
            c.drawLine(bx, yy, bx + len, yy, stroke)
            val p = 0.5f + 0.5f * sin(time * (0.6f + 0.25f * k) + k * 1.3f)
            fill.alpha = 200
            c.drawRect(bx + p * (len - unit * 0.03f), yy - unit * 0.008f, bx + p * (len - unit * 0.03f) + unit * 0.03f, yy + unit * 0.008f, fill)
        }
        // полосы загрузки снизу слева
        for (k in 0 until 3) {
            val yy = h * 0.88f + k * unit * 0.035f
            val len = unit * 0.36f
            val p = ((time * (0.12f + 0.07f * k) + k * 0.31f) % 1f)
            stroke.alpha = 70
            c.drawRect(unit * 0.05f, yy, unit * 0.05f + len, yy + unit * 0.014f, stroke)
            fill.alpha = 150
            c.drawRect(unit * 0.05f, yy, unit * 0.05f + len * p, yy + unit * 0.014f, fill)
        }
        // счётчики
        mono.color = inkOlive
        mono.alpha = 170
        mono.textSize = unit * 0.03f
        val t = (time * 37f).toInt()
        c.drawText(String.format(java.util.Locale.US, "%03d·%03d", (t / 7) % 1000, t % 1000), w - unit * 0.30f, h * 0.08f, mono)
        c.drawText(String.format(java.util.Locale.US, "SYNC %05.2f", (time * 3.7f) % 100f), unit * 0.05f, h * 0.86f, mono)
        fill.color = Color.WHITE
        stroke.color = Color.WHITE

        // развёртка
        if (scanH != height(h)) {
            scanH = height(h)
            scan.rewind()
            var sy = 0f
            while (sy < h) { scan.moveTo(0f, sy); scan.lineTo(w * 4f, sy); sy += 4f }
        }
        stroke.color = Color.BLACK
        stroke.strokeWidth = 1.5f
        stroke.alpha = 40
        c.drawPath(scan, stroke)
        stroke.color = Color.WHITE
    }

    private fun height(h: Float) = h.toInt()

    // ---------------- Змейка: небо, песок, мягкая зелёная змейка ----------------

    private var skyShader: Shader? = null
    private var sandShader: Shader? = null
    private var snakeKey = -1L
    private val skyPaint = Paint()
    private val sandPaint = Paint()
    private val snakePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.rgb(14, 14, 14); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val snakeTop = FloatArray(41 * 2)
    private val softGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 1f, intArrayOf(Color.argb(70, 255, 170, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
    }

    private fun drawSnake(c: Canvas, w: Float, h: Float, unit: Float, time: Float) {
        val horizon = h * (if (h > w) 0.62f else 0.60f)
        val key = w.toLong() * 100000L + h.toLong()
        if (key != snakeKey) {
            snakeKey = key
            skyPaint.shader = LinearGradient(0f, 0f, 0f, horizon,
                intArrayOf(Color.rgb(64, 8, 132), Color.rgb(44, 108, 194), Color.rgb(84, 92, 200), Color.rgb(156, 52, 200)),
                floatArrayOf(0f, 0.5f, 0.78f, 1f), Shader.TileMode.CLAMP)
            sandPaint.shader = LinearGradient(0f, horizon, 0f, h,
                intArrayOf(Color.rgb(248, 228, 142), Color.rgb(244, 220, 128), Color.rgb(240, 210, 110)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w, horizon + 2f, skyPaint)

        // мягкие светящиеся пятна в небе
        for (k in 0 until 3) {
            val gx = w * (0.2f + 0.3f * k + 0.05f * sin(time * 0.1f + k))
            val gy = horizon * (0.35f + 0.15f * sin(time * 0.13f + k * 2f))
            val gr = unit * (0.35f + 0.1f * k)
            c.save(); c.translate(gx, gy); c.scale(gr, gr); c.drawCircle(0f, 0f, 1f, softGlow); c.restore()
        }

        // песок с мягкими полосами
        c.drawRect(0f, horizon, w, h, sandPaint)
        fill.color = Color.WHITE
        for (k in 0 until 6) {
            val y = horizon + (h - horizon) * (0.12f + k * 0.15f) + unit * 0.01f * sin(time * 0.3f + k)
            fill.alpha = 18
            c.drawRect(0f, y, w, y + unit * 0.02f, fill)
        }
        // линия горизонта
        stroke.color = Color.rgb(246, 176, 28)
        stroke.strokeWidth = max(2f, unit * 0.007f)
        stroke.alpha = 255
        c.drawLine(0f, horizon, w, horizon, stroke)
        stroke.color = Color.WHITE

        // камушки
        rock(c, w * 0.80f, horizon + (h - horizon) * 0.32f, unit * 0.06f)
        rock(c, w * 0.05f, horizon + (h - horizon) * 0.42f, unit * 0.035f)
        rock(c, w * 0.10f, horizon + (h - horizon) * 0.46f, unit * 0.014f)
        rock(c, w * 0.55f, horizon + (h - horizon) * 0.75f, unit * 0.045f)

        drawCreature(c, w, horizon, unit, time)
    }

    // ---------------- сама змейка ----------------

    /** Контур тела как на референсе: x 0..1 от носа к хвосту, y — высота над землёй (доля высоты тела). */
    private val bodyX = floatArrayOf(
        0.000f, 0.025f, 0.075f, 0.150f, 0.250f, 0.380f, 0.500f, 0.600f, 0.690f, 0.770f, 0.840f, 0.900f, 0.950f, 1.000f,
        0.930f, 0.800f, 0.620f, 0.420f, 0.230f, 0.100f, 0.030f
    )
    private val bodyY = floatArrayOf(
        0.300f, 0.580f, 0.800f, 0.940f, 1.000f, 1.000f, 0.950f, 0.840f, 0.680f, 0.500f, 0.330f, 0.200f, 0.120f, 0.070f,
        0.020f, 0.000f, -0.020f, -0.030f, -0.040f, -0.020f, 0.060f
    )
    private val bx = FloatArray(bodyX.size)
    private val by = FloatArray(bodyX.size)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 1f, intArrayOf(Color.argb(80, 120, 80, 20), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
    }

    /**
     * Змейка лежит на горизонте, как на референсе, и спокойно дышит:
     * тело мягко поднимается и опускается (объём сохраняется — выше значит чуть короче),
     * на вдохе спина приподнимается сильнее, чем хвост; иногда моргает, кончик хвоста чуть шевелится.
     */
    private fun drawCreature(c: Canvas, w: Float, horizon: Float, unit: Float, time: Float) {
        val baseL = min(w * 0.78f, unit * 0.85f)
        // медленное дыхание: вдох ~1.6 с, выдох ~2.2 с
        val cycle = 3.8f
        val ph = (time % cycle) / cycle
        val breath = if (ph < 0.42f) smooth(0f, 1f, ph / 0.42f) else 1f - smooth(0f, 1f, (ph - 0.42f) / 0.58f)
        val hMul = 1f + 0.07f * breath
        val lMul = 1f - 0.02f * breath
        val L = baseL * lMul
        val H = baseL * 0.27f * hMul
        val cx = w * 0.47f
        val base = horizon + unit * 0.02f
        val tailWiggle = sin(time * 0.9f) * sin(time * 0.37f)

        for (k in bodyX.indices) {
            val xn = bodyX[k]
            // спина «вдыхает» сильнее, чем голова и хвост
            val back = exp(-((xn - 0.42f) / 0.30f) * ((xn - 0.42f) / 0.30f))
            val yMul = 1f + 0.06f * breath * back
            var y = bodyY[k] * (if (bodyY[k] > 0f) yMul else 1f)
            // кончик хвоста чуть поднимается и опускается
            if (xn > 0.88f && k <= 13) y += 0.05f * tailWiggle * smooth(0.88f, 1f, xn)
            bx[k] = (xn - 0.5f) * L
            by[k] = -y * H
        }

        // мягкая тень
        c.save(); c.translate(cx, base + H * 0.04f); c.scale(L * 0.55f, H * 0.14f); c.drawCircle(0f, 0f, 1f, shadowPaint); c.restore()

        c.save()
        c.translate(cx, base)
        closedSpline(path, bx, by)
        snakePaint.shader = LinearGradient(0f, -H * 1.05f, 0f, H * 0.05f,
            intArrayOf(Color.rgb(184, 236, 120), Color.rgb(110, 188, 16), Color.rgb(80, 150, 0)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(path, snakePaint)
        // мягкий блик на спине
        fill.color = Color.WHITE
        fill.alpha = (40 + 20 * breath).toInt()
        c.drawOval(-L * 0.30f, -H * 0.93f, L * 0.02f, -H * 0.72f, fill)
        ink.strokeWidth = max(3f, unit * 0.009f)
        c.drawPath(path, ink)

        // глаз: моргает раз в ~4.5 с
        val ex = (0.13f - 0.5f) * L
        val ey = -0.64f * H
        val blink = (time % 4.6f) < 0.15f
        fill.color = Color.rgb(14, 14, 14)
        fill.alpha = 255
        if (blink) c.drawRect(ex - unit * 0.010f, ey - unit * 0.0016f, ex + unit * 0.010f, ey + unit * 0.0016f, fill)
        else c.drawCircle(ex, ey, unit * 0.0065f, fill)
        fill.color = Color.WHITE
        // улыбка от низа носа вверх, как на референсе
        path.rewind()
        path.moveTo((0.015f - 0.5f) * L, -0.07f * H)
        path.quadTo((0.13f - 0.5f) * L, -0.02f * H, (0.25f - 0.5f) * L, -0.30f * H)
        ink.strokeWidth = max(2.5f, unit * 0.0075f)
        c.drawPath(path, ink)
        c.restore()
    }

    /** Плавный замкнутый контур через точки (Catmull-Rom → кривые Безье). */
    private fun closedSpline(p: Path, xs: FloatArray, ys: FloatArray) {
        val n = xs.size
        p.rewind()
        p.moveTo(xs[0], ys[0])
        for (i in 0 until n) {
            val i0 = (i - 1 + n) % n
            val i2 = (i + 1) % n
            val i3 = (i + 2) % n
            val c1x = xs[i] + (xs[i2] - xs[i0]) / 6f
            val c1y = ys[i] + (ys[i2] - ys[i0]) / 6f
            val c2x = xs[i2] - (xs[i3] - xs[i]) / 6f
            val c2y = ys[i2] - (ys[i3] - ys[i]) / 6f
            p.cubicTo(c1x, c1y, c2x, c2y, xs[i2], ys[i2])
        }
        p.close()
    }

    // ---------------- Кристаллы: космос над морем чужой планеты ----------------

    private fun mixC(a: Int, b: Int, f: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt(),
    )
    private fun argb(a: Int, col: Int) = Color.argb(a.coerceIn(0, 255), Color.red(col), Color.green(col), Color.blue(col))
    private val skyP = Paint()
    private val seaP = Paint()
    private val shoreP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val crescentP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sunGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 1f,
            intArrayOf(Color.WHITE, Color.argb(200, 255, 255, 255), Color.argb(60, 220, 235, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.12f, 0.4f, 1f), Shader.TileMode.CLAMP)
    }
    private val planetPath = Path()
    private val cutPath = Path()
    private var worldAccent = 0
    private var worldKey = 0L

    private fun drawCrystalWorld(c: Canvas, w: Float, h: Float, unit: Float, time: Float) {
        val acc = accent()
        val horizon = h * (if (h > w) 0.58f else 0.60f)
        val shoreY = h * (if (h > w) 0.84f else 0.86f)
        val key = w.toLong() * 100000L + h.toLong()
        if (acc != worldAccent || key != worldKey) {
            worldAccent = acc
            worldKey = key
            val space = mixC(acc, Color.BLACK, 0.82f)
            val skyMid = mixC(acc, Color.BLACK, 0.35f)
            val skyLow = mixC(acc, Color.WHITE, 0.45f)
            skyP.shader = LinearGradient(0f, 0f, 0f, horizon, intArrayOf(space, skyMid, skyLow), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            seaP.shader = LinearGradient(0f, horizon, 0f, shoreY,
                intArrayOf(mixC(acc, Color.WHITE, 0.55f), mixC(acc, Color.WHITE, 0.15f), mixC(acc, Color.BLACK, 0.25f)),
                floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
            shoreP.shader = LinearGradient(0f, shoreY, 0f, h,
                intArrayOf(Color.rgb(250, 248, 244), Color.rgb(232, 226, 214)), null, Shader.TileMode.CLAMP)
            crescentP.shader = LinearGradient(0f, -1f, 0f, 1f,
                intArrayOf(Color.WHITE, mixC(acc, Color.WHITE, 0.8f), mixC(acc, Color.WHITE, 0.55f)), null, Shader.TileMode.CLAMP)
        }
        // небо → космос
        c.drawRect(0f, 0f, w, horizon + 1f, skyP)
        for (i in 0 until starN) {
            val y = starY[i] * horizon * 0.95f
            sparkPaint.alpha = ((60 + 170 * (0.5f + 0.5f * sin(time * 1.6f + starP[i]))) * (1f - y / horizon)).toInt()
            c.drawCircle(starX[i] * w, y, unit * (0.0013f + 0.0013f * (i % 3)), sparkPaint)
        }

        // планета-полумесяц
        val px = w * 0.62f
        val py = horizon * 0.48f
        val pr = unit * 0.42f
        fill.color = mixC(acc, Color.BLACK, 0.55f)
        fill.alpha = 120
        c.drawCircle(px, py, pr, fill)
        planetPath.rewind(); planetPath.addCircle(px, py, pr, Path.Direction.CW)
        cutPath.rewind(); cutPath.addCircle(px + pr * 0.22f, py + pr * 0.10f, pr * 0.97f, Path.Direction.CW)
        planetPath.op(cutPath, Path.Op.DIFFERENCE)
        c.save(); c.translate(px, py); c.scale(pr, pr); c.translate(-px / pr, -py / pr)
        c.restore()
        c.drawPath(planetPath, crescentP.also { it.shader?.setLocalMatrix(android.graphics.Matrix().apply { setScale(pr, pr); postTranslate(px, py) }) })
        stroke.color = Color.WHITE
        stroke.strokeWidth = max(1f, unit * 0.003f)
        stroke.alpha = 70
        c.drawCircle(px, py, pr * 1.01f, stroke)

        // солнце с лучами и бликом линзы
        val sx = w * 0.30f
        val sy = horizon * 0.40f
        val sr = unit * 0.34f * (1f + 0.03f * sin(time * 0.8f))
        c.save(); c.translate(sx, sy); c.scale(sr, sr); c.drawCircle(0f, 0f, 1f, sunGlow); c.restore()
        stroke.color = Color.WHITE
        for (k in 0 until 8) {
            val ang = k * PI.toFloat() / 4f + time * 0.03f
            val len = unit * (if (k % 2 == 0) 0.55f else 0.32f)
            stroke.strokeWidth = max(1.5f, unit * (if (k % 2 == 0) 0.006f else 0.004f))
            stroke.alpha = (50 + 25 * sin(time * 1.3f + k)).toInt()
            c.drawLine(sx, sy, sx + cos(ang) * len, sy + sin(ang) * len, stroke)
        }
        stroke.strokeWidth = max(2f, unit * 0.005f)
        stroke.color = Color.rgb(255, 170, 200)
        stroke.alpha = 70
        c.drawCircle(sx, sy, unit * 0.14f, stroke)
        stroke.color = Color.WHITE

        // полигональные острова: дальние — в дымке, ближние — контрастные
        drawIslands(c, w, h, horizon, unit, time, acc, nearOnly = false)

        // полигональный океан с рассеиванием и бликами
        drawPolySea(c, w, horizon, shoreY + unit * 0.05f, unit, time, acc, sx)

        // ближний утёс поверх воды
        drawIslands(c, w, h, horizon, unit, time, acc, nearOnly = true)

        // берег с набегающей пеной
        val wash = unit * 0.025f * sin(time * 0.6f)
        path.rewind()
        path.moveTo(0f, shoreY + unit * 0.06f + wash)
        path.quadTo(w * 0.45f, shoreY - unit * 0.03f + wash, w, shoreY + unit * 0.02f + wash)
        path.lineTo(w, h); path.lineTo(0f, h); path.close()
        c.drawPath(path, shoreP)
        stroke.color = Color.WHITE
        stroke.strokeWidth = max(2f, unit * 0.008f)
        stroke.alpha = (170 + 60 * sin(time * 0.6f)).toInt()
        path.rewind()
        path.moveTo(0f, shoreY + unit * 0.06f + wash)
        path.quadTo(w * 0.45f, shoreY - unit * 0.03f + wash, w, shoreY + unit * 0.02f + wash)
        c.drawPath(path, stroke)
        // камушки
        fill.color = Color.rgb(40, 40, 48)
        fill.alpha = 200
        c.drawOval(w * 0.78f, shoreY + unit * 0.07f, w * 0.84f, shoreY + unit * 0.095f, fill)
        c.drawCircle(w * 0.30f, h * 0.95f, unit * 0.006f, fill)
        c.drawCircle(w * 0.58f, h * 0.92f, unit * 0.005f, fill)
        fill.color = Color.WHITE
    }

    // ---------------- полигональный океан ----------------

    private val seaC = 18
    private val seaR = 14
    private val seaJit = FloatArray((seaC + 1) * (seaR + 1)) { rnd.nextFloat() - 0.5f }
    private val gx = FloatArray((seaC + 1) * (seaR + 1))
    private val gy = FloatArray((seaC + 1) * (seaR + 1))
    private val gz = FloatArray((seaC + 1) * (seaR + 1))
    private val gd = FloatArray((seaC + 1) * (seaR + 1))
    private val triV = FloatArray(4000)
    private val triC = IntArray(2000)
    private var triN = 0
    private val vtxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glintPts = FloatArray(400)
    private var glintN = 0

    private fun addTri(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, col: Int) {
        if (triN * 6 + 6 > triV.size) return
        val o = triN * 6
        triV[o] = x1; triV[o + 1] = y1; triV[o + 2] = x2; triV[o + 3] = y2; triV[o + 4] = x3; triV[o + 5] = y3
        triC[triN * 3] = col; triC[triN * 3 + 1] = col; triC[triN * 3 + 2] = col
        triN++
    }

    private fun flushTris(c: Canvas) {
        if (triN == 0) return
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            c.drawVertices(Canvas.VertexMode.TRIANGLES, triN * 6, triV, 0, null, 0, triC, 0, null, 0, 0, vtxPaint)
        } else {
            for (t in 0 until triN) {
                val o = t * 6
                path.rewind()
                path.moveTo(triV[o], triV[o + 1]); path.lineTo(triV[o + 2], triV[o + 3]); path.lineTo(triV[o + 4], triV[o + 5]); path.close()
                fill.color = triC[t * 3]; fill.alpha = 255
                c.drawPath(path, fill)
            }
            fill.color = Color.WHITE
        }
        triN = 0
    }

    private fun clampC(v: Float) = v.coerceIn(0f, 255f).toInt()

    /** Цвет грани: база + свет + рассеивание + блик. */
    private fun shade(base: Int, glow: Int, diffuse: Float, sss: Float, spec: Float): Int {
        var r = Color.red(base) * (0.62f + 0.55f * diffuse)
        var g = Color.green(base) * (0.62f + 0.55f * diffuse)
        var b = Color.blue(base) * (0.62f + 0.55f * diffuse)
        r += (Color.red(glow) - r) * sss; g += (Color.green(glow) - g) * sss; b += (Color.blue(glow) - b) * sss
        r += (255f - r) * spec; g += (255f - g) * spec; b += (255f - b) * spec
        return Color.rgb(clampC(r), clampC(g), clampC(b))
    }

    private fun drawPolySea(c: Canvas, w: Float, horizon: Float, bottom: Float, unit: Float, time: Float, acc: Int, sunX: Float) {
        val far = mixC(acc, Color.WHITE, 0.62f)
        val deep = mixC(acc, Color.BLACK, 0.30f)
        val glowCol = mixC(mixC(acc, Color.WHITE, 0.35f), Color.rgb(140, 255, 240), 0.15f)
        // сетка вершин в перспективе
        for (r in 0..seaR) {
            val v = r / seaR.toFloat()
            val y = horizon + (bottom - horizon) * Math.pow(v.toDouble(), 1.55).toFloat()
            val spread = 1.05f + 0.9f * v
            val amp = unit * (0.003f + 0.05f * v * v)
            for (cI in 0..seaC) {
                val k = r * (seaC + 1) + cI
                val uu = cI / seaC.toFloat() + seaJit[k] * 0.5f / seaC
                val x = w / 2f + (uu - 0.5f) * w * spread
                val hgt = amp * (0.65f * sin(v * 30f - time * 1.2f + x / (unit * 0.6f) + seaJit[k] * 0.6f) +
                    0.35f * sin(x / (unit * 0.09f * (0.4f + v)) + v * 13f + time * 0.7f))
                gx[k] = x; gy[k] = y - hgt; gz[k] = hgt / max(amp, 0.001f); gd[k] = v
            }
        }
        // источник света — солнце сверху-слева за горизонтом
        val lx = -0.35f; val ly = -0.75f; val lz = 0.56f
        glintN = 0
        for (r in 0 until seaR) for (cI in 0 until seaC) {
            val a = r * (seaC + 1) + cI
            val b = a + 1
            val d = a + seaC + 1
            val e = d + 1
            for (tri in 0..1) {
                val i1 = a; val i2 = if (tri == 0) b else e; val i3 = if (tri == 0) e else d
                // нормаль грани по псевдо-3D (x, глубина, высота)
                val ax = gx[i2] - gx[i1]; val ay = (gd[i2] - gd[i1]) * unit * 2f; val az = (gz[i2] - gz[i1]) * unit * 0.04f
                val bxv = gx[i3] - gx[i1]; val byv = (gd[i3] - gd[i1]) * unit * 2f; val bzv = (gz[i3] - gz[i1]) * unit * 0.04f
                var nx = ay * bzv - az * byv
                var ny = az * bxv - ax * bzv
                var nz = ax * byv - ay * bxv
                if (nz < 0f) { nx = -nx; ny = -ny; nz = -nz }
                val nl = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
                nx /= nl; ny /= nl; nz /= nl
                val v = (gd[i1] + gd[i2] + gd[i3]) / 3f
                val cxm = (gx[i1] + gx[i2] + gx[i3]) / 3f
                val crest = ((gz[i1] + gz[i2] + gz[i3]) / 3f).coerceIn(-1f, 1f)
                val diffuse = max(0f, nx * lx + ny * ly + nz * lz)
                // рассеивание: подсвеченные изнутри гребни, повёрнутые к зрителю
                val sss = (0.5f + 0.5f * crest) * max(0f, ny) * 0.9f * (0.3f + 0.7f * v)
                // блик: отражение солнца к зрителю, сильнее в солнечной дорожке
                val rdot = 2f * (nx * lx + ny * ly + nz * lz)
                val rx = rdot * nx - lx; val ry = rdot * ny - ly; val rz = rdot * nz - lz
                val vdot = max(0f, ry * 0.75f + rz * 0.66f)
                val column = exp(-((cxm - sunX) / (w * (0.10f + 0.18f * v))).let { it * it })
                val spec = (Math.pow(vdot.toDouble(), 32.0).toFloat() * (0.4f + 1.2f * column)).coerceIn(0f, 1f)
                val base = mixC(far, deep, v)
                addTri(gx[i1], gy[i1], gx[i2], gy[i2], gx[i3], gy[i3], shade(base, glowCol, diffuse, sss, spec))
                if (spec > 0.55f && glintN < glintPts.size / 2 - 1) {
                    glintPts[glintN * 2] = cxm; glintPts[glintN * 2 + 1] = (gy[i1] + gy[i2] + gy[i3]) / 3f; glintN++
                }
            }
        }
        flushTris(c)
        // искры на самых ярких гранях
        for (k in 0 until glintN) {
            val tw = 0.5f + 0.5f * sin(time * 6f + k * 2.3f)
            sparkPaint.alpha = (200 * tw).toInt()
            c.drawCircle(glintPts[k * 2], glintPts[k * 2 + 1], unit * 0.004f * (0.6f + tw), sparkPaint)
        }
    }

    // ---------------- полигональные острова с глубиной ----------------

    /** x (доля ширины), масштаб, глубина 0 — ближний … 1 — у горизонта. */
    private val islands = arrayOf(
        floatArrayOf(0.50f, 0.07f, 0.95f), floatArrayOf(0.12f, 0.10f, 0.88f), floatArrayOf(0.90f, 0.12f, 0.82f),
        floatArrayOf(0.32f, 0.15f, 0.70f), floatArrayOf(0.74f, 0.21f, 0.58f),
        floatArrayOf(-0.04f, 0.62f, 0.05f),
    )
    private val islOuter = Array(islands.size) { FloatArray(0) }
    private val islInner = Array(islands.size) { FloatArray(0) }

    private fun buildIsland(idx: Int) {
        val r = Random(100L + idx)
        val base = floatArrayOf(
            -0.35f, 0f, -0.30f, -0.45f, -0.55f, -0.72f, -1.00f, -0.85f, -0.95f, -1.05f, -0.62f, -1.22f,
            -0.20f, -1.16f, 0.15f, -1.30f, 0.55f, -1.18f, 0.95f, -1.04f, 1.00f, -0.84f, 0.55f, -0.74f,
            0.32f, -0.45f, 0.36f, 0f
        )
        val out = FloatArray(base.size)
        for (k in base.indices) out[k] = base[k] + (r.nextFloat() - 0.5f) * 0.12f
        out[1] = 0f; out[base.size - 1] = 0f
        val inner = FloatArray(base.size)
        val cx = 0f; val cy = -0.85f
        for (k in 0 until base.size / 2) {
            val f = 0.5f + r.nextFloat() * 0.12f
            inner[k * 2] = cx + (out[k * 2] - cx) * f
            inner[k * 2 + 1] = cy + (out[k * 2 + 1] - cy) * f
        }
        islOuter[idx] = out
        islInner[idx] = inner
    }

    private fun drawIslands(c: Canvas, w: Float, h: Float, horizon: Float, unit: Float, time: Float, acc: Int, nearOnly: Boolean) {
        val haze = mixC(acc, Color.WHITE, 0.55f)
        val rock = mixC(mixC(acc, Color.rgb(60, 70, 80), 0.5f), Color.BLACK, 0.45f)
        val lit = mixC(rock, mixC(acc, Color.WHITE, 0.5f), 0.55f)
        val lx = -0.55f; val ly = -0.5f; val lz = 0.67f
        for (idx in islands.indices) {
            val isl = islands[idx]
            val depth = isl[2]
            if (nearOnly != (depth < 0.2f)) continue
            if (islOuter[idx].isEmpty()) buildIsland(idx)
            val s = unit * isl[1]
            val drift = sin(time * 0.05f + idx) * w * 0.012f * (1f - depth)
            val ox = isl[0] * w + drift
            val oy = if (depth < 0.2f) h * 0.80f else horizon + unit * 0.004f
            val out = islOuter[idx]
            val inn = islInner[idx]
            val n = out.size / 2
            val ccx = ox; val ccy = oy - 0.85f * s
            fun tri(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float, x3: Float, y3: Float, z3: Float) {
                val ax = x2 - x1; val ay = y2 - y1; val az = (z2 - z1) * s
                val bxv = x3 - x1; val byv = y3 - y1; val bzv = (z3 - z1) * s
                var nx = ay * bzv - az * byv
                var ny = az * bxv - ax * bzv
                var nz = ax * byv - ay * bxv
                if (nz < 0f) { nx = -nx; ny = -ny; nz = -nz }
                val nl = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
                val l = max(0f, (nx * lx + ny * ly + nz * lz) / nl)
                val col = mixC(mixC(rock, lit, l), haze, depth * 0.85f)
                addTri(x1, y1, x2, y2, x3, y3, col)
            }
            for (k in 0 until n - 1) {
                val o1x = ox + out[k * 2] * s; val o1y = oy + out[k * 2 + 1] * s
                val o2x = ox + out[k * 2 + 2] * s; val o2y = oy + out[k * 2 + 3] * s
                val i1x = ox + inn[k * 2] * s; val i1y = oy + inn[k * 2 + 1] * s
                val i2x = ox + inn[k * 2 + 2] * s; val i2y = oy + inn[k * 2 + 3] * s
                tri(o1x, o1y, 0f, o2x, o2y, 0f, i1x, i1y, 0.55f)
                tri(i1x, i1y, 0.55f, o2x, o2y, 0f, i2x, i2y, 0.55f)
                tri(ccx, ccy, 1f, i1x, i1y, 0.55f, i2x, i2y, 0.55f)
            }
            // замыкание нижней грани
            tri(ox + out[(n - 1) * 2] * s, oy + out[(n - 1) * 2 + 1] * s, 0f, ox + out[0] * s, oy + out[1] * s, 0f, ccx, ccy, 1f)
        }
        flushTris(c)
    }

    /** Силуэт летающей скалы-«гриба» на горизонте. */
    private fun rockIsland(c: Canvas, x: Float, base: Float, s: Float, col: Int) {
        path.rewind()
        path.moveTo(x - s * 0.35f, base)
        path.quadTo(x - s * 0.25f, base - s * 0.8f, x - s * 0.95f, base - s * 1.1f)
        path.quadTo(x - s * 0.9f, base - s * 1.45f, x - s * 0.3f, base - s * 1.42f)
        path.quadTo(x, base - s * 1.7f, x + s * 0.35f, base - s * 1.45f)
        path.quadTo(x + s * 1.0f, base - s * 1.4f, x + s * 0.9f, base - s * 1.08f)
        path.quadTo(x + s * 0.3f, base - s * 0.8f, x + s * 0.35f, base)
        path.close()
        fill.color = col
        fill.alpha = 210
        c.drawPath(path, fill)
        fill.color = Color.WHITE
        fill.alpha = 40
        c.drawRect(x - s * 0.5f, base, x + s * 0.5f, base + s * 0.08f, fill)
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private val rockLight = Color.rgb(236, 184, 48)
    private val rockDark = Color.rgb(176, 124, 16)

    private fun rock(c: Canvas, x: Float, y: Float, s: Float) {
        path.rewind()
        path.moveTo(x - s, y + s * 0.5f); path.lineTo(x - s * 0.7f, y - s * 0.6f); path.lineTo(x + s * 0.1f, y - s * 0.75f)
        path.lineTo(x + s, y - s * 0.1f); path.lineTo(x + s * 0.9f, y + s * 0.55f); path.lineTo(x, y + s * 0.75f); path.close()
        fill.color = rockLight; fill.alpha = 255
        c.drawPath(path, fill)
        path.rewind()
        path.moveTo(x + s * 0.1f, y - s * 0.75f); path.lineTo(x + s, y - s * 0.1f); path.lineTo(x + s * 0.9f, y + s * 0.55f)
        path.lineTo(x, y + s * 0.75f); path.lineTo(x - s * 0.1f, y - s * 0.05f); path.close()
        fill.color = rockDark
        c.drawPath(path, fill)
        fill.color = Color.WHITE
        path.rewind()
        path.moveTo(x - s, y + s * 0.5f); path.lineTo(x - s * 0.7f, y - s * 0.6f); path.lineTo(x + s * 0.1f, y - s * 0.75f)
        path.lineTo(x + s, y - s * 0.1f); path.lineTo(x + s * 0.9f, y + s * 0.55f); path.lineTo(x, y + s * 0.75f); path.close()
        ink.strokeWidth = max(1.5f, s * 0.09f)
        c.drawPath(path, ink)
    }

    // =====================================================================

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

    @Suppress("unused")
    private fun clamp(v: Float) = min(1f, max(0f, v))
}
