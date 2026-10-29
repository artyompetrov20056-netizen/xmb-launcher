package com.xmb.launcher

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Тема «Губы»: глянцевые губы с высунутым языком.
 * Объём считается один раз (карты высот → нормали → свет и отражение софтбокса),
 * губы хранятся в каналах (R — рассеянный свет, G — ободок, B — блик), поэтому
 * перекрашиваются в цвет темы каждый кадр без пересчёта. Язык покачивается сеткой.
 */
class LipsArt {

    private class Layers(
        val sw: Int,
        val interior: Bitmap,
        val lower: Bitmap,
        val tongue: Bitmap,
        val over: Bitmap,
        val upper: Bitmap,
    )

    @Volatile private var layers: Layers? = null
    @Volatile private var building = false

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val diffP = Paint(Paint.FILTER_BITMAP_FLAG)
    private val rimP = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val specP = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shadowP = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0.38f, 0f))
    }
    private val glowP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private var glowKey = 0L
    private var filterAcc = 0
    private val dst = RectF()

    private val meshW = 8
    private val meshH = 16
    private val verts = FloatArray((meshW + 1) * (meshH + 1) * 2)

    companion object {
        const val X0 = -1.15f
        const val X1 = 1.15f
        const val Y0 = -0.62f
        const val Y1 = 1.30f
        const val TOX = 0.06f
        const val TSH = 0.10f
        const val TL = 1.02f

        fun lipAlbedo(acc: Int): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(acc, hsv)
            hsv[0] = (hsv[0] - 18f + 360f) % 360f
            hsv[1] = 0.82f
            hsv[2] = 0.50f
            return Color.HSVToColor(hsv)
        }

        private fun mixC(a: Int, b: Int, f: Float) = Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt(),
        )
    }

    fun draw(c: Canvas, w: Float, h: Float, unit: Float, time: Float, acc: Int) {
        val portrait = h >= w
        val s = if (portrait) w * 0.44f else min(w * 0.28f, h * 0.40f)
        val cx = w / 2f
        val cy = if (portrait) h * 0.60f else h * 0.52f
        val sw = min(s, 300f).toInt().coerceAtLeast(40)

        // мягкое свечение и тень под ртом
        val key = acc.toLong() * 31 + s.toLong() * 7 + cy.toLong()
        if (key != glowKey) {
            glowKey = key
            val g = mixC(acc, Color.WHITE, 0.25f)
            glowP.shader = RadialGradient(0f, 0f, 1f,
                intArrayOf(Color.argb(95, Color.red(g), Color.green(g), Color.blue(g)), Color.argb(30, Color.red(g), Color.green(g), Color.blue(g)), Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            shade.shader = RadialGradient(0f, 0f, 1f,
                intArrayOf(Color.argb(140, 0, 0, 0), Color.argb(60, 0, 0, 0), Color.TRANSPARENT),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        }
        c.save(); c.translate(cx, cy + 0.2f * s); c.scale(s * 1.9f, s * 1.5f); c.drawCircle(0f, 0f, 1f, glowP); c.restore()
        c.save(); c.translate(cx, cy + 0.32f * s); c.scale(s * 1.15f, s * 0.62f); c.drawCircle(0f, 0f, 1f, shade); c.restore()

        val l = layers
        if ((l == null || l.sw != sw) && !building) {
            building = true
            Thread {
                try { layers = build(sw) } catch (_: Throwable) {} finally { building = false }
            }.start()
        }
        if (l == null) return

        if (acc != filterAcc) { filterAcc = acc; setFilters(acc) }
        dst.set(cx + X0 * s, cy + Y0 * s, cx + X1 * s, cy + Y1 * s)
        val shimmer = 0.85f + 0.15f * sin(time * 0.9f)

        c.drawBitmap(l.interior, null, dst, bmpPaint)
        drawLip(c, l.lower, shimmer)

        // язык: покачивание кончика и лёгкий подъём
        val wag = sin(time * 1.6f) * 0.06f
        val lift = 0.03f * sin(time * 2.1f + 0.7f)
        var k = 0
        for (r in 0..meshH) {
            val ly = Y0 + (Y1 - Y0) * r / meshH
            val f = ((ly - 0.1f) / (TL - 0.1f)).coerceIn(0f, 1f).pow(1.5f)
            for (q in 0..meshW) {
                val lx = X0 + (X1 - X0) * q / meshW
                verts[k++] = cx + (lx + wag * f) * s
                verts[k++] = cy + (ly + lift * f) * s
            }
        }
        c.save(); c.translate(s * 0.025f, s * 0.045f)
        c.drawBitmapMesh(l.tongue, meshW, meshH, verts, 0, null, 0, shadowP)
        c.restore()
        c.drawBitmapMesh(l.tongue, meshW, meshH, verts, 0, null, 0, bmpPaint)

        c.drawBitmap(l.over, null, dst, bmpPaint)
        drawLip(c, l.upper, shimmer)
    }

    private fun drawLip(c: Canvas, b: Bitmap, shimmer: Float) {
        c.drawBitmap(b, null, dst, diffP)
        c.drawBitmap(b, null, dst, rimP)
        specP.alpha = (255 * shimmer).toInt()
        c.drawBitmap(b, null, dst, specP)
    }

    private fun setFilters(acc: Int) {
        val alb = lipAlbedo(acc)
        val rim = mixC(acc, Color.rgb(160, 255, 240), 0.55f)
        val k = 1.25f / 255f
        diffP.colorFilter = ColorMatrixColorFilter(floatArrayOf(
            Color.red(alb) * k, 0f, 0f, 0f, 0f,
            Color.green(alb) * k, 0f, 0f, 0f, 0f,
            Color.blue(alb) * k, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f))
        val r = 1f / 255f
        rimP.colorFilter = ColorMatrixColorFilter(floatArrayOf(
            0f, Color.red(rim) * r, 0f, 0f, 0f,
            0f, Color.green(rim) * r, 0f, 0f, 0f,
            0f, Color.blue(rim) * r, 0f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f))
        specP.colorFilter = ColorMatrixColorFilter(floatArrayOf(
            0f, 0f, 0f, 0f, 255f,
            0f, 0f, 0f, 0f, 255f,
            0f, 0f, 0f, 0f, 255f,
            0f, 0f, 1f, 0f, 0f))
    }

    // =====================================================================
    // Построение слоёв (в фоновом потоке)
    // =====================================================================

    private class Seg(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x3: Float, val y3: Float) {
        fun rev() = Seg(x3, y3, x2, y2, x1, y1, x0, y0)
    }

    private val upperOuter = listOf(
        Seg(-1.0f, 0.0f, -0.82f, -0.12f, -0.58f, -0.44f, -0.30f, -0.46f),
        Seg(-0.30f, -0.46f, -0.14f, -0.47f, -0.06f, -0.33f, 0.0f, -0.32f),
        Seg(0.0f, -0.32f, 0.06f, -0.33f, 0.14f, -0.47f, 0.30f, -0.46f),
        Seg(0.30f, -0.46f, 0.58f, -0.44f, 0.82f, -0.12f, 1.0f, 0.0f),
    )
    private val upperInner = listOf(
        Seg(1.0f, 0.0f, 0.72f, -0.05f, 0.38f, -0.15f, 0.10f, -0.13f),
        Seg(0.10f, -0.13f, 0.04f, -0.10f, -0.04f, -0.10f, -0.10f, -0.13f),
        Seg(-0.10f, -0.13f, -0.38f, -0.15f, -0.72f, -0.05f, -1.0f, 0.0f),
    )
    private val lowerInner = listOf(
        Seg(-1.0f, 0.0f, -0.72f, 0.08f, -0.38f, 0.22f, 0.0f, 0.23f),
        Seg(0.0f, 0.23f, 0.38f, 0.22f, 0.72f, 0.08f, 1.0f, 0.0f),
    )
    private val lowerOuter = listOf(
        Seg(1.0f, 0.0f, 0.93f, 0.32f, 0.56f, 0.72f, 0.0f, 0.74f),
        Seg(0.0f, 0.74f, -0.56f, 0.72f, -0.93f, 0.32f, -1.0f, 0.0f),
    )
    private val tongueRaw = listOf(
        Seg(-0.22f, 0.07f, -0.30f, 0.16f, -0.44f, 0.36f, -0.42f, 0.62f),
        Seg(-0.42f, 0.62f, -0.40f, 0.88f, -0.22f, TL + 0.04f, 0.0f, TL + 0.05f),
        Seg(0.0f, TL + 0.05f, 0.22f, TL + 0.04f, 0.40f, 0.88f, 0.42f, 0.62f),
        Seg(0.42f, 0.62f, 0.44f, 0.36f, 0.30f, 0.16f, 0.22f, 0.07f),
        Seg(0.22f, 0.07f, 0.12f, 0.04f, -0.12f, 0.04f, -0.22f, 0.07f),
    )
    private val teethSegs = listOf(
        Seg(-0.52f, -0.10f, -0.30f, -0.15f, 0.30f, -0.15f, 0.52f, -0.10f),
        Seg(0.52f, -0.10f, 0.46f, -0.01f, -0.46f, -0.01f, -0.52f, -0.10f),
    )

    private fun shear(sg: Seg): Seg {
        fun sx(x: Float, y: Float) = x + TOX + TSH * max(0f, y - 0.05f)
        return Seg(sx(sg.x0, sg.y0), sg.y0, sx(sg.x1, sg.y1), sg.y1, sx(sg.x2, sg.y2), sg.y2, sx(sg.x3, sg.y3), sg.y3)
    }

    private fun build(sw: Int): Layers {
        val bw = ((X1 - X0) * sw).toInt()
        val bh = ((Y1 - Y0) * sw).toInt()
        val n = bw * bh
        val swf = sw.toFloat()
        fun px(x: Float) = (x - X0) * swf
        fun py(y: Float) = (y - Y0) * swf

        fun path(vararg lists: List<Seg>): Path {
            val p = Path()
            var first = true
            for (l in lists) for (sg in l) {
                if (first) { p.moveTo(px(sg.x0), py(sg.y0)); first = false }
                p.cubicTo(px(sg.x1), py(sg.y1), px(sg.x2), py(sg.y2), px(sg.x3), py(sg.y3))
            }
            p.close()
            return p
        }

        val tmpBmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val tmpCanvas = Canvas(tmpBmp)
        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val pixBuf = IntArray(n)
        fun mask(p: Path, blur: Float = 0f, dy: Float = 0f): FloatArray {
            tmpBmp.eraseColor(Color.TRANSPARENT)
            maskPaint.maskFilter = if (blur > 0f) BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL) else null
            tmpCanvas.save(); tmpCanvas.translate(0f, dy)
            tmpCanvas.drawPath(p, maskPaint)
            tmpCanvas.restore()
            tmpBmp.getPixels(pixBuf, 0, bw, 0, 0, bw, bh)
            return FloatArray(n) { (pixBuf[it] ushr 24) / 255f }
        }

        val upperPath = path(upperOuter, upperInner)
        val mUpper = mask(upperPath)
        val mLower = mask(path(lowerInner, lowerOuter))
        val mOpen = mask(path(upperInner.reversed().map { it.rev() }, lowerInner.reversed().map { it.rev() }))
        val mTongue = mask(path(tongueRaw.map { shear(it) }))
        val mTeethRaw = mask(path(teethSegs))
        val mTeeth = FloatArray(n) { mTeethRaw[it] * mOpen[it] }
        val lshRaw = mask(upperPath, blur = 0.03f * swf, dy = 0.05f * swf)
        tmpBmp.recycle()

        fun dome(m: FloatArray, power: Float): FloatArray {
            val inf = 1e9f
            val d = FloatArray(n) { if (m[it] > 0.5f) inf else 0f }
            val dg = 1.4142f
            for (y in 0 until bh) for (x in 0 until bw) {
                val i = y * bw + x
                if (d[i] == 0f) continue
                var v = d[i]
                if (x > 0) v = min(v, d[i - 1] + 1f)
                if (y > 0) {
                    v = min(v, d[i - bw] + 1f)
                    if (x > 0) v = min(v, d[i - bw - 1] + dg)
                    if (x < bw - 1) v = min(v, d[i - bw + 1] + dg)
                }
                d[i] = v
            }
            for (y in bh - 1 downTo 0) for (x in bw - 1 downTo 0) {
                val i = y * bw + x
                if (d[i] == 0f) continue
                var v = d[i]
                if (x < bw - 1) v = min(v, d[i + 1] + 1f)
                if (y < bh - 1) {
                    v = min(v, d[i + bw] + 1f)
                    if (x < bw - 1) v = min(v, d[i + bw + 1] + dg)
                    if (x > 0) v = min(v, d[i + bw - 1] + dg)
                }
                d[i] = v
            }
            var mx = 1f
            for (v in d) if (v < inf && v > mx) mx = v
            for (i in 0 until n) d[i] = if (d[i] >= inf) 0f else (d[i] / mx).pow(power)
            return d
        }

        val hUp = dome(mUpper, 0.45f)
        val hLo = dome(mLower, 0.40f)
        val hTo = dome(mTongue, 0.55f)
        for (y in 0 until bh) for (x in 0 until bw) {
            val i = y * bw + x
            val lx = X0 + x / swf
            val ly = Y0 + y / swf
            // складочки на губах
            val warp = 3f * sin(lx * 2.3f + ly * 1.7f) + 2f * sin(lx * 4.1f - ly * 3.3f)
            val cr = (sin(lx * (34f + 10f * sin(lx * 3.1f)) + sin(ly * 9f) * 1.5f + warp) * 0.5f + 0.5f).pow(8)
            hUp[i] -= cr * 0.05f * hUp[i]
            hLo[i] -= cr * 0.06f * hLo[i]
            // бороздка посередине языка
            val gcx = TOX + TSH * max(0f, ly - 0.05f)
            val gd = (lx - gcx) / 0.07f
            val groove = exp(-gd * gd) * ((ly - 0.12f) / 0.3f).coerceIn(0f, 1f) * ((TL - 0.12f - ly) / 0.3f).coerceIn(0f, 1f)
            hTo[i] -= 0.30f * groove
        }

        // свет
        val lx0 = -0.45f; val ly0 = -0.65f; val lz0 = 0.62f
        val ll = sqrt(lx0 * lx0 + ly0 * ly0 + lz0 * lz0)
        val lX = lx0 / ll; val lY = ly0 / ll; val lZ = lz0 / ll
        val hx0 = lX; val hy0 = lY; val hz0 = lZ + 1f
        val hl = sqrt(hx0 * hx0 + hy0 * hy0 + hz0 * hz0)
        val hX = hx0 / hl; val hY = hy0 / hl; val hZ = hz0 / hl

        fun ss(a: Float, b: Float, x: Float): Float {
            val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
            return t * t * (3 - 2 * t)
        }
        fun box(rx: Float, ry: Float, cxb: Float, cyb: Float, wx: Float, wy: Float, e: Float): Float =
            ss(-wx - e, -wx, rx - cxb) * ss(-wx - e, -wx, cxb - rx) * ss(-wy - e, -wy, ry - cyb) * ss(-wy - e, -wy, cyb - ry)

        class Shaded(var nx: Float = 0f, var ny: Float = 0f, var nz: Float = 1f)
        val nrm = Shaded()
        fun normal(hh: FloatArray, i: Int, x: Int, y: Int, scale: Float) {
            val gx = if (x in 1 until bw - 1) (hh[i + 1] - hh[i - 1]) * 0.5f * scale else 0f
            val gy = if (y in 1 until bh - 1) (hh[i + bw] - hh[i - bw]) * 0.5f * scale else 0f
            val l = sqrt(gx * gx + gy * gy + 1f)
            nrm.nx = -gx / l; nrm.ny = -gy / l; nrm.nz = 1f / l
        }

        fun lipLayer(m: FloatArray, hh: FloatArray): Bitmap {
            val out = IntArray(n)
            val sc = swf * 0.10f
            for (y in 0 until bh) for (x in 0 until bw) {
                val i = y * bw + x
                val a = m[i]
                if (a <= 0.003f) continue
                normal(hh, i, x, y, sc)
                val diff = max(0f, nrm.nx * lX + nrm.ny * lY + nrm.nz * lZ)
                val spec = max(0f, nrm.nx * hX + nrm.ny * hY + nrm.nz * hZ).pow(70)
                val fres = (1f - nrm.nz).pow(2.5f)
                val rx = 2f * nrm.nz * nrm.nx
                val ry = 2f * nrm.nz * nrm.ny
                val bx = box(rx, ry, -0.38f, -0.55f, 0.20f, 0.10f, 0.06f) + 0.8f * box(rx, ry, 0.42f, -0.50f, 0.12f, 0.07f, 0.06f)
                val d = ((0.18f + 0.95f * diff) / 1.25f).coerceIn(0f, 1f)
                val f = (fres * 0.9f).coerceIn(0f, 1f)
                val sp = (spec * 0.9f + bx).coerceIn(0f, 1f)
                out[i] = ((a * 255).toInt() shl 24) or ((d * 255).toInt() shl 16) or ((f * 255).toInt() shl 8) or (sp * 255).toInt()
            }
            return Bitmap.createBitmap(out, bw, bh, Bitmap.Config.ARGB_8888)
        }

        val lower = lipLayer(mLower, hLo)
        val upper = lipLayer(mUpper, hUp)

        // язык
        val tOut = IntArray(n)
        run {
            val ar = 222f; val ag = 96f; val ab = 124f
            val sc = swf * 0.07f
            for (y in 0 until bh) for (x in 0 until bw) {
                val i = y * bw + x
                val a = mTongue[i]
                if (a <= 0.003f) continue
                val ly = Y0 + y / swf
                normal(hTo, i, x, y, sc)
                val diff = max(0f, nrm.nx * lX + nrm.ny * lY + nrm.nz * lZ)
                val spec = max(0f, nrm.nx * hX + nrm.ny * hY + nrm.nz * hZ).pow(60)
                val fres = (1f - nrm.nz).pow(2.5f)
                val rx = 2f * nrm.nz * nrm.nx
                val ry = 2f * nrm.nz * nrm.ny
                val bx = box(rx, ry, -0.16f, -0.20f, 0.07f, 0.10f, 0.03f) + 0.8f * box(rx, ry, 0.20f, -0.14f, 0.05f, 0.06f, 0.03f)
                val dd = 0.18f + 0.95f * diff
                var r = ar * dd + 255f * fres * 0.25f
                var g = ag * dd + 170f * fres * 0.25f
                var b = ab * dd + 190f * fres * 0.25f
                val sp = (spec * 0.6f + bx * 0.9f).coerceIn(0f, 1f)
                r += (255f - r) * sp; g += (255f - g) * sp; b += (255f - b) * sp
                val root = 0.06f + 0.94f * ((ly - 0.05f) / 0.26f).coerceIn(0f, 1f).pow(1.3f)
                r *= root; g *= root; b *= root
                tOut[i] = ((a * 255).toInt() shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
            }
        }
        val tongue = Bitmap.createBitmap(tOut, bw, bh, Bitmap.Config.ARGB_8888)

        // рот изнутри
        val iOut = IntArray(n)
        val oOut = IntArray(n)
        val gaps = floatArrayOf(-0.33f, -0.18f, 0f, 0.18f, 0.33f)
        for (y in 0 until bh) for (x in 0 until bw) {
            val i = y * bw + x
            val lx = X0 + x / swf
            val ly = Y0 + y / swf
            val mo = mOpen[i]
            if (mo > 0.003f) {
                val k = (1f - abs(lx) * 1.1f).coerceIn(0f, 1f) * ((ly + 0.05f) / 0.3f).coerceIn(0f, 1f) * 0.55f
                val r = 14f + 110f * k; val g = 4f + 26f * k; val b = 10f + 48f * k
                iOut[i] = ((mo * 255).toInt() shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
            }
            // зубы + тень верхней губы
            val tm = mTeeth[i]
            val lsh = lshRaw[i] * (1f - mUpper[i]) * 0.45f
            val a = tm + (1f - tm) * lsh
            if (a > 0.003f) {
                var gp = 0f
                for (gx in gaps) { val q = (lx - gx) / 0.008f; gp = max(gp, exp(-q * q)) }
                val base = (0.78f + 0.22f * (1f - abs(lx) * 1.5f).coerceIn(0f, 1f)) * (1f - 0.25f * gp) * (0.7f + 0.3f * ((ly + 0.14f) / 0.06f).coerceIn(0f, 1f)) * (1f - lsh)
                val r = 236f * base * tm / a; val g = 232f * base * tm / a; val b = 222f * base * tm / a
                oOut[i] = ((a * 255).toInt().coerceIn(0, 255) shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
            }
        }
        val interior = Bitmap.createBitmap(iOut, bw, bh, Bitmap.Config.ARGB_8888)
        val over = Bitmap.createBitmap(oOut, bw, bh, Bitmap.Config.ARGB_8888)
        return Layers(sw, interior, lower, tongue, over, upper)
    }
}
