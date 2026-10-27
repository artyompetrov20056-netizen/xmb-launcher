package com.xmb.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.TextPaint
import android.text.format.DateFormat
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Экран звонка будильника / таймера в стиле XMB. Показывается и поверх экрана блокировки. */
class AlarmActivity : Activity() {

    private lateinit var view: AlarmView
    private val finishListener: () -> Unit = { runOnUiThread { if (!isFinishing) finish() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val info = AlarmService.ringing
        if (info == null) { finish(); return }

        view = AlarmView(this, info.title, info.canSnooze)
        view.bg.loadFromPrefs(this)
        view.onStop = { AlarmService.send(this, AlarmService.ACTION_STOP); finish() }
        view.onSnooze = { AlarmService.send(this, AlarmService.ACTION_SNOOZE); finish() }
        setContentView(view)
        hideBars()
        AlarmService.listeners.add(finishListener)
    }

    override fun onResume() {
        super.onResume()
        if (AlarmService.ringing == null) finish()
    }

    override fun onDestroy() {
        AlarmService.listeners.remove(finishListener)
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && ::view.isInitialized) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> return true
                // кнопки громкости и □/X — отложить, ✕/A — выключить
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_BUTTON_X -> {
                    if (view.canSnooze) view.onSnooze() else view.onStop()
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    view.onStop(); return true
                }
            }
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.dispatchKeyEvent(event)
    }

    @Suppress("DEPRECATION")
    private fun hideBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
    }
}

private class AlarmView(context: Context, val title: String, val canSnooze: Boolean) : View(context) {

    val bg = XmbBackground()
    var onStop: () -> Unit = {}
    var onSnooze: () -> Unit = {}

    private var time = 0f
    private var lastFrame = 0L
    private val stopRect = RectF()
    private val snoozeRect = RectF()
    private var pressed = 0
    private val padStyle = context.getSharedPreferences("xmb", Context.MODE_PRIVATE).getInt("pad_style", PadIcons.STYLE_PS)

    private val clockP = text("sans-serif-thin").apply { textAlign = Paint.Align.CENTER }
    private val titleP = text("sans-serif").apply { textAlign = Paint.Align.CENTER }
    private val btnP = text("sans-serif").apply { textAlign = Paint.Align.CENTER }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }

    private fun text(family: String) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(family, Typeface.NORMAL)
        setShadowLayer(6f, 0f, 1.5f, Color.argb(150, 0, 0, 0))
    }

    private fun hasPad() = InputDevice.getDeviceIds().any { id ->
        val d = InputDevice.getDevice(id) ?: return@any false
        !d.isVirtual && (d.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else (now - lastFrame).coerceIn(0L, 50L) / 1000f
        lastFrame = now
        time += dt
        bg.step(dt)
        val w = width.toFloat()
        val h = height.toFloat()
        val u = max(1f, min(w, h))
        val portrait = h >= w
        bg.draw(c, width, height, u, if (portrait) 1f else 0f, time)

        // мягкая пульсация, как у звонящего пункта
        val pulse = 0.5f + 0.5f * sin(time * 3f)
        val cy = h * (if (portrait) 0.36f else 0.42f)
        val glowR = u * (0.42f + 0.03f * pulse)
        fill.alpha = (20 + 18 * pulse).toInt()
        c.drawCircle(w / 2f, cy - u * 0.08f, glowR, fill)

        Glyphs.draw(c, if (title == "Таймер") Glyph.TIMER else Glyph.ALARM, w / 2f, cy - u * 0.36f, u * 0.13f, 240)
        clockP.textSize = u * 0.26f
        val fmt = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
        c.drawText(SimpleDateFormat(fmt, Locale.getDefault()).format(Date()), w / 2f, cy + u * 0.02f, clockP)
        titleP.textSize = u * 0.055f
        c.drawText(if (title == "Таймер") "Время вышло" else title, w / 2f, cy + u * 0.12f, titleP)

        // кнопки
        val bw = u * 0.36f
        val bh = u * 0.13f
        val by = if (portrait) h * 0.74f else h * 0.80f
        val gap = u * 0.05f
        if (canSnooze) {
            snoozeRect.set(w / 2f - gap / 2f - bw, by - bh / 2f, w / 2f - gap / 2f, by + bh / 2f)
            stopRect.set(w / 2f + gap / 2f, by - bh / 2f, w / 2f + gap / 2f + bw, by + bh / 2f)
        } else {
            snoozeRect.setEmpty()
            stopRect.set(w / 2f - bw / 2f, by - bh / 2f, w / 2f + bw / 2f, by + bh / 2f)
        }
        val pad = hasPad()
        if (canSnooze) drawButton(c, snoozeRect, "Отложить ${AlarmStore.SNOOZE_MINUTES} мин", pressed == 2, u, if (pad) PadBtn.WEST else null)
        drawButton(c, stopRect, "Выключить", pressed == 1, u, if (pad) PadBtn.SOUTH else null)

        postInvalidateOnAnimation()
    }

    private fun drawButton(c: Canvas, r: RectF, label: String, down: Boolean, u: Float, pad: PadBtn?) {
        fill.alpha = if (down) 80 else 26
        c.drawRoundRect(r, u * 0.02f, u * 0.02f, fill)
        line.strokeWidth = max(1.5f, u * 0.002f)
        line.alpha = 150
        c.drawRoundRect(r, u * 0.02f, u * 0.02f, line)
        btnP.textSize = u * 0.04f
        var tx = r.centerX()
        if (pad != null) {
            val ir = u * 0.024f
            PadIcons.draw(c, pad, r.left + u * 0.05f, r.centerY(), ir, padStyle, 230)
            tx += u * 0.025f
        }
        val s = android.text.TextUtils.ellipsize(label, btnP, r.width() - u * 0.12f, android.text.TextUtils.TruncateAt.END)
        c.drawText(s, 0, s.length, tx, r.centerY() + btnP.textSize * 0.36f, btnP)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                pressed = when {
                    stopRect.contains(e.x, e.y) -> 1
                    snoozeRect.contains(e.x, e.y) -> 2
                    else -> 0
                }
            MotionEvent.ACTION_UP -> {
                when {
                    pressed == 1 && stopRect.contains(e.x, e.y) -> { performClick(); onStop() }
                    pressed == 2 && snoozeRect.contains(e.x, e.y) -> { performClick(); onSnooze() }
                }
                pressed = 0
            }
            MotionEvent.ACTION_CANCEL -> pressed = 0
        }
        invalidate()
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
