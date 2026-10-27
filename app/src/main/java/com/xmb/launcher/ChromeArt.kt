package com.xmb.launcher

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.atan2
import kotlin.math.max

/**
 * Массивный «биомеханический» хром для стиля «Хром»:
 * толстые трубки с поперечными рёбрами (как позвоночник), шипы, хромовые узлы.
 * Работает в любых единицах: толщина T задаётся в тех же единицах, что и путь.
 */
object ChromeArt {

    private fun c(v: Long) = v.toInt()

    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        color = Color.rgb(14, 6, 34)
    }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        color = Color.rgb(96, 100, 128)
    }
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        color = Color.rgb(214, 218, 238)
    }
    private val hot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    /** Хромовые полосы поперёк рёбра (координаты -0.5..0.5). */
    private val ridge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, -0.5f, 0f, 0.5f,
            intArrayOf(c(0xFF2A2C44), c(0xFFE9ECFF), c(0xFF7E83A4), c(0xFFFFFFFF), c(0xFF5C6080), c(0xFF1C1E30)),
            floatArrayOf(0f, 0.18f, 0.42f, 0.55f, 0.8f, 1f), Shader.TileMode.CLAMP)
    }
    private val groove = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(16, 8, 36) }
    private val spike = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(-0.5f, 0f, 0.5f, 0f,
            intArrayOf(c(0xFF3A3D5A), c(0xFFF4F5FF), c(0xFF8D92B2), c(0xFF22243A)),
            floatArrayOf(0f, 0.4f, 0.65f, 1f), Shader.TileMode.CLAMP)
    }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(-0.35f, -0.4f, 1.35f,
            intArrayOf(Color.WHITE, c(0xFFC4C8E2), c(0xFF5A5E80), c(0xFF181A2C)),
            floatArrayOf(0f, 0.3f, 0.72f, 1f), Shader.TileMode.CLAMP)
    }
    private val gem = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(-0.3f, -0.35f, 1.2f,
            intArrayOf(c(0xFFFFE0F4), c(0xFFE0247A), c(0xFF4A0420)), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
    }
    private val pm = PathMeasure()
    private val pos = FloatArray(2)
    private val tan = FloatArray(2)
    private val tri = Path()

    private fun setAlpha(a: Int) {
        for (p in arrayOf(shadow, body, sheen, hot, ridge, groove, spike, knob, gem)) p.alpha = a
    }

    /**
     * Рёберная хромовая трубка вдоль пути.
     * spikes: 0 — без шипов, 1 — шипы по очереди в обе стороны, 2 — шипы только влево (внутрь рамки), 3 — только вправо.
     */
    fun tube(c: Canvas, path: Path, t: Float, spikes: Int, alpha: Int = 255) {
        setAlpha(alpha)
        val off = t * 0.14f
        // объём трубки: тень, тело, отлив, горячий блик
        c.save(); c.translate(off, off * 1.2f)
        shadow.strokeWidth = t * 1.22f
        shadow.alpha = (alpha * 0.85f).toInt()
        c.drawPath(path, shadow)
        c.restore()
        body.strokeWidth = t
        c.drawPath(path, body)
        c.save(); c.translate(-t * 0.1f, -t * 0.12f)
        sheen.strokeWidth = t * 0.5f
        c.drawPath(path, sheen)
        c.restore()
        c.save(); c.translate(-t * 0.16f, -t * 0.2f)
        hot.strokeWidth = t * 0.14f
        hot.alpha = (alpha * 0.8f).toInt()
        c.drawPath(path, hot)
        c.restore()

        // поперечные рёбра и шипы
        val step = t * 0.42f
        pm.setPath(path, false)
        var k = 0
        do {
            val len = pm.length
            if (len < step * 0.5f) continue
            val n = max(1, (len / step).toInt())
            for (i in 0..n) {
                pm.getPosTan(len * i / n, pos, tan)
                val ang = Math.toDegrees(atan2(tan[1], tan[0]).toDouble()).toFloat()
                c.save()
                c.translate(pos[0], pos[1])
                c.rotate(ang)
                // рёбро
                c.save()
                c.scale(t * 0.22f, t * 1.28f)
                c.drawRoundRect(-0.5f, -0.5f, 0.5f, 0.5f, 0.45f, 0.18f, ridge)
                c.restore()
                // борозда за рёбром
                c.drawRect(t * 0.11f, -t * 0.5f, t * 0.16f, t * 0.5f, groove)
                // шипы
                if (spikes != 0 && k % 3 == 1) {
                    val side = when (spikes) {
                        1 -> if ((k / 3) % 2 == 0) -1f else 1f
                        2 -> -1f
                        else -> 1f
                    }
                    tri.rewind()
                    tri.moveTo(-t * 0.16f, side * t * 0.55f)
                    tri.quadTo(t * 0.05f, side * t * 0.95f, t * 0.12f, side * t * 1.35f)
                    tri.lineTo(t * 0.16f, side * t * 0.55f)
                    tri.close()
                    c.save(); c.scale(1f, 1f)
                    c.drawPath(tri, spike)
                    c.restore()
                }
                c.restore()
                k++
            }
        } while (pm.nextContour())
    }

    /** Массивный хромовый узел-шар с шипами вокруг. */
    fun boss(c: Canvas, x: Float, y: Float, r: Float, spikes: Int, alpha: Int = 255, rot: Float = 0f) {
        setAlpha(alpha)
        c.save()
        c.translate(x, y)
        c.rotate(rot)
        for (i in 0 until spikes) {
            c.save()
            c.rotate(i * 360f / spikes)
            tri.rewind()
            tri.moveTo(-r * 0.28f, -r * 0.8f)
            tri.quadTo(r * 0.05f, -r * 1.35f, r * 0.12f, -r * 1.75f)
            tri.lineTo(r * 0.28f, -r * 0.8f)
            tri.close()
            c.drawPath(tri, spike)
            c.restore()
        }
        c.save(); c.translate(r * 0.1f, r * 0.12f); c.scale(r * 1.05f, r * 1.05f)
        groove.alpha = (alpha * 0.7f).toInt()
        c.drawCircle(0f, 0f, 1f, groove)
        c.restore()
        c.save(); c.scale(r, r); c.drawCircle(0f, 0f, 1f, knob); c.restore()
        c.restore()
    }

    /** Малиновый камень с бликом. */
    fun jewel(c: Canvas, x: Float, y: Float, r: Float, alpha: Int = 255) {
        gem.alpha = alpha
        c.save(); c.translate(x, y); c.scale(r, r); c.drawCircle(0f, 0f, 1f, gem); c.restore()
    }

    /** Хромовая заливка формы (сплошной узел). */
    fun solid(c: Canvas, path: Path, cx: Float, cy: Float, rx: Float, ry: Float, alpha: Int = 255) {
        setAlpha(alpha)
        // форма с хромовой заливкой по ограничивающему прямоугольнику
        val m = android.graphics.Matrix()
        m.setScale(max(rx, 0.001f), max(ry, 0.001f))
        m.postTranslate(cx, cy)
        val sh = knob.shader
        sh.setLocalMatrix(m)
        c.save(); c.translate(rx * 0.08f, ry * 0.1f)
        groove.alpha = (alpha * 0.8f).toInt()
        c.drawPath(path, groove)
        c.restore()
        c.drawPath(path, knob)
        sh.setLocalMatrix(android.graphics.Matrix())
    }
}
