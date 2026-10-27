package com.xmb.launcher

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.provider.DocumentsContract
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.zip.Inflater
import kotlin.math.max
import kotlin.math.min

/**
 * Интеграция с PPSSPP: читает «Недавние» из ppsspp.ini в папке PSP (memstick),
 * достаёт из образов ICON0 / PIC1 / название и запускает игру в PPSSPP.
 */
object Ppsspp {
    private val PACKAGES = listOf("org.ppsspp.ppssppgold", "org.ppsspp.ppsspp", "org.ppsspp.ppsspplegacy")
    private const val EXT_AUTH = "com.android.externalstorage.documents"

    class Game(val entry: String, val title: String, val icon: Bitmap?, val pic: File?)

    @Volatile var games: List<Game> = emptyList()
        private set
    @Volatile private var loading = false
    private var lastSig = ""

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("xmb_ppsspp", Context.MODE_PRIVATE)

    fun installed(ctx: Context): String? = PACKAGES.firstOrNull {
        try { ctx.packageManager.getPackageInfo(it, 0); true } catch (_: Exception) { false }
    }

    fun memTree(ctx: Context): Uri? = prefs(ctx).getString("mem", null)?.let { Uri.parse(it) }
    fun gamesTree(ctx: Context): Uri? = prefs(ctx).getString("games", null)?.let { Uri.parse(it) }

    fun setTree(ctx: Context, games: Boolean, uri: Uri?) {
        prefs(ctx).edit().apply { if (uri == null) remove(if (games) "games" else "mem") else putString(if (games) "games" else "mem", uri.toString()) }.apply()
        // при новой папке — заново пробуем прочитать образы
        File(ctx.filesDir, "psp").listFiles()?.filter { it.name.endsWith(".txt") && it.length() == 0L }?.forEach { it.delete() }
        lastSig = ""
    }

    /** Понятный путь выбранной папки для подписи. */
    fun treeLabel(uri: Uri?): String {
        if (uri == null) return "не выбрана"
        return try {
            val id = DocumentsContract.getTreeDocumentId(uri)
            val vol = id.substringBefore(':')
            val rel = id.substringAfter(':')
            (if (vol == "primary") "Память" else "SD") + "/" + rel
        } catch (_: Exception) { uri.toString() }
    }

    fun refresh(ctx: Context, done: () -> Unit) {
        if (loading) return
        loading = true
        val app = ctx.applicationContext
        Thread {
            try {
                val list = load(app)
                val sig = status + "#" + list.joinToString("|") { it.entry + ":" + it.title + ":" + (it.icon != null) + ":" + (it.pic != null) }
                if (sig != lastSig) {
                    lastSig = sig
                    games = list
                    Handler(Looper.getMainLooper()).post(done)
                }
            } catch (_: Throwable) {
            } finally {
                loading = false
            }
        }.start()
    }

    /** Что не так, если игр нет (для подписи пункта). */
    @Volatile var status = ""
        private set

    private fun load(ctx: Context): List<Game> {
        if (installed(ctx) == null) { status = "PPSSPP не установлен"; return emptyList() }
        val tree = memTree(ctx) ?: run { status = "Выберите папку PSP, чтобы видеть недавние игры"; return emptyList() }
        val ini = findIni(ctx, tree) ?: run { status = "В выбранной папке нет SYSTEM/ppsspp.ini"; return emptyList() }
        val text = ctx.contentResolver.openInputStream(ini)?.use { String(it.readBytes(), Charsets.UTF_8) }
            ?: run { status = "Нет доступа к ppsspp.ini — выберите папку заново"; return emptyList() }
        val entries = parseRecent(text)
        status = if (entries.isEmpty()) "Список недавних в PPSSPP пуст" else "Недавних игр: ${entries.size}"
        return entries.take(16).map { game(ctx, it) }
    }

    private fun parseRecent(text: String): List<String> {
        val out = ArrayList<Pair<Int, String>>()
        var inRecent = false
        val re = Regex("""^FileName(\d+)\s*=\s*(.*)$""")
        for (raw in text.lineSequence()) {
            val l = raw.trim()
            if (l.startsWith("[")) { inRecent = l.equals("[Recent]", true); continue }
            if (!inRecent) continue
            val m = re.find(l) ?: continue
            val v = m.groupValues[2].trim()
            if (v.isNotEmpty()) out += m.groupValues[1].toInt() to v
        }
        return out.sortedBy { it.first }.map { it.second }.distinct()
    }

