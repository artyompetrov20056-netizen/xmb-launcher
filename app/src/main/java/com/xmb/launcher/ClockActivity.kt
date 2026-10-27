package com.xmb.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.TextPaint
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/** Полноэкранные «Часы» в стиле XMB: вкладки «Таймер» и «Секундомер». */
class ClockActivity : Activity() {

    private lateinit var view: ClockView
    private lateinit var sounds: XmbSounds

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val p = getSharedPreferences("xmb", MODE_PRIVATE)
        sounds = XmbSounds()
        sounds.level = p.getInt("sound_level", 2)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC

        view = ClockView(this, sounds, intent.getIntExtra(EXTRA_TAB, TAB_TIMER))
        view.bg.loadFromPrefs(this)
        view.padStyle = p.getInt("pad_style", PadIcons.STYLE_PS)
        view.onClose = { finish() }
        view.setOnApplyWindowInsetsListener { _, insets -> view.applyInsets(insets); insets }
        setContentView(view)
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.requestFocus()
        hideBars()
    }

    override fun onResume() {
        super.onResume()
        sounds.start()
        view.bg.loadFromPrefs(this)
    }

    override fun onPause() {
        sounds.stopSoon()
        super.onPause()
    }

    override fun onDestroy() {
        sounds.release()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        view.tab = intent.getIntExtra(EXTRA_TAB, view.tab)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) { sounds.back(); finish() }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Suppress("DEPRECATION")
    private fun hideBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_FULLSCREEN)
        }
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_TIMER = 0
        const val TAB_STOPWATCH = 1

        fun open(ctx: Context, tab: Int) {
            ctx.startActivity(Intent(ctx, ClockActivity::class.java).putExtra(EXTRA_TAB, tab).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

@SuppressLint("ViewConstructor")
private class ClockView(context: Context, private val sounds: XmbSounds, startTab: Int) : View(context) {

    val bg = XmbBackground()
    var onClose: () -> Unit = {}
    var padStyle = PadIcons.STYLE_PS
    var tab = startTab
        set(v) { field = v.coerceIn(0, 1); invalidate() }

    private var time = 0f
    private var lastFrame = 0L
    private var safeL = 0f
    private var safeT = 0f
    private var safeR = 0f
    private var safeB = 0f
    private var u = 1f
    private var w = 0f
    private var h = 0f
    private var portrait = true

    // ---- колёса таймера ----
    private val wheelMax = intArrayOf(24, 60, 60)
    private val pos = FloatArray(3)
    private val target = IntArray(3)
    private val lastIdx = IntArray(3)
    private var focus = 1
    private val prefs = context.getSharedPreferences("xmb_clock", Context.MODE_PRIVATE)

    // ---- раскладка (пересчитывается каждый кадр) ----
    private var wheelCy = 0f
    private var wheelSp = 0f
    private var colW = 0f
    private var wheelRows = 2
    private val colRects = Array(3) { RectF() }
    private val chipRects = Array(6) { RectF() }
    private val tabRects = Array(2) { RectF() }
    private val btnL = RectF()
    private val btnR = RectF()
    private val btnC = RectF()
    private val tmp = RectF()

    // ---- касания ----
    private var downX = 0f
    private var downY = 0f
    private var dragCol = -1
    private var dragging = false
    private var dragStart = 0f
    private var pressedId = 0
    private var velocity: VelocityTracker? = null

    private val bigP = text("sans-serif-thin").apply { textAlign = Paint.Align.CENTER }
    private val titleP = text("sans-serif-light")
    private val labelP = text("sans-serif").apply { textAlign = Paint.Align.CENTER }
    private val rowP = text("sans-serif")
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeCap = Paint.Cap.ROUND
    }

    init {
        val last = prefs.getLong("wheel_ms", 150_000L)
        setWheels(last)
    }

    private fun text(family: String) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(family, Typeface.NORMAL)
        setShadowLayer(6f, 0f, 1.5f, Color.argb(140, 0, 0, 0))
    }

    private fun setWheels(ms: Long) {
        val total = ms / 1000
        target[0] = ((total / 3600) % 24).toInt()
        target[1] = ((total / 60) % 60).toInt()
        target[2] = (total % 60).toInt()
        for (i in 0..2) { if (lastFrame == 0L) pos[i] = target[i].toFloat(); lastIdx[i] = target[i] }
    }

    private fun wheelValue(i: Int) = ((round(pos[i]).toInt() % wheelMax[i]) + wheelMax[i]) % wheelMax[i]
    private fun targetValue(i: Int) = ((target[i] % wheelMax[i]) + wheelMax[i]) % wheelMax[i]
    private fun wheelMs() = (targetValue(0) * 3600L + targetValue(1) * 60L + targetValue(2)) * 1000L

    fun applyInsets(insets: WindowInsets) {
        if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            safeL = i.left.toFloat(); safeT = i.top.toFloat(); safeR = i.right.toFloat(); safeB = i.bottom.toFloat()
        } else {
            @Suppress("DEPRECATION")
            run {
                safeL = insets.systemWindowInsetLeft.toFloat(); safeT = insets.systemWindowInsetTop.toFloat()
                safeR = insets.systemWindowInsetRight.toFloat(); safeB = insets.systemWindowInsetBottom.toFloat()
            }
        }
    }

    private fun two(v: Long) = String.format(Locale.US, "%02d", v)
    private fun fmtTimer(ms: Long): String {
        val s = (ms + 999) / 1000
        return "${two(s / 3600)}:${two((s / 60) % 60)}:${two(s % 60)}"
    }

    private fun hasPad() = InputDevice.getDeviceIds().any { id ->
        val d = InputDevice.getDevice(id) ?: return@any false
        !d.isVirtual && (d.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
    }

    // =====================================================================
    // Отрисовка
    // =====================================================================

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else (now - lastFrame).coerceIn(0L, 50L) / 1000f
        lastFrame = now
        time += dt
        bg.step(dt)
        w = width.toFloat(); h = height.toFloat()
        u = max(1f, min(w, h))
        portrait = h >= w
        bg.draw(c, width, height, u, if (portrait) 1f else 0f, time)

        for (i in 0..2) if (!(dragging && dragCol == i)) pos[i] += (target[i] - pos[i]) * (1f - exp(-dt * 16f))
        for (i in 0..2) {
            val v = wheelValue(i)
            if (v != lastIdx[i]) { lastIdx[i] = v; sounds.tick() }
        }

        // заголовок
        titleP.textSize = u * 0.085f
        val title = if (tab == ClockActivity.TAB_TIMER) "Таймер" else "Секундомер"
        c.drawText(title, safeL + u * 0.07f, safeT + u * 0.17f, titleP)

        layoutButtons()
        if (tab == ClockActivity.TAB_TIMER) drawTimer(c) else drawStopwatch(c)
        drawTabs(c)
        if (hasPad()) drawPadHints(c)
        postInvalidateOnAnimation()
    }

    private fun layoutButtons() {
        val by = if (portrait) h - safeB - u * 0.40f else h - safeB - u * 0.13f
        val bh = u * 0.14f
        val bw = u * 0.34f
        val gap = u * 0.06f
        val cx = if (portrait) w / 2f else safeL + (w - safeL - safeR) * 0.42f
        btnC.set(cx - bw / 2f, by - bh / 2f, cx + bw / 2f, by + bh / 2f)
        btnL.set(cx - gap / 2f - bw, by - bh / 2f, cx - gap / 2f, by + bh / 2f)
        btnR.set(cx + gap / 2f, by - bh / 2f, cx + gap / 2f + bw, by + bh / 2f)
    }

    private fun pill(c: Canvas, r: RectF, id: Int, label: String?, glyph: Glyph?) {
        val down = pressedId == id
        fill.alpha = if (down) 80 else 26
        c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, fill)
        line.strokeWidth = max(1.5f, u * 0.002f)
        line.alpha = 140
        c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, line)
        if (glyph != null) Glyphs.draw(c, glyph, r.centerX(), r.centerY(), r.height() * 0.42f, 250)
        if (label != null) {
            labelP.textSize = u * 0.04f
            c.drawText(label, r.centerX(), r.centerY() + labelP.textSize * 0.36f, labelP)
        }
    }

    private fun drawTimer(c: Canvas) {
        val end = AlarmStore.timerEnd(context)
        val paused = AlarmStore.timerPaused(context)
        val cx = if (portrait) w / 2f else safeL + (w - safeL - safeR) * 0.42f
        wheelCy = if (portrait) h * 0.36f else h * 0.43f

        if (end > 0 || paused > 0) {
            // идёт отсчёт: большое время и кольцо прогресса
            val left = if (end > 0) (end - System.currentTimeMillis()).coerceAtLeast(0) else paused
            val total = AlarmStore.timerTotal(context).coerceAtLeast(left).coerceAtLeast(1)
            val rr = if (portrait) min(w * 0.40f, h * 0.24f) else h * 0.30f
            tmp.set(cx - rr, wheelCy - rr, cx + rr, wheelCy + rr)
            line.strokeWidth = max(2f, u * 0.006f)
            line.alpha = 45
            c.drawCircle(cx, wheelCy, rr, line)
            line.alpha = 230
            c.drawArc(tmp, -90f, 360f * left / total, false, line)
            bigP.textSize = u * (if (portrait) 0.15f else 0.13f)
            bigP.alpha = if (paused > 0) (170 + 85 * kotlin.math.sin(time * 4f)).toInt().coerceIn(80, 255) else 255
            c.drawText(fmtTimer(left), cx, wheelCy + bigP.textSize * 0.35f, bigP)
            labelP.textSize = u * 0.036f
            labelP.alpha = 190
            c.drawText(if (paused > 0) "Пауза" else "из " + fmtTimer(total), cx, wheelCy + bigP.textSize * 0.35f + u * 0.08f, labelP)
            labelP.alpha = 255
            pill(c, btnL, ID_RESET, "Сброс", null)
            pill(c, btnR, ID_PLAY, null, if (end > 0) Glyph.PAUSE else Glyph.PLAY)
            chipRects.forEach { it.setEmpty() }
            colRects.forEach { it.setEmpty() }
            return
        }

        // колёса часы : минуты : секунды
        colW = u * (if (portrait) 0.28f else 0.24f)
        wheelSp = u * (if (portrait) 0.135f else 0.12f)
        wheelRows = if (portrait) 2 else 1
        for (i in 0..2) {
            val x = cx + (i - 1) * colW
            colRects[i].set(x - colW / 2f, wheelCy - wheelSp * (wheelRows + 0.5f), x + colW / 2f, wheelCy + wheelSp * (wheelRows + 0.5f))
            if (i == focus && hasPad()) {
                fill.alpha = 14
                c.drawRoundRect(colRects[i], u * 0.02f, u * 0.02f, fill)
            }
            c.save()
            c.clipRect(colRects[i])
            val base = floor(pos[i]).toInt()
            for (k in base - wheelRows - 1..base + wheelRows + 1) {
                val off = k - pos[i]
                if (abs(off) > wheelRows + 0.6f) continue
                val near = 1f - min(abs(off), 1f)
                bigP.textSize = u * ((if (portrait) 0.10f else 0.085f) + (if (portrait) 0.075f else 0.06f) * near)
                bigP.alpha = (255 * (1f - min(abs(off), 2.4f) * 0.36f)).toInt()
                val v = ((k % wheelMax[i]) + wheelMax[i]) % wheelMax[i]
                c.drawText(two(v.toLong()), x, wheelCy + off * wheelSp + bigP.textSize * 0.36f, bigP)
            }
            c.restore()
        }
        bigP.alpha = 255
        bigP.textSize = u * (if (portrait) 0.13f else 0.11f)
        c.drawText(":", cx - colW / 2f, wheelCy + bigP.textSize * 0.3f, bigP)
        c.drawText(":", cx + colW / 2f, wheelCy + bigP.textSize * 0.3f, bigP)
        labelP.textSize = u * 0.03f
        labelP.alpha = 150
        val capY = colRects[0].top - u * 0.01f
        c.drawText("часы", cx - colW, capY, labelP)
        c.drawText("минуты", cx, capY, labelP)
        c.drawText("секунды", cx + colW, capY, labelP)
        labelP.alpha = 255

        // быстрые кнопки с последними временами
        val recent = AlarmStore.recentTimers(context)
        val chipH = u * 0.12f
        val gap = u * 0.035f
        if (portrait) {
            val left = safeL + u * 0.05f
            val cw = (w - safeL - safeR - u * 0.10f - gap * 2) / 3f
            val top = h * 0.585f
            for (i in 0 until 6) {
                val col = i % 3
                val row = i / 3
                val l = left + col * (cw + gap)
                val t = top + row * (chipH + gap)
                chipRects[i].set(l, t, l + cw, t + chipH)
            }
        } else {
            val l0 = safeL + (w - safeL - safeR) * 0.74f
            val cw = (w - safeR - u * 0.05f - l0 - gap) / 2f
            val top = safeT + u * 0.30f
            for (i in 0 until 6) {
                val col = i % 2
                val row = i / 2
                val l = l0 + col * (cw + gap)
                val t = top + row * (chipH + gap)
                chipRects[i].set(l, t, l + cw, t + chipH)
            }
        }
        val selectedMs = wheelMs()
        for (i in 0 until 6) {
            val r = chipRects[i]
            val ms = recent.getOrNull(i) ?: continue
            val sel = ms == selectedMs
            fill.alpha = if (pressedId == ID_CHIP0 + i) 80 else if (sel) 55 else 20
            c.drawRoundRect(r, u * 0.03f, u * 0.03f, fill)
            line.strokeWidth = max(1f, u * 0.0018f)
            line.alpha = if (sel) 200 else 90
            c.drawRoundRect(r, u * 0.03f, u * 0.03f, line)
            labelP.textSize = min(u * 0.042f, r.width() * 0.17f)
            c.drawText(fmtTimer(ms), r.centerX(), r.centerY() + labelP.textSize * 0.36f, labelP)
        }

        pill(c, btnC, ID_PLAY, null, Glyph.PLAY)
    }

    private fun drawStopwatch(c: Canvas) {
        val e = StopwatchStore.elapsed(context)
        val running = StopwatchStore.running(context)
        val cx = if (portrait) w / 2f else safeL + (w - safeL - safeR) * 0.42f
        val cy = if (portrait) h * 0.33f else h * 0.42f
        bigP.textSize = u * (if (portrait) 0.17f else 0.15f)
        bigP.alpha = if (!running && e > 0) (170 + 85 * kotlin.math.sin(time * 4f)).toInt().coerceIn(80, 255) else 255
        c.drawText(StopwatchStore.format(e), cx, cy + bigP.textSize * 0.35f, bigP)
        bigP.alpha = 255

        // круги
        val laps = StopwatchStore.laps(context)
        if (laps.isNotEmpty()) {
            val l: Float
            val r: Float
            var y: Float
            val bottom: Float
            if (portrait) {
                l = safeL + u * 0.08f; r = w - safeR - u * 0.08f
                y = cy + u * 0.17f; bottom = btnC.top - u * 0.06f
            } else {
                l = safeL + (w - safeL - safeR) * 0.70f; r = w - safeR - u * 0.05f
                y = safeT + u * 0.30f; bottom = h - safeB - u * 0.05f
            }
            rowP.textSize = u * 0.036f
            val rowH = u * 0.075f
            line.strokeWidth = max(1f, u * 0.0016f)
            line.alpha = 50
            c.drawLine(l, y, r, y, line)
            for (i in laps.indices.reversed()) {
                if (y + rowH > bottom) break
                val lapTime = laps[i] - (if (i > 0) laps[i - 1] else 0L)
                val ty = y + rowH * 0.66f
                rowP.alpha = 180
                c.drawText("Круг ${i + 1}", l, ty, rowP)
                rowP.alpha = 255
                val a = StopwatchStore.format(lapTime)
                c.drawText(a, (l + r) / 2f - rowP.measureText(a) / 2f, ty, rowP)
                rowP.alpha = 170
                val b = StopwatchStore.format(laps[i])
                c.drawText(b, r - rowP.measureText(b), ty, rowP)
                y += rowH
                line.alpha = 30
                c.drawLine(l, y, r, y, line)
            }
            rowP.alpha = 255
        }

        when {
            running -> {
                pill(c, btnL, ID_LAP, "Круг", null)
                pill(c, btnR, ID_PLAY, null, Glyph.PAUSE)
            }
            e > 0 -> {
                pill(c, btnL, ID_RESET, "Сброс", null)
                pill(c, btnR, ID_PLAY, null, Glyph.PLAY)
            }
            else -> pill(c, btnC, ID_PLAY, null, Glyph.PLAY)
        }
    }

    private fun drawTabs(c: Canvas) {
        val glyphs = arrayOf(Glyph.HOURGLASS, Glyph.TIMER)
        val names = arrayOf("Таймер", "Секундомер")
        val gs = u * 0.075f
        labelP.textSize = u * 0.032f
        for (i in 0..1) {
            val x: Float
            val y: Float
            if (portrait) {
                x = w * (if (i == 0) 0.32f else 0.68f)
                y = h - safeB - u * 0.15f
            } else {
                x = w - safeR - u * (if (i == 0) 0.42f else 0.16f)
                y = safeT + u * 0.11f
            }
            tabRects[i].set(x - u * 0.14f, y - gs, x + u * 0.14f, y + gs + u * 0.06f)
            val sel = tab == i
            if (sel) {
                fill.alpha = 22
                c.drawCircle(x, y, gs * 0.95f, fill)
            }
            Glyphs.draw(c, glyphs[i], x, y, gs, if (sel) 255 else 140)
            labelP.alpha = if (sel) 255 else 140
            c.drawText(names[i], x, y + gs * 0.6f + u * 0.045f, labelP)
        }
        labelP.alpha = 255
        if (portrait) {
            line.strokeWidth = max(1f, u * 0.0016f)
            line.alpha = 40
            val ly = h - safeB - u * 0.27f
            c.drawLine(safeL + u * 0.05f, ly, w - safeR - u * 0.05f, ly, line)
        }
    }

    private fun drawPadHints(c: Canvas) {
        val hints = if (tab == ClockActivity.TAB_TIMER && AlarmStore.timerEnd(context) == 0L && AlarmStore.timerPaused(context) == 0L) {
            listOf(PadBtn.DPAD to "Время", PadBtn.SOUTH to "Старт", PadBtn.SHOULDERS to "Вкладки", PadBtn.EAST to "Выход")
        } else if (tab == ClockActivity.TAB_TIMER) {
            listOf(PadBtn.SOUTH to "Пауза", PadBtn.WEST to "Сброс", PadBtn.SHOULDERS to "Вкладки", PadBtn.EAST to "Выход")
        } else {
            listOf(PadBtn.SOUTH to "Старт/пауза", PadBtn.WEST to "Круг/сброс", PadBtn.SHOULDERS to "Вкладки", PadBtn.EAST to "Выход")
        }
        val r = u * 0.02f
        rowP.textSize = u * 0.026f
        var total = 0f
        for (hnt in hints) total += PadIcons.width(hnt.first, r) + r * 0.5f + rowP.measureText(hnt.second) + r * 1.4f
        var x = (w - total) / 2f
        val y = if (portrait) safeT + u * 0.27f else h - safeB - u * 0.03f
        rowP.alpha = 220
        for (hnt in hints) {
            val iw = PadIcons.width(hnt.first, r)
            PadIcons.draw(c, hnt.first, x + iw / 2f, y, r, padStyle, 220)
            x += iw + r * 0.5f
            c.drawText(hnt.second, x, y + rowP.textSize * 0.36f, rowP)
            x += rowP.measureText(hnt.second) + r * 1.4f
        }
        rowP.alpha = 255
    }

    // =====================================================================
    // Действия
    // =====================================================================

    private fun playPause() {
        if (tab == ClockActivity.TAB_TIMER) {
            val end = AlarmStore.timerEnd(context)
            val paused = AlarmStore.timerPaused(context)
            when {
                end > 0 -> { AlarmStore.pauseTimer(context); sounds.back() }
                paused > 0 -> { AlarmStore.resumeTimer(context); sounds.ok() }
                else -> {
                    val ms = wheelMs()
                    if (ms > 0) {
                        prefs.edit().putLong("wheel_ms", ms).apply()
                        AlarmStore.startTimer(context, ms)
                        sounds.ok()
                    } else sounds.back()
                }
            }
        } else {
            if (StopwatchStore.running(context)) { StopwatchStore.pause(context); sounds.back() }
            else { StopwatchStore.start(context); sounds.ok() }
        }
        invalidate()
    }

    private fun secondary() {
        if (tab == ClockActivity.TAB_TIMER) {
            if (AlarmStore.timerEnd(context) > 0 || AlarmStore.timerPaused(context) > 0) {
                AlarmStore.cancelTimer(context); sounds.back()
            }
        } else {
            if (StopwatchStore.running(context)) { StopwatchStore.lap(context); sounds.tick() }
            else if (StopwatchStore.elapsed(context) > 0) { StopwatchStore.reset(context); sounds.back() }
        }
        invalidate()
    }

    private fun hit(x: Float, y: Float): Int {
        for (i in 0..1) if (tabRects[i].contains(x, y)) return ID_TAB0 + i
        val inTimerIdle = tab == ClockActivity.TAB_TIMER && AlarmStore.timerEnd(context) == 0L && AlarmStore.timerPaused(context) == 0L
        if (inTimerIdle || tab == ClockActivity.TAB_STOPWATCH && !StopwatchStore.running(context) && StopwatchStore.elapsed(context) == 0L) {
            if (btnC.contains(x, y)) return ID_PLAY
        } else {
            if (btnR.contains(x, y)) return ID_PLAY
            if (btnL.contains(x, y)) return if (tab == ClockActivity.TAB_STOPWATCH && StopwatchStore.running(context)) ID_LAP else ID_RESET
        }
        if (inTimerIdle) for (i in 0 until 6) if (chipRects[i].contains(x, y)) return ID_CHIP0 + i
        return 0
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                dragging = false
                pressedId = hit(e.x, e.y)
                dragCol = if (pressedId == 0 && tab == ClockActivity.TAB_TIMER) colRects.indexOfFirst { it.contains(e.x, e.y) } else -1
                if (dragCol >= 0) { focus = dragCol; dragStart = pos[dragCol] }
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                if (dragCol >= 0) {
                    if (!dragging && abs(e.y - downY) > u * 0.02f) { dragging = true; dragStart = pos[dragCol] }
                    if (dragging) pos[dragCol] = dragStart - (e.y - downY) / wheelSp
                } else if (pressedId != 0 && hit(e.x, e.y) != pressedId) pressedId = 0
            }
            MotionEvent.ACTION_UP -> {
                if (dragCol >= 0) {
                    if (dragging) {
                        val vt = velocity
                        vt?.addMovement(e)
                        vt?.computeCurrentVelocity(1000)
                        val v = vt?.yVelocity ?: 0f
                        target[dragCol] = round(pos[dragCol] - v * 0.12f / wheelSp).toInt()
                    } else {
                        target[dragCol] = round(pos[dragCol]).toInt() + round((e.y - wheelCy) / wheelSp).toInt()
                    }
                } else if (pressedId != 0 && hit(e.x, e.y) == pressedId) {
                    performClick()
                    when (pressedId) {
                        ID_PLAY -> playPause()
                        ID_RESET, ID_LAP -> secondary()
                        ID_TAB0, ID_TAB0 + 1 -> { if (tab != pressedId - ID_TAB0) sounds.tick(); tab = pressedId - ID_TAB0 }
                        else -> if (pressedId >= ID_CHIP0) {
                            AlarmStore.recentTimers(context).getOrNull(pressedId - ID_CHIP0)?.let { setWheels(it); sounds.tick() }
                        }
                    }
                }
                dragging = false
                dragCol = -1
                pressedId = 0
                velocity?.recycle(); velocity = null
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; dragCol = -1; pressedId = 0 }
        }
        invalidate()
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val idle = tab == ClockActivity.TAB_TIMER && AlarmStore.timerEnd(context) == 0L && AlarmStore.timerPaused(context) == 0L
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (idle && focus > 0) { focus--; sounds.tick() }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (idle && focus < 2) { focus++; sounds.tick() }
            KeyEvent.KEYCODE_DPAD_UP -> if (idle) target[focus]--
            KeyEvent.KEYCODE_DPAD_DOWN -> if (idle) target[focus]++
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> playPause()
            KeyEvent.KEYCODE_BUTTON_X -> secondary()
            KeyEvent.KEYCODE_BUTTON_L1 -> { if (tab != 0) sounds.tick(); tab = 0 }
            KeyEvent.KEYCODE_BUTTON_R1 -> { if (tab != 1) sounds.tick(); tab = 1 }
            KeyEvent.KEYCODE_BUTTON_B -> { sounds.back(); onClose() }
            else -> return super.onKeyDown(keyCode, event)
        }
        invalidate()
        return true
    }

    companion object {
        const val ID_PLAY = 1
        const val ID_RESET = 2
        const val ID_LAP = 3
        const val ID_TAB0 = 10
        const val ID_CHIP0 = 20
    }
}
