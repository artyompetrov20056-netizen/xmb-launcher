package com.xmb.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import java.io.File

/**
 * Фон «Видео»: зацикленный ролик под меню.
 * Поле вокруг ролика заливается тем же цветом, что и фон ролика (белый), поэтому ролик с белым фоном
 * сливается с экраном. Окраска — умножение на цвет: белый фон становится этим цветом, картинка — оттенком.
 */
class VideoBackdrop(ctx: Context) : FrameLayout(ctx), TextureView.SurfaceTextureListener {

    private val tex = TextureView(ctx)
    /** Для фото вместо ролика. */
    private val pic = android.widget.ImageView(ctx).apply { scaleType = android.widget.ImageView.ScaleType.FIT_XY; visibility = GONE }
    private var picFile: File? = null
    private var picStamp = 0L
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var playing: File? = null
    private var vw = 0
    private var vh = 0
    private var resumed = true
    private var tint = Color.WHITE
    private val layerPaint = Paint()
    private val box = Rect()
    /** Полоски поверх верхнего и нижнего края ролика: у многих роликов там тёмная строка пикселей. */
    private val edgeTop = android.view.View(ctx)
    private val edgeBottom = android.view.View(ctx)
    private var edge = 0

    var active = false
        set(v) {
            if (field == v) return
            field = v
            visibility = if (v) VISIBLE else GONE
            sync()
        }

    init {
        setBackgroundColor(Color.WHITE)
        tex.surfaceTextureListener = this
        addView(tex)
        addView(pic)
        for (e in arrayOf(edgeTop, edgeBottom)) { e.setBackgroundColor(Color.WHITE); addView(e) }
        visibility = GONE
    }

    fun setResumed(r: Boolean) {
        resumed = r
        if (!r) try { player?.pause() } catch (_: Exception) { }
        else sync()
    }

    /** Перечитать файлы (после выбора нового видео). */
    fun reload() { stop(); picFile = null; sync() }

    fun setTint(c: Int) {
        if (c == tint) return
        tint = c
        setBackgroundColor(c)
        edgeTop.setBackgroundColor(c); edgeBottom.setBackgroundColor(c)
        layerPaint.colorFilter = if (c == Color.WHITE) null else LightingColorFilter(c, 0)
        tex.setLayerPaint(layerPaint)
        pic.colorFilter = layerPaint.colorFilter
    }

    // ---------------- размещение ----------------

