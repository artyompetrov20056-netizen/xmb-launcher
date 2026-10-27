package com.xmb.launcher

import android.graphics.Bitmap
import android.graphics.Color

/** Одна иконка в списке под категорией (приложение или пункт настроек). */
class XmbItem(
    val title: String,
    var subtitle: String = "",
    val glyph: Glyph? = null,
    val icon: Bitmap? = null,
    val packageName: String? = null,
    /** Уникальный ключ приложения (пакет/активность) — для избранного. */
    val key: String? = null,
    val onClick: ((MainActivity) -> Unit)? = null,
    /** Своя картинка фона при наведении (например PIC1 игры PSP). */
    val pic: java.io.File? = null,
)

/** Категория в главной линейке (Настройки, Музыка, Игры...). */
class XmbCategory(
    val id: String,
    val title: String,
    val glyph: Glyph,
    val items: List<XmbItem>,
    /** Нажатие на саму иконку категории сразу открывает её первый пункт (Поиск, Выход). */
    val tapOpens: Boolean = false,
)

/** Цветовая тема фона, как в PS3. */
class XmbTheme(val name: String, val top: Int, val mid: Int, val bottom: Int) {
    companion object {
        private fun c(v: Long) = v.toInt()

        val ALL = listOf(
            XmbTheme("Синий", c(0xFF051A4A), c(0xFF2A78D0), c(0xFF082A66)),
            XmbTheme("Фиолетовый", c(0xFF20173F), c(0xFF7D6BC0), c(0xFF2E2360)),
            XmbTheme("Зелёный", c(0xFF082E14), c(0xFF3E9A4C), c(0xFF0E3C22)),
            XmbTheme("Красный", c(0xFF3A0707), c(0xFFC23A2C), c(0xFF4E0E0E)),
            XmbTheme("Оранжевый", c(0xFF3E1D00), c(0xFFD9842C), c(0xFF4E2A04)),
            XmbTheme("Розовый", c(0xFF3A0E2C), c(0xFFD06AA6), c(0xFF4C163C)),
            XmbTheme("Бирюзовый", c(0xFF033034), c(0xFF2AA4A6), c(0xFF074048)),
            XmbTheme("Золотой", c(0xFF2E2404), c(0xFFC9A63A), c(0xFF3A2E08)),
            XmbTheme("Тёмный", c(0xFF040507), c(0xFF2B2F37), c(0xFF0A0B0F)),
        )

        /** Порядок цветов для режима «Переливающийся» (без тёмной темы). */
        val CYCLE = ALL.filter { it.name != "Тёмный" }

        const val AUTO_NAME = "Переливающийся"

        fun previewColor(i: Int) = ALL[i % ALL.size].mid or Color.BLACK
    }
}
