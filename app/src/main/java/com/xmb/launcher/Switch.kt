package com.xmb.launcher

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import java.io.File
import java.security.MessageDigest
import java.text.Collator
import kotlin.math.max

/**
 * Игры Nintendo Switch: сканирует выбранную папку с играми (nsp, xci, nsz, xcz, nro)
 * и запускает их в любом установленном эмуляторе Switch.
 *
 * Эмуляторы ищутся сами: у всех форков yuzu (Eden, Citron, Sudachi, Suyu, Torzu, Yuzu…)
 * и у Skyline/Strato есть экран EmulationActivity, который принимает файл игры.
 * Поэтому подхватываются и новые сборки/форки с другим названием пакета.
 *
 * Обложки: картинка с тем же именем, что у игры (Игра.png / .jpg / .webp),
 * рядом с игрой или в подпапке covers. Фон: Игра-bg.jpg (или в подпапке backgrounds).
 */
object Switch {
    private const val ACTION_NFC = "android.nfc.action.TECH_DISCOVERED"
    private val GAME_EXT = setOf("nsp", "xci", "nsz", "xcz", "nro")
    private val IMG_EXT = setOf("png", "jpg", "jpeg", "webp")
    /** Названия семейств эмуляторов Switch — по ним отличаем их от Dolphin, Citra и прочих. */
    private val FAMILY = Regex("yuzu|suyu|sudachi|citron|eden|torzu|uzuy|sumi|skyline|strato|kenji|ryujinx", RegexOption.IGNORE_CASE)

    class Emu(val label: String, val pkg: String, val activity: String, val action: String)

    class Game(val docId: String, val title: String, val file: String, val icon: Bitmap?, val pic: File?)

    @Volatile var games: List<Game> = emptyList()
        private set
    @Volatile var emus: List<Emu> = emptyList()
        private set
    /** Что не так, если игр нет (для подписи пункта). */
    @Volatile var status = ""
        private set
    @Volatile private var loading = false
    private var lastSig = ""
    private val iconCache = HashMap<String, Bitmap?>()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("xmb_switch", Context.MODE_PRIVATE)

    fun tree(ctx: Context): Uri? = prefs(ctx).getString("tree", null)?.let { Uri.parse(it) }

    fun setTree(ctx: Context, uri: Uri?) {
        prefs(ctx).edit().apply { if (uri == null) remove("tree") else putString("tree", uri.toString()) }.apply()
        synchronized(iconCache) { iconCache.clear() }
        lastSig = ""
    }

    /** Эмулятор по умолчанию (пакет) или null — «спрашивать». */
    fun defaultEmu(ctx: Context): String? = prefs(ctx).getString("default", null)
    fun setDefaultEmu(ctx: Context, pkg: String?) {
        prefs(ctx).edit().apply { if (pkg == null) remove("default") else putString("default", pkg) }.apply()
    }

    /** Эмулятор, выбранный для конкретной игры. */
    fun gameEmu(ctx: Context, g: Game): String? = prefs(ctx).getString("g:" + g.docId, null)
    fun setGameEmu(ctx: Context, g: Game, pkg: String?) {
        prefs(ctx).edit().apply { if (pkg == null) remove("g:" + g.docId) else putString("g:" + g.docId, pkg) }.apply()
    }

    fun emuLabel(pkg: String?): String? = emus.firstOrNull { it.pkg == pkg }?.label

    /** Понятный путь выбранной папки для подписи. */
    fun treeLabel(uri: Uri?): String = Ppsspp.treeLabel(uri)

    // ---------------- поиск эмуляторов ----------------

    fun findEmulators(ctx: Context): List<Emu> {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val launchable = try {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .mapNotNull { it.activityInfo?.packageName }.toSet()
        } catch (_: Exception) { emptySet() }
        val out = ArrayList<Emu>()
        for (pkg in launchable) {
            @Suppress("DEPRECATION")
            val info = try { pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES) } catch (_: Throwable) { continue }
            val acts = info.activities ?: continue
            val emu = acts.firstOrNull { a ->
                a.exported && a.name.endsWith("EmulationActivity") && (FAMILY.containsMatchIn(a.name) || FAMILY.containsMatchIn(pkg))
            } ?: continue
            // Оригинальный yuzu и Suyu ждут игру с этим «NFC»-действием (так их запускает и ES-DE)
            val action = if (pkg.startsWith("org.yuzu") || pkg.contains("suyu")) ACTION_NFC else Intent.ACTION_VIEW
            val label = try { info.applicationInfo?.loadLabel(pm)?.toString() } catch (_: Exception) { null } ?: pkg
            out += Emu(label, pkg, emu.name, action)
        }
        val collator = Collator.getInstance()
        return out.sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    // ---------------- сканирование папки ----------------

    fun refresh(ctx: Context, done: () -> Unit) {
        if (loading) return
        loading = true
        val app = ctx.applicationContext
        Thread {
            try {
                val e = findEmulators(app)
                val list = load(app)
                val sig = status + "#" + e.joinToString { it.pkg } + "#" +
                    list.joinToString("|") { it.docId + ":" + it.title + ":" + (it.icon != null) + ":" + (it.pic != null) }
                if (sig != lastSig) {
                    lastSig = sig
                    emus = e
                    games = list
                    Handler(Looper.getMainLooper()).post(done)
                }
            } catch (_: Throwable) {
            } finally {
                loading = false
            }
        }.start()
    }

    private class Doc(val id: String, val name: String, val dir: Boolean, val modified: Long)