    private fun child(ctx: Context, tree: Uri, parentId: String, name: String): String? {
        val q = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        ctx.contentResolver.query(q, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(1).equals(name, true)) return c.getString(0)
        }
        return null
    }

    private fun findIni(ctx: Context, tree: Uri): Uri? {
        val root = try { DocumentsContract.getTreeDocumentId(tree) } catch (_: Exception) { return null }
        for (path in listOf(listOf("SYSTEM", "ppsspp.ini"), listOf("PSP", "SYSTEM", "ppsspp.ini"))) {
            var id: String? = root
            for (seg in path) { id = id?.let { child(ctx, tree, it, seg) } ?: break }
            if (id != null) return DocumentsContract.buildDocumentUriUsingTree(tree, id)
        }
        return null
    }

    private fun hash(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        return d.take(8).joinToString("") { "%02x".format(it) }
    }

    fun nameFromEntry(entry: String): String {
        val s = Uri.decode(entry)
        var n = s.substringAfterLast('/').substringAfterLast(':')
        if (n.equals("EBOOT.PBP", true)) n = s.substringBeforeLast('/').substringAfterLast('/').substringAfterLast(':')
        n = n.substringBeforeLast('.')
        return n.replace('_', ' ').trim().ifEmpty { "PSP" }
    }

    private fun game(ctx: Context, entry: String): Game {
        val dir = File(ctx.filesDir, "psp").apply { mkdirs() }
        val h = hash(entry)
        val iconF = File(dir, "${h}_icon.png")
        val picF = File(dir, "${h}_pic1.png")
        val metaF = File(dir, "$h.txt")
        if (!metaF.exists()) {
            try { extract(ctx, entry, iconF, picF, metaF) } catch (_: Throwable) {}
        }
        val title = if (metaF.exists()) metaF.readText().trim() else ""
        val icon = if (iconF.exists()) BitmapFactory.decodeFile(iconF.path)?.let { square(it) } else null
        return Game(entry, title.ifEmpty { nameFromEntry(entry) }, icon, picF.takeIf { it.exists() && it.length() > 0 })
    }

    /** ICON0 у PSP широкий (144×80) — кладём в квадрат с прозрачными полями. */
    private fun square(b: Bitmap): Bitmap {
        if (b.width == b.height) return b
        val side = max(b.width, b.height)
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(b, (side - b.width) / 2f, (side - b.height) / 2f, null)
        return out
    }

    /** URI образа, который мы сами можем прочитать (через выданные нам папки). */
    private fun readableUri(ctx: Context, entry: String): Uri? {
        val docId: String
        val auth: String
        if (entry.startsWith("content://")) {
            val u = Uri.parse(entry)
            auth = u.authority ?: return null
            docId = try { DocumentsContract.getDocumentId(u) } catch (_: Exception) { return null }
        } else {
            val p = entry.removePrefix("file://")
            auth = EXT_AUTH
            docId = when {
                p.startsWith("/storage/emulated/0/") -> "primary:" + p.removePrefix("/storage/emulated/0/")
                p.startsWith("/sdcard/") -> "primary:" + p.removePrefix("/sdcard/")
                p.startsWith("/storage/") -> {
                    val rest = p.removePrefix("/storage/")
                    rest.substringBefore('/') + ":" + rest.substringAfter('/', "")
                }
                else -> return null
            }
        }
        for (tree in listOfNotNull(gamesTree(ctx), memTree(ctx))) {
            if (tree.authority != auth) continue
            val tid = try { DocumentsContract.getTreeDocumentId(tree) } catch (_: Exception) { continue }
            val prefix = if (tid.endsWith(":")) tid else "$tid/"
            if (docId == tid || docId.startsWith(prefix)) return DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        }
        return null
    }

    // ---------------- чтение образов ----------------

    private interface Img { fun read(off: Long, len: Int): ByteArray }

    private class RawImg(val ch: FileChannel) : Img {
        override fun read(off: Long, len: Int): ByteArray {
            val buf = ByteBuffer.allocate(len)
            var pos = off
            while (buf.hasRemaining()) {
                val n = ch.read(buf, pos)
                if (n <= 0) break
                pos += n
            }
            return buf.array()
        }
    }

    private class CsoImg(val raw: RawImg) : Img {
        private val hdr = raw.read(0, 24)
        private val total = le64(hdr, 8)
        private val block = le32(hdr, 16).toInt()
        private val align = hdr[21].toInt() and 0xFF
        private var cacheIdx = -1
        private var cache = ByteArray(0)

        private fun blockData(b: Int): ByteArray {
            if (b == cacheIdx) return cache
            val e = raw.read(24L + 4L * b, 8)
            val i0 = le32(e, 0)
            val i1 = le32(e, 4)
            val plain = (i0 and 0x80000000L) != 0L
            val start = (i0 and 0x7FFFFFFFL) shl align
            val end = (i1 and 0x7FFFFFFFL) shl align
            val src = raw.read(start, (end - start).toInt().coerceIn(0, block * 2))
            val out = if (plain) src.copyOf(block) else {
                val inf = Inflater(true)
                inf.setInput(src)
                val o = ByteArray(block)
                try { inf.inflate(o) } finally { inf.end() }
                o
            }
            cacheIdx = b; cache = out
            return out
        }

        override fun read(off: Long, len: Int): ByteArray {
            val out = ByteArray(len)
            var pos = off
            var o = 0
            while (o < len && pos < total) {
                val b = (pos / block).toInt()
                val inBlk = (pos % block).toInt()
                val d = blockData(b)
                val n = min(len - o, block - inBlk)
                System.arraycopy(d, inBlk, out, o, n)
                o += n; pos += n
            }
            return out
        }
    }

    private fun le32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    private fun le64(b: ByteArray, o: Int): Long = le32(b, o) or (le32(b, o + 4) shl 32)

    private fun le16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun isoFile(img: Img, path: List<String>): Pair<Long, Int>? {
        val pvd = img.read(16 * 2048L, 2048)
        if (String(pvd, 1, 5, Charsets.US_ASCII) != "CD001") return null
        var lba = le32(pvd, 156 + 2)
        var size = le32(pvd, 156 + 10).toInt()
        for (name in path) {
            val dir = img.read(lba * 2048L, size.coerceIn(0, 256 * 1024))
            var p = 0
            var found = false
            while (p + 33 < dir.size) {
                val len = dir[p].toInt() and 0xFF
                if (len == 0) { p = (p / 2048 + 1) * 2048; continue }
                val nl = dir[p + 32].toInt() and 0xFF
                if (p + 33 + nl > dir.size) break
                val nm = String(dir, p + 33, nl, Charsets.US_ASCII).substringBefore(';')
                if (nm.equals(name, true)) {
                    lba = le32(dir, p + 2); size = le32(dir, p + 10).toInt(); found = true; break
                }
                p += len
            }
            if (!found) return null
        }
        return lba * 2048L to size
    }

    private fun sfoTitle(b: ByteArray): String? {
        if (b.size < 20 || b[1] != 'P'.code.toByte() || b[2] != 'S'.code.toByte() || b[3] != 'F'.code.toByte()) return null
        val keys = le32(b, 8).toInt()
        val data = le32(b, 12).toInt()
        val n = le32(b, 16).toInt()
        for (i in 0 until min(n, 64)) {
            val e = 20 + i * 16
            if (e + 16 > b.size) break
            val ko = keys + le16(b, e)
            var ke = ko
            while (ke < b.size && b[ke] != 0.toByte()) ke++
            if (String(b, ko, ke - ko, Charsets.US_ASCII) != "TITLE") continue
            val len = le32(b, e + 4).toInt()
            val off = data + le32(b, e + 12).toInt()
            if (off + len > b.size) return null
            return String(b, off, len, Charsets.UTF_8).trimEnd('\u0000').trim()
        }
        return null
    }

    private fun extract(ctx: Context, entry: String, iconF: File, picF: File, metaF: File) {
        val uri = readableUri(ctx, entry) ?: return
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return
        pfd.use {
            FileInputStream(it.fileDescriptor).channel.use { ch ->
                val raw = RawImg(ch)
                val head = raw.read(0, 40)
                var title: String? = null
                var icon: ByteArray? = null
                var pic: ByteArray? = null
                if (head[0] == 0.toByte() && head[1] == 'P'.code.toByte() && head[2] == 'B'.code.toByte() && head[3] == 'P'.code.toByte()) {
                    // EBOOT.PBP
                    val off = LongArray(8) { i -> le32(head, 8 + i * 4) }
                    fun part(a: Int): ByteArray? {
                        val len = (off[a + 1] - off[a]).toInt()
                        return if (len in 1..(4 shl 20)) raw.read(off[a], len) else null
                    }
                    title = part(0)?.let { sfoTitle(it) }
                    icon = part(1)
                    pic = part(4)
                } else {
                    val img: Img = if (String(head, 0, 4, Charsets.US_ASCII) == "CISO") CsoImg(raw) else raw
                    fun file(n: String): ByteArray? = isoFile(img, listOf("PSP_GAME", n))?.let { (o, l) ->
                        if (l in 1..(4 shl 20)) img.read(o, l) else null
                    }
                    title = file("PARAM.SFO")?.let { sfoTitle(it) }
                    icon = file("ICON0.PNG")
                    pic = file("PIC1.PNG")
                }
                icon?.let { iconF.writeBytes(it) }
                pic?.let { picF.writeBytes(it) }
                metaF.writeText(title ?: "")
            }
        }
    }

    // ---------------- запуск ----------------

    fun launch(act: Activity, entry: String): Boolean {
        val pkg = installed(act) ?: return false
        val isContent = entry.startsWith("content://")
        val uri = if (isContent) Uri.parse(entry) else Uri.fromFile(File(entry.removePrefix("file://")))
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/octet-stream")
            .setComponent(ComponentName(pkg, "org.ppsspp.ppsspp.PpssppActivity"))
            .addCategory(Intent.CATEGORY_DEFAULT)
            .putExtra("org.ppsspp.ppsspp.Args", "--pause-menu-exit")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (!isContent) StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        return try { act.startActivity(i); true } catch (_: Exception) { false }
    }
}
