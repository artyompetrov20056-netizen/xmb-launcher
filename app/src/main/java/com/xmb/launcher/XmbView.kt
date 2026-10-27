package com.xmb.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Экран в стиле PS3 XMB.
 *
 * Горизонтально (альбомная ориентация): категории — строка сверху, содержимое — столбик вниз.
 * Вертикально (портретная ориентация): категории — столбик слева, содержимое — строка вправо.
 * При повороте телефона иконки плавно «перетекают» из одной раскладки в другую.
 */
class XmbView(context: Context) : View(context) {

    interface Listener {
        fun onItemActivated(item: XmbItem)
        fun onItemLongPress(item: XmbItem)
        fun onCategorySelected(category: XmbCategory)
    }

    var listener: Listener? = null
    var initialCategoryId: String? = null

    // ---------- состояние меню ----------
    private var categories: List<XmbCategory> = emptyList()
    private var catPos = 0f
    private var catTarget = 0
    private var itemPos = FloatArray(0)
    private var itemTarget = IntArray(0)

    /** 0 = альбомная раскладка, 1 = портретная; между ними — анимация поворота. */
    private var orient = 0f
    private var orientReady = false

    private var time = 0f
    private var lastFrame = 0L
    private var flash = 0f

    // ---------- безопасные отступы (вырез камеры, навигация) ----------
    private var safeL = 0f
    private var safeT = 0f
    private var safeR = 0f
    private var safeB = 0f

    // ---------- размеры (пересчитываются каждый кадр) ----------
    private var unit = 1f
    private var catSize = 0f
    private var itemSize = 0f
    private var lAnchorX = 0f
    private var lRowY = 0f
    private var lCatSp = 0f
    private var lItemSelY = 0f
    private var lItemBeforeY = 0f
    private var lItemSp = 0f
    private var pAnchorX = 0f
    private var pAnchorY = 0f
    private var pCatSp = 0f
    private var pItemSelX = 0f
    private var pItemBeforeX = 0f
    private var pItemSp = 0f

    // ---------- цвета темы ----------
    private val targetColors = IntArray(3)
    private val curColors = FloatArray(9)
    private var shaderColors = IntArray(3)
    private var shaderH = -1

