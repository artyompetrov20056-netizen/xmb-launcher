package com.xmb.launcher

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

enum class Glyph {
    SETTINGS, PHOTO, MUSIC, VIDEO, GAME, NETWORK, APPS,
    WIFI, BLUETOOTH, DISPLAY, SOUND, PALETTE, HOME, GEAR, INFO, EMPTY,
    FAVORITE, SEARCH, LOCK,
    TORCH, PLAY, PAUSE, PREV, NEXT,
    ALARM, TIMER, PLUS, HOURGLASS
}

/**
 * Белые контурные иконки категорий в стиле XMB, нарисованные кодом
 * (никаких картинок из PS3 — всё своё).
 * Координаты в квадрате -0.5..0.5, затем масштабируются до нужного размера.
 */
object Glyphs {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val r = RectF()

    /** Толщина линий значков — задаётся стилем оформления. */
    var weight = 1f
    /** Цвет значков — задаётся стилем оформления. */
    var tint = Color.WHITE

    fun draw(c: Canvas, g: Glyph, cx: Float, cy: Float, size: Float, alpha: Int) {
        val a = alpha.coerceIn(0, 255)
        if (boneMode && !caching) { drawCachedChrome(c, g, cx, cy, size, a); return }
        curAlpha = a
        stroke.color = tint
        stroke.alpha = a
        stroke.strokeWidth = 0.075f * weight
        fill.color = tint
        fill.alpha = a

        c.save()
        c.translate(cx, cy)
        c.scale(size, size)
        when (g) {
            Glyph.SETTINGS -> { // ящик с инструментами
                rr(c, -0.42f, -0.10f, 0.42f, 0.36f, 0.07f, stroke)
                rr(c, -0.16f, -0.30f, 0.16f, -0.10f, 0.04f, stroke)
                L(c, -0.42f, 0.08f, 0.42f, 0.08f, stroke)
                rr(c, -0.07f, 0.0f, 0.07f, 0.16f, 0.02f, fill)
            }
            Glyph.PHOTO -> { // фотоаппарат
                rr(c, -0.42f, -0.20f, 0.42f, 0.32f, 0.08f, stroke)
                rr(c, -0.20f, -0.33f, 0.04f, -0.20f, 0.03f, fill)
                C(c, 0f, 0.06f, 0.16f, stroke)
                C(c, 0.28f, -0.08f, 0.035f, fill)
            }
            Glyph.MUSIC -> { // нота
                r.set(-0.36f, 0.18f, -0.10f, 0.38f); O(c, r, fill)
                r.set(0.10f, 0.10f, 0.36f, 0.30f); O(c, r, fill)
                L(c, -0.11f, 0.26f, -0.11f, -0.28f, stroke)
                L(c, 0.35f, 0.18f, 0.35f, -0.38f, stroke)
                path.rewind()
                path.moveTo(-0.11f, -0.30f); path.lineTo(0.35f, -0.40f)
                path.lineTo(0.35f, -0.26f); path.lineTo(-0.11f, -0.16f); path.close()
                P(c, path, fill)
            }
            Glyph.VIDEO -> { // плёнка
                rr(c, -0.40f, -0.32f, 0.40f, 0.32f, 0.03f, stroke)
                L(c, -0.24f, -0.32f, -0.24f, 0.32f, stroke)
                L(c, 0.24f, -0.32f, 0.24f, 0.32f, stroke)
                for (y in floatArrayOf(-0.20f, 0f, 0.20f)) {
                    rr(c, -0.36f, y - 0.05f, -0.28f, y + 0.05f, 0.01f, fill)
                    rr(c, 0.28f, y - 0.05f, 0.36f, y + 0.05f, 0.01f, fill)
                }
                path.rewind()
                path.moveTo(-0.07f, -0.12f); path.lineTo(0.12f, 0f); path.lineTo(-0.07f, 0.12f); path.close()
                P(c, path, fill)
            }
            Glyph.GAME -> { // геймпад
                path.rewind()
                path.moveTo(-0.30f, -0.18f)
                path.lineTo(0.30f, -0.18f)
                path.cubicTo(0.48f, -0.18f, 0.50f, 0.10f, 0.46f, 0.26f)
                path.cubicTo(0.42f, 0.38f, 0.30f, 0.36f, 0.22f, 0.22f)
                path.lineTo(-0.22f, 0.22f)
                path.cubicTo(-0.30f, 0.36f, -0.42f, 0.38f, -0.46f, 0.26f)
                path.cubicTo(-0.50f, 0.10f, -0.48f, -0.18f, -0.30f, -0.18f)
                path.close()
                P(c, path, stroke)
                L(c, -0.33f, 0.01f, -0.13f, 0.01f, stroke)
                L(c, -0.23f, -0.09f, -0.23f, 0.11f, stroke)
                C(c, 0.23f, -0.07f, 0.035f, fill)
                C(c, 0.23f, 0.09f, 0.035f, fill)
                C(c, 0.15f, 0.01f, 0.035f, fill)
                C(c, 0.31f, 0.01f, 0.035f, fill)
            }
            Glyph.NETWORK -> { // глобус
                C(c, 0f, 0f, 0.40f, stroke)
                r.set(-0.16f, -0.40f, 0.16f, 0.40f); O(c, r, stroke)
                L(c, -0.40f, 0f, 0.40f, 0f, stroke)
                L(c, -0.34f, -0.20f, 0.34f, -0.20f, stroke)
                L(c, -0.34f, 0.20f, 0.34f, 0.20f, stroke)
            }
            Glyph.APPS -> { // сетка 3x3
                for (ix in -1..1) for (iy in -1..1) {
                    val x = ix * 0.28f; val y = iy * 0.28f
                    rr(c, x - 0.10f, y - 0.10f, x + 0.10f, y + 0.10f, 0.035f, fill)
                }
            }
            Glyph.WIFI -> {
                for (rad in floatArrayOf(0.18f, 0.36f, 0.54f)) {
                    r.set(-rad, 0.30f - rad, rad, 0.30f + rad)
                    A(c, r, 225f, 90f, false, stroke)
                }
                C(c, 0f, 0.30f, 0.055f, fill)
            }
            Glyph.BLUETOOTH -> {
                path.rewind()
                path.moveTo(-0.20f, -0.18f); path.lineTo(0.20f, 0.18f); path.lineTo(0f, 0.38f)
                path.lineTo(0f, -0.38f); path.lineTo(0.20f, -0.18f); path.lineTo(-0.20f, 0.18f)
                P(c, path, stroke)
            }
            Glyph.DISPLAY -> {
                rr(c, -0.42f, -0.30f, 0.42f, 0.18f, 0.04f, stroke)
                L(c, 0f, 0.18f, 0f, 0.32f, stroke)
                L(c, -0.20f, 0.34f, 0.20f, 0.34f, stroke)
            }
            Glyph.SOUND -> {
                path.rewind()
                path.moveTo(-0.40f, -0.12f); path.lineTo(-0.20f, -0.12f); path.lineTo(0f, -0.30f)
                path.lineTo(0f, 0.30f); path.lineTo(-0.20f, 0.12f); path.lineTo(-0.40f, 0.12f); path.close()
                P(c, path, fill)
                for (rad in floatArrayOf(0.20f, 0.36f)) {
                    r.set(-rad + 0.02f, -rad, rad + 0.02f, rad)
                    A(c, r, -45f, 90f, false, stroke)
                }
            }
            Glyph.PALETTE -> {
                C(c, 0f, 0f, 0.40f, stroke)
                for (k in 0 until 4) {
                    val ang = (k * 90.0 + 45.0) * PI / 180.0
                    fill.color = XmbTheme.previewColor(k * 2)
                    fill.alpha = a
                    C(c, (cos(ang) * 0.20).toFloat(), (sin(ang) * 0.20).toFloat(), 0.10f, fill)
                }
                fill.color = tint
                fill.alpha = a
            }
            Glyph.HOME -> {
                path.rewind()
                path.moveTo(-0.40f, 0.0f); path.lineTo(0f, -0.36f); path.lineTo(0.40f, 0.0f)
                P(c, path, stroke)
                path.rewind()
                path.moveTo(-0.28f, -0.10f); path.lineTo(-0.28f, 0.36f); path.lineTo(0.28f, 0.36f); path.lineTo(0.28f, -0.10f)
                P(c, path, stroke)
                rr(c, -0.08f, 0.14f, 0.08f, 0.36f, 0.02f, fill)
            }
            Glyph.GEAR -> {
                C(c, 0f, 0f, 0.27f, stroke)
                C(c, 0f, 0f, 0.09f, stroke)
                stroke.strokeWidth = 0.12f
                stroke.strokeCap = Paint.Cap.BUTT
                for (k in 0 until 8) {
                    val ang = k * PI / 4.0
                    val cs = cos(ang).toFloat(); val sn = sin(ang).toFloat()
                    L(c, cs * 0.30f, sn * 0.30f, cs * 0.43f, sn * 0.43f, stroke)
                }
                stroke.strokeCap = Paint.Cap.ROUND
            }
            Glyph.INFO -> {
                C(c, 0f, 0f, 0.40f, stroke)
                C(c, 0f, -0.18f, 0.05f, fill)
                L(c, 0f, -0.04f, 0f, 0.22f, stroke)
            }
            Glyph.FAVORITE -> { // сердце
                path.rewind()
                path.moveTo(0f, 0.36f)
                path.cubicTo(-0.56f, 0.02f, -0.42f, -0.44f, 0f, -0.18f)
                path.cubicTo(0.42f, -0.44f, 0.56f, 0.02f, 0f, 0.36f)
                path.close()
                P(c, path, stroke)
            }
            Glyph.SEARCH -> { // лупа
                C(c, -0.08f, -0.08f, 0.25f, stroke)
                stroke.strokeWidth = 0.11f
                L(c, 0.11f, 0.11f, 0.38f, 0.38f, stroke)
            }
            Glyph.LOCK -> { // замок
                rr(c, -0.30f, -0.06f, 0.30f, 0.38f, 0.06f, stroke)
                r.set(-0.19f, -0.40f, 0.19f, 0.02f)
                A(c, r, 180f, 180f, false, stroke)
                L(c, -0.19f, -0.19f, -0.19f, -0.06f, stroke)
                L(c, 0.19f, -0.19f, 0.19f, -0.06f, stroke)
                C(c, 0f, 0.14f, 0.05f, fill)
            }
            Glyph.TORCH -> { // фонарик
                path.rewind()
                path.moveTo(-0.22f, -0.40f); path.lineTo(0.22f, -0.40f)
                path.lineTo(0.13f, -0.14f); path.lineTo(-0.13f, -0.14f); path.close()
                P(c, path, stroke)
                rr(c, -0.12f, -0.14f, 0.12f, 0.40f, 0.04f, stroke)
                C(c, 0f, 0.05f, 0.04f, fill)
            }
            Glyph.PLAY -> {
                path.rewind()
                path.moveTo(-0.20f, -0.30f); path.lineTo(0.30f, 0f); path.lineTo(-0.20f, 0.30f); path.close()
                P(c, path, fill)
            }
            Glyph.PAUSE -> {
                rr(c, -0.22f, -0.30f, -0.06f, 0.30f, 0.03f, fill)
                rr(c, 0.06f, -0.30f, 0.22f, 0.30f, 0.03f, fill)
            }
            Glyph.PREV -> {
                rr(c, -0.34f, -0.28f, -0.24f, 0.28f, 0.02f, fill)
                path.rewind()
                path.moveTo(0.30f, -0.30f); path.lineTo(-0.20f, 0f); path.lineTo(0.30f, 0.30f); path.close()
                P(c, path, fill)
            }
            Glyph.NEXT -> {
                rr(c, 0.24f, -0.28f, 0.34f, 0.28f, 0.02f, fill)
                path.rewind()
                path.moveTo(-0.30f, -0.30f); path.lineTo(0.20f, 0f); path.lineTo(-0.30f, 0.30f); path.close()
                P(c, path, fill)
            }
            Glyph.ALARM -> { // будильник: циферблат, стрелки, «колокольчики»
                C(c, 0f, 0.04f, 0.32f, stroke)
                L(c, 0f, 0.04f, 0f, -0.14f, stroke)
                L(c, 0f, 0.04f, 0.13f, 0.12f, stroke)
                r.set(-0.46f, -0.46f, -0.14f, -0.14f); A(c, r, 200f, 70f, false, stroke)
                r.set(0.14f, -0.46f, 0.46f, -0.14f); A(c, r, 270f, 70f, false, stroke)
                L(c, -0.22f, 0.30f, -0.30f, 0.40f, stroke)
                L(c, 0.22f, 0.30f, 0.30f, 0.40f, stroke)
            }
            Glyph.TIMER -> { // секундомер
                C(c, 0f, 0.06f, 0.33f, stroke)
                L(c, -0.10f, -0.40f, 0.10f, -0.40f, stroke)
                L(c, 0f, -0.40f, 0f, -0.27f, stroke)
                L(c, 0.25f, -0.22f, 0.32f, -0.29f, stroke)
                L(c, 0f, 0.06f, 0.14f, -0.08f, stroke)
            }
            Glyph.HOURGLASS -> { // песочные часы
                L(c, -0.26f, -0.40f, 0.26f, -0.40f, stroke)
                L(c, -0.26f, 0.40f, 0.26f, 0.40f, stroke)
                path.rewind()
                path.moveTo(-0.20f, -0.40f); path.lineTo(0.20f, -0.40f)
                path.lineTo(0.03f, 0f); path.lineTo(0.20f, 0.40f)
                path.lineTo(-0.20f, 0.40f); path.lineTo(-0.03f, 0f); path.close()
                P(c, path, stroke)
                path.rewind()
                path.moveTo(-0.11f, 0.33f); path.lineTo(0.11f, 0.33f); path.lineTo(0f, 0.16f); path.close()
                P(c, path, fill)
            }
            Glyph.PLUS -> {
                C(c, 0f, 0f, 0.40f, stroke)
                L(c, -0.18f, 0f, 0.18f, 0f, stroke)
                L(c, 0f, -0.18f, 0f, 0.18f, stroke)
            }
            Glyph.EMPTY -> {
                C(c, 0f, 0f, 0.30f, stroke)
                L(c, -0.14f, 0f, 0.14f, 0f, stroke)
            }
        }
        c.restore()
    }

