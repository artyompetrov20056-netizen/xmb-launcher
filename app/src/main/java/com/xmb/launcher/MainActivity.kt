package com.xmb.launcher

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.LauncherApps
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
    private var themeIndex = 0
    private var themeItem: XmbItem? = null
    private var apps: List<AppEntry>? = null
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

        xmb = XmbView(this)
        xmb.listener = this
        xmb.setTheme(XmbTheme.ALL[themeIndex], animate = false)
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
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) setupWindow()
    }

    /** Нажали «Домой», уже находясь на рабочем столе — возвращаемся к началу списка. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        xmb.goToFirstItem()
    }

    /** «Назад» на рабочем столе ничего не закрывает, только возвращает к первому пункту. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) xmb.goToFirstItem()
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
    // Список приложений
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
                    xmb.setCategories(buildCategories())
                }
            }
        }.start()
    }

    private fun buildCategories(): List<XmbCategory> {
        val list = apps
        fun appsOf(filter: (AppEntry) -> Boolean): List<XmbItem> = when {
            list == null -> listOf(XmbItem("Загрузка…", glyph = Glyph.EMPTY))
            else -> list.filter(filter).map { appItem(it) }
                .ifEmpty { listOf(XmbItem("Нет приложений", "Здесь пока пусто", glyph = Glyph.EMPTY)) }
        }

        val theme = XmbItem(
            "Цвет темы", "Сейчас: ${XmbTheme.ALL[themeIndex].name} · нажмите, чтобы сменить",
            glyph = Glyph.PALETTE,
            onClick = { it.cycleTheme() }
        )
        themeItem = theme

        val settings = listOf(
            settingsItem("Сеть Wi‑Fi", "Подключение к беспроводным сетям", Glyph.WIFI,
                Intent(Settings.ACTION_WIFI_SETTINGS)),
            settingsItem("Bluetooth", "Наушники, геймпады и другие устройства", Glyph.BLUETOOTH,
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS)),
            settingsItem("Экран", "Яркость, автоповорот, размер шрифта", Glyph.DISPLAY,
                Intent(Settings.ACTION_DISPLAY_SETTINGS)),
            settingsItem("Звук", "Громкость и мелодии", Glyph.SOUND,
                Intent(Settings.ACTION_SOUND_SETTINGS)),
            theme,
            settingsItem("Рабочий стол по умолчанию", "Вернуть стандартный рабочий стол", Glyph.HOME,
                Intent(Settings.ACTION_HOME_SETTINGS),
                Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)),
            settingsItem("Все настройки", "Системные настройки Android", Glyph.GEAR,
                Intent(Settings.ACTION_SETTINGS)),
        )

        return listOf(
            XmbCategory("settings", "Настройки", Glyph.SETTINGS, settings),
            XmbCategory("photo", "Фото", Glyph.PHOTO, appsOf { Kind.PHOTO in it.kinds }),
            XmbCategory("music", "Музыка", Glyph.MUSIC, appsOf { Kind.MUSIC in it.kinds }),
            XmbCategory("video", "Видео", Glyph.VIDEO, appsOf { Kind.VIDEO in it.kinds }),
            XmbCategory("game", "Игры", Glyph.GAME, appsOf { Kind.GAME in it.kinds }),
            XmbCategory("network", "Сеть", Glyph.NETWORK, appsOf { Kind.NETWORK in it.kinds }),
            XmbCategory("apps", "Приложения", Glyph.APPS, appsOf { true }),
        )
    }

    private fun appItem(e: AppEntry) = XmbItem(
        title = e.label,
        icon = e.icon,
        packageName = e.component.packageName,
        onClick = { act ->
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

    fun cycleTheme() {
        themeIndex = (themeIndex + 1) % XmbTheme.ALL.size
        prefs.edit().putInt(PREF_THEME, themeIndex).apply()
        xmb.setTheme(XmbTheme.ALL[themeIndex], animate = true)
        themeItem?.subtitle = "Сейчас: ${XmbTheme.ALL[themeIndex].name} · нажмите, чтобы сменить"
    }

    override fun onItemActivated(item: XmbItem) {
        item.onClick?.invoke(this)
    }

    /** Долгое нажатие на приложение — его системная страница (там же «Удалить»). */
    override fun onItemLongPress(item: XmbItem) {
        val pkg = item.packageName ?: return
        startSafe(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
    }

    override fun onCategorySelected(category: XmbCategory) {
        prefs.edit().putString(PREF_CATEGORY, category.id).apply()
    }

    companion object {
        private const val PREF_THEME = "theme"
        private const val PREF_CATEGORY = "category"
    }
}
