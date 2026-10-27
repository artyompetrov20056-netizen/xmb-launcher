package com.xmb.launcher

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Уведомление для экрана блокировки. */
class LockNote(
    val key: String,
    val packageName: String,
    val icon: Bitmap?,
    val title: String,
    val text: String,
    val time: Long,
    val intent: PendingIntent?,
    val autoCancel: Boolean,
)

/** Что сейчас играет. */
class LockMedia(val title: String, val artist: String, val playing: Boolean)

/**
 * Экран блокировки в стиле XMB: фон с волнами, часы, дата, заряд,
 * музыка, уведомления, камера, фонарик. Свайп вверх — разблокировка
 * (дальше системный PIN-код или отпечаток, один раз).
 */
class LockView(context: Context) : View(context) {

    interface Listener {
        fun onUnlockSwipe()
        fun onNoteTap(note: LockNote)
        fun onCamera()
        fun onTorch()
        fun onMedia(action: Int)
    }

    var listener: Listener? = null
    var notes: List<LockNote> = emptyList()
        set(v) { field = v; invalidate() }
    var media: LockMedia? = null
        set(v) { field = v; invalidate() }
    var torchOn = false
        set(v) { field = v; invalidate() }
    var battery = -1
    var charging = false

    val bg = XmbBackground()

    private var time = 0f
    private var lastFrame = 0L
    private var safeL = 0f
    private var safeT = 0f
    private var safeR = 0f
    private var safeB = 0f
    private var unit = 1f

    // смещение контента при свайпе вверх
    private var offset = 0f
    private var dragging = false
    private var unlocking = false
    private var downX = 0f
    private var downY = 0f
    private var pressed = TARGET_NONE
    private var pressedNote = -1
    private var velocity: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    // зоны нажатия
    private val camRect = RectF()
    private val torchRect = RectF()
    private val mediaRects = Array(3) { RectF() }
    private val noteRects = ArrayList<RectF>()
    private val tmp = RectF()
    private val chevron = Path()

    private val clockP = text("sans-serif-thin").apply { textAlign = Paint.Align.CENTER }
    private val dateP = text("sans-serif").apply { textAlign = Paint.Align.CENTER }
    private val titleP = text("sans-serif-medium")
    private val bodyP = text("sans-serif")
    private val smallP = text("sans-serif")
    private val hintP = text("sans-serif").apply { textAlign = Paint.Align.CENTER }
    private val cardFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val cardLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    private var clockSec = -1L
    private var clockText = ""
    private var dateText = ""

