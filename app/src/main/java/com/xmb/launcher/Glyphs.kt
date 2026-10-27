package com.xmb.launcher

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class Glyph {
    SETTINGS, PHOTO, MUSIC, VIDEO, GAME, NETWORK, APPS,
    WIFI, BLUETOOTH, DISPLAY, SOUND, PALETTE, HOME, GEAR, INFO, EMPTY,
    FAVORITE, SEARCH
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
