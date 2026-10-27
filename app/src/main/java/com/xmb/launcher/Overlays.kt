package com.xmb.launcher

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Размеры экрана и безопасные отступы — передаются окнам при отрисовке. */
class Frame {
    var w = 0f
    var h = 0f
    var unit = 1f
    var l = 0f
    var t = 0f
    var r = 0f
    var b = 0f
    var portrait = false
}

interface OverlayHost {
    fun closeOverlay()
    fun playTick()
    fun playKey()
    fun playOk()
    fun playBack()
}

/** Всплывающее окно поверх XMB (меню, подтверждение, поиск). */
abstract class Overlay {
    lateinit var host: OverlayHost
    var fade = 0f

    abstract fun draw(c: Canvas, f: Frame, time: Float)
    abstract fun onTouch(e: MotionEvent, f: Frame)
    open fun onKey(keyCode: Int, event: KeyEvent): Boolean = false
    open fun onBack() {
        host.playBack()
        host.closeOverlay()
    }

    protected fun a(v: Float) = (v * fade).toInt().coerceIn(0, 255)

    companion object {
        fun textPaint(): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setShadowLayer(5f, 0f, 1f, Color.argb(140, 0, 0, 0))
        }

        fun wrap(text: String, p: Paint, maxW: Float): List<String> {
            val out = ArrayList<String>()
            var line = ""
            for (word in text.split(' ')) {
                val cand = if (line.isEmpty()) word else "$line $word"
                if (line.isEmpty() || p.measureText(cand) <= maxW) line = cand
                else { out += line; line = word }
            }
            if (line.isNotEmpty()) out += line
            return out
        }

        fun drawText(c: Canvas, text: String, p: TextPaint, x: Float, y: Float, maxW: Float) {
            if (maxW <= 4f) return
            val s = TextUtils.ellipsize(text, p, maxW, TextUtils.TruncateAt.END)
            c.drawText(s, 0, s.length, x, y, p)
        }
    }
}

// =========================================================================
// Меню / подтверждение
// =========================================================================

/**
 * Окно со списком вариантов: меню по удержанию приложения,
 * «Действительно хотите выйти? Да / Нет», выбор цвета темы.
 */
