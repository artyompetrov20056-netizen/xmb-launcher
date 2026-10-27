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

class MainActivity : Activity(), XmbView.Listener {

    private lateinit var xmb: XmbView
    private lateinit var prefs: SharedPreferences
    lateinit var sounds: XmbSounds
        private set
    private var themeIndex = 0
    private var autoTheme = false
    private var themeItem: XmbItem? = null
    private var soundItem: XmbItem? = null
    private var apps: List<AppEntry>? = null
    private val favorites = LinkedHashSet<String>()
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

        // Кнопки громкости на рабочем столе регулируют медиа — через этот поток идут звуки меню
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        sounds = XmbSounds(this)
        sounds.level = prefs.getInt(PREF_SOUND_LEVEL, if (prefs.getBoolean(PREF_SOUND, true)) 2 else 0)

        xmb = XmbView(this)
        xmb.listener = this
        xmb.sounds = sounds
        xmb.setTheme(XmbTheme.ALL[themeIndex], animate = false)
        xmb.setAutoTheme(autoTheme)
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
        reloadApps()
    }

    override fun onDestroy() {
        try { launcherApps.unregisterCallback(appsCallback) } catch (_: Exception) { }
        handler.removeCallbacks(reloadRunnable)
        sounds.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) setupWindow()
    }

    /** Нажали «Домой», уже находясь на рабочем столе — закрываем окна и возвращаемся к началу списка. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        xmb.closeOverlay()
        xmb.goToFirstItem()
    }

    /** «Назад» закрывает открытое окно, иначе возвращает к первому пункту. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) xmb.onBack()
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
        val list = apps
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
            XmbCategory("photo", "Фото", Glyph.PHOTO, appsOf { Kind.PHOTO in it.kinds }),
            XmbCategory("music", "Музыка", Glyph.MUSIC, appsOf { Kind.MUSIC in it.kinds }),
            XmbCategory("video", "Видео", Glyph.VIDEO, appsOf { Kind.VIDEO in it.kinds }),
            XmbCategory("game", "Игры", Glyph.GAME, appsOf { Kind.GAME in it.kinds }),
            XmbCategory("network", "Сеть", Glyph.NETWORK, appsOf { Kind.NETWORK in it.kinds }),
            XmbCategory("apps", "Приложения", Glyph.APPS, appsOf { true }),
            XmbCategory("favorites", "Избранное", Glyph.FAVORITE, favItems),
            XmbCategory("search", "Поиск", Glyph.SEARCH, search, tapOpens = true),
            XmbCategory("exit", "Выход", Glyph.HOME, exit, tapOpens = true),
        )
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

    /** Поиск: выбранное приложение не запускается сразу, а открывает меню действий. */
    fun openSearch() {
        val all = apps?.map { appItem(it) } ?: emptyList()
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
    }
}
