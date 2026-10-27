package com.xmb.launcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** Будильник. days — дни недели битами: 0 — Пн … 6 — Вс; 0 — один раз. */
class Alarm(
    val id: Int,
    var hour: Int,
    var minute: Int,
    var days: Int,
    var enabled: Boolean,
    /** Путь к своей мелодии (MP3) или null — мелодия по умолчанию. */
    var sound: String?,
)

/**
 * Хранение и планирование будильников, таймера и «отложить».
 * Срабатывание через AlarmManager.setAlarmClock — точно, в том числе в режиме сна,
 * и в строке состояния появляется значок будильника.
 */
object AlarmStore {
    private const val PREFS = "xmb_alarms"
    private const val ACTION = "com.xmb.launcher.ALARM_FIRE"
    const val KIND_ALARM = "alarm"
    const val KIND_TIMER = "timer"
    const val KIND_SNOOZE = "snooze"
    const val SNOOZE_MINUTES = 10

    val DAY_SHORT = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
    val DAY_FULL = listOf("Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------- будильники ----------------

    fun load(ctx: Context): MutableList<Alarm> {
        val list = ArrayList<Alarm>()
        val arr = try { JSONArray(prefs(ctx).getString("alarms", "[]")) } catch (_: Exception) { JSONArray() }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list += Alarm(
                o.optInt("id"), o.optInt("h"), o.optInt("m"), o.optInt("d"),
                o.optBoolean("on", true), if (o.has("s")) o.optString("s") else null
            )
        }
        list.sortWith(compareBy({ it.hour }, { it.minute }))
        return list
    }

    fun save(ctx: Context, list: List<Alarm>) {
        val arr = JSONArray()
        for (a in list) {
            val o = JSONObject().put("id", a.id).put("h", a.hour).put("m", a.minute).put("d", a.days).put("on", a.enabled)
            a.sound?.let { o.put("s", it) }
            arr.put(o)
        }
        prefs(ctx).edit().putString("alarms", arr.toString()).apply()
    }

    fun get(ctx: Context, id: Int) = load(ctx).firstOrNull { it.id == id }

    fun add(ctx: Context, hour: Int, minute: Int): Alarm {
        val p = prefs(ctx)
        val id = p.getInt("next_id", 1)
        p.edit().putInt("next_id", id + 1).apply()
        val a = Alarm(id, hour, minute, 0, true, null)
        val list = load(ctx)
        list += a
        save(ctx, list)
        schedule(ctx, a)
        return a
    }

    fun update(ctx: Context, a: Alarm) {
        val list = load(ctx)
        val i = list.indexOfFirst { it.id == a.id }
        if (i >= 0) list[i] = a else list += a
        save(ctx, list)
        if (a.enabled) schedule(ctx, a) else cancel(ctx, a.id)
    }

    fun remove(ctx: Context, id: Int) {
        cancel(ctx, id)
        save(ctx, load(ctx).filter { it.id != id })
    }

