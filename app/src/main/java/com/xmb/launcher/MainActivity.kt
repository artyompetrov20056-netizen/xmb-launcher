package com.xmb.launcher

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity(), XmbView.Listener {

    private lateinit var xmb: XmbView
    private lateinit var prefs: SharedPreferences
    lateinit var sounds: XmbSounds
        private set
    private var themeIndex = 0
    private var autoTheme = false
    private var themeItem: XmbItem? = null
    private var soundItem: XmbItem? = null
    private var lockItem: XmbItem? = null
    private var padItem: XmbItem? = null
    private var musicItem: XmbItem? = null
    private var musicVolItem: XmbItem? = null
    private lateinit var media: AppMedia
    private lateinit var music: XmbMusic
    private var pendingKey: String? = null
    private var timerItem: XmbItem? = null
    private var stopwatchItem: XmbItem? = null

    /** Обратный отсчёт таймера в подписи пункта, раз в секунду. */
    private val timerTick = object : Runnable {
        override fun run() {
            timerItem?.subtitle = timerSubtitle()
            stopwatchItem?.subtitle = stopwatchSubtitle()
            xmb.invalidate()
            if (AlarmStore.timerEnd(this@MainActivity) > 0 || StopwatchStore.running(this@MainActivity)) {
                handler.postDelayed(this, 1000)
            } else if (timerItem?.subtitle?.startsWith("Осталось") == true) {
                refreshMenu()
            }
        }
    }

    /** Подключение / отключение геймпада. */
    private val inputListener = object : android.hardware.input.InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = updateGamepad()
        override fun onInputDeviceRemoved(deviceId: Int) = updateGamepad()
        override fun onInputDeviceChanged(deviceId: Int) = updateGamepad()
    }
    private var reopenLockSetup = false
    private var apps: List<AppEntry>? = null
    private val favorites = LinkedHashSet<String>()
    /** Скрытые из меню приложения (ключи) и открыта ли сейчас папка «Скрытые приложения». */
    private val hidden = LinkedHashSet<String>()
    private var hiddenOpen = false
    private var loadGeneration = 0
    private val handler = Handler(Looper.getMainLooper())
    private val reloadRunnable = Runnable { reloadApps() }

    private val launcherApps: LauncherApps by lazy { getSystemService(LauncherApps::class.java) }

    /** Обновляем меню, когда приложение установили, удалили или обновили. */
    private val appsCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String?, user: UserHandle?) = scheduleReload()
        override fun onPackageAdded(packageName: String?, user: UserHandle?) = scheduleReload()
        override fun onPackageChanged(packageName: String?, user: UserHandle?) = scheduleReload()
        override fun onPackagesAvailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = scheduleReload()
        override fun onPackagesUnavailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = scheduleReload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("xmb", MODE_PRIVATE)
        themeIndex = prefs.getInt(PREF_THEME, 0).coerceIn(0, XmbTheme.ALL.lastIndex)
        autoTheme = prefs.getBoolean(PREF_THEME_AUTO, false)
        prefs.getString(PREF_FAVORITES, "")!!.split('\n').filter { it.isNotBlank() }.forEach { favorites += it }
        prefs.getString(PREF_HIDDEN, "")!!.split('\n').filter { it.isNotBlank() }.forEach { hidden += it }

        // Кнопки громкости на рабочем столе регулируют медиа — через этот поток идут звуки меню
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        sounds = XmbSounds()
        sounds.level = prefs.getInt(PREF_SOUND_LEVEL, if (prefs.getBoolean(PREF_SOUND, true)) 2 else 0)

        xmb = XmbView(this)
        xmb.listener = this
        xmb.sounds = sounds
        xmb.setTheme(XmbTheme.ALL[themeIndex], animate = false)
        xmb.setAutoTheme(autoTheme)
        xmb.padStyle = prefs.getInt(PREF_PAD_STYLE, PadIcons.STYLE_PS)

        media = AppMedia(this)
        music = XmbMusic()
        music.level = prefs.getInt(PREF_MUSIC_LEVEL, 2)
        music.setGlobal(media.globalMusic())
        xmb.backgroundProvider = { item, landscape -> item.key?.let { media.background(it, landscape) } }
        xmb.initialCategoryId = prefs.getString(PREF_CATEGORY, "game")
        xmb.setOnApplyWindowInsetsListener { _, insets ->
            xmb.applyInsets(insets)
            insets
        }
        setContentView(xmb)
        setupWindow()

        xmb.setCategories(buildCategories())
        xmb.requestFocus()

        try { launcherApps.registerCallback(appsCallback) } catch (_: Exception) { }
        try {
            getSystemService(android.hardware.input.InputManager::class.java)?.registerInputDeviceListener(inputListener, handler)
        } catch (_: Exception) { }
        updateGamepad()
        reloadApps()
        LockController.start(this)
    }

    override fun onDestroy() {
        try { launcherApps.unregisterCallback(appsCallback) } catch (_: Exception) { }
        try {
            getSystemService(android.hardware.input.InputManager::class.java)?.unregisterInputDeviceListener(inputListener)
        } catch (_: Exception) { }
        handler.removeCallbacks(reloadRunnable)
        sounds.release()
        music.release()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        sounds.start()
        music.resume()
        handler.removeCallbacks(timerTick)
        handler.post(timerTick)
        LockController.start(this)
        if (reopenLockSetup) {
            // Вернулись из системных настроек — показываем обновлённый список разрешений
            reopenLockSetup = false
            handler.postDelayed({ openLockPermissions() }, 350)
        }
    }

    override fun onPause() {
        sounds.stopSoon()
        music.pause()
        handler.removeCallbacks(timerTick)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) setupWindow()
    }

    /** Нажали «Домой», уже находясь на рабочем столе — закрываем окна и возвращаемся к началу списка. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        xmb.closeOverlay()
        if (hiddenOpen) { hiddenOpen = false; refreshMenu() }
        xmb.goToFirstItem()
    }

    /** «Назад» закрывает открытое окно, иначе возвращает к первому пункту. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                if (!xmb.hasOverlay() && hiddenOpen) closeHidden() else xmb.onBack()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Suppress("DEPRECATION")
    private fun setupWindow() {
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
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

    // =====================================================================
    // Список приложений и категории
    // =====================================================================

    private fun scheduleReload() {
        handler.removeCallbacks(reloadRunnable)
        handler.postDelayed(reloadRunnable, 600)
    }

    private fun reloadApps() {
        val gen = ++loadGeneration
        Thread {
            val loaded = try { AppRepository.load(applicationContext) } catch (_: Exception) { emptyList() }
            runOnUiThread {
                if (gen == loadGeneration && !isDestroyed) {
                    apps = loaded
                    refreshMenu()
                }
            }
        }.start()
    }

    private fun refreshMenu() = xmb.setCategories(buildCategories())

    @Suppress("DEPRECATION")
    private fun appVersion(): String =
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" }

    private fun themeName() = if (autoTheme) XmbTheme.AUTO_NAME else XmbTheme.ALL[themeIndex].name

    private fun buildCategories(): List<XmbCategory> {
        // скрытые приложения не попадают ни в одну категорию, избранное и поиск
        val list = apps?.filter { it.component.flattenToString() !in hidden }
        fun appsOf(filter: (AppEntry) -> Boolean): List<XmbItem> = when {
            list == null -> listOf(XmbItem("Загрузка…", glyph = Glyph.EMPTY))
            else -> list.filter(filter).map { appItem(it) }
                .ifEmpty { listOf(XmbItem("Нет приложений", "Здесь пока пусто", glyph = Glyph.EMPTY)) }
        }

        val theme = XmbItem(
            "Цвет темы", "Сейчас: ${themeName()}",
            glyph = Glyph.PALETTE,
            onClick = { it.openThemePicker() }
        )
        themeItem = theme

        val sound = XmbItem(
            "Звук меню", soundSubtitle(),
            glyph = Glyph.SOUND,
            onClick = { it.openSoundPicker() }
        )
        soundItem = sound

        val lock = XmbItem(
            "Экран блокировки XMB", lockSubtitle(),
            glyph = Glyph.LOCK,
            onClick = { it.openLockMenu() }
        )
        lockItem = lock

        val pad = XmbItem(
            "Кнопки геймпада", padSubtitle(),
            glyph = Glyph.GAME,
            onClick = { it.openPadStylePicker() }
        )
        padItem = pad

        val bgMusic = XmbItem(
            "Фоновая музыка", musicSubtitle(),
            glyph = Glyph.MUSIC,
            onClick = { it.openGlobalMusicMenu() }
        )
        musicItem = bgMusic
        val musicVol = XmbItem(
            "Громкость музыки", "Громкость: ${XmbMusic.LEVEL_NAMES[music.level]}",
            glyph = Glyph.SOUND,
            onClick = { it.openMusicVolumePicker() }
        )
        musicVolItem = musicVol

        val settings = listOf(
            settingsItem("Сеть Wi‑Fi", "Подключение к беспроводным сетям", Glyph.WIFI,
                Intent(Settings.ACTION_WIFI_SETTINGS)),
            settingsItem("Bluetooth", "Наушники, геймпады и другие устройства", Glyph.BLUETOOTH,
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS)),
            settingsItem("Экран", "Яркость, автоповорот, размер шрифта", Glyph.DISPLAY,
                Intent(Settings.ACTION_DISPLAY_SETTINGS)),
            settingsItem("Громкость", "Громкость и мелодии телефона", Glyph.SOUND,
                Intent(Settings.ACTION_SOUND_SETTINGS)),
            theme,
            sound,
            pad,
            bgMusic,
            musicVol,
            lock,
            settingsItem("Рабочий стол по умолчанию", "Выбрать, какой рабочий стол открывается кнопкой «Домой»", Glyph.HOME,
                Intent(Settings.ACTION_HOME_SETTINGS),
                Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)),
            settingsItem("Все настройки", "Системные настройки Android", Glyph.GEAR,
                Intent(Settings.ACTION_SETTINGS)),
            XmbItem("О рабочем столе XMB", "Версия ${appVersion()}", Glyph.INFO),
        )

        val favItems: List<XmbItem> = when (list) {
            null -> listOf(XmbItem("Загрузка…", glyph = Glyph.EMPTY))
            else -> {
                val byKey = list.associateBy { it.component.flattenToString() }
                favorites.mapNotNull { byKey[it] }.map { appItem(it) }.ifEmpty {
                    listOf(XmbItem("Пока пусто", "Удерживайте приложение 1 секунду и выберите «Добавить в избранное»", glyph = Glyph.EMPTY))
                }
            }
        }

        val search = listOf(
            XmbItem("Найти приложение", "Поиск по названию с экранной клавиатурой", Glyph.SEARCH,
                onClick = { it.openSearch() })
        )

        val exit = listOf(
            XmbItem("Выйти в обычный рабочий стол", "Открыть стандартный рабочий стол телефона", Glyph.HOME,
                onClick = { it.confirmExit() })
        )

        return listOf(
            XmbCategory("settings", "Настройки", Glyph.SETTINGS, settings),
            XmbCategory("alarm", "Будильник", Glyph.ALARM, buildAlarmItems()),
            XmbCategory("photo", "Фото", Glyph.PHOTO, appsOf { Kind.PHOTO in it.kinds }),
            XmbCategory("music", "Музыка", Glyph.MUSIC, appsOf { Kind.MUSIC in it.kinds }),
            XmbCategory("video", "Видео", Glyph.VIDEO, appsOf { Kind.VIDEO in it.kinds }),
            XmbCategory("game", "Игры", Glyph.GAME, appsOf { Kind.GAME in it.kinds }),
            XmbCategory("network", "Сеть", Glyph.NETWORK, appsOf { Kind.NETWORK in it.kinds }),
            XmbCategory("apps", "Приложения", Glyph.APPS, buildAppsItems(list)),
            XmbCategory("favorites", "Избранное", Glyph.FAVORITE, favItems),
            XmbCategory("search", "Поиск", Glyph.SEARCH, search, tapOpens = true),
            XmbCategory("exit", "Выход", Glyph.HOME, exit, tapOpens = true),
        )
    }

    /** Категория «Приложения»: обычный список или открытая папка «Скрытые приложения». */
    private fun buildAppsItems(visible: List<AppEntry>?): List<XmbItem> {
        val all = apps ?: return listOf(XmbItem("Загрузка…", glyph = Glyph.EMPTY))
        if (hiddenOpen) {
            val items = ArrayList<XmbItem>()
            items += XmbItem("Назад", "К списку приложений", Glyph.PREV, onClick = { it.closeHidden() })
            val hiddenApps = all.filter { it.component.flattenToString() in hidden }
            if (hiddenApps.isEmpty()) items += XmbItem("Пусто", "Скрытых приложений нет", glyph = Glyph.EMPTY)
            else hiddenApps.forEach { items += appItem(it) }
            return items
        }
        val items = ArrayList<XmbItem>()
        items += XmbItem(
            "Скрытые приложения",
            if (hidden.isEmpty()) "Пусто · дважды коснитесь экрана на приложении → «Скрыть из меню»" else "${hidden.size} шт.",
            Glyph.LOCK,
            onClick = { it.openHidden() }
        )
        (visible ?: emptyList()).forEach { items += appItem(it) }
        return items
    }

    fun openHidden() {
        hiddenOpen = true
        refreshMenu()
        xmb.goToFirstItem()
    }

    fun closeHidden() {
        hiddenOpen = false
        sounds.back()
        refreshMenu()
        xmb.goToFirstItem()
    }

    private fun appItem(e: AppEntry) = XmbItem(
        title = e.label,
        icon = e.icon,
        packageName = e.component.packageName,
        key = e.component.flattenToString(),
        onClick = { act ->
            act.sounds.launch()
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            act.startSafe(intent)
        }
    )

    private fun settingsItem(title: String, subtitle: String, glyph: Glyph, vararg intents: Intent) =
        XmbItem(title, subtitle, glyph, onClick = { act ->
            act.startSafe(*intents, Intent(Settings.ACTION_SETTINGS))
        })

    // =====================================================================
    // Действия
    // =====================================================================

    fun startSafe(vararg intents: Intent): Boolean {
        for (i in intents) {
            try {
                startActivity(Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: Exception) {
            }
        }
        Toast.makeText(this, "Не удалось открыть", Toast.LENGTH_SHORT).show()
        return false
    }

    /** Выбор цвета темы, включая «Переливающийся». */
    fun openThemePicker() {
        val names = XmbTheme.ALL.map { it.name } + "${XmbTheme.AUTO_NAME} — новый цвет каждые 2 минуты"
        val current = if (autoTheme) names.lastIndex else themeIndex
        xmb.showOverlay(DialogOverlay("Цвет темы", null, names, selected = current, checked = current) { pick ->
            if (pick == names.lastIndex) {
                autoTheme = true
            } else {
                autoTheme = false
                themeIndex = pick
                xmb.setTheme(XmbTheme.ALL[pick], animate = true)
            }
            xmb.setAutoTheme(autoTheme)
            prefs.edit().putInt(PREF_THEME, themeIndex).putBoolean(PREF_THEME_AUTO, autoTheme).apply()
            themeItem?.subtitle = "Сейчас: ${themeName()}"
        })
    }

    private fun soundSubtitle() = "Громкость: ${XmbSounds.LEVEL_NAMES[sounds.level]}"

    /** Громкость звуков меню — рулеткой: Выключен / Минимальная / Средняя / Высокая. */
    fun openSoundPicker() {
        val original = sounds.level
        xmb.showRoulette(
            XmbSounds.LEVEL_NAMES, original,
            onChange = { sounds.level = it },
            onDone = {
                sounds.level = it
                prefs.edit().putInt(PREF_SOUND_LEVEL, it).apply()
                soundItem?.subtitle = soundSubtitle()
            },
            onCancel = { sounds.level = original },
        )
    }

    // =====================================================================
    // Геймпад
    // =====================================================================

    private fun updateGamepad() {
        val connected = android.view.InputDevice.getDeviceIds().any { id ->
            val d = android.view.InputDevice.getDevice(id) ?: return@any false
            !d.isVirtual && (
                (d.sources and android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD ||
                    (d.sources and android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK
                )
        }
        xmb.setGamepadConnected(connected)
    }

    private fun padSubtitle() = PadIcons.STYLE_NAMES[xmb.padStyle]

    /** Стиль значков кнопок — рулеткой: PlayStation или Xbox. */
    fun openPadStylePicker() {
        val original = xmb.padStyle
        xmb.showRoulette(
            PadIcons.STYLE_NAMES, original,
            onChange = { xmb.padStyle = it },
            onDone = {
                xmb.padStyle = it
                prefs.edit().putInt(PREF_PAD_STYLE, it).apply()
                padItem?.subtitle = padSubtitle()
            },
            onCancel = { xmb.padStyle = original },
        )
    }

    override fun onItemOptions(item: XmbItem) = showAppActions(item, withOpen = true, back = null)

    override fun onOpenSearch() = openSearch()

    // =====================================================================
    // Фон и музыка приложений (как PIC1 и SND0 на PS3), общая фоновая музыка
    // =====================================================================

    /** Задержались на приложении — играет его мелодия (если есть), иначе общая. */
    // =====================================================================
    // Будильник и таймер
    // =====================================================================

    private fun two(v: Int) = String.format(Locale.getDefault(), "%02d", v)

    private fun buildAlarmItems(): List<XmbItem> {
        val items = ArrayList<XmbItem>()
        items += XmbItem("Новый будильник", "Выбрать время", Glyph.PLUS, onClick = { it.newAlarm() })
        val timer = XmbItem("Таймер", timerSubtitle(), Glyph.HOURGLASS, onClick = { it.openTimer() })
        timerItem = timer
        items += timer
        val sw = XmbItem("Секундомер", stopwatchSubtitle(), Glyph.TIMER, onClick = { it.openStopwatch() })
        stopwatchItem = sw
        items += sw
        for (a in AlarmStore.load(this)) {
            val state = if (a.enabled) {
                "Включён · " + AlarmStore.inText(AlarmStore.nextTrigger(a) - System.currentTimeMillis())
            } else "Выключен"
            items += XmbItem(
                "${two(a.hour)}:${two(a.minute)}",
                "${AlarmStore.daysText(a.days)} · $state",
                Glyph.ALARM,
                onClick = { it.openAlarmMenu(a.id) }
            )
        }
        items += XmbItem(
            "Мелодия по умолчанию",
            if (AlarmStore.defaultSound(this) != null) "Своя мелодия (MP3)" else "Системная мелодия будильника",
            Glyph.MUSIC,
            onClick = { it.openDefaultSoundMenu() }
        )
        return items
    }

    private fun fmtDuration(ms: Long): String {
        val total = ((ms + 999) / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total / 60) % 60
        val sec = total % 60
        return if (h > 0) "$h:${two(m.toInt())}:${two(sec.toInt())}" else "${two(m.toInt())}:${two(sec.toInt())}"
    }

    private fun timerSubtitle(): String {
        val end = AlarmStore.timerEnd(this)
        if (end > 0) return "Осталось " + fmtDuration(end - System.currentTimeMillis())
        val left = AlarmStore.timerPaused(this)
        if (left > 0) return "На паузе · " + fmtDuration(left)
        return "Не запущен"
    }

    /** Уведомления и показ поверх экрана нужны, чтобы будильник мог зазвонить. */
    private fun ensureAlarmPermissions() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2)
            return
        }
        val nm = getSystemService(android.app.NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 34 && nm != null && !nm.canUseFullScreenIntent() && !Settings.canDrawOverlays(this)) {
            handler.postDelayed({
                xmb.showOverlay(
                    DialogOverlay(
                        "Будильник", "Разрешите показывать будильник на весь экран — иначе он придёт только уведомлением.",
                        listOf("Открыть настройки", "Позже")
                    ) { pick ->
                        if (pick == 0) startSafe(
                            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")),
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                        )
                    }
                )
            }, 400)
        }
    }

    private fun toastNext(a: Alarm) {
        Toast.makeText(
            this, "Будильник сработает " + AlarmStore.inText(AlarmStore.nextTrigger(a) - System.currentTimeMillis()),
            Toast.LENGTH_LONG
        ).show()
    }

    fun newAlarm() {
        val hours = (0..23).map { two(it) }
        val mins = (0..59).map { two(it) }
        xmb.showOverlay(
            PickerOverlay("Новый будильник", listOf(hours, mins), intArrayOf(7, 0), listOf("часы", "минуты")) { r ->
                val a = AlarmStore.add(this, r[0], r[1])
                toastNext(a)
                ensureAlarmPermissions()
                refreshMenu()
            }
        )
    }

    fun openAlarmMenu(id: Int) {
        val a = AlarmStore.get(this, id) ?: return
        val options = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        options += if (a.enabled) "Выключить" else "Включить"
        actions += {
            a.enabled = !a.enabled
            AlarmStore.update(this, a)
            if (a.enabled) { toastNext(a); ensureAlarmPermissions() }
            refreshMenu()
        }
        options += "Изменить время"
        actions += {
            val hours = (0..23).map { two(it) }
            val mins = (0..59).map { two(it) }
            xmb.showOverlay(
                PickerOverlay("Время будильника", listOf(hours, mins), intArrayOf(a.hour, a.minute), listOf("часы", "минуты")) { r ->
                    a.hour = r[0]; a.minute = r[1]; a.enabled = true
                    AlarmStore.update(this, a)
                    toastNext(a)
                    refreshMenu()
                }
            )
        }
        options += "Повтор: " + AlarmStore.daysText(a.days)
        actions += {
            val init = BooleanArray(7) { (a.days shr it) and 1 == 1 }
            xmb.showOverlay(ChecklistOverlay("Повторять по дням", AlarmStore.DAY_FULL, init) { res ->
                var mask = 0
                for (i in 0..6) if (res[i]) mask = mask or (1 shl i)
                a.days = mask
                if (a.enabled) { AlarmStore.update(this, a); toastNext(a) } else AlarmStore.update(this, a)
                refreshMenu()
            })
        }
        options += if (a.sound != null) "Мелодия: своя — заменить MP3" else "Мелодия: выбрать MP3"
        actions += { pickFile(REQ_ALARM_SOUND, a.id.toString(), "audio/*") }
        if (a.sound != null) {
            options += "Мелодия по умолчанию"
            actions += {
                a.sound?.let { java.io.File(it).delete() }
                a.sound = null
                AlarmStore.update(this, a)
                refreshMenu()
            }
        }
        options += "Удалить будильник"
        actions += {
            a.sound?.let { java.io.File(it).delete() }
            AlarmStore.remove(this, a.id)
            Toast.makeText(this, "Будильник удалён", Toast.LENGTH_SHORT).show()
            refreshMenu()
        }
        xmb.showOverlay(DialogOverlay("Будильник ${two(a.hour)}:${two(a.minute)}", null, options) { actions[it]() })
    }

    fun openDefaultSoundMenu() {
        val has = AlarmStore.defaultSound(this) != null
        val options = ArrayList<String>()
        options += if (has) "Заменить MP3" else "Выбрать MP3"
        if (has) options += "Системная мелодия будильника"
        xmb.showOverlay(
            DialogOverlay("Мелодия по умолчанию", "Для таймера и будильников без своей мелодии.", options) { pick ->
                if (pick == 0) pickFile(REQ_DEFAULT_ALARM_SOUND, null, "audio/*")
                else {
                    AlarmStore.defaultSound(this)?.let { java.io.File(it).delete() }
                    AlarmStore.setDefaultSound(this, null)
                    refreshMenu()
                }
            }
        )
    }

    private fun restartTick() {
        handler.removeCallbacks(timerTick)
        handler.post(timerTick)
    }

    private fun stopwatchSubtitle(): String {
        val e = StopwatchStore.elapsed(this)
        return when {
            StopwatchStore.running(this) -> StopwatchStore.format(e) + " · идёт"
            e > 0 -> StopwatchStore.format(e) + " · пауза"
            else -> "Не запущен"
        }
    }

    fun openStopwatch() {
        val running = StopwatchStore.running(this)
        val e = StopwatchStore.elapsed(this)
        val options = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        options += if (running) "Пауза" else if (e > 0) "Продолжить" else "Старт"
        actions += { if (running) StopwatchStore.pause(this) else StopwatchStore.start(this) }
        if (running) { options += "Круг"; actions += { StopwatchStore.lap(this) } }
        if (e > 0) { options += "Сбросить"; actions += { StopwatchStore.reset(this) } }
        options += "Открыть на весь экран"
        actions += { ClockActivity.open(this, ClockActivity.TAB_STOPWATCH) }
        xmb.showOverlay(DialogOverlay("Секундомер", stopwatchSubtitle(), options) { pick ->
            actions[pick]()
            stopwatchItem?.subtitle = stopwatchSubtitle()
            restartTick()
        })
    }

    fun openTimer() {
        val end = AlarmStore.timerEnd(this)
        val paused = AlarmStore.timerPaused(this)
        if (end == 0L && paused == 0L) {
            xmb.showOverlay(DialogOverlay("Таймер", null, listOf("Задать время", "Открыть на весь экран")) { pick ->
                if (pick == 0) showTimerPicker() else ClockActivity.open(this, ClockActivity.TAB_TIMER)
            })
            return
        }
        val options = listOf(if (end > 0) "Пауза" else "Продолжить", "Сбросить", "Новый таймер", "Открыть на весь экран")
        xmb.showOverlay(DialogOverlay("Таймер", timerSubtitle(), options) { pick ->
            when (pick) {
                0 -> if (end > 0) AlarmStore.pauseTimer(this) else AlarmStore.resumeTimer(this)
                1 -> AlarmStore.cancelTimer(this)
                2 -> { AlarmStore.cancelTimer(this); showTimerPicker() }
                3 -> ClockActivity.open(this, ClockActivity.TAB_TIMER)
            }
            timerItem?.subtitle = timerSubtitle()
            handler.removeCallbacks(timerTick)
            handler.post(timerTick)
        })
    }

    private fun showTimerPicker() {
        val hours = (0..23).map { it.toString() }
        val sixty = (0..59).map { two(it) }
        val lastMin = prefs.getInt("timer_last_min", 5)
        xmb.showOverlay(
            PickerOverlay("Таймер", listOf(hours, sixty, sixty), intArrayOf(0, lastMin, 0), listOf("часы", "минуты", "секунды")) { r ->
                val ms = (r[0] * 3600L + r[1] * 60L + r[2]) * 1000L
                if (ms <= 0L) {
                    Toast.makeText(this, "Задайте время больше нуля", Toast.LENGTH_SHORT).show()
                } else {
                    prefs.edit().putInt("timer_last_min", r[1]).apply()
                    AlarmStore.startTimer(this, ms)
                    ensureAlarmPermissions()
                    Toast.makeText(this, "Таймер запущен: " + fmtDuration(ms), Toast.LENGTH_SHORT).show()
                    timerItem?.subtitle = timerSubtitle()
                    handler.removeCallbacks(timerTick)
                    handler.post(timerTick)
                }
            }
        )
    }

    override fun onHover(item: XmbItem?) {
        music.setHover(item?.key?.let { media.music(it) })
    }

    private fun musicSubtitle() = if (media.globalMusic() != null) "Своя мелодия" else "Не выбрана"

    fun openGlobalMusicMenu() {
        val has = media.globalMusic() != null
        val options = ArrayList<String>()
        options += if (has) "Заменить MP3" else "Выбрать MP3"
        if (has) options += "Убрать музыку"
        xmb.showOverlay(
            DialogOverlay("Фоновая музыка", "Играет на рабочем столе XMB. Мелодия приложения на время её заменяет.", options) { pick ->
                if (pick == 0) pickFile(REQ_GLOBAL_MUSIC, null, "audio/*")
                else {
                    media.removeGlobalMusic()
                    music.setGlobal(null)
                    musicItem?.subtitle = musicSubtitle()
                }
            }
        )
    }

    fun openMusicVolumePicker() {
        val original = music.level
        xmb.showRoulette(
            XmbMusic.LEVEL_NAMES, original,
            onChange = { music.level = it },
            onDone = {
                music.level = it
                prefs.edit().putInt(PREF_MUSIC_LEVEL, it).apply()
                musicVolItem?.subtitle = "Громкость: ${XmbMusic.LEVEL_NAMES[it]}"
            },
            onCancel = { music.level = original },
        )
    }

    private fun pickFile(request: Int, key: String?, mime: String) {
        pendingKey = key
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mime)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, request)
        } catch (_: Exception) {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType(mime), request)
            } catch (_: Exception) {
                Toast.makeText(this, "Не удалось открыть выбор файла", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        val key = pendingKey
        Toast.makeText(this, "Сохраняю…", Toast.LENGTH_SHORT).show()
        Thread {
            val ok = when (requestCode) {
                REQ_BG -> key != null && media.saveBackground(uri, key, landscape = false)
                REQ_BG_LAND -> key != null && media.saveBackground(uri, key, landscape = true)
                REQ_APP_MUSIC -> key != null && media.saveMusic(uri, key)
                REQ_GLOBAL_MUSIC -> media.saveMusic(uri, null)
                REQ_ALARM_SOUND -> {
                    val id = key?.toIntOrNull()
                    val path = media.saveAlarmSound(uri, "a$id")
                    val a = if (id != null) AlarmStore.get(this, id) else null
                    if (path != null && a != null) { a.sound = path; AlarmStore.update(this, a); true } else false
                }
                REQ_DEFAULT_ALARM_SOUND -> {
                    val path = media.saveAlarmSound(uri, "default")
                    if (path != null) { AlarmStore.setDefaultSound(this, path); true } else false
                }
                else -> false
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (!ok) {
                    Toast.makeText(this, "Не удалось сохранить файл", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                when (requestCode) {
                    REQ_ALARM_SOUND, REQ_DEFAULT_ALARM_SOUND -> {
                        Toast.makeText(this, "Мелодия будильника установлена", Toast.LENGTH_SHORT).show()
                        refreshMenu()
                    }
                    REQ_GLOBAL_MUSIC -> {
                        music.setGlobal(media.globalMusic())
                        musicItem?.subtitle = musicSubtitle()
                        Toast.makeText(this, "Фоновая музыка установлена", Toast.LENGTH_SHORT).show()
                    }
                    REQ_APP_MUSIC -> {
                        Toast.makeText(this, "Музыка назначена — задержитесь на приложении", Toast.LENGTH_SHORT).show()
                        xmb.refreshHover()
                    }
                    else -> {
                        Toast.makeText(this, "Фон назначен — задержитесь на приложении", Toast.LENGTH_SHORT).show()
                        xmb.refreshHover()
                    }
                }
            }
        }.start()
    }

    // =====================================================================
    // Экран блокировки
    // =====================================================================

    private fun lockSubtitle() = if (LockController.isEnabled(this)) "Включён" else "Выключен"

    fun openLockMenu() {
        val on = LockController.isEnabled(this)
        xmb.showOverlay(
            DialogOverlay(
                "Экран блокировки XMB",
                "Показывается поверх системного. Пароль или отпечаток вводится один раз — в системном окне после свайпа вверх.",
                listOf(if (on) "Выключить" else "Включить", "Разрешения", "Посмотреть, как выглядит")
            ) { pick ->
                when (pick) {
                    0 -> if (on) {
                        LockController.setEnabled(this, false)
                        Toast.makeText(this, "Экран блокировки XMB выключен", Toast.LENGTH_SHORT).show()
                    } else {
                        enableLock()
                    }
                    1 -> openLockPermissions()
                    2 -> startSafe(Intent(this, LockActivity::class.java))
                }
                lockItem?.subtitle = lockSubtitle()
            }
        )
    }

    private fun enableLock() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        LockController.setEnabled(this, true)
        lockItem?.subtitle = lockSubtitle()
        Toast.makeText(this, "Экран блокировки XMB включён", Toast.LENGTH_SHORT).show()
        if (!Settings.canDrawOverlays(this) || !LockNotificationService.isEnabled(this)) {
            handler.postDelayed({ openLockPermissions() }, 400)
        }
    }

    private fun isXiaomi(): Boolean {
        val m = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        return "xiaomi" in m || "redmi" in m || "poco" in m
    }

    /** Пошаговая выдача разрешений. ✓ — уже выдано. */
    fun openLockPermissions() {
        fun mark(ok: Boolean) = if (ok) "✓  " else "○  "
        val overlay = Settings.canDrawOverlays(this)
        val notif = LockNotificationService.isEnabled(this)
        val options = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()

        options += mark(overlay) + "Поверх других приложений"
        actions += {
            startSafe(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        options += mark(notif) + "Доступ к уведомлениям"
        actions += {
            val list = ArrayList<Intent>()
            if (Build.VERSION.SDK_INT >= 30) {
                list += Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, LockNotificationService.component(this).flattenToString())
            }
            list += Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startSafe(*list.toTypedArray())
        }
        if (isXiaomi()) {
            options += "Xiaomi: «Экран блокировки» и «Всплывающие окна»"
            actions += {
                startSafe(
                    Intent("miui.intent.action.APP_PERM_EDITOR")
                        .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                        .putExtra("extra_pkgname", packageName),
                    Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", packageName),
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
        }
        options += "Автозапуск и батарея без ограничений"
        actions += {
            startSafe(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }

        xmb.showOverlay(
            DialogOverlay(
                "Разрешения", 
                "Выдайте по очереди. Если «Доступ к уведомлениям» не включается: О приложении → ⋮ → «Разрешить ограниченные настройки».",
                options
            ) { pick ->
                reopenLockSetup = true
                actions[pick]()
            }
        )
    }

    /** Поиск: выбранное приложение не запускается сразу, а открывает меню действий. */
    fun openSearch() {
        val all = apps?.filter { it.component.flattenToString() !in hidden }?.map { appItem(it) } ?: emptyList()
        lateinit var search: SearchOverlay
        search = SearchOverlay(all) { item ->
            showAppActions(item, withOpen = true, back = { xmb.showOverlay(search) })
        }
        xmb.showOverlay(search)
    }

    /**
     * Меню действий с приложением: (Открыть), Добавить/Убрать из избранного, Удалить.
     * back — куда вернуться после отмены или после изменения избранного (например, в поиск).
     */
    private fun showAppActions(item: XmbItem, withOpen: Boolean, back: (() -> Unit)?) {
        val key = item.key ?: return
        val pkg = item.packageName ?: return
        val isFav = key in favorites
        val options = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (withOpen) {
            options += "Открыть"
            actions += { item.onClick?.invoke(this) }
        }
        options += if (isFav) "Убрать из избранного" else "Добавить в избранное"
        actions += {
            sounds.ok()
            if (isFav) favorites.remove(key) else favorites.add(key)
            prefs.edit().putString(PREF_FAVORITES, favorites.joinToString("\n")).apply()
            Toast.makeText(this, if (isFav) "Убрано из избранного" else "Добавлено в избранное", Toast.LENGTH_SHORT).show()
            refreshMenu()
            back?.invoke()
        }
        val isHidden = key in hidden
        options += if (isHidden) "Показать в меню" else "Скрыть из меню"
        actions += {
            sounds.ok()
            if (isHidden) hidden.remove(key) else { hidden.add(key); favorites.remove(key) }
            prefs.edit()
                .putString(PREF_HIDDEN, hidden.joinToString("\n"))
                .putString(PREF_FAVORITES, favorites.joinToString("\n"))
                .apply()
            Toast.makeText(
                this,
                if (isHidden) "Приложение снова в меню" else "Скрыто. Найти: Приложения → Скрытые приложения",
                Toast.LENGTH_LONG
            ).show()
            if (hiddenOpen && hidden.isEmpty()) hiddenOpen = false
            refreshMenu()
            back?.invoke()
        }
        options += if (media.hasBackground(key)) "Заменить фон" else "Выбрать фон"
        actions += { pickFile(REQ_BG, key, "image/*") }
        options += "Отдельный фон для горизонтального режима"
        actions += { pickFile(REQ_BG_LAND, key, "image/*") }
        if (media.hasBackground(key)) {
            options += "Убрать фон"
            actions += {
                sounds.ok()
                media.removeBackground(key)
                xmb.refreshHover()
                back?.invoke()
            }
        }
        options += if (media.music(key) != null) "Заменить музыку (MP3)" else "Выбрать музыку (MP3)"
        actions += { pickFile(REQ_APP_MUSIC, key, "audio/*") }
        if (media.music(key) != null) {
            options += "Убрать музыку"
            actions += {
                sounds.ok()
                media.removeMusic(key)
                music.setHover(null)
                xmb.refreshHover()
                back?.invoke()
            }
        }
        options += "Удалить приложение"
        actions += {
            sounds.ok()
            startSafe(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            )
        }
        xmb.showOverlay(DialogOverlay(item.title, null, options, pickSound = false, onCancel = back) { actions[it]() })
    }

    fun confirmExit() {
        xmb.showOverlay(
            DialogOverlay(
                "Выход", "Действительно хотите выйти в обычный рабочий стол?",
                listOf("Да", "Нет"), selected = 1
            ) { pick -> if (pick == 0) exitToStockHome() }
        )
    }

    /** Открывает стандартный рабочий стол телефона (системный лаунчер). */
    private fun exitToStockHome() {
        @Suppress("DEPRECATION")
        val homes = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
        ).filter {
            val ai = it.activityInfo
            ai != null && ai.packageName != packageName && !ai.name.contains("FallbackHome")
        }
        val pick = homes.maxWithOrNull(
            compareBy<ResolveInfo>(
                { if ((it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) 1 else 0 },
                { it.priority }
            )
        )
        if (pick == null) {
            startSafe(Intent(Settings.ACTION_HOME_SETTINGS), Intent(Settings.ACTION_SETTINGS))
            return
        }
        startSafe(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setClassName(pick.activityInfo.packageName, pick.activityInfo.name)
        )
    }

    override fun onItemActivated(item: XmbItem) {
        item.onClick?.invoke(this)
    }

    /** Удержание приложения — меню: добавить/убрать из избранного, удалить. */
    override fun onItemLongPress(item: XmbItem) {
        showAppActions(item, withOpen = false, back = null)
    }

    override fun onCategorySelected(category: XmbCategory) {
        prefs.edit().putString(PREF_CATEGORY, category.id).apply()
    }

    companion object {
        private const val PREF_THEME = "theme"
        private const val PREF_THEME_AUTO = "theme_auto"
        private const val PREF_CATEGORY = "category"
        private const val PREF_SOUND = "sound"
        private const val PREF_SOUND_LEVEL = "sound_level"
        private const val PREF_FAVORITES = "favorites"
        private const val PREF_HIDDEN = "hidden_apps"
        private const val PREF_PAD_STYLE = "pad_style"
        private const val PREF_MUSIC_LEVEL = "music_level"
        private const val REQ_BG = 101
        private const val REQ_BG_LAND = 102
        private const val REQ_APP_MUSIC = 103
        private const val REQ_GLOBAL_MUSIC = 104
        private const val REQ_ALARM_SOUND = 105
        private const val REQ_DEFAULT_ALARM_SOUND = 106
    }
}