    private fun children(ctx: Context, tree: Uri, parentId: String): List<Doc> {
        val q = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val out = ArrayList<Doc>()
        ctx.contentResolver.query(q, arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        ), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (name.startsWith(".")) continue
                out += Doc(c.getString(0), name, c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                    if (c.isNull(3)) 0L else c.getLong(3))
            }
        }
        return out
    }

    private fun load(ctx: Context): List<Game> {
        val tree = tree(ctx) ?: run { status = "Выберите папку с играми Switch"; return emptyList() }
        val root = try { DocumentsContract.getTreeDocumentId(tree) } catch (_: Exception) {
            status = "Папка недоступна — выберите её заново"; return emptyList()
        }
        val gameDocs = ArrayList<Doc>()
        val images = HashMap<String, Doc>()   // «имя без расширения» в нижнем регистре → картинка
        fun walk(id: String, depth: Int) {
            val kids = try { children(ctx, tree, id) } catch (_: Exception) { return }
            for (d in kids) {
                val ext = d.name.substringAfterLast('.', "").lowercase()
                when {
                    d.dir -> if (depth < 4) walk(d.id, depth + 1)
                    ext in GAME_EXT -> gameDocs += d
                    ext in IMG_EXT -> images.putIfAbsent(d.name.substringBeforeLast('.').lowercase(), d)
                }
            }
        }
        walk(root, 0)
        if (gameDocs.isEmpty()) { status = "В папке нет игр (nsp, xci, nsz, xcz, nro)"; return emptyList() }

        // Обновление и DLC часто лежат рядом с игрой отдельными nsp — их не показываем как игры
        val baseGames = gameDocs.filterNot { isUpdateOrDlc(it.name) }.ifEmpty { gameDocs }
        val list = baseGames.map { d ->
            val base = d.name.substringBeforeLast('.')
            val key = base.lowercase()
            val title = cleanTitle(base)
            val iconDoc = images[key] ?: images[title.lowercase()]
            val bgDoc = images["$key-bg"] ?: images["${key}_bg"] ?: images["${title.lowercase()}-bg"]
            val icon = iconDoc?.let { icon(ctx, tree, it) }
            val pic = bgDoc?.let { background(ctx, tree, it) }
            Game(d.id, title, d.name, icon, pic)
        }
        val collator = Collator.getInstance()
        status = "Игр: ${list.size}"
        return list.sortedWith { a, b -> collator.compare(a.title, b.title) }
    }

    private fun isUpdateOrDlc(name: String): Boolean {
        val n = name.lowercase()
        if (Regex("""\[(upd|update|dlc)]""").containsMatchIn(n)) return true
        // Title ID: у игры заканчивается на 000, у обновления — на 800, у DLC — другое
        val tid = Regex("""\[(01[0-9a-f]{14})]""").find(n)?.groupValues?.get(1) ?: return false
        return !tid.endsWith("000")
    }

    /** «Super_Mario [0100000000010000][v0] (EU).nsp» → «Super Mario». */
    fun cleanTitle(base: String): String {
        var t = base.replace(Regex("""\[[^\]]*]|\([^)]*\)|\{[^}]*\}"""), " ")
        t = t.replace('_', ' ').replace(Regex("""\s+"""), " ").trim().trimEnd('-', '.', ' ')
        return t.ifEmpty { base }
    }

    private fun hash(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        return d.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun icon(ctx: Context, tree: Uri, d: Doc): Bitmap? {
        val ck = d.id + "@" + d.modified
        synchronized(iconCache) { if (iconCache.containsKey(ck)) return iconCache[ck] }
        val b = try {
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, d.id)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= ICON_PX) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }?.let { fit(it) }
        } catch (_: Throwable) { null }
        synchronized(iconCache) { iconCache[ck] = b }
        return b
    }

    private const val ICON_PX = 160

    /** Обложка любой формы — в квадрат с прозрачными полями, как иконки приложений. */
    private fun fit(src: Bitmap): Bitmap {
        val k = ICON_PX.toFloat() / max(src.width, src.height)
        val w = (src.width * k).toInt().coerceAtLeast(1)
        val h = (src.height * k).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        val out = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(scaled, (ICON_PX - w) / 2f, (ICON_PX - h) / 2f, null)
        return out
    }

    /** Фон копируем к себе, чтобы фон XMB мог открыть его как обычный файл. */
    private fun background(ctx: Context, tree: Uri, d: Doc): File? {
        val dir = File(ctx.filesDir, "switch").apply { mkdirs() }
        val f = File(dir, hash(d.id) + "_" + d.modified + ".img")
        if (f.exists() && f.length() > 0) return f
        return try {
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, d.id)
            ctx.contentResolver.openInputStream(uri)?.use { inp -> f.outputStream().use { inp.copyTo(it) } }
            f.takeIf { it.length() > 0 }
        } catch (_: Throwable) { null }
    }

    // ---------------- запуск ----------------

    fun launch(act: Activity, g: Game, emu: Emu): Boolean {
        val tree = tree(act) ?: return false
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, g.docId)
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        try { act.grantUriPermission(emu.pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        for (action in listOf(emu.action, if (emu.action == ACTION_NFC) Intent.ACTION_VIEW else ACTION_NFC)) {
            val i = Intent(action).setData(uri).setComponent(ComponentName(emu.pkg, emu.activity)).addFlags(flags)
            try { act.startActivity(i); return true } catch (_: Exception) {}
        }
        return false
    }
}
