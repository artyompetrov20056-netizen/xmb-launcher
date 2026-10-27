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
    ALARM, TIMER, PLUS
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

    fun draw(c: Canvas, g: Glyph, cx: Float, cy: Float, size: Float, alpha: Int) {
        val a = alpha.coerceIn(0, 255)
        stroke.color = Color.WHITE
        stroke.alpha = a
        stroke.strokeWidth = 0.075f
        fill.color = Color.WHITE
        fill.alpha = a

        c.save()
        c.translate(cx, cy)
        c.scale(size, size)
        when (g) {
            Glyph.SETTINGS -> { // ящик с инструментами
                rr(c, -0.42f, -0.10f, 0.42f, 0.36f, 0.07f, stroke)
                rr(c, -0.16f, -0.30f, 0.16f, -0.10f, 0.04f, stroke)
                c.drawLine(-0.42f, 0.08f, 0.42f, 0.08f, stroke)
                rr(c, -0.07f, 0.0f, 0.07f, 0.16f, 0.02f, fill)
            }
            Glyph.PHOTO -> { // фотоаппарат
                rr(c, -0.42f, -0.20f, 0.42f, 0.32f, 0.08f, stroke)
                rr(c, -0.20f, -0.33f, 0.04f, -0.20f, 0.03f, fill)
                c.drawCircle(0f, 0.06f, 0.16f, stroke)
                c.drawCircle(0.28f, -0.08f, 0.035f, fill)
            }
            Glyph.MUSIC -> { // нота
                r.set(-0.36f, 0.18f, -0.10f, 0.38f); c.drawOval(r, fill)
                r.set(0.10f, 0.10f, 0.36f, 0.30f); c.drawOval(r, fill)
                c.drawLine(-0.11f, 0.26f, -0.11f, -0.28f, stroke)
                c.drawLine(0.35f, 0.18f, 0.35f, -0.38f, stroke)
                path.rewind()
                path.moveTo(-0.11f, -0.30f); path.lineTo(0.35f, -0.40f)
                path.lineTo(0.35f, -0.26f); path.lineTo(-0.11f, -0.16f); path.close()
                c.drawPath(path, fill)
            }
            Glyph.VIDEO -> { // плёнка
                rr(c, -0.40f, -0.32f, 0.40f, 0.32f, 0.03f, stroke)
                c.drawLine(-0.24f, -0.32f, -0.24f, 0.32f, stroke)
                c.drawLine(0.24f, -0.32f, 0.24f, 0.32f, stroke)
                for (y in floatArrayOf(-0.20f, 0f, 0.20f)) {
                    rr(c, -0.36f, y - 0.05f, -0.28f, y + 0.05f, 0.01f, fill)
                    rr(c, 0.28f, y - 0.05f, 0.36f, y + 0.05f, 0.01f, fill)
                }
                path.rewind()
                path.moveTo(-0.07f, -0.12f); path.lineTo(0.12f, 0f); path.lineTo(-0.07f, 0.12f); path.close()
                c.drawPath(path, fill)
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
                c.drawPath(path, stroke)
                c.drawLine(-0.33f, 0.01f, -0.13f, 0.01f, stroke)
                c.drawLine(-0.23f, -0.09f, -0.23f, 0.11f, stroke)
                c.drawCircle(0.23f, -0.07f, 0.035f, fill)
                c.drawCircle(0.23f, 0.09f, 0.035f, fill)
                c.drawCircle(0.15f, 0.01f, 0.035f, fill)
                c.drawCircle(0.31f, 0.01f, 0.035f, fill)
            }
            Glyph.NETWORK -> { // глобус
                c.drawCircle(0f, 0f, 0.40f, stroke)
                r.set(-0.16f, -0.40f, 0.16f, 0.40f); c.drawOval(r, stroke)
                c.drawLine(-0.40f, 0f, 0.40f, 0f, stroke)
                c.drawLine(-0.34f, -0.20f, 0.34f, -0.20f, stroke)
                c.drawLine(-0.34f, 0.20f, 0.34f, 0.20f, stroke)
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
                    c.drawArc(r, 225f, 90f, false, stroke)
                }
                c.drawCircle(0f, 0.30f, 0.055f, fill)
            }
            Glyph.BLUETOOTH -> {
                path.rewind()
                path.moveTo(-0.20f, -0.18f); path.lineTo(0.20f, 0.18f); path.lineTo(0f, 0.38f)
                path.lineTo(0f, -0.38f); path.lineTo(0.20f, -0.18f); path.lineTo(-0.20f, 0.18f)
                c.drawPath(path, stroke)
            }
            Glyph.DISPLAY -> {
                rr(c, -0.42f, -0.30f, 0.42f, 0.18f, 0.04f, stroke)
                c.drawLine(0f, 0.18f, 0f, 0.32f, stroke)
                c.drawLine(-0.20f, 0.34f, 0.20f, 0.34f, stroke)
            }
            Glyph.SOUND -> {
                path.rewind()
                path.moveTo(-0.40f, -0.12f); path.lineTo(-0.20f, -0.12f); path.lineTo(0f, -0.30f)
                path.lineTo(0f, 0.30f); path.lineTo(-0.20f, 0.12f); path.lineTo(-0.40f, 0.12f); path.close()
                c.drawPath(path, fill)
                for (rad in floatArrayOf(0.20f, 0.36f)) {
                    r.set(-rad + 0.02f, -rad, rad + 0.02f, rad)
                    c.drawArc(r, -45f, 90f, false, stroke)
                }
            }
            Glyph.PALETTE -> {
                c.drawCircle(0f, 0f, 0.40f, stroke)
                for (k in 0 until 4) {
                    val ang = (k * 90.0 + 45.0) * PI / 180.0
                    fill.color = XmbTheme.previewColor(k * 2)
                    fill.alpha = a
                    c.drawCircle((cos(ang) * 0.20).toFloat(), (sin(ang) * 0.20).toFloat(), 0.10f, fill)
                }
                fill.color = Color.WHITE
                fill.alpha = a
            }
            Glyph.HOME -> {
                path.rewind()
                path.moveTo(-0.40f, 0.0f); path.lineTo(0f, -0.36f); path.lineTo(0.40f, 0.0f)
                c.drawPath(path, stroke)
                path.rewind()
                path.moveTo(-0.28f, -0.10f); path.lineTo(-0.28f, 0.36f); path.lineTo(0.28f, 0.36f); path.lineTo(0.28f, -0.10f)
                c.drawPath(path, stroke)
                rr(c, -0.08f, 0.14f, 0.08f, 0.36f, 0.02f, fill)
            }
            Glyph.GEAR -> {
                c.drawCircle(0f, 0f, 0.27f, stroke)
                c.drawCircle(0f, 0f, 0.09f, stroke)
                stroke.strokeWidth = 0.12f
                stroke.strokeCap = Paint.Cap.BUTT
                for (k in 0 until 8) {
                    val ang = k * PI / 4.0
                    val cs = cos(ang).toFloat(); val sn = sin(ang).toFloat()
                    c.drawLine(cs * 0.30f, sn * 0.30f, cs * 0.43f, sn * 0.43f, stroke)
                }
                stroke.strokeCap = Paint.Cap.ROUND
            }
            Glyph.INFO -> {
                c.drawCircle(0f, 0f, 0.40f, stroke)
                c.drawCircle(0f, -0.18f, 0.05f, fill)
                c.drawLine(0f, -0.04f, 0f, 0.22f, stroke)
            }
            Glyph.FAVORITE -> { // сердце
                path.rewind()
                path.moveTo(0f, 0.36f)
                path.cubicTo(-0.56f, 0.02f, -0.42f, -0.44f, 0f, -0.18f)
                path.cubicTo(0.42f, -0.44f, 0.56f, 0.02f, 0f, 0.36f)
                path.close()
                c.drawPath(path, stroke)
            }
            Glyph.SEARCH -> { // лупа
                c.drawCircle(-0.08f, -0.08f, 0.25f, stroke)
                stroke.strokeWidth = 0.11f
                c.drawLine(0.11f, 0.11f, 0.38f, 0.38f, stroke)
            }
            Glyph.LOCK -> { // замок
                rr(c, -0.30f, -0.06f, 0.30f, 0.38f, 0.06f, stroke)
                r.set(-0.19f, -0.40f, 0.19f, 0.02f)
                c.drawArc(r, 180f, 180f, false, stroke)
                c.drawLine(-0.19f, -0.19f, -0.19f, -0.06f, stroke)
                c.drawLine(0.19f, -0.19f, 0.19f, -0.06f, stroke)
                c.drawCircle(0f, 0.14f, 0.05f, fill)
            }
            Glyph.TORCH -> { // фонарик
                path.rewind()
                path.moveTo(-0.22f, -0.40f); path.lineTo(0.22f, -0.40f)
                path.lineTo(0.13f, -0.14f); path.lineTo(-0.13f, -0.14f); path.close()
                c.drawPath(path, stroke)
                rr(c, -0.12f, -0.14f, 0.12f, 0.40f, 0.04f, stroke)
                c.drawCircle(0f, 0.05f, 0.04f, fill)
            }
            Glyph.PLAY -> {
                path.rewind()
                path.moveTo(-0.20f, -0.30f); path.lineTo(0.30f, 0f); path.lineTo(-0.20f, 0.30f); path.close()
                c.drawPath(path, fill)
            }
            Glyph.PAUSE -> {
                rr(c, -0.22f, -0.30f, -0.06f, 0.30f, 0.03f, fill)
                rr(c, 0.06f, -0.30f, 0.22f, 0.30f, 0.03f, fill)
            }
            Glyph.PREV -> {
                rr(c, -0.34f, -0.28f, -0.24f, 0.28f, 0.02f, fill)
                path.rewind()
                path.moveTo(0.30f, -0.30f); path.lineTo(-0.20f, 0f); path.lineTo(0.30f, 0.30f); path.close()
                c.drawPath(path, fill)
            }
            Glyph.NEXT -> {
                rr(c, 0.24f, -0.28f, 0.34f, 0.28f, 0.02f, fill)
                path.rewind()
                path.moveTo(-0.30f, -0.30f); path.lineTo(0.20f, 0f); path.lineTo(-0.30f, 0.30f); path.close()
                c.drawPath(path, fill)
            }
            Glyph.ALARM -> { // будильник: циферблат, стрелки, «колокольчики»
                c.drawCircle(0f, 0.04f, 0.32f, stroke)
                c.drawLine(0f, 0.04f, 0f, -0.14f, stroke)
                c.drawLine(0f, 0.04f, 0.13f, 0.12f, stroke)
                r.set(-0.46f, -0.46f, -0.14f, -0.14f); c.drawArc(r, 200f, 70f, false, stroke)
                r.set(0.14f, -0.46f, 0.46f, -0.14f); c.drawArc(r, 270f, 70f, false, stroke)
                c.drawLine(-0.22f, 0.30f, -0.30f, 0.40f, stroke)
                c.drawLine(0.22f, 0.30f, 0.30f, 0.40f, stroke)
            }
            Glyph.TIMER -> { // секундомер
                c.drawCircle(0f, 0.06f, 0.33f, stroke)
                c.drawLine(-0.10f, -0.40f, 0.10f, -0.40f, stroke)
                c.drawLine(0f, -0.40f, 0f, -0.27f, stroke)
                c.drawLine(0.25f, -0.22f, 0.32f, -0.29f, stroke)
                c.drawLine(0f, 0.06f, 0.14f, -0.08f, stroke)
            }
            Glyph.PLUS -> {
                c.drawCircle(0f, 0f, 0.40f, stroke)
                c.drawLine(-0.18f, 0f, 0.18f, 0f, stroke)
                c.drawLine(0f, -0.18f, 0f, 0.18f, stroke)
            }
            Glyph.EMPTY -> {
                c.drawCircle(0f, 0f, 0.30f, stroke)
                c.drawLine(-0.14f, 0f, 0.14f, 0f, stroke)
            }
        }
        c.restore()
    }

    private fun rr(c: Canvas, l: Float, t: Float, rt: Float, b: Float, rad: Float, p: Paint) {
        r.set(l, t, rt, b)
        c.drawRoundRect(r, rad, rad, p)
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