    private fun place(w: Int, h: Int) {
        edge = 0
        if (vw <= 0 || vh <= 0) { box.set(0, 0, w, h); return }
        val land = w > h
        if (pic.visibility == VISIBLE || (vw > vh) == land) {
            // фото или ролик той же ориентации, что и экран — на весь экран
            val s = maxOf(w / vw.toFloat(), h / vh.toFloat())
            val dw = (vw * s).toInt(); val dh = (vh * s).toInt()
            box.set((w - dw) / 2, (h - dh) / 2, (w - dw) / 2 + dw, (h - dh) / 2 + dh)
            return
        }
        // вертикальный ролик: прижат к низу экрана, остальное — цвет фона
        val s = if (land) h / vh.toFloat() else h * 0.80f / vh
        val dw = (vw * s).toInt(); val dh = (vh * s).toInt()
        edge = kotlin.math.ceil(s * 3f).toInt().coerceAtLeast(2)
        var left = if (land) (w * 0.70f).toInt() - dw / 2 else (w - dw) / 2
        if (land) left = left.coerceAtMost(w - dw)
        box.set(left, h - dh, left + dw, h)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        place(w, h)
        tex.measure(MeasureSpec.makeMeasureSpec(box.width().coerceAtLeast(1), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(box.height().coerceAtLeast(1), MeasureSpec.EXACTLY))
        pic.measure(MeasureSpec.makeMeasureSpec(box.width().coerceAtLeast(1), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(box.height().coerceAtLeast(1), MeasureSpec.EXACTLY))
        for (e in arrayOf(edgeTop, edgeBottom)) e.measure(MeasureSpec.makeMeasureSpec(box.width().coerceAtLeast(1), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(edge, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        place(r - l, b - t)
        tex.layout(box.left, box.top, box.right, box.bottom)
        pic.layout(box.left, box.top, box.right, box.bottom)
        edgeTop.layout(box.left, box.top, box.right, box.top + edge)
        edgeBottom.layout(box.left, box.bottom - edge, box.right, box.bottom)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if ((w > h) != (oldw > oldh)) post { sync() }   // поворот — может понадобиться другой ролик
    }

    // ---------------- проигрывание ----------------

    private fun chosen(): File? {
        val land = width > height
        return file(context, land).takeIf { land && it.exists() } ?: file(context, false).takeIf { it.exists() }
    }

    /** Фото показываем картинкой (без плеера). Возвращает true, если выбранный файл — фото. */
    private fun showPicture(): Boolean {
        val f = chosen()
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (f != null) android.graphics.BitmapFactory.decodeFile(f.path, o)
        if (f == null || o.outWidth <= 0) {
            if (pic.visibility != GONE) { pic.visibility = GONE; pic.setImageDrawable(null); picFile = null }
            edgeTop.visibility = VISIBLE; edgeBottom.visibility = VISIBLE
            return false
        }
        if (f != picFile || f.lastModified() != picStamp) {
            val target = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= target) sample *= 2
            var bmp = android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return false
            val rot = try {
                when (android.media.ExifInterface(f.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                    6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
                }
            } catch (_: Exception) { 0f }
            if (rot != 0f) bmp = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height,
                android.graphics.Matrix().apply { postRotate(rot) }, true)
            pic.setImageBitmap(bmp)
            picFile = f; picStamp = f.lastModified()
            vw = bmp.width; vh = bmp.height
            requestLayout()
        }
        stop()
        pic.visibility = VISIBLE
        edgeTop.visibility = GONE; edgeBottom.visibility = GONE   // у фото нет тёмной строки по краю
        return true
    }

    private fun wanted(): File? {
        if (!active || !resumed || surface == null) return null
        val land = width > height
        return file(context, land).takeIf { land && it.exists() } ?: file(context, false).takeIf { it.exists() }
    }

    private fun sync() {
        if (active && showPicture()) return
        val f = wanted()
        if (f == null) {
            if (!active) stop() else try { player?.pause() } catch (_: Exception) { }
            return
        }
        val p = player
        if (p != null && f == playing) {
            try { if (!p.isPlaying) p.start() } catch (_: Exception) { }
            return
        }
        stop()
        try {
            val np = MediaPlayer()
            np.setDataSource(context, Uri.fromFile(f))
            np.setSurface(surface)
            np.isLooping = true
            np.setVolume(0f, 0f)
            np.setOnVideoSizeChangedListener { _, w, h ->
                if (w > 0 && h > 0 && (w != vw || h != vh)) { vw = w; vh = h; requestLayout() }
            }
            np.setOnPreparedListener { if (it === player && resumed && active) it.start() }
            np.setOnErrorListener { _, _, _ -> true }
            np.prepareAsync()
            player = np
            playing = f
        } catch (_: Exception) {
            player = null
            playing = null
        }
    }

    private fun stop() {
        try { player?.release() } catch (_: Exception) { }
        player = null
        playing = null
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        surface = Surface(st)
        sync()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        stop()
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    companion object {
        fun file(ctx: Context, land: Boolean) = File(ctx.filesDir, if (land) "video_bg_land.mp4" else "video_bg.mp4")

        /** Скопировать выбранный ролик к себе (через временный файл — старый может ещё проигрываться). */
        fun save(ctx: Context, uri: Uri, land: Boolean): Boolean = try {
            val dst = file(ctx, land)
            val tmp = File(ctx.filesDir, dst.name + ".tmp")
            ctx.contentResolver.openInputStream(uri)!!.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
            tmp.renameTo(dst)
        } catch (_: Exception) { false }
    }
}