class DialogOverlay(
    private val title: String?,
    private val message: String?,
    private val options: List<String>,
    private var selected: Int = 0,
    private val checked: Int = -1,
    /** false — звук выбора проиграет сам вызывающий код (например, звук запуска приложения). */
    private val pickSound: Boolean = true,
    /** Вызывается при отмене («Назад» или нажатие мимо окна). */
    private val onCancel: (() -> Unit)? = null,
    private val onPick: (Int) -> Unit,
) : Overlay() {

    private val dim = Paint().apply { color = Color.BLACK }
    private val panelFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val panelLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val hl = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val titleP = textPaint()
    private val msgP = textPaint()
    private val optP = textPaint()
    private val panel = RectF()
    private val rows = Array(options.size) { RectF() }
    private var msgLines: List<String> = emptyList()
    private var headerTop = 0f
    private var pad = 0f
    private var pressed = -1
    private var downOutside = false

    private fun layout(f: Frame) {
        val u = f.unit
        val pw = min(f.w - f.l - f.r - u * 0.1f, u * 1.1f)
        pad = u * 0.05f
        titleP.textSize = u * 0.048f
        msgP.textSize = u * 0.038f
        val inner = pw - pad * 2
        msgLines = message?.let { wrap(it, msgP, inner) } ?: emptyList()

        var headH = 0f
        if (title != null) headH += titleP.textSize * 1.5f
        headH += msgLines.size * msgP.textSize * 1.4f
        if (headH > 0f) headH += pad * 0.5f

        val maxH = f.h - f.t - f.b - u * 0.08f
        val rowH = min(u * 0.10f, (maxH - headH - pad * 2) / max(1, options.size)).coerceAtLeast(u * 0.05f)
        optP.textSize = min(u * 0.042f, rowH * 0.45f)
        val ph = pad * 2 + headH + rowH * options.size
        val cx = (f.l + f.w - f.r) / 2f
        val cy = (f.t + f.h - f.b) / 2f
        panel.set(cx - pw / 2f, cy - ph / 2f, cx + pw / 2f, cy + ph / 2f)
        headerTop = panel.top + pad
        var y = panel.top + pad + headH
        for (i in options.indices) {
            rows[i].set(panel.left + pad * 0.5f, y, panel.right - pad * 0.5f, y + rowH)
            y += rowH
        }
    }

    override fun draw(c: Canvas, f: Frame, time: Float) {
        layout(f)
        dim.alpha = a(150f)
        c.drawRect(0f, 0f, f.w, f.h, dim)

        val rad = f.unit * 0.02f
        panelFill.color = Color.rgb(12, 18, 34)
        panelFill.alpha = a(228f)
        c.drawRoundRect(panel, rad, rad, panelFill)
        panelLine.strokeWidth = max(1f, f.unit * 0.0018f)
        panelLine.alpha = a(70f)
        c.drawRoundRect(panel, rad, rad, panelLine)

        val inner = panel.width() - pad * 2
        var y = headerTop
        if (title != null) {
            titleP.alpha = a(255f)
            drawText(c, title, titleP, panel.left + pad, y + titleP.textSize, inner)
            y += titleP.textSize * 1.5f
        }
        msgP.alpha = a(220f)
        for (line in msgLines) {
            c.drawText(line, panel.left + pad, y + msgP.textSize, msgP)
            y += msgP.textSize * 1.4f
        }

        for (i in options.indices) {
            val rr = rows[i]
            if (i == pressed || i == selected) {
                hl.alpha = a(if (i == pressed) 75f else 38f + 14f * sin(time * 3f))
                c.drawRoundRect(rr, rad * 0.6f, rad * 0.6f, hl)
            }
            var tx = rr.left + pad * 0.5f
            if (checked >= 0) {
                if (i == checked) {
                    hl.alpha = a(255f)
                    c.drawCircle(tx + optP.textSize * 0.3f, rr.centerY(), optP.textSize * 0.2f, hl)
                }
                tx += optP.textSize * 0.95f
            }
            optP.alpha = a(255f)
            drawText(c, options[i], optP, tx, rr.centerY() + optP.textSize * 0.35f, rr.right - tx - pad * 0.5f)
        }
    }

    private fun rowAt(x: Float, y: Float) = rows.indexOfFirst { it.contains(x, y) }

    override fun onTouch(e: MotionEvent, f: Frame) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = rowAt(e.x, e.y)
                downOutside = !panel.contains(e.x, e.y)
            }
            MotionEvent.ACTION_MOVE -> if (pressed >= 0 && rowAt(e.x, e.y) != pressed) pressed = -1
            MotionEvent.ACTION_UP -> {
                val r = rowAt(e.x, e.y)
                if (pressed >= 0 && r == pressed) pick(r)
                else if (downOutside && !panel.contains(e.x, e.y)) onBack()
                pressed = -1
            }
            MotionEvent.ACTION_CANCEL -> pressed = -1
        }
    }

    private fun pick(i: Int) {
        selected = i
        if (pickSound) host.playOk()
        host.closeOverlay()
        onPick(i)
    }

    override fun onBack() {
        host.playBack()
        host.closeOverlay()
        onCancel?.invoke()
    }

    override fun onKey(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (selected > 0) { selected--; host.playTick() }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (selected < options.lastIndex) { selected++; host.playTick() }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> pick(selected)
            KeyEvent.KEYCODE_BUTTON_B -> onBack()
            else -> return false
        }
        return true
    }
}

// =========================================================================
// Поиск с экранной клавиатурой
// =========================================================================