    private fun text(family: String) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(family, Typeface.NORMAL)
        setShadowLayer(6f, 0f, 1.5f, Color.argb(150, 0, 0, 0))
    }

    fun applyInsets(insets: WindowInsets) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            safeL = i.left.toFloat(); safeT = i.top.toFloat(); safeR = i.right.toFloat(); safeB = i.bottom.toFloat()
        } else {
            legacyInsets(insets)
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

    /** Разблокировку отменили (закрыли окно PIN-кода) — вернуть экран на место. */
    fun resetUnlock() {
        unlocking = false
        invalidate()
    }

    // =====================================================================

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else (now - lastFrame).coerceIn(0L, 50L) / 1000f
        lastFrame = now
        time += dt
        bg.step(dt)

        val w = width.toFloat()
        val h = height.toFloat()
        unit = max(1f, min(w, h))
        val portrait = h >= w

        if (!dragging) {
            val target = if (unlocking) -h * 0.5f else 0f
            offset += (target - offset) * (1f - exp(-dt * 12f))
        }

        bg.draw(c, width, height, unit, if (portrait) 1f else 0f, time)

        val fA = (1f - abs(offset) / (h * 0.35f)).coerceIn(0f, 1f)
        c.save()
        c.translate(0f, offset)
        updateClock()
        if (portrait) drawPortrait(c, w, h, fA) else drawLandscape(c, w, h, fA)
        c.restore()
        drawBottom(c, w, h, fA)

        postInvalidateOnAnimation()
    }

    private fun updateClock() {
        val nowMs = System.currentTimeMillis()
        if (nowMs / 1000 != clockSec) {
            clockSec = nowMs / 1000
            val d = Date(nowMs)
            clockText = SimpleDateFormat(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm", Locale.getDefault()).format(d)
            dateText = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(d)
        }
    }

    private fun a(v: Float, f: Float) = (v * f).toInt().coerceIn(0, 255)

    private fun drawPortrait(c: Canvas, w: Float, h: Float, fA: Float) {
        val u = unit
        val m = u * 0.06f
        drawBattery(c, w, fA)

        val cy = safeT + h * 0.20f
        clockP.textSize = u * 0.26f
        clockP.alpha = a(255f, fA)
        c.drawText(clockText, w / 2f, cy, clockP)
        dateP.textSize = u * 0.046f
        dateP.alpha = a(230f, fA)
        c.drawText(dateText, w / 2f, cy + u * 0.09f, dateP)

        var top = cy + u * 0.17f
        top = drawMedia(c, safeL + m, top, w - safeR - m, fA)
        drawNotes(c, safeL + m, top, w - safeR - m, h - safeB - u * 0.40f, fA)
    }

    private fun drawLandscape(c: Canvas, w: Float, h: Float, fA: Float) {
        val u = unit
        val m = u * 0.06f
        drawBattery(c, w, fA)

        val cx = safeL + (w - safeL - safeR) * 0.27f
        val cy = h * 0.42f
        clockP.textSize = u * 0.30f
        clockP.alpha = a(255f, fA)
        c.drawText(clockText, cx, cy, clockP)
        dateP.textSize = u * 0.05f
        dateP.alpha = a(230f, fA)
        c.drawText(dateText, cx, cy + u * 0.10f, dateP)

        val left = safeL + (w - safeL - safeR) * 0.52f
        var top = safeT + u * 0.16f
        top = drawMedia(c, left, top, w - safeR - m, fA)
        drawNotes(c, left, top, w - safeR - m, h - safeB - u * 0.30f, fA)
    }

    private fun drawBattery(c: Canvas, w: Float, fA: Float) {
        if (battery < 0) return
        val u = unit
        smallP.textSize = u * 0.034f
        smallP.alpha = a(240f, fA)
        val label = "$battery %"
        val tw = smallP.measureText(label)
        val right = w - safeR - u * 0.05f
        val top = safeT + u * 0.045f
        val hgt = smallP.textSize * 1.7f
        val bw = u * 0.05f
        tmp.set(right - tw - bw - u * 0.07f, top, right, top + hgt)
        cardFill.alpha = a(22f, fA)
        c.drawRoundRect(tmp, u * 0.006f, u * 0.006f, cardFill)
        cardLine.alpha = a(90f, fA)
        cardLine.strokeWidth = max(1f, u * 0.0016f)
        c.drawRoundRect(tmp, u * 0.006f, u * 0.006f, cardLine)
        c.drawLine(tmp.left - u * 0.18f, tmp.bottom, tmp.left, tmp.bottom, cardLine)

        // значок батареи
        val bx = tmp.left + u * 0.025f
        val by = tmp.centerY()
        val bh = hgt * 0.42f
        val body = RectF(bx, by - bh / 2f, bx + bw, by + bh / 2f)
        lineP.strokeWidth = max(1.5f, u * 0.003f)
        lineP.alpha = a(240f, fA)
        c.drawRoundRect(body, u * 0.004f, u * 0.004f, lineP)
        c.drawLine(body.right + u * 0.006f, by - bh * 0.2f, body.right + u * 0.006f, by + bh * 0.2f, lineP)
        cardFill.alpha = a(if (battery <= 15 && !charging) 255f else 230f, fA)
        val inset = u * 0.005f
        val fillW = (body.width() - inset * 2f) * (battery / 100f)
        cardFill.color = if (battery <= 15 && !charging) Color.rgb(255, 90, 80) else if (charging) Color.rgb(140, 255, 150) else Color.WHITE
        c.drawRect(body.left + inset, body.top + inset, body.left + inset + fillW, body.bottom - inset, cardFill)
        cardFill.color = Color.WHITE
        c.drawText(label, right - u * 0.022f - tw, top + hgt / 2f + smallP.textSize * 0.35f, smallP)
    }

    /** Карточка музыки. Возвращает нижнюю границу. */
    private fun drawMedia(c: Canvas, l: Float, t: Float, r: Float, fA: Float): Float {
        val md = media ?: run { mediaRects.forEach { it.setEmpty() }; return t }
        val u = unit
        val hgt = u * 0.25f
        tmp.set(l, t, r, t + hgt)
        cardFill.alpha = a(26f, fA)
        c.drawRoundRect(tmp, u * 0.02f, u * 0.02f, cardFill)
        cardLine.alpha = a(60f, fA)
        cardLine.strokeWidth = max(1f, u * 0.0016f)
        c.drawRoundRect(tmp, u * 0.02f, u * 0.02f, cardLine)

        val gx = l + u * 0.08f
        Glyphs.draw(c, Glyph.MUSIC, gx, t + u * 0.075f, u * 0.075f, a(240f, fA))
        val tx = l + u * 0.15f
        val avail = r - tx - u * 0.03f
        titleP.textSize = u * 0.042f
        titleP.alpha = a(255f, fA)
        Overlay.drawText(c, md.title, titleP, tx, t + u * 0.065f, avail)
        bodyP.textSize = u * 0.034f
        bodyP.alpha = a(200f, fA)
        Overlay.drawText(c, md.artist, bodyP, tx, t + u * 0.11f, avail)

        val cy = t + hgt - u * 0.065f
        val mid = (l + r) / 2f
        val sp = u * 0.17f
        val glyphs = arrayOf(Glyph.PREV, if (md.playing) Glyph.PAUSE else Glyph.PLAY, Glyph.NEXT)
        for (i in 0..2) {
            val x = mid + (i - 1) * sp
            val s = if (i == 1) u * 0.075f else u * 0.06f
            mediaRects[i].set(x - u * 0.07f, cy - u * 0.06f, x + u * 0.07f, cy + u * 0.06f)
            if (pressed == TARGET_MEDIA0 + i) {
                cardFill.alpha = a(70f, fA)
                c.drawCircle(x, cy, u * 0.06f, cardFill)
            }
            Glyphs.draw(c, glyphs[i], x, cy, s, a(250f, fA))
        }
        // зоны нажатия сдвигаются вместе с контентом
        for (rr in mediaRects) rr.offset(0f, offset)
        return t + hgt + u * 0.04f
    }

    private fun drawNotes(c: Canvas, l: Float, t: Float, r: Float, bottom: Float, fA: Float) {
        noteRects.clear()
        if (notes.isEmpty()) return
        val u = unit
        val rowH = u * 0.15f
        val gap = u * 0.02f
        val fit = max(0, floor((bottom - t + gap) / (rowH + gap)).toInt())
        val shown = if (notes.size > fit) max(0, fit - 1) else notes.size
        var y = t
        val timeFmt = SimpleDateFormat(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm", Locale.getDefault())
        for (i in 0 until shown) {
            val n = notes[i]
            tmp.set(l, y, r, y + rowH)
            cardFill.alpha = a(if (pressedNote == i) 70f else 24f, fA)
            c.drawRoundRect(tmp, u * 0.02f, u * 0.02f, cardFill)
            cardLine.alpha = a(45f, fA)
            c.drawRoundRect(tmp, u * 0.02f, u * 0.02f, cardLine)

            val isz = u * 0.075f
            val icx = l + u * 0.07f
            val icy = y + rowH / 2f
            n.icon?.let {
                iconPaint.alpha = a(255f, fA)
                c.drawBitmap(it, null, RectF(icx - isz / 2f, icy - isz / 2f, icx + isz / 2f, icy + isz / 2f), iconPaint)
            }
            val tx = l + u * 0.14f
            smallP.textSize = u * 0.03f
            smallP.alpha = a(160f, fA)
            val ts = timeFmt.format(Date(n.time))
            val tw = smallP.measureText(ts)
            c.drawText(ts, r - u * 0.03f - tw, y + u * 0.055f, smallP)

            titleP.textSize = u * 0.038f
            titleP.alpha = a(255f, fA)
            Overlay.drawText(c, n.title, titleP, tx, y + u * 0.058f, r - tx - tw - u * 0.06f)
            bodyP.textSize = u * 0.033f
            bodyP.alpha = a(205f, fA)
            Overlay.drawText(c, n.text.replace('\n', ' '), bodyP, tx, y + u * 0.108f, r - tx - u * 0.03f)

            noteRects.add(RectF(l, y + offset, r, y + rowH + offset))
            y += rowH + gap
        }
        if (notes.size > shown) {
            bodyP.textSize = u * 0.034f
            bodyP.alpha = a(200f, fA)
            c.drawText("и ещё ${notes.size - shown}", l + u * 0.03f, y + u * 0.05f, bodyP)
        }
    }

    private fun drawBottom(c: Canvas, w: Float, h: Float, fA: Float) {
        val u = unit
        val m = u * 0.07f
        val by = h - safeB - u * 0.13f
        val r = u * 0.075f

        camRect.set(safeL + m, by - r, safeL + m + r * 2f, by + r)
        torchRect.set(w - safeR - m - r * 2f, by - r, w - safeR - m, by + r)

        cardFill.alpha = a(if (pressed == TARGET_CAMERA) 90f else 30f, fA)
        c.drawCircle(camRect.centerX(), by, r, cardFill)
        Glyphs.draw(c, Glyph.PHOTO, camRect.centerX(), by, r * 0.95f, a(240f, fA))

        cardFill.alpha = a(if (torchOn) 125f else if (pressed == TARGET_TORCH) 90f else 30f, fA)
        c.drawCircle(torchRect.centerX(), by, r, cardFill)
        if (torchOn) {
            lineP.strokeWidth = max(2f, u * 0.004f)
            lineP.alpha = a(255f, fA)
            c.drawCircle(torchRect.centerX(), by, r, lineP)
        }
        Glyphs.draw(c, Glyph.TORCH, torchRect.centerX(), by, r * 0.95f, a(255f, fA))

        // подсказка и стрелка
        val pulse = 0.55f + 0.45f * sin(time * 2.5f)
        hintP.textSize = u * 0.034f
        hintP.alpha = a(150f + 90f * pulse, fA)
        val textY = by + hintP.textSize * 0.35f
        val textW = w - (camRect.right + u * 0.03f) - (w - torchRect.left + u * 0.03f)
        val hint = TextUtils.ellipsize("Проведите вверх, чтобы разблокировать", hintP, textW, TextUtils.TruncateAt.END)
        c.drawText(hint, 0, hint.length, w / 2f, textY + offset * 0.3f, hintP)

        val chY = by - u * 0.085f - u * 0.02f * pulse + offset * 0.3f
        chevron.rewind()
        chevron.moveTo(w / 2f - u * 0.035f, chY + u * 0.018f)
        chevron.lineTo(w / 2f, chY - u * 0.012f)
        chevron.lineTo(w / 2f + u * 0.035f, chY + u * 0.018f)
        lineP.strokeWidth = max(2f, u * 0.005f)
        lineP.alpha = a(200f * pulse + 40f, fA)
        c.drawPath(chevron, lineP)
        Glyphs.draw(c, Glyph.LOCK, w / 2f, chY - u * 0.06f, u * 0.055f, a(200f, fA))
    }

    // =====================================================================

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (unlocking) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                dragging = false
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                pressed = targetAt(e.x, e.y)
                pressedNote = if (pressed == TARGET_NOTE) noteRects.indexOfFirst { it.contains(e.x, e.y) } else -1
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                val dy = e.y - downY
                if (!dragging && (abs(dy) > slop * 1.5f || abs(e.x - downX) > slop * 1.5f)) {
                    dragging = abs(dy) > abs(e.x - downX) && dy < 0
                    pressed = TARGET_NONE
                    pressedNote = -1
                }
                if (dragging) offset = min(0f, dy)
            }
            MotionEvent.ACTION_UP -> {
                velocity?.addMovement(e)
                velocity?.computeCurrentVelocity(1000)
                val vy = velocity?.yVelocity ?: 0f
                if (dragging) {
                    dragging = false
                    if (-offset > height * 0.18f || vy < -1800f) {
                        unlocking = true
                        listener?.onUnlockSwipe()
                    }
                } else if (pressed != TARGET_NONE && targetAt(e.x, e.y) == pressed) {
                    performClick()
                    when (pressed) {
                        TARGET_CAMERA -> listener?.onCamera()
                        TARGET_TORCH -> listener?.onTorch()
                        TARGET_MEDIA0, TARGET_MEDIA0 + 1, TARGET_MEDIA0 + 2 -> listener?.onMedia(pressed - TARGET_MEDIA0)
                        TARGET_NOTE -> notes.getOrNull(pressedNote)?.let {
                            unlocking = true
                            listener?.onNoteTap(it)
                        }
                    }
                }
                pressed = TARGET_NONE
                pressedNote = -1
                velocity?.recycle(); velocity = null
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                pressed = TARGET_NONE
                pressedNote = -1
            }
        }
        invalidate()
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun targetAt(x: Float, y: Float): Int {
        if (camRect.contains(x, y)) return TARGET_CAMERA
        if (torchRect.contains(x, y)) return TARGET_TORCH
        for (i in 0..2) if (mediaRects[i].contains(x, y)) return TARGET_MEDIA0 + i
        if (noteRects.any { it.contains(x, y) }) return TARGET_NOTE
        return TARGET_NONE
    }

    companion object {
        const val TARGET_NONE = 0
        const val TARGET_CAMERA = 1
        const val TARGET_TORCH = 2
        const val TARGET_NOTE = 3
        const val TARGET_MEDIA0 = 10
    }
}