    /** Ближайшее время срабатывания (мс). */
    fun nextTrigger(a: Alarm, now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, a.hour)
        cal.set(Calendar.MINUTE, a.minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (a.days == 0) {
            if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
            return cal.timeInMillis
        }
        for (i in 0..7) {
            val dayIdx = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Пн = 0
            if ((a.days shr dayIdx) and 1 == 1 && cal.timeInMillis > now) return cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    fun daysText(days: Int): String = when (days) {
        0 -> "Один раз"
        0b1111111 -> "Каждый день"
        0b0011111 -> "По будням"
        0b1100000 -> "По выходным"
        else -> (0..6).filter { (days shr it) and 1 == 1 }.joinToString(", ") { DAY_SHORT[it] }
    }

    /** «через 7 ч 12 мин» */
    fun inText(ms: Long): String {
        val totalMin = ((ms + 59_999) / 60_000).coerceAtLeast(0)
        val d = totalMin / (60 * 24)
        val h = (totalMin / 60) % 24
        val m = totalMin % 60
        val parts = ArrayList<String>()
        if (d > 0) parts += "$d дн"
        if (h > 0) parts += "$h ч"
        if (m > 0 || parts.isEmpty()) parts += "$m мин"
        return "через " + parts.joinToString(" ")
    }

    // ---------------- таймер ----------------

    /** Время окончания запущенного таймера, 0 — не запущен. */
    fun timerEnd(ctx: Context) = prefs(ctx).getLong("timer_end", 0L)
    /** Остаток на паузе (мс), 0 — не на паузе. */
    fun timerPaused(ctx: Context) = prefs(ctx).getLong("timer_left", 0L)

    /** Полная длительность текущего таймера (для кольца прогресса). */
    fun timerTotal(ctx: Context) = prefs(ctx).getLong("timer_total", 0L)

    fun startTimer(ctx: Context, durationMs: Long, total: Long = durationMs) {
        val end = System.currentTimeMillis() + durationMs
        prefs(ctx).edit().putLong("timer_end", end).putLong("timer_left", 0L).putLong("timer_total", total).apply()
        set(ctx, KIND_TIMER, 0, end)
        if (total == durationMs) addRecent(ctx, durationMs)
    }

    /** Последние запущенные длительности (для быстрых кнопок), самые свежие первыми. */
    fun recentTimers(ctx: Context): List<Long> {
        val raw = prefs(ctx).getString("timer_recent", null)
        val list = raw?.split(',')?.mapNotNull { it.toLongOrNull() } ?: emptyList()
        val defaults = listOf(60_000L, 180_000L, 300_000L, 600_000L, 900_000L, 1_800_000L)
        return (list + defaults.filter { it !in list }).take(6)
    }

    private fun addRecent(ctx: Context, ms: Long) {
        val raw = prefs(ctx).getString("timer_recent", null)
        val list = raw?.split(',')?.mapNotNull { it.toLongOrNull() }?.toMutableList() ?: ArrayList()
        list.remove(ms)
        list.add(0, ms)
        prefs(ctx).edit().putString("timer_recent", list.take(6).joinToString(",")).apply()
    }

    fun pauseTimer(ctx: Context) {
        val end = timerEnd(ctx)
        if (end == 0L) return
        val left = (end - System.currentTimeMillis()).coerceAtLeast(1000L)
        prefs(ctx).edit().putLong("timer_end", 0L).putLong("timer_left", left).apply()
        cancelKind(ctx, KIND_TIMER, 0)
    }

    fun resumeTimer(ctx: Context) {
        val left = timerPaused(ctx)
        if (left > 0) startTimer(ctx, left, timerTotal(ctx).coerceAtLeast(left))
    }

    fun cancelTimer(ctx: Context) {
        prefs(ctx).edit().putLong("timer_end", 0L).putLong("timer_left", 0L).apply()
        cancelKind(ctx, KIND_TIMER, 0)
    }

    // ---------------- отложить ----------------

    fun snooze(ctx: Context, alarmId: Int, sound: String?) {
        val at = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L
        prefs(ctx).edit().putLong("snooze_at", at).putInt("snooze_id", alarmId).putString("snooze_sound", sound).apply()
        set(ctx, KIND_SNOOZE, alarmId, at)
    }

    fun clearSnooze(ctx: Context) {
        prefs(ctx).edit().putLong("snooze_at", 0L).apply()
        cancelKind(ctx, KIND_SNOOZE, 0)
    }

    fun snoozeSound(ctx: Context): String? = prefs(ctx).getString("snooze_sound", null)

    // ---------------- мелодия по умолчанию ----------------

    fun defaultSound(ctx: Context): String? = prefs(ctx).getString("default_sound", null)
    fun setDefaultSound(ctx: Context, path: String?) = prefs(ctx).edit().putString("default_sound", path).apply()

    // ---------------- AlarmManager ----------------

    fun schedule(ctx: Context, a: Alarm) {
        if (!a.enabled) return
        set(ctx, KIND_ALARM, a.id, nextTrigger(a))
    }

    fun cancel(ctx: Context, id: Int) = cancelKind(ctx, KIND_ALARM, id)

    /** Всё заново — после перезагрузки, смены времени или часового пояса. */
    fun rescheduleAll(ctx: Context) {
        for (a in load(ctx)) if (a.enabled) schedule(ctx, a) else cancel(ctx, a.id)
        val end = timerEnd(ctx)
        if (end > 0) set(ctx, KIND_TIMER, 0, end.coerceAtLeast(System.currentTimeMillis() + 1000))
        val sn = prefs(ctx).getLong("snooze_at", 0L)
        if (sn > 0) set(ctx, KIND_SNOOZE, prefs(ctx).getInt("snooze_id", 0), sn.coerceAtLeast(System.currentTimeMillis() + 1000))
    }

    fun canScheduleExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return false
        return am.canScheduleExactAlarms()
    }

    private fun requestCode(kind: String, id: Int) = when (kind) {
        KIND_TIMER -> 1
        KIND_SNOOZE -> 2
        else -> 1000 + id
    }

    private fun operation(ctx: Context, kind: String, id: Int, flags: Int): PendingIntent? {
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(ACTION).putExtra("kind", kind).putExtra("id", id)
        return PendingIntent.getBroadcast(ctx, requestCode(kind, id), i, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun set(ctx: Context, kind: String, id: Int, at: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val op = operation(ctx, kind, id, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val show = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        try {
            if (canScheduleExact(ctx)) {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), op)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op)
        }
    }

    private fun cancelKind(ctx: Context, kind: String, id: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val op = operation(ctx, kind, id, PendingIntent.FLAG_NO_CREATE) ?: return
        am.cancel(op)
        op.cancel()
    }
}

/** Секундомер: идёт по монотонным часам, продолжает считать в фоне. */
object StopwatchStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("xmb_clock", Context.MODE_PRIVATE)
    private fun now() = android.os.SystemClock.elapsedRealtime()

    fun running(ctx: Context) = prefs(ctx).getBoolean("sw_running", false)

    fun elapsed(ctx: Context): Long {
        val p = prefs(ctx)
        val acc = p.getLong("sw_acc", 0L)
        if (!p.getBoolean("sw_running", false)) return acc
        val start = p.getLong("sw_start", now())
        // после перезагрузки монотонные часы начинаются заново
        if (start > now()) return acc
        return acc + (now() - start)
    }

    fun start(ctx: Context) {
        if (running(ctx)) return
        prefs(ctx).edit().putBoolean("sw_running", true).putLong("sw_start", now()).apply()
    }

    fun pause(ctx: Context) {
        if (!running(ctx)) return
        val e = elapsed(ctx)
        prefs(ctx).edit().putBoolean("sw_running", false).putLong("sw_acc", e).apply()
    }

    fun reset(ctx: Context) {
        prefs(ctx).edit().putBoolean("sw_running", false).putLong("sw_acc", 0L).putString("sw_laps", "").apply()
    }

    /** Отметки кругов — общее время на момент круга. */
    fun laps(ctx: Context): List<Long> =
        prefs(ctx).getString("sw_laps", "")!!.split(',').mapNotNull { it.toLongOrNull() }

    fun lap(ctx: Context) {
        if (!running(ctx)) return
        val list = laps(ctx) + elapsed(ctx)
        prefs(ctx).edit().putString("sw_laps", list.takeLast(99).joinToString(",")).apply()
    }

    fun format(ms: Long): String {
        val cs = (ms / 10) % 100
        val s = (ms / 1000) % 60
        val m = (ms / 60_000) % 60
        val h = ms / 3_600_000
        return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d.%02d", h, m, s, cs)
        else String.format(java.util.Locale.US, "%02d:%02d.%02d", m, s, cs)
    }
}

/** Срабатывание будильника, таймера или «отложить». */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val kind = intent.getStringExtra("kind") ?: return
        val id = intent.getIntExtra("id", 0)
        var title = "Будильник"
        var sound: String? = null
        when (kind) {
            AlarmStore.KIND_ALARM -> {
                val a = AlarmStore.get(ctx, id) ?: return
                if (!a.enabled) return
                if (a.days == 0) {
                    a.enabled = false
                    AlarmStore.update(ctx, a)
                } else {
                    AlarmStore.schedule(ctx, a)
                }
                sound = a.sound
                title = "Будильник"
            }
            AlarmStore.KIND_TIMER -> {
                AlarmStore.cancelTimer(ctx)
                title = "Таймер"
            }
            AlarmStore.KIND_SNOOZE -> {
                sound = AlarmStore.snoozeSound(ctx)
                AlarmStore.clearSnooze(ctx)
                title = "Будильник"
            }
        }
        AlarmService.ring(ctx, title, sound ?: AlarmStore.defaultSound(ctx), if (kind == AlarmStore.KIND_TIMER) -1 else id)
    }
}