    // ---------- кисти ----------
    private val bgPaint = Paint()
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(Color.argb(170, 255, 255, 255), Color.argb(45, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
    }
    private val titlePaint = textPaint(Typeface.NORMAL)
    private val subPaint = textPaint(Typeface.NORMAL)
    private val catPaint = textPaint(Typeface.NORMAL).apply { textAlign = Paint.Align.CENTER }
    private val clockPaint = textPaint(Typeface.NORMAL).apply { textAlign = Paint.Align.RIGHT }
    private val boxFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; alpha = 22 }
    private val boxLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; alpha = 90 }
    private val tmpRect = RectF()

    private fun textPaint(style: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif", style)
        setShadowLayer(6f, 0f, 1.5f, Color.argb(150, 0, 0, 0))
    }

    // ---------- волны и искры ----------
    private val waveTop = Array(3) { Path() }
    private val waveBand = Array(3) { Path() }
    private val waveSegs = 48
    private val waveYs = FloatArray(waveSegs + 1)
    private val sparkN = 48
    private val rnd = Random(7)
    private val sparkX = FloatArray(sparkN) { rnd.nextFloat() }
    private val sparkY = FloatArray(sparkN) { rnd.nextFloat() - 0.5f }
    private val sparkSpeed = FloatArray(sparkN) { 0.004f + rnd.nextFloat() * 0.018f }
    private val sparkPhase = FloatArray(sparkN) { rnd.nextFloat() * 6.28f }

    // ---------- часы ----------
    private var clockSec = -1L
    private var clockText = ""

    // ---------- касания ----------
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
    private var downX = 0f
    private var downY = 0f
    private var dragAxis = AXIS_NONE
    private var dragCat = 0
    private var dragStart = 0f
    private var dragOrigin = 0f
    private var longPressFired = false
    private var velocity: VelocityTracker? = null
    private val longPressRunnable = Runnable {
        longPressFired = true
        val ci = currentCat()
        val hit = hitItem(downX, downY)
        if (hit >= 0) {
            categories.getOrNull(ci)?.items?.getOrNull(hit)?.let {
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                listener?.onItemLongPress(it)
            }
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setTheme(XmbTheme.ALL[0], animate = false)
    }

    // =====================================================================
    // Публичные методы
    // =====================================================================

    fun setCategories(list: List<XmbCategory>) {
        val oldSel = HashMap<String, Int>()
        categories.forEachIndexed { i, c -> oldSel[c.id] = itemTarget.getOrElse(i) { 0 } }
        val oldCatId = categories.getOrNull(catTarget)?.id

        categories = list
        itemPos = FloatArray(list.size)
        itemTarget = IntArray(list.size)
        list.forEachIndexed { i, c ->
            val sel = (oldSel[c.id] ?: 0).coerceIn(0, max(0, c.items.size - 1))
            itemTarget[i] = sel
            itemPos[i] = sel.toFloat()
        }
        val wantId = oldCatId ?: initialCategoryId
        val idx = list.indexOfFirst { it.id == wantId }
        val newCat = if (idx >= 0) idx else catTarget.coerceIn(0, max(0, list.size - 1))
        if (oldCatId == null) catPos = newCat.toFloat()
        catTarget = newCat
        cancelDrag()
        invalidate()
    }

    fun setTheme(theme: XmbTheme, animate: Boolean) {
        targetColors[0] = theme.top
        targetColors[1] = theme.mid
        targetColors[2] = theme.bottom
        if (!animate) {
            for (i in 0 until 3) for (ch in 0 until 3) curColors[i * 3 + ch] = channel(targetColors[i], ch).toFloat()
        }
        invalidate()
    }

    fun applyInsets(insets: WindowInsets) {
        if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout())
            safeL = i.left.toFloat(); safeT = i.top.toFloat(); safeR = i.right.toFloat(); safeB = i.bottom.toFloat()
        } else {
            legacyInsets(insets)
            if (Build.VERSION.SDK_INT >= 28) {
                insets.displayCutout?.let {
                    safeL = max(safeL, it.safeInsetLeft.toFloat())
                    safeT = max(safeT, it.safeInsetTop.toFloat())
                    safeR = max(safeR, it.safeInsetRight.toFloat())
                    safeB = max(safeB, it.safeInsetBottom.toFloat())
                }
            }
        }
        invalidate()
    }

    @Suppress("DEPRECATION")
    private fun legacyInsets(insets: WindowInsets) {
        safeL = insets.systemWindowInsetLeft.toFloat()
        safeT = insets.systemWindowInsetTop.toFloat()
        safeR = insets.systemWindowInsetRight.toFloat()
        safeB = insets.systemWindowInsetBottom.toFloat()
    }

    /** Кнопка «Назад» / «Домой»: вернуться к первому пункту текущей категории. */
    fun goToFirstItem() {
        if (categories.isEmpty()) return
        itemTarget[currentCat()] = 0
        invalidate()
    }

    // =====================================================================
    // Отрисовка
    // =====================================================================

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else (now - lastFrame).coerceIn(0L, 50L) / 1000f
        lastFrame = now
        time += dt

        val moving = step(dt)
        updateMetrics()

        drawBackground(c)
        drawWaves(c)
        drawSparkles(c)
        if (categories.isNotEmpty()) {
            drawItems(c)
            drawCategories(c)
        }
        drawClock(c)

        // Пока что-то двигается — 60 кадров/с, в покое — только волны ~30 кадров/с
        if (moving) postInvalidateOnAnimation() else postInvalidateDelayed(33)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        lastFrame = 0L
        if (visibility == VISIBLE) invalidate()
    }

    private fun step(dt: Float): Boolean {
        var moving = false
        val k = 1f - exp(-dt * 14f)

        if (dragAxis == AXIS_CAT) {
            moving = true
        } else {
            val d = catTarget - catPos
            if (abs(d) > 0.001f) { catPos += d * k; moving = true } else catPos = catTarget.toFloat()
        }
        for (i in itemPos.indices) {
            if (dragAxis == AXIS_ITEM && i == dragCat) { moving = true; continue }
            val d = itemTarget[i] - itemPos[i]
            if (abs(d) > 0.001f) { itemPos[i] += d * k; moving = true } else itemPos[i] = itemTarget[i].toFloat()
        }

        val want = if (height > width) 1f else 0f
        if (!orientReady && width > 0) { orient = want; orientReady = true }
        val od = want - orient
        if (abs(od) > 0.001f) { orient += od * (1f - exp(-dt * 8f)); moving = true } else orient = want

        val kc = 1f - exp(-dt * 3f)
        for (i in 0 until 3) for (ch in 0 until 3) {
            val idx = i * 3 + ch
            val d = channel(targetColors[i], ch) - curColors[idx]
            if (abs(d) > 0.5f) { curColors[idx] += d * kc; moving = true } else curColors[idx] = channel(targetColors[i], ch).toFloat()
        }

        if (flash > 0f) { flash = max(0f, flash - dt * 3f); moving = true }
        return moving
    }

    private fun updateMetrics() {
        val w = width.toFloat()
        val h = height.toFloat()
        unit = max(1f, min(w, h))
        catSize = unit * 0.115f
        itemSize = unit * 0.10f

        // Альбомная: категории строкой, содержимое столбиком
        lAnchorX = safeL + w * 0.19f
        lRowY = safeT + h * 0.24f
        lCatSp = unit * 0.27f
        lItemSelY = lRowY + unit * 0.27f
        lItemBeforeY = lRowY - unit * 0.19f
        lItemSp = unit * 0.13f

        // Портретная: категории столбиком, содержимое строкой
        pAnchorX = safeL + w * 0.17f
        pAnchorY = safeT + h * 0.30f
        pCatSp = unit * 0.30f
        pItemSelX = pAnchorX + unit * 0.30f
        pItemBeforeX = pAnchorX - unit * 0.16f
        pItemSp = unit * 0.17f
    }

    private fun drawBackground(c: Canvas) {
        val cols = IntArray(3) { Color.rgb(curColors[it * 3].toInt(), curColors[it * 3 + 1].toInt(), curColors[it * 3 + 2].toInt()) }
        if (!cols.contentEquals(shaderColors) || shaderH != height) {
            shaderColors = cols
            shaderH = height
            bgPaint.shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(), cols, floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
    }

    private fun waveBase() = height * lerp(0.60f, 0.52f, orient)

    private fun drawWaves(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        val base = waveBase()
        linePaint.strokeWidth = max(1.5f, unit * 0.0025f)
        for (k in 0 until 3) {
            val top = waveTop[k]
            val band = waveBand[k]
            top.rewind(); band.rewind()
            val amp = h * (0.045f + 0.018f * k) * lerp(1f, 0.55f, orient)
            val f1 = (1.1f + 0.4f * k) * 2f * PI.toFloat() / w
            val ph = time * (0.22f + 0.07f * k) + k * 1.9f
            for (s in 0..waveSegs) {
                val x = w * s / waveSegs
                val y = base + k * h * 0.012f + amp * sin(x * f1 + ph) + amp * 0.35f * sin(x * f1 * 0.47f - ph * 1.4f)
                waveYs[s] = y
                if (s == 0) { top.moveTo(x, y); band.moveTo(x, y) } else { top.lineTo(x, y); band.lineTo(x, y) }
            }
            for (s in waveSegs downTo 0) {
                val x = w * s / waveSegs
                val th = h * 0.022f * (1.2f + sin(x * f1 * 0.8f + ph * 0.7f + k))
                band.lineTo(x, waveYs[s] + th)
            }
            band.close()
            bandPaint.alpha = 24 - k * 5
            c.drawPath(band, bandPaint)
            linePaint.alpha = 80 - k * 18
            c.drawPath(top, linePaint)
        }
    }

    private fun drawSparkles(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val base = waveBase()
        for (i in 0 until sparkN) {
            val x = ((sparkX[i] + time * sparkSpeed[i]) % 1f) * w
            val y = base + sparkY[i] * h * 0.30f
            val a = 0.5f + 0.5f * sin(time * 2.2f + sparkPhase[i])
            sparkPaint.alpha = (25 + 120 * a).toInt()
            c.drawCircle(x, y, unit * (0.0015f + 0.0012f * (i % 3)), sparkPaint)
        }
    }

    private fun drawItems(c: Canvas) {
        val ci = currentCat()
        val cat = categories[ci]
        if (cat.items.isEmpty()) return
        val catAlpha = (1f - abs(catPos - ci) * 2f).coerceIn(0f, 1f)
        if (catAlpha <= 0.01f) return
        val pos = itemPos[ci]
        val w = width.toFloat()
        val h = height.toFloat()

        for (j in cat.items.indices) {
            val d = j - pos
            if (d < -6f || d > 14f) continue
            val x = itemX(d)
            val y = itemY(d)
            val cl = max(0f, 1f - abs(d))
            val size = itemSize * (1f + 0.45f * cl + if (j == itemTarget[ci]) flash * 0.18f else 0f)
            if (x < -size || x > w + size || y < -size || y > h + size) continue

            val before = (-d).coerceIn(0f, 1f)
            val a = 255f * catAlpha * lerp(0.75f, 1f, cl) * (1f - 0.4f * before)
            if (cl > 0.05f) drawGlow(c, x, y, size * 1.25f, catAlpha * cl * (0.55f + 0.15f * sin(time * 3f)))
            drawIcon(c, cat.items[j], x, y, size, a.toInt())

            val la = a * (1f - orient)
            if (la > 3f) drawLandscapeLabel(c, cat.items[j], x, y, size, cl, la)
        }

        if (orient > 0.01f) {
            val jn = pos.roundToInt().coerceIn(0, cat.items.lastIndex)
            val cl = max(0f, 1f - abs(jn - pos))
            drawPortraitLabel(c, cat.items[jn], catAlpha * cl * orient)
        }
    }

    private fun drawCategories(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        catPaint.textSize = unit * 0.032f
        for (i in categories.indices) {
            val d = i - catPos
            val x = catX(i)
            val y = catY(i)
            val cl = max(0f, 1f - abs(d))
            val size = catSize * (1f + 0.25f * cl)
            if (x < -size * 2 || x > w + size * 2 || y < -size * 2 || y > h + size * 2) continue
            if (cl > 0.05f) drawGlow(c, x, y, size * 1.15f, cl * 0.45f)
            Glyphs.draw(c, categories[i].glyph, x, y, size * 0.85f, (255 * lerp(0.6f, 1f, cl)).toInt())
            if (cl > 0.02f) {
                catPaint.alpha = (255 * cl).toInt()
                c.drawText(categories[i].title, x, y + size * 0.5f + unit * 0.045f, catPaint)
            }
        }
    }

    private fun drawIcon(c: Canvas, item: XmbItem, x: Float, y: Float, size: Float, alpha: Int) {
        val bmp = item.icon
        if (bmp != null) {
            val s = size * 0.46f
            tmpRect.set(x - s, y - s, x + s, y + s)
            iconPaint.alpha = alpha.coerceIn(0, 255)
            c.drawBitmap(bmp, null, tmpRect, iconPaint)
        } else {
            Glyphs.draw(c, item.glyph ?: Glyph.EMPTY, x, y, size * 0.8f, alpha)
        }
    }

    private fun drawGlow(c: Canvas, x: Float, y: Float, r: Float, a: Float) {
        glowPaint.alpha = (255 * a).toInt().coerceIn(0, 255)
        c.save()
        c.translate(x, y)
        c.scale(r, r)
        c.drawCircle(0f, 0f, 1f, glowPaint)
        c.restore()
    }

    private fun drawLandscapeLabel(c: Canvas, item: XmbItem, x: Float, y: Float, size: Float, cl: Float, alpha: Float) {
        val tx = x + size * 0.5f + unit * 0.035f
        val avail = width - safeR - tx - unit * 0.04f
        if (avail < 20f) return
        val ts = lerp(unit * 0.034f, unit * 0.044f, cl)
        titlePaint.textSize = ts
        titlePaint.alpha = alpha.toInt().coerceIn(0, 255)
        val hasSub = item.subtitle.isNotEmpty() && cl > 0.01f
        val ty = y + ts * 0.35f - if (hasSub) cl * ts * 0.45f else 0f
        val title = TextUtils.ellipsize(item.title, titlePaint, avail, TextUtils.TruncateAt.END)
        c.drawText(title, 0, title.length, tx, ty, titlePaint)
        if (hasSub) {
            subPaint.textSize = unit * 0.027f
            subPaint.alpha = (alpha * cl * 0.85f).toInt().coerceIn(0, 255)
            val sub = TextUtils.ellipsize(item.subtitle, subPaint, avail, TextUtils.TruncateAt.END)
            c.drawText(sub, 0, sub.length, tx, ty + ts * 0.95f, subPaint)
        }
    }

    private fun drawPortraitLabel(c: Canvas, item: XmbItem, f: Float) {
        val a = (255 * f).toInt()
        if (a < 3) return
        val size = itemSize * 1.45f
        val tx = pItemSelX - size * 0.5f
        val ty = pAnchorY + size * 0.5f + unit * 0.075f
        val avail = width - safeR - tx - unit * 0.05f
        if (avail < 20f) return
        titlePaint.textSize = unit * 0.045f
        titlePaint.alpha = a
        val title = TextUtils.ellipsize(item.title, titlePaint, avail, TextUtils.TruncateAt.END)
        c.drawText(title, 0, title.length, tx, ty, titlePaint)
        if (item.subtitle.isNotEmpty()) {
            subPaint.textSize = unit * 0.030f
            subPaint.alpha = (a * 0.85f).toInt()
            val sub = TextUtils.ellipsize(item.subtitle, subPaint, avail, TextUtils.TruncateAt.END)
            c.drawText(sub, 0, sub.length, tx, ty + unit * 0.05f, subPaint)
        }
    }

    private fun drawClock(c: Canvas) {
        val nowMs = System.currentTimeMillis()
        if (nowMs / 1000 != clockSec) {
            clockSec = nowMs / 1000
            val pattern = if (DateFormat.is24HourFormat(context)) "d/M   HH:mm" else "d/M   h:mm a"
            clockText = SimpleDateFormat(pattern, Locale.getDefault()).format(Date(nowMs))
        }
        clockPaint.textSize = unit * 0.032f
        val tw = clockPaint.measureText(clockText)
        val pad = unit * 0.022f
        val right = width - safeR - unit * 0.05f
        val top = safeT + unit * 0.045f
        val hgt = clockPaint.textSize * 1.7f
        tmpRect.set(right - tw - pad * 2.5f, top, right, top + hgt)
        boxLine.strokeWidth = max(1f, unit * 0.0016f)
        c.drawRoundRect(tmpRect, unit * 0.006f, unit * 0.006f, boxFill)
        c.drawRoundRect(tmpRect, unit * 0.006f, unit * 0.006f, boxLine)
        c.drawLine(tmpRect.left - unit * 0.18f, tmpRect.bottom, tmpRect.left, tmpRect.bottom, boxLine)
        c.drawText(clockText, right - pad * 1.25f, top + hgt / 2f + clockPaint.textSize * 0.35f, clockPaint)
    }

    // =====================================================================
    // Геометрия
    // =====================================================================

    private fun along(d: Float, sel: Float, before: Float, sp: Float, extra: Float): Float = when {
        d >= 0f -> sel + d * sp + min(d, 1f) * extra
        d >= -1f -> lerp(sel, before, -d)
        else -> before + (d + 1f) * sp
    }

    private fun catX(i: Int) = lerp(lAnchorX + (i - catPos) * lCatSp, pAnchorX, orient)
    private fun catY(i: Int) = lerp(lRowY, pAnchorY + (i - catPos) * pCatSp, orient)
    private fun itemX(d: Float) = lerp(lAnchorX, along(d, pItemSelX, pItemBeforeX, pItemSp, unit * 0.04f), orient)
    private fun itemY(d: Float) = lerp(along(d, lItemSelY, lItemBeforeY, lItemSp, unit * 0.05f), pAnchorY, orient)

    private fun currentCat() = if (categories.isEmpty()) 0 else catPos.roundToInt().coerceIn(0, categories.lastIndex)
    private fun portraitNow() = orient > 0.5f

    // =====================================================================
    // Управление: касания
    // =====================================================================

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (categories.isEmpty()) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                dragAxis = AXIS_NONE
                longPressFired = false
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                postDelayed(longPressRunnable, longPressMs)
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                val dx = e.x - downX
                val dy = e.y - downY
                if (dragAxis == AXIS_NONE && !longPressFired && hypot(dx, dy) > touchSlop) {
                    removeCallbacks(longPressRunnable)
                    val horizontal = abs(dx) > abs(dy)
                    // Альбомная: горизонтальный свайп — категории. Портретная: вертикальный свайп — категории.
                    dragAxis = if (horizontal != portraitNow()) AXIS_CAT else AXIS_ITEM
                    dragCat = currentCat()
                    dragStart = if (dragAxis == AXIS_CAT) catPos else itemPos[dragCat]
                    dragOrigin = if (portraitNow() == (dragAxis == AXIS_CAT)) dy else dx
                }
                val delta = (if (portraitNow() == (dragAxis == AXIS_CAT)) dy else dx) - dragOrigin
                when (dragAxis) {
                    AXIS_CAT -> {
                        catPos = rubber(dragStart - delta / catSpacing(), 0f, (categories.size - 1).toFloat())
                    }
                    AXIS_ITEM -> {
                        val n = categories[dragCat].items.size
                        itemPos[dragCat] = rubber(dragStart - delta / itemSpacing(), 0f, (n - 1).toFloat())
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                val vt = velocity
                vt?.addMovement(e)
                vt?.computeCurrentVelocity(1000)
                val v = if (vt == null) 0f else if (portraitNow() == (dragAxis == AXIS_CAT)) vt.yVelocity else vt.xVelocity
                when (dragAxis) {
                    AXIS_CAT -> {
                        val proj = catPos - v * 0.08f / catSpacing()
                        val t = proj.roundToInt().coerceIn(0, categories.lastIndex)
                        dragAxis = AXIS_NONE
                        selectCategory(t)
                    }
                    AXIS_ITEM -> {
                        val n = categories[dragCat].items.size
                        val proj = itemPos[dragCat] - v * 0.22f / itemSpacing()
                        itemTarget[dragCat] = proj.roundToInt().coerceIn(0, max(0, n - 1))
                        dragAxis = AXIS_NONE
                    }
                    else -> if (!longPressFired) { performClick(); handleTap(e.x, e.y) }
                }
                velocity?.recycle(); velocity = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                cancelDrag()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun cancelDrag() {
        if (dragAxis == AXIS_CAT) catTarget = catPos.roundToInt().coerceIn(0, max(0, categories.size - 1))
        if (dragAxis == AXIS_ITEM && dragCat < itemTarget.size) {
            itemTarget[dragCat] = itemPos[dragCat].roundToInt().coerceIn(0, max(0, categories[dragCat].items.size - 1))
        }
        dragAxis = AXIS_NONE
        velocity?.recycle(); velocity = null
        invalidate()
    }

    private fun catSpacing() = lerp(lCatSp, pCatSp, orient)
    private fun itemSpacing() = lerp(lItemSp, pItemSp, orient)

    private fun rubber(v: Float, lo: Float, hi: Float) = when {
        v < lo -> lo + (v - lo) * 0.3f
        v > hi -> hi + (v - hi) * 0.3f
        else -> v
    }

    /** Индекс пункта под пальцем, или -1. */
    private fun hitItem(x: Float, y: Float): Int {
        val ci = currentCat()
        val cat = categories.getOrNull(ci) ?: return -1
        val pos = itemPos[ci]
        var best = -1
        var bestDist = Float.MAX_VALUE
        for (j in cat.items.indices) {
            val d = j - pos
            if (d < -6f || d > 14f) continue
            val cl = max(0f, 1f - abs(d))
            val r = itemSize * (1f + 0.45f * cl) * 0.65f
            val dist = hypot(x - itemX(d), y - itemY(d))
            if (dist < r && dist < bestDist) { best = j; bestDist = dist }
        }
        if (best >= 0 || cat.items.isEmpty()) return best

        // Нажатие по подписи выбранного пункта
        val sel = itemTarget[ci]
        val d = sel - pos
        val ix = itemX(d)
        val iy = itemY(d)
        val s = itemSize * 1.45f
        val inLabel = if (portraitNow()) {
            x > ix - s * 0.5f && y > iy + s * 0.5f && y < iy + s * 0.5f + unit * 0.16f
        } else {
            x > ix - s * 0.5f && abs(y - iy) < s * 0.6f
        }
        return if (inLabel) sel else -1
    }

    private fun handleTap(x: Float, y: Float) {
        val ci = currentCat()
        val cat = categories.getOrNull(ci) ?: return
        val hit = hitItem(x, y)
        if (hit >= 0) {
            if (hit == itemTarget[ci] && abs(itemPos[ci] - hit) < 0.3f) activate(cat.items[hit])
            else itemTarget[ci] = hit
            return
        }
        for (i in categories.indices) {
            if (hypot(x - catX(i), y - catY(i)) < catSize * 0.8f) {
                selectCategory(i)
                return
            }
        }
    }

    private fun activate(item: XmbItem) {
        flash = 1f
        listener?.onItemActivated(item)
        invalidate()
    }

    private fun selectCategory(i: Int) {
        if (categories.isEmpty()) return
        catTarget = i.coerceIn(0, categories.lastIndex)
        listener?.onCategorySelected(categories[catTarget])
        invalidate()
    }

    // =====================================================================
    // Управление: клавиши и геймпад
    // =====================================================================

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (categories.isEmpty()) return super.onKeyDown(keyCode, event)
        val p = portraitNow()
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (p) moveItem(-1) else moveCat(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (p) moveItem(1) else moveCat(1)
            KeyEvent.KEYCODE_DPAD_UP -> if (p) moveCat(-1) else moveItem(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (p) moveCat(1) else moveItem(1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                val ci = currentCat()
                categories[ci].items.getOrNull(itemTarget[ci])?.let { activate(it) }
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun moveCat(delta: Int) = selectCategory(catTarget + delta)

    private fun moveItem(delta: Int) {
        val ci = currentCat()
        val n = categories[ci].items.size
        itemTarget[ci] = (itemTarget[ci] + delta).coerceIn(0, max(0, n - 1))
        invalidate()
    }

    // =====================================================================

    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

    private fun channel(color: Int, ch: Int): Int = when (ch) {
        0 -> Color.red(color)
        1 -> Color.green(color)
        else -> Color.blue(color)
    }

    companion object {
        private const val AXIS_NONE = 0
        private const val AXIS_CAT = 1
        private const val AXIS_ITEM = 2
    }
}
