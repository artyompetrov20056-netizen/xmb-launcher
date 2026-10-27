package com.xmb.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import java.util.WeakHashMap

/** Оформление иконок в стиле. */
enum class FrameKind { NONE, BUBBLE, BEVEL, SQUARE, GLOW, ROUNDED, CARD, CHROME, HUD }

/** Анимированная сцена фона. */
enum class Scene { WAVES, SPHERES, TILES, CUBES, SOFT_WAVE, LINES, RIBBONS, LIGHT, PARTICLES, CHROME, WEBCORE }

/**
 * Стиль оформления: анимированный фон + вид иконок.
 * palette — свои цвета фона (верх, середина, низ); null — берётся «Цвет темы».
 */
class VisualStyle(
    val name: String,
    val scene: Scene,
    val palette: IntArray?,
    val frame: FrameKind,
    /** Толщина линий значков относительно обычной. */
    val weight: Float,
    /** Иконки приложений: 0 — как есть, 1 — в круге, 2 — скруглённый квадрат. */
    val iconShape: Int,
    /** Раскладка меню: 0 — обычная XMB, 1 — колесо. */
    val layout: Int = 0,
    /** Наклон всего меню в градусах. */
    val tilt: Float = 0f,
    /** Цвет значков и подписей. */
    val ink: Int = Color.WHITE,
    /** Перекраска иконок приложений: 0 — нет, 1 — монохром в цвет стиля. */
    val iconFilter: Int = 0,
)

object VisualStyles {
    private fun c(v: Long) = v.toInt()

    val ALL = listOf(
        VisualStyle("XMB", Scene.WAVES, null, FrameKind.NONE, 1f, 0),
        VisualStyle("Сферы", Scene.SPHERES, intArrayOf(c(0xFF120C3E), c(0xFF34389E), c(0xFF0A0830)), FrameKind.BUBBLE, 1.25f, 1),
        VisualStyle("Плитка", Scene.TILES, intArrayOf(c(0xFF55585E), c(0xFF83868C), c(0xFF44464A)), FrameKind.BEVEL, 1.1f, 2),
        VisualStyle("Кубы", Scene.CUBES, intArrayOf(c(0xFF000000), c(0xFF0A1530), c(0xFF000000)), FrameKind.SQUARE, 0.85f, 2),
        VisualStyle("Мягкая волна", Scene.SOFT_WAVE, null, FrameKind.GLOW, 0.75f, 0),
        VisualStyle("Портатив", Scene.LINES, null, FrameKind.GLOW, 0.9f, 0),
        VisualStyle("Вита", Scene.RIBBONS, null, FrameKind.BUBBLE, 0.95f, 1),
        VisualStyle("Глубина", Scene.LIGHT, intArrayOf(c(0xFF02060F), c(0xFF0B2A5E), c(0xFF01040C)), FrameKind.ROUNDED, 0.7f, 2),
        VisualStyle("Частицы", Scene.PARTICLES, intArrayOf(c(0xFF060606), c(0xFF141417), c(0xFF020202)), FrameKind.CARD, 0.65f, 2),
        VisualStyle(
            "Хром", Scene.CHROME, intArrayOf(c(0xFF07021A), c(0xFF31237C), c(0xFF0A0322)), FrameKind.CHROME, 1.15f, 1,
            tilt = -7f,
        ),
        VisualStyle(
            "Веб-кор", Scene.WEBCORE, intArrayOf(c(0xFF080C06), c(0xFF1A2412), c(0xFF050704)), FrameKind.HUD, 0.8f, 2,
            layout = 1, ink = c(0xFFD2DB9C), iconFilter = 1,
        ),
    )

    val NAMES = ALL.map { it.name }

    fun index(ctx: Context) = ctx.getSharedPreferences("xmb", Context.MODE_PRIVATE)
        .getInt("visual_style", 0).coerceIn(0, ALL.lastIndex)

    fun current(ctx: Context) = ALL[index(ctx)]

