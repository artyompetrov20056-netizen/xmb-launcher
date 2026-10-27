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
import kotlin.math.max

/** Оформление иконок в стиле. */
enum class FrameKind { NONE, BUBBLE, BEVEL, SQUARE, GLOW, ROUNDED, CARD, CHROME, HUD, PEBBLE, CRYSTAL }

/** Анимированная сцена фона. */
enum class Scene { WAVES, SPHERES, TILES, CUBES, SOFT_WAVE, LINES, RIBBONS, LIGHT, PARTICLES, CHROME, WEBCORE, SNAKE, CRYSTAL }

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
    /** Цвет самих значков (если отличается от подписей). */
    val glyphInk: Int = ink,
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
        VisualStyle(
            "Змейка", Scene.SNAKE, intArrayOf(c(0xFF3F0A84), c(0xFF2C6DC2), c(0xFF9A35C8)), FrameKind.PEBBLE, 1.3f, 2,
            glyphInk = c(0xFF16250A),
        ),
        VisualStyle("Кристаллы", Scene.CRYSTAL, null, FrameKind.CRYSTAL, 0.95f, 2),
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
    /** Основной цвет темы — для стилей, которые подстраиваются под «Цвет темы». */
    var accent = Color.rgb(42, 120, 208)

    fun drawFrame(c: Canvas, s: VisualStyle, cx: Float, cy: Float, size: Float, alpha: Int, sel: Float, time: Float = 0f, app: Boolean = false) {
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
            FrameKind.PEBBLE -> drawPebble(c, cx, cy, size, a, sel)
            FrameKind.CRYSTAL -> drawCrystal(c, cx, cy, size, a, sel, time, app)
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

    /** Хром: никакой рамки — значок сам собран из костей; у выбранного только мягкий отблеск. */
    private fun drawChromeFrame(c: Canvas, cx: Float, cy: Float, size: Float, a: Float, sel: Float, time: Float) {
        if (sel <= 0.05f) return
        glow.alpha = (255 * a * sel * (0.30f + 0.08f * kotlin.math.sin(time * 2.5f))).toInt()
        c.save(); c.translate(cx, cy); c.scale(size * 0.9f, size * 0.9f)
        c.drawCircle(0f, 0f, 1f, glow); c.restore()
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

    // ---------------- Кристаллы: вращающийся гранёный кристалл ----------------

    private val facet = android.graphics.Path()
    private val ex = FloatArray(13)
    private val ed = FloatArray(13)
    private val tintGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    private var tintGlowColor = 0

    private fun mix(a: Int, b: Int, f: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt(),
    )

    private fun aura(c: Canvas, cx: Float, cy: Float, rr: Float, alpha: Int) {
        val col = mix(accent, Color.WHITE, 0.45f)
        if (col != tintGlowColor) {
            tintGlowColor = col
            tintGlow.shader = RadialGradient(0f, 0f, 1f,
                intArrayOf(Color.argb(200, Color.red(col), Color.green(col), Color.blue(col)), Color.argb(60, Color.red(col), Color.green(col), Color.blue(col)), Color.TRANSPARENT),
                floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
        }
        tintGlow.alpha = alpha.coerceIn(0, 255)
        c.save(); c.translate(cx, cy); c.scale(rr, rr); c.drawCircle(0f, 0f, 1f, tintGlow); c.restore()
    }

    private fun sparkle(c: Canvas, x: Float, y: Float, s: Float, alpha: Int) {
        line.color = Color.WHITE
        line.strokeWidth = max(1f, s * 0.18f)
        line.alpha = alpha.coerceIn(0, 255)
        c.drawLine(x - s, y, x + s, y, line)
        c.drawLine(x, y - s, x, y + s, line)
        line.strokeWidth = max(1f, s * 0.1f)
        c.drawLine(x - s * 0.5f, y - s * 0.5f, x + s * 0.5f, y + s * 0.5f, line)
        c.drawLine(x - s * 0.5f, y + s * 0.5f, x + s * 0.5f, y - s * 0.5f, line)
    }

    private fun drawCrystal(c: Canvas, cx: Float, cy: Float, size: Float, a: Float, sel: Float, time: Float, app: Boolean) {
        val al = (255 * a).toInt()
        aura(c, cx, cy, size * (0.85f + 0.25f * sel), (al * (0.25f + 0.45f * sel)).toInt())
        if (app) {
            // иконкам приложений — стеклянный ободок
            line.color = mix(accent, Color.WHITE, 0.7f)
            line.strokeWidth = (size * 0.035f).coerceAtLeast(1.5f)
            line.alpha = (al * (0.5f + 0.4f * sel)).toInt()
            r.set(cx - size * 0.5f, cy - size * 0.5f, cx + size * 0.5f, cy + size * 0.5f)
            c.drawRoundRect(r, size * 0.13f, size * 0.13f, line)
            line.color = Color.WHITE
            return
        }
        val hh = size * 0.74f
        val ww = size * 0.44f
        val n = 6
        val rot = time * (0.5f + 0.4f * sel) + cx * 0.011f + cy * 0.007f
        val light = mix(accent, Color.WHITE, 0.72f)
        val mid = mix(accent, Color.WHITE, 0.25f)
        val deep = mix(accent, Color.BLACK, 0.5f)
        val twoPi = (2.0 * Math.PI).toFloat()
        for (i in 0..n) {
            val th = rot + i * twoPi / n
            ex[i] = cx + ww * kotlin.math.sin(th)
            ed[i] = kotlin.math.cos(th)
        }
        // грани (видны только передние)
        for (i in 0 until n) {
            val thm = rot + (i + 0.5f) * twoPi / n
            if (kotlin.math.cos(thm) <= 0f) continue
            val lit = 0.25f + 0.75f * max(0f, kotlin.math.cos(thm + 0.9f))
            // верхняя грань
            facet.rewind()
            facet.moveTo(cx, cy - hh); facet.lineTo(ex[i], cy); facet.lineTo(ex[i + 1], cy); facet.close()
            fill.color = if (lit > 0.6f) mix(mid, light, (lit - 0.6f) / 0.4f) else mix(deep, mid, lit / 0.6f)
            fill.alpha = (al * 0.93f).toInt()
            c.drawPath(facet, fill)
            // нижняя грань — темнее
            facet.rewind()
            facet.moveTo(cx, cy + hh); facet.lineTo(ex[i], cy); facet.lineTo(ex[i + 1], cy); facet.close()
            val lb = lit * 0.65f
            fill.color = if (lb > 0.6f) mix(mid, light, (lb - 0.6f) / 0.4f) else mix(deep, mid, lb / 0.6f)
            c.drawPath(facet, fill)
        }
        // внутренняя «преломлённая» огранка
        line.color = Color.WHITE
        line.strokeWidth = (size * 0.012f).coerceAtLeast(1f)
        line.alpha = (al * 0.35f).toInt()
        for (i in 0 until n) {
            val th = -rot * 1.3f + i * twoPi / n
            if (kotlin.math.cos(th) < 0f) continue
            val ix = cx + ww * 0.5f * kotlin.math.sin(th)
            c.drawLine(cx, cy - hh * 0.55f, ix, cy + hh * 0.08f, line)
            c.drawLine(ix, cy + hh * 0.08f, cx, cy + hh * 0.6f, line)
        }
        // рёбра
        line.strokeWidth = (size * 0.018f).coerceAtLeast(1f)
        for (i in 0..n) {
            if (ed[i] < -0.05f) continue
            line.alpha = (al * (0.35f + 0.4f * ed[i])).toInt()
            c.drawLine(ex[i], cy, cx, cy - hh, line)
            c.drawLine(ex[i], cy, cx, cy + hh, line)
        }
        line.alpha = (al * 0.6f).toInt()
        var first = -1
        for (i in 0 until n) {
            val thm = rot + (i + 0.5f) * twoPi / n
            if (kotlin.math.cos(thm) > 0f) { c.drawLine(ex[i], cy, ex[i + 1], cy, line); if (first < 0) first = i }
        }
        // бегущий блик по грани
        val glint = (time * 0.35f + cx * 0.003f) % 1f
        if (glint < 0.35f) {
            val g = glint / 0.35f
            line.strokeWidth = size * 0.05f
            line.alpha = (al * 0.75f * kotlin.math.sin(g * Math.PI).toFloat()).toInt()
            val yy = cy - hh * (0.9f - g * 0.8f)
            val half = ww * (1f - (cy - yy).let { kotlin.math.abs(it) } / hh) * 0.75f
            c.drawLine(cx - half, yy + half * 0.3f, cx + half * 0.3f, yy - half * 0.2f, line)
        }
        // искры
        val nSp = if (sel > 0.3f) 5 else 2
        for (k in 0 until nSp) {
            val ang = k * 2.4f + time * 0.3f
            val rr = size * (0.62f + 0.18f * kotlin.math.sin(time + k))
            val tw = 0.5f + 0.5f * kotlin.math.sin(time * 3.1f + k * 1.7f)
            sparkle(c, cx + rr * kotlin.math.cos(ang), cy + rr * kotlin.math.sin(ang) * 1.2f, size * 0.07f * (0.5f + tw), (al * tw * (0.5f + 0.5f * sel)).toInt())
        }
    }

    // ---------------- Змейка: мягкая зелёная «галечка» с контуром ----------------

    private val pebbleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = android.graphics.LinearGradient(0f, -0.5f, 0f, 0.5f,
            intArrayOf(c(0xFFB9E87E), c(0xFF7CC22A), c(0xFF4E9A00)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    }
    private val pebbleLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = c(0xFF101010); strokeJoin = Paint.Join.ROUND
    }

    private fun drawPebble(c: Canvas, cx: Float, cy: Float, size: Float, a: Float, sel: Float) {
        val h = size * (0.62f + 0.04f * sel)
        // мягкая тень
        fill.color = c(0xFF2A0E52)
        fill.alpha = (255 * a * 0.25f).toInt()
        r.set(cx - h, cy - h * 0.9f + h * 0.18f, cx + h, cy + h * 0.9f + h * 0.18f)
        c.drawRoundRect(r, h * 0.55f, h * 0.55f, fill)
        fill.color = Color.WHITE
        // сама галечка с градиентом
        r.set(-0.5f, -0.5f, 0.5f, 0.5f)
        pebbleFill.alpha = (255 * a).toInt()
        c.save(); c.translate(cx, cy); c.scale(h * 2f, h * 1.8f)
        c.drawRoundRect(r, 0.27f, 0.3f, pebbleFill)
        c.restore()
        pebbleLine.strokeWidth = (size * 0.045f).coerceAtLeast(2f)
        pebbleLine.alpha = (255 * a).toInt()
        r.set(cx - h, cy - h * 0.9f, cx + h, cy + h * 0.9f)
        c.drawRoundRect(r, h * 0.55f, h * 0.55f, pebbleLine)
        // блик
        fill.alpha = (255 * a * (0.30f + 0.2f * sel)).toInt()
        r.set(cx - h * 0.65f, cy - h * 0.72f, cx - h * 0.05f, cy - h * 0.45f)
        c.drawOval(r, fill)
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