/** Поиск приложений по названию с экранной клавиатурой в стиле PS3. */
class SearchOverlay(
    private val apps: List<XmbItem>,
    /** Выбрано приложение в результатах — показываем меню действий. */
    private val onChoose: (XmbItem) -> Unit,
) : Overlay() {

    private class Key(val label: String, val kind: Int, val weight: Int, val ch: Char = ' ') {
        val rect = RectF()
    }

    private val query = StringBuilder()
    private var ru = true
    private var rows: List<List<Key>> = buildRows()
    private var keys: List<Key> = rows.flatten()
    private var sel = 0
    private var pressedKey = -1
    private var pressedResult = -1
    private var results: List<XmbItem> = emptyList()

    private val field = RectF()
    private val resArea = RectF()
    private var rowH = 1f
    private var visibleRows = 0
    private val tmp = RectF()

    private val dim = Paint().apply { color = Color.BLACK }
    private val boxFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val boxLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textP = textPaint()
    private val hintP = textPaint()
    private val keyP = textPaint().apply { textAlign = Paint.Align.CENTER }

    private fun buildRows(): List<List<Key>> {
        fun chars(s: String) = s.map { Key(it.toString(), K_CHAR, 1, it) }
        val letters = if (ru) listOf("йцукенгшщзхъ", "фывапролджэ", "ячсмитьбюё")
        else listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        return listOf(chars("1234567890")) + letters.map { chars(it) } + listOf(
            listOf(
                Key(if (ru) "ABC" else "АБВ", K_LANG, 2),
                Key("Пробел", K_SPACE, 6),
                Key("Стереть", K_DEL, 2),
                Key("Закрыть", K_CLOSE, 2),
            )
        )
    }

    // ---------------- раскладка ----------------

    private fun layout(f: Frame) {
        val u = f.unit
        val m = u * 0.045f
        val key: Float
        val kbLeft: Float
        val kbTop: Float
        if (f.portrait) {
            field.set(f.l + m, f.t + m * 1.2f, f.w - f.r - m, f.t + m * 1.2f + u * 0.12f)
            val kbW = f.w - f.l - f.r - 2 * m
            key = kbW / 12f
            kbLeft = f.l + m
            kbTop = f.h - f.b - m - key * 1.12f * 5f
            resArea.set(field.left, field.bottom + m, field.right, kbTop - m)
        } else {
            val right = f.l + (f.w - f.l - f.r) * 0.6f
            field.set(f.l + m, f.t + m, right, f.t + m + u * 0.11f)
            val kbW = right - field.left
            key = min(kbW / 12f, (f.h - f.b - m - (field.bottom + m)) / (5f * 1.12f))
            kbLeft = field.left + (kbW - key * 12f) / 2f
            kbTop = f.h - f.b - m - key * 1.12f * 5f
            resArea.set(right + m, f.t + m, f.w - f.r - m, f.h - f.b - m)
        }
        val rh = key * 1.12f
        rows.forEachIndexed { ri, row ->
            val total = row.sumOf { it.weight }
            var x = kbLeft + (12 - total) * key / 2f
            val y = kbTop + ri * rh
            for (k in row) {
                val w = k.weight * key
                k.rect.set(x + key * 0.05f, y + key * 0.05f, x + w - key * 0.05f, y + rh - key * 0.05f)
                x += w
            }
        }
        keyP.textSize = key * 0.42f
        rowH = u * 0.10f
        visibleRows = max(0, floor(resArea.height() / rowH).toInt())
    }

    // ---------------- отрисовка ----------------

    override fun draw(c: Canvas, f: Frame, time: Float) {
        layout(f)
        val u = f.unit
        dim.alpha = a(178f)
        c.drawRect(0f, 0f, f.w, f.h, dim)
        val rad = u * 0.012f
        boxLine.strokeWidth = max(1f, u * 0.0018f)

        // Поле ввода
        boxFill.alpha = a(30f)
        c.drawRoundRect(field, rad, rad, boxFill)
        boxLine.alpha = a(120f)
        c.drawRoundRect(field, rad, rad, boxLine)
        val gs = field.height()
        Glyphs.draw(c, Glyph.SEARCH, field.left + gs * 0.55f, field.centerY(), gs * 0.5f, a(220f))
        val tx = field.left + gs * 1.05f
        textP.textSize = u * 0.048f
        val ty = field.centerY() + textP.textSize * 0.35f
        if (query.isEmpty()) {
            hintP.textSize = textP.textSize
            hintP.alpha = a(120f)
            drawText(c, "Название приложения", hintP, tx, ty, field.right - tx - gs * 0.3f)
        } else {
            textP.alpha = a(255f)
            val s = query.toString()
            val maxW = field.right - tx - gs * 0.4f
            // показываем конец строки, если она длинная
            var shown = s
            while (shown.length > 1 && textP.measureText(shown) > maxW) shown = shown.substring(1)
            c.drawText(shown, tx, ty, textP)
            if ((time * 2f).toInt() % 2 == 0) {
                val cx = tx + textP.measureText(shown) + u * 0.006f
                boxFill.alpha = a(230f)
                c.drawRect(cx, field.centerY() - textP.textSize * 0.55f, cx + max(2f, u * 0.003f), field.centerY() + textP.textSize * 0.55f, boxFill)
            }
        }

        // Результаты
        hintP.textSize = u * 0.036f
        hintP.alpha = a(170f)
        when {
            query.isBlank() -> drawText(c, "Начните вводить название", hintP, resArea.left, resArea.top + rowH * 0.6f, resArea.width())
            results.isEmpty() -> drawText(c, "Ничего не найдено", hintP, resArea.left, resArea.top + rowH * 0.6f, resArea.width())
            else -> {
                textP.textSize = u * 0.04f
                for (i in 0 until min(visibleRows, results.size)) {
                    val item = results[i]
                    resultRect(i)
                    if (i == pressedResult) {
                        boxFill.alpha = a(60f)
                        c.drawRoundRect(tmp, rad, rad, boxFill)
                    }
                    val isz = rowH * 0.72f
                    val icx = tmp.left + rowH * 0.5f
                    val icy = tmp.centerY()
                    item.icon?.let {
                        iconPaint.alpha = a(255f)
                        tmp.set(icx - isz / 2f, icy - isz / 2f, icx + isz / 2f, icy + isz / 2f)
                        c.drawBitmap(it, null, tmp, iconPaint)
                    }
                    textP.alpha = a(255f)
                    val lx = icx + rowH * 0.6f
                    drawText(c, item.title, textP, lx, icy + textP.textSize * 0.35f, resArea.right - lx)
                }
            }
        }

        // Клавиатура
        for ((i, k) in keys.withIndex()) {
            val active = i == pressedKey
            boxFill.alpha = a(if (active) 130f else if (i == sel) 60f else 28f)
            c.drawRoundRect(k.rect, rad, rad, boxFill)
            boxLine.alpha = a(if (i == sel) 170f else 70f)
            c.drawRoundRect(k.rect, rad, rad, boxLine)
            val small = k.kind != K_CHAR
            keyP.textSize = if (small) min(k.rect.height() * 0.36f, u * 0.034f) else k.rect.height() * 0.46f
            keyP.alpha = a(255f)
            c.drawText(k.label, k.rect.centerX(), k.rect.centerY() + keyP.textSize * 0.36f, keyP)
        }
    }

    private fun resultRect(i: Int) {
        tmp.set(resArea.left, resArea.top + i * rowH, resArea.right, resArea.top + (i + 1) * rowH)
    }

    // ---------------- ввод ----------------

    private fun keyAt(x: Float, y: Float) = keys.indexOfFirst { it.rect.contains(x, y) }

    private fun resultAt(x: Float, y: Float): Int {
        if (!resArea.contains(x, y)) return -1
        val i = ((y - resArea.top) / rowH).toInt()
        return if (i in 0 until min(visibleRows, results.size)) i else -1
    }

    override fun onTouch(e: MotionEvent, f: Frame) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedKey = keyAt(e.x, e.y)
                pressedResult = if (pressedKey < 0) resultAt(e.x, e.y) else -1
                if (pressedKey >= 0) sel = pressedKey
            }
            MotionEvent.ACTION_MOVE -> {
                if (pressedKey >= 0 && keyAt(e.x, e.y) != pressedKey) pressedKey = -1
                if (pressedResult >= 0 && resultAt(e.x, e.y) != pressedResult) pressedResult = -1
            }
            MotionEvent.ACTION_UP -> {
                if (pressedKey >= 0 && keyAt(e.x, e.y) == pressedKey) press(keys[pressedKey])
                else if (pressedResult >= 0 && resultAt(e.x, e.y) == pressedResult) launch(results[pressedResult])
                pressedKey = -1
                pressedResult = -1
            }
            MotionEvent.ACTION_CANCEL -> { pressedKey = -1; pressedResult = -1 }
        }
    }

    private fun press(k: Key) {
        when (k.kind) {
            K_CHAR -> if (query.length < 40) query.append(k.ch)
            K_SPACE -> if (query.isNotEmpty() && !query.endsWith(" ")) query.append(' ')
            K_DEL -> if (query.isNotEmpty()) query.deleteCharAt(query.length - 1)
            K_LANG -> {
                ru = !ru
                rows = buildRows()
                keys = rows.flatten()
                sel = sel.coerceIn(0, keys.lastIndex)
            }
            K_CLOSE -> { onBack(); return }
        }
        host.playKey()
        updateResults()
    }

    private fun launch(item: XmbItem) {
        host.playOk()
        host.closeOverlay()
        onChoose(item)
    }

    private fun norm(s: String) = s.lowercase().replace('ё', 'е')

    private fun updateResults() {
        val q = norm(query.toString().trim())
        if (q.isEmpty()) { results = emptyList(); return }
        results = apps
            .map { it to norm(it.title) }
            .filter { it.second.contains(q) }
            .sortedBy {
                val t = it.second
                when {
                    t.startsWith(q) -> 0
                    t.contains(" $q") -> 1
                    else -> 2
                }
            }
            .map { it.first }
    }

    override fun onKey(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> moveSel(-1f, 0f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveSel(1f, 0f)
            KeyEvent.KEYCODE_DPAD_UP -> moveSel(0f, -1f)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveSel(0f, 1f)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A -> press(keys[sel])
            KeyEvent.KEYCODE_ENTER -> results.firstOrNull()?.let { launch(it) }
            KeyEvent.KEYCODE_DEL -> press(Key("", K_DEL, 1))
            KeyEvent.KEYCODE_BUTTON_B -> onBack()
            else -> {
                val ch = event.unicodeChar
                if (ch == 0 || Character.isISOControl(ch)) return false
                if (ch == ' '.code) press(Key("", K_SPACE, 1))
                else press(Key("", K_CHAR, 1, ch.toChar().lowercaseChar()))
            }
        }
        return true
    }

    /** Перемещение выделения клавиш с помощью крестовины геймпада. */
    private fun moveSel(dx: Float, dy: Float) {
        val cur = keys[sel].rect
        var best = -1
        var bestScore = Float.MAX_VALUE
        for ((i, k) in keys.withIndex()) {
            if (i == sel) continue
            val vx = k.rect.centerX() - cur.centerX()
            val vy = k.rect.centerY() - cur.centerY()
            val along = vx * dx + vy * dy
            if (along <= 1f) continue
            val across = abs(vx * dy - vy * dx)
            val score = along + across * 2.5f
            if (score < bestScore) { bestScore = score; best = i }
        }
        if (best >= 0) { sel = best; host.playTick() }
    }

    companion object {
        private const val K_CHAR = 0
        private const val K_SPACE = 1
        private const val K_DEL = 2
        private const val K_LANG = 3
        private const val K_CLOSE = 4
    }
}