    // ---------------- рамки иконок ----------------

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.BLACK }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 1f,
            intArrayOf(Color.argb(150, 255, 255, 255), Color.argb(40, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    }
    private val gloss = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(-0.35f, -0.45f, 1.1f,
            intArrayOf(Color.argb(120, 255, 255, 255), Color.argb(30, 255, 255, 255), Color.argb(10, 255, 255, 255)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
    }
    private val r = RectF()

    /**
     * Подложка под значок. size — размер значка, sel — насколько пункт выбран (0..1).
     */
    fun drawFrame(c: Canvas, s: VisualStyle, cx: Float, cy: Float, size: Float, alpha: Int, sel: Float, time: Float = 0f) {
        val a = alpha.coerceIn(0, 255) / 255f
        val lw = (size * 0.03f).coerceAtLeast(1.2f)
        when (s.frame) {
            FrameKind.NONE -> {}
            FrameKind.GLOW -> {
                glow.alpha = (255 * a * (0.25f + 0.35f * sel)).toInt()
                c.save(); c.translate(cx, cy); c.scale(size * 0.85f, size * 0.85f)
                c.drawCircle(0f, 0f, 1f, glow); c.restore()
            }
            FrameKind.BUBBLE -> {
                val rad = size * 0.62f
                gloss.alpha = (255 * a * (0.8f + 0.2f * sel)).toInt()
                c.save(); c.translate(cx, cy); c.scale(rad, rad)
                c.drawCircle(0f, 0f, 1f, gloss); c.restore()
                line.strokeWidth = lw
                line.alpha = (255 * a * (0.45f + 0.45f * sel)).toInt()
                c.drawCircle(cx, cy, rad, line)
                // блик
                fill.alpha = (255 * a * 0.35f).toInt()
                r.set(cx - rad * 0.5f, cy - rad * 0.86f, cx + rad * 0.2f, cy - rad * 0.55f)
                c.drawOval(r, fill)
            }
            FrameKind.BEVEL -> {
                val h = size * 0.6f
                r.set(cx - h, cy - h, cx + h, cy + h)
                fill.alpha = (255 * a * (0.14f + 0.12f * sel)).toInt()
                c.drawRect(r, fill)
                line.strokeWidth = lw * 1.3f
                line.alpha = (255 * a * 0.65f).toInt()
                c.drawLine(r.left, r.bottom, r.left, r.top, line)
                c.drawLine(r.left, r.top, r.right, r.top, line)
                dark.strokeWidth = lw * 1.3f
                dark.alpha = (255 * a * 0.45f).toInt()
                c.drawLine(r.right, r.top, r.right, r.bottom, dark)
                c.drawLine(r.right, r.bottom, r.left, r.bottom, dark)
            }
            FrameKind.SQUARE -> {
                val h = size * 0.58f
                r.set(cx - h, cy - h, cx + h, cy + h)
                line.strokeWidth = lw * 0.8f
                line.alpha = (255 * a * (0.30f + 0.6f * sel)).toInt()
                c.drawRect(r, line)
                fill.alpha = (255 * a * 0.06f * sel).toInt()
                c.drawRect(r, fill)
            }
            FrameKind.ROUNDED -> {
                val h = size * 0.62f
                r.set(cx - h, cy - h, cx + h, cy + h)
                fill.alpha = (255 * a * (0.07f + 0.10f * sel)).toInt()
                c.drawRoundRect(r, h * 0.35f, h * 0.35f, fill)
                line.strokeWidth = lw * 0.8f
                line.alpha = (255 * a * (0.22f + 0.6f * sel)).toInt()
                c.drawRoundRect(r, h * 0.35f, h * 0.35f, line)
            }
            FrameKind.CHROME -> drawChromeFrame(c, cx, cy, size, a, sel, time)
            FrameKind.HUD -> drawHudFrame(c, s, cx, cy, size, a, sel, time)
            FrameKind.CARD -> {
                val h = size * 0.64f
                r.set(cx - h, cy - h, cx + h, cy + h)
                fill.alpha = (255 * a * (0.06f + 0.06f * sel)).toInt()
                c.drawRoundRect(r, h * 0.22f, h * 0.22f, fill)
                if (sel > 0.05f) {
                    line.strokeWidth = lw * 1.4f
                    line.alpha = (255 * a * sel).toInt()
                    r.inset(-h * 0.08f, -h * 0.08f)
                    c.drawRoundRect(r, h * 0.28f, h * 0.28f, line)
                }
            }
        }
    }

    // ---------------- Хром: кольцо из хромовых позвонков ----------------

    /** Хромовые полосы в координатах -0.5..0.5 по вертикали. */
    private val chrome = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = android.graphics.LinearGradient(0f, -0.5f, 0f, 0.5f,
            intArrayOf(c(0xFFFFFFFF), c(0xFFB7BCD6), c(0xFF5A5F7A), c(0xFFF4F4FF), c(0xFF8A8FAE), c(0xFF2C2F44)),
            floatArrayOf(0f, 0.22f, 0.45f, 0.55f, 0.78f, 1f), Shader.TileMode.CLAMP)
    }
    private val chromeOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(150, 10, 8, 30) }
    private val jewel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(-0.35f, -0.35f, 1.2f,
            intArrayOf(c(0xFFFFE0F4), c(0xFFE0247A), c(0xFF5A0628)), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
    }
    private val hornPath = android.graphics.Path()

    private fun drawChromeFrame(c: Canvas, cx: Float, cy: Float, size: Float, a: Float, sel: Float, time: Float) {
        val R = size * 0.66f
        val al = (255 * a).toInt()
        c.save()
        c.translate(cx, cy)
        // позвонки по кругу
        val n = 14
        val segW = size * 0.15f
        val segH = size * 0.10f
        chrome.alpha = al
        chromeOutline.alpha = (150 * a).toInt()
        for (i in 0 until n) {
            c.save()
            c.rotate(i * 360f / n + sel * time * 12f)
            c.translate(0f, -R)
            c.scale(segW, segH)
            r.set(-0.5f, -0.5f, 0.5f, 0.5f)
            c.drawRoundRect(r, 0.35f, 0.45f, chrome)
            chromeOutline.strokeWidth = 0.06f
            c.drawRoundRect(r, 0.35f, 0.45f, chromeOutline)
            c.restore()
        }
        // рожки сверху
        for (side in intArrayOf(-1, 1)) {
            c.save()
            c.scale(side.toFloat() * R, R)
            hornPath.rewind()
            hornPath.moveTo(0.22f, -0.92f)
            hornPath.quadTo(0.75f, -1.25f, 0.62f, -1.75f)
            hornPath.quadTo(0.52f, -1.30f, 0.40f, -0.98f)
            hornPath.close()
            c.drawPath(hornPath, chrome)
            c.restore()
        }
        // камень снизу
        val jr = size * 0.085f
        jewel.alpha = al
        c.save(); c.translate(0f, R + jr * 0.4f); c.scale(jr, jr); c.drawCircle(0f, 0f, 1f, jewel); c.restore()
        fill.alpha = (200 * a).toInt()
        c.drawCircle(-jr * 0.35f, R + jr * 0.05f, jr * 0.25f, fill)
        // блеск, пробегающий по выбранному кольцу
        if (sel > 0.05f) {
            line.strokeWidth = size * 0.035f
            line.alpha = (180 * a * sel * (0.5f + 0.5f * kotlin.math.sin(time * 2.5f))).toInt()
            r.set(-R, -R, R, R)
            c.drawArc(r, (time * 120f) % 360f, 40f, false, line)
        }
        c.restore()
    }

    // ---------------- Веб-кор: HUD-уголки и бегущий ползунок ----------------

    private fun drawHudFrame(c: Canvas, s: VisualStyle, cx: Float, cy: Float, size: Float, a: Float, sel: Float, time: Float) {
        val h = size * 0.62f
        val k = h * 0.35f
        line.color = s.ink
        line.strokeWidth = (size * 0.03f).coerceAtLeast(1.2f)
        line.alpha = (255 * a * (0.45f + 0.5f * sel)).toInt()
        // уголки
        c.drawLine(cx - h, cy - h, cx - h + k, cy - h, line); c.drawLine(cx - h, cy - h, cx - h, cy - h + k, line)
        c.drawLine(cx + h, cy - h, cx + h - k, cy - h, line); c.drawLine(cx + h, cy - h, cx + h, cy - h + k, line)
        c.drawLine(cx - h, cy + h, cx - h + k, cy + h, line); c.drawLine(cx - h, cy + h, cx - h, cy + h - k, line)
        c.drawLine(cx + h, cy + h, cx + h - k, cy + h, line); c.drawLine(cx + h, cy + h, cx + h, cy + h - k, line)
        fill.color = s.ink
        fill.alpha = (255 * a * 0.05f).toInt()
        r.set(cx - h, cy - h, cx + h, cy + h)
        c.drawRect(r, fill)
        if (sel > 0.05f) {
            // бегущий ползунок под иконкой
            val y = cy + h * 1.25f
            line.alpha = (120 * a * sel).toInt()
            c.drawLine(cx - h, y, cx + h, y, line)
            val p = (kotlin.math.sin(time * 1.7f) * 0.5f + 0.5f)
            fill.alpha = (230 * a * sel).toInt()
            r.set(cx - h + p * h * 1.6f, y - h * 0.08f, cx - h + p * h * 1.6f + h * 0.4f, y + h * 0.08f)
            c.drawRect(r, fill)
        }
        line.color = Color.WHITE
        fill.color = Color.WHITE
    }

    private val oliveFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(floatArrayOf(
        0.24f, 0.47f, 0.09f, 0f, 8f,
        0.27f, 0.53f, 0.10f, 0f, 12f,
        0.13f, 0.26f, 0.05f, 0f, 2f,
        0f, 0f, 0f, 1f, 0f,
    )))

    // ---------------- иконки приложений нужной формы ----------------

    private val shaders = WeakHashMap<Bitmap, BitmapShader>()
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val m = Matrix()

    /** Рисует иконку приложения в прямоугольнике dst с формой стиля (сглаженные края). */
    fun drawAppIcon(c: Canvas, s: VisualStyle, bmp: Bitmap, dst: RectF, alpha: Int) {
        iconPaint.colorFilter = if (s.iconFilter == 1) oliveFilter else null
        if (s.iconShape == 0) {
            iconPaint.shader = null
            iconPaint.alpha = alpha.coerceIn(0, 255)
            c.drawBitmap(bmp, null, dst, iconPaint)
            return
        }
        val sh = shaders.getOrPut(bmp) { BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        m.reset()
        m.setScale(dst.width() / bmp.width, dst.height() / bmp.height)
        m.postTranslate(dst.left, dst.top)
        sh.setLocalMatrix(m)
        iconPaint.shader = sh
        iconPaint.alpha = alpha.coerceIn(0, 255)
        if (s.iconShape == 1) {
            c.drawCircle(dst.centerX(), dst.centerY(), dst.width() / 2f, iconPaint)
        } else {
            val rr = dst.width() * 0.22f
            c.drawRoundRect(dst, rr, rr, iconPaint)
        }
        iconPaint.shader = null
    }
}