    private fun rr(c: Canvas, l: Float, t: Float, rt: Float, b: Float, rad: Float, p: Paint) {
        r.set(l, t, rt, b)
        if (!boneMode) { c.drawRoundRect(r, rad, rad, p); return }
        tmp.rewind()
        tmp.addRoundRect(r, rad, rad, Path.Direction.CW)
        if (p === fill) blob(c, tmp) else boneify(c, tmp)
    }

    // =====================================================================
    // Режим «хромированные кости» (стиль «Хром»): каждая линия значка —
    // цепочка костей с мыщелками, каждая заливка — хромовая шишка.
    // =====================================================================

    /** Включается стилем «Хром». */
    var boneMode = false
    private var curAlpha = 255
    private val tmp = Path()
    private val pm = android.graphics.PathMeasure()
    private val p0 = FloatArray(2)
    private val p1 = FloatArray(2)
    private val boneRect = RectF()
    private val boneMatrix = android.graphics.Matrix()
    private val chromeLin = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = android.graphics.LinearGradient(0f, -0.5f, 0f, 0.5f,
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFB9BED8.toInt(), 0xFF555A76.toInt(), 0xFFF6F6FF.toInt(), 0xFF8C91B0.toInt(), 0xFF262940.toInt()),
            floatArrayOf(0f, 0.2f, 0.45f, 0.56f, 0.8f, 1f), android.graphics.Shader.TileMode.CLAMP)
    }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = android.graphics.RadialGradient(-0.35f, -0.4f, 1.3f,
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFAEB2CC.toInt(), 0xFF3A3D58.toInt()), floatArrayOf(0f, 0.45f, 1f),
            android.graphics.Shader.TileMode.CLAMP)
    }
    private val blobShader = android.graphics.RadialGradient(-0.35f, -0.4f, 1.3f,
        intArrayOf(0xFFFFFFFF.toInt(), 0xFFAEB2CC.toInt(), 0xFF3A3D58.toInt()), floatArrayOf(0f, 0.45f, 1f),
        android.graphics.Shader.TileMode.CLAMP)
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = blobShader }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(12, 6, 32) }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.rgb(20, 10, 40) }

    private fun L(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, p: Paint) {
        if (boneMode) bone(c, x0, y0, x1, y1) else c.drawLine(x0, y0, x1, y1, p)
    }

    private fun C(c: Canvas, x: Float, y: Float, rad: Float, p: Paint) {
        if (!boneMode) { c.drawCircle(x, y, rad, p); return }
        tmp.rewind()
        tmp.addCircle(x, y, rad, Path.Direction.CW)
        if (p === fill) blob(c, tmp) else boneify(c, tmp)
    }

    private fun P(c: Canvas, path: Path, p: Paint) {
        if (!boneMode) { c.drawPath(path, p); return }
        if (p === fill) blob(c, path) else boneify(c, path)
    }

    private fun A(c: Canvas, oval: RectF, start: Float, sweep: Float, useCenter: Boolean, p: Paint) {
        if (!boneMode) { c.drawArc(oval, start, sweep, useCenter, p); return }
        tmp.rewind()
        tmp.addArc(oval, start, sweep)
        boneify(c, tmp)
    }

    private fun O(c: Canvas, oval: RectF, p: Paint) {
        if (!boneMode) { c.drawOval(oval, p); return }
        tmp.rewind()
        tmp.addOval(oval, Path.Direction.CW)
        if (p === fill) blob(c, tmp) else boneify(c, tmp)
    }

    /** Толщина хромовой трубки в долях значка. */
    private fun tubeT() = 0.16f * weight

    /** Контур → рёберная хромовая трубка с шипами. */
    private fun boneify(c: Canvas, path: Path) {
        ChromeArt.tube(c, path, tubeT(), 1)
    }

    private fun bone(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        tmp.rewind()
        tmp.moveTo(x0, y0)
        tmp.lineTo(x1, y1)
        ChromeArt.tube(c, tmp, tubeT(), 1)
    }

    /** Заливка → сплошной хромовый узел. */
    private fun blob(c: Canvas, path: Path) {
        path.computeBounds(boneRect, true)
        ChromeArt.solid(c, path, boneRect.centerX(), boneRect.centerY(),
            max(boneRect.width(), 0.02f) / 2f, max(boneRect.height(), 0.02f) / 2f)
    }

    // ---------------- кэш хромовых значков (рисуются один раз) ----------------

    private var caching = false
    private val chromeCache = object : LinkedHashMap<Long, android.graphics.Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, android.graphics.Bitmap>?) = size > 90
    }
    private val cachePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private fun drawCachedChrome(c: Canvas, g: Glyph, cx: Float, cy: Float, size: Float, alpha: Int) {
        val q = max(8, (kotlin.math.round(size / 6f) * 6).toInt())
        val px = (q * 1.7f).toInt()
        val key = g.ordinal.toLong() * 100000L + q
        val bmp = chromeCache[key] ?: run {
            val b = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
            caching = true
            try { draw(Canvas(b), g, px / 2f, px / 2f, q.toFloat(), 255) } finally { caching = false }
            chromeCache[key] = b
            b
        }
        val s = size / q
        cachePaint.alpha = alpha
        boneRect.set(cx - px * s / 2f, cy - px * s / 2f, cx + px * s / 2f, cy + px * s / 2f)
        c.drawBitmap(bmp, null, boneRect, cachePaint)
    }
}

