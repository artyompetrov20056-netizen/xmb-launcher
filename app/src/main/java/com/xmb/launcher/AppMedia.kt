package com.xmb.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.max

/**
 * Картинки и музыка, которые пользователь назначил приложениям (как PIC1 и SND0 на PS3),
 * плюс общая фоновая музыка XMB. Всё копируется внутрь XMB, чтобы не зависеть от галереи.
 */
class AppMedia(private val ctx: Context) {

    private val dir = File(ctx.filesDir, "appmedia")

    private fun id(key: String): String {
        val d = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun bgFile(key: String, landscape: Boolean) = File(dir, id(key) + if (landscape) "_l.jpg" else ".jpg")
    private fun musicFile(key: String) = File(dir, id(key) + ".audio")
    val globalMusicFile: File get() = File(dir, "global.audio")

    /** Фон для нужной ориентации; если отдельного нет — общий. */
    fun background(key: String, landscape: Boolean): File? {
        val main = bgFile(key, false)
        val land = bgFile(key, true)
        return when {
            landscape && land.exists() -> land
            main.exists() -> main
            land.exists() -> land
            else -> null
        }
    }

    fun hasBackground(key: String) = bgFile(key, false).exists() || bgFile(key, true).exists()
    fun music(key: String): File? = musicFile(key).takeIf { it.exists() }
    fun globalMusic(): File? = globalMusicFile.takeIf { it.exists() }

    fun removeBackground(key: String) {
        bgFile(key, false).delete()
        bgFile(key, true).delete()
    }

    fun removeMusic(key: String) = musicFile(key).delete()
    fun removeGlobalMusic() = globalMusicFile.delete()

    /** Сохранить картинку: уменьшить до размера экрана и повернуть по EXIF. */
    fun saveBackground(uri: Uri, key: String, landscape: Boolean): Boolean = try {
        val dm = ctx.resources.displayMetrics
        val maxSide = max(dm.widthPixels, dm.heightPixels).coerceAtLeast(1280)
        val bmp = decodeScaled(uri, maxSide) ?: throw IllegalStateException()
        dir.mkdirs()
        FileOutputStream(bgFile(key, landscape)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bmp.recycle()
        true
    } catch (_: Throwable) {
        false
    }

    fun saveMusic(uri: Uri, key: String?): Boolean = try {
        dir.mkdirs()
        val target = if (key == null) globalMusicFile else musicFile(key)
        val tmp = File(dir, target.name + ".tmp")
        var total = 0L
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_AUDIO_BYTES) throw IllegalStateException("too big")
                    out.write(buf, 0, n)
                }
            }
        } ?: throw IllegalStateException()
        tmp.renameTo(target)
    } catch (_: Throwable) {
        false
    }

    private fun decodeScaled(uri: Uri, maxSide: Int): Bitmap? {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

        val rotation = try {
            cr.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (_: Exception) {
            0f
        }
        if (rotation == 0f) return bmp
        val m = Matrix().apply { postRotate(rotation) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (rotated !== bmp) bmp.recycle()
        return rotated
    }

    companion object {
        private const val MAX_AUDIO_BYTES = 60L * 1024 * 1024

        /** Загрузить сохранённую картинку для показа (с уменьшением под экран). */
        fun loadForDisplay(file: File, maxSide: Int): Bitmap? = try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: Throwable) {
            null
        }
    }
}