// =========================================================================
// Рулетка выбора значения (как в настройках PS3)
// =========================================================================

/**
 * Вертикальная «рулетка» рядом с выбранным пунктом: значение в рамке,
 * соседние значения выше и ниже, прокрутка по кругу.
 * Свайп вверх/вниз крутит, нажатие на рамку (или мимо) — применить, «Назад» — отменить.
 */
class RouletteOverlay(
    private val values: List<String>,
    start: Int,
    /** Где сейчас выбранный пункт меню (центр иконки). */
    private val anchor: (android.graphics.PointF) -> Unit,
    /** Значение в рамке поменялось (для предпросмотра). */
    private val onChange: (Int) -> Unit,
    private val onDone: (Int) -> Unit,
    private val onCancel: () -> Unit,
) : Overlay() {

    private val n = values.size
    private var pos = start.toFloat()
    private var target = start
    private var lastIdx = start
    private var lastTime = -1f
    private val p = android.graphics.PointF()
    private var cx = 0f
    private var cy = 0f
    private var spacing = 1f
    private val box = RectF()
    private val panel = RectF()

    private var dragging = false
    private var downY = 0f
    private var downX = 0f
    private var dragStart = 0f
    private var velocity: android.view.VelocityTracker? = null

    private val dim = Paint().apply { color = Color.BLACK }
    private val panelFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val boxFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val boxLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val textP = textPaint().apply { textAlign = Paint.Align.CENTER }

    private fun wrapIdx(k: Int) = ((k % n) + n) % n

    private fun layout(f: Frame) {
        val u = f.unit
        anchor(p)
        spacing = u * 0.085f
        if (f.portrait) {
            cx = f.w - f.r - u * 0.27f
            cy = p.y + u * 0.30f
        } else {
            cx = f.w - f.r - u * 0.32f
            cy = p.y
        }
        cy = cy.coerceIn(f.t + spacing * 2.6f, f.h - f.b - spacing * 2.6f)
        val bw = u * 0.42f
        box.set(cx - bw / 2f, cy - spacing * 0.48f, cx + bw / 2f, cy + spacing * 0.48f)
        panel.set(cx - bw / 2f - u * 0.02f, cy - spacing * 2.6f, cx + bw / 2f + u * 0.02f, cy + spacing * 2.6f)
    }

    override fun draw(c: Canvas, f: Frame, time: Float) {
        val dt = if (lastTime < 0f) 0f else (time - lastTime).coerceIn(0f, 0.05f)
        lastTime = time
        if (!dragging) pos += (target - pos) * (1f - kotlin.math.exp(-dt * 16f))
        checkChange()

        layout(f)
        val u = f.unit
        dim.alpha = a(70f)
        c.drawRect(0f, 0f, f.w, f.h, dim)
        panelFill.alpha = a(110f)
        c.drawRoundRect(panel, u * 0.02f, u * 0.02f, panelFill)

        val base = floor(pos).toInt()
        for (k in base - 2..base + 3) {
            val off = k - pos
            if (abs(off) > 2.4f) continue
            val y = cy + off * spacing
            val near = (1f - min(abs(off), 1f))
            textP.textSize = u * (0.03f + 0.016f * near)
            textP.alpha = a(255f * (1f - min(abs(off), 2.2f) * 0.38f))
            c.drawText(values[wrapIdx(k)], cx, y + textP.textSize * 0.36f, textP)
        }

        boxFill.alpha = a(28f)
        c.drawRoundRect(box, u * 0.012f, u * 0.012f, boxFill)
        boxLine.strokeWidth = max(2f, u * 0.004f)
        boxLine.alpha = a(240f)
        c.drawRoundRect(box, u * 0.012f, u * 0.012f, boxLine)
    }

    private fun checkChange() {
        val idx = wrapIdx(kotlin.math.round(pos).toInt())
        if (idx != lastIdx) {
            lastIdx = idx
            onChange(idx)
            host.playTick()
        }
    }

    override fun onTouch(e: MotionEvent, f: Frame) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                dragging = false
                dragStart = pos
                velocity?.recycle()
                velocity = android.view.VelocityTracker.obtain().also { it.addMovement(e) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                if (!dragging && abs(e.y - downY) > f.unit * 0.02f) { dragging = true; dragStart = pos }
                if (dragging) {
                    pos = dragStart - (e.y - downY) / spacing
                    checkChange()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    val vt = velocity
                    vt?.addMovement(e)
                    vt?.computeCurrentVelocity(1000)
                    val v = vt?.yVelocity ?: 0f
                    target = kotlin.math.round(pos - v * 0.12f / spacing).toInt()
                    dragging = false
                } else {
                    when {
                        box.contains(e.x, e.y) -> confirm()
                        panel.contains(e.x, e.y) -> {
                            val off = kotlin.math.round((e.y - cy) / spacing).toInt()
                            target = kotlin.math.round(pos).toInt() + off
                        }
                        else -> confirm()
                    }
                }
                velocity?.recycle(); velocity = null
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                target = kotlin.math.round(pos).toInt()
            }
        }
    }

    private fun confirm() {
        val idx = wrapIdx(kotlin.math.round(target.toFloat()).toInt())
        if (idx != lastIdx) onChange(idx)
        host.playOk()
        host.closeOverlay()
        onDone(idx)
    }

    override fun onBack() {
        host.closeOverlay()
        onCancel()
        host.playBack()
    }

    override fun onKey(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> target--
            KeyEvent.KEYCODE_DPAD_DOWN -> target++
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> confirm()
            KeyEvent.KEYCODE_BUTTON_B -> onBack()
            else -> return false
        }
        return true
    }
}