/** Кнопки геймпада по расположению: нижняя, правая, левая, верхняя. */
enum class PadBtn { SOUTH, EAST, WEST, NORTH, DPAD, START, SHOULDERS }

/**
 * Значки кнопок геймпада в стиле XMB: тонкие белые контуры, без цветных заливок.
 * style 0 — PlayStation (✕ ○ □ △), style 1 — Xbox (A B X Y).
 * Нижняя = ✕/A, правая = ○/B, левая = □/X, верхняя = △/Y.
 */
object PadIcons {
    const val STYLE_PS = 0
    const val STYLE_XBOX = 1
    val STYLE_NAMES = listOf("PlayStation  ✕ ○ △ □", "Xbox  A B X Y")

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = Color.WHITE
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
    }
    private val path = Path()
    private val r = RectF()
    private val XB_LETTERS = arrayOf("A", "B", "X", "Y")

    /** Ширина значка (для раскладки подсказок). */
    fun width(b: PadBtn, rad: Float) = if (b == PadBtn.SHOULDERS || b == PadBtn.START) rad * 3.0f else rad * 2f

    fun draw(c: Canvas, b: PadBtn, cx: Float, cy: Float, rad: Float, style: Int, alpha: Int) {
        val a = alpha.coerceIn(0, 255)
        val line = max(1.2f, rad * 0.09f)
        // общий контур: тонкое кольцо и едва заметная заливка, как рамка часов XMB
        fun ring() {
            fill.alpha = (a * 0.10f).toInt()
            c.drawCircle(cx, cy, rad, fill)
            stroke.alpha = (a * 0.85f).toInt()
            stroke.strokeWidth = line
            c.drawCircle(cx, cy, rad, stroke)
        }
        when (b) {
            PadBtn.SOUTH, PadBtn.EAST, PadBtn.WEST, PadBtn.NORTH -> {
                ring()
                if (style == STYLE_XBOX) {
                    text.alpha = a
                    text.textSize = rad * 1.05f
                    c.drawText(XB_LETTERS[b.ordinal], cx, cy + text.textSize * 0.36f, text)
                } else {
                    stroke.alpha = a
                    stroke.strokeWidth = line * 1.1f
                    val s = rad * 0.38f
                    when (b) {
                        PadBtn.SOUTH -> {
                            c.drawLine(cx - s, cy - s, cx + s, cy + s, stroke)
                            c.drawLine(cx - s, cy + s, cx + s, cy - s, stroke)
                        }
                        PadBtn.EAST -> c.drawCircle(cx, cy, rad * 0.42f, stroke)
                        PadBtn.WEST -> { r.set(cx - s, cy - s, cx + s, cy + s); c.drawRect(r, stroke) }
                        else -> {
                            path.rewind()
                            path.moveTo(cx, cy - rad * 0.46f)
                            path.lineTo(cx + rad * 0.44f, cy + rad * 0.30f)
                            path.lineTo(cx - rad * 0.44f, cy + rad * 0.30f)
                            path.close()
                            c.drawPath(path, stroke)
                        }
                    }
                }
            }
            PadBtn.DPAD -> {
                // контур крестовины
                val t = rad * 0.32f
                val l = rad * 0.95f
                path.rewind()
                path.moveTo(cx - t, cy - l); path.lineTo(cx + t, cy - l); path.lineTo(cx + t, cy - t)
                path.lineTo(cx + l, cy - t); path.lineTo(cx + l, cy + t); path.lineTo(cx + t, cy + t)
                path.lineTo(cx + t, cy + l); path.lineTo(cx - t, cy + l); path.lineTo(cx - t, cy + t)
                path.lineTo(cx - l, cy + t); path.lineTo(cx - l, cy - t); path.lineTo(cx - t, cy - t)
                path.close()
                fill.alpha = (a * 0.10f).toInt()
                c.drawPath(path, fill)
                stroke.alpha = (a * 0.85f).toInt()
                stroke.strokeWidth = line
                c.drawPath(path, stroke)
            }
            PadBtn.START, PadBtn.SHOULDERS -> {
                val w = width(b, rad)
                r.set(cx - w / 2f, cy - rad * 0.68f, cx + w / 2f, cy + rad * 0.68f)
                fill.alpha = (a * 0.10f).toInt()
                c.drawRoundRect(r, rad * 0.68f, rad * 0.68f, fill)
                stroke.alpha = (a * 0.85f).toInt()
                stroke.strokeWidth = line
                c.drawRoundRect(r, rad * 0.68f, rad * 0.68f, stroke)
                val label = when {
                    b == PadBtn.START && style == STYLE_XBOX -> "MENU"
                    b == PadBtn.START -> "START"
                    style == STYLE_XBOX -> "LB RB"
                    else -> "L1 R1"
                }
                text.alpha = a
                text.textSize = rad * 0.6f
                c.drawText(label, cx, cy + text.textSize * 0.36f, text)
            }
        }
    }
}
