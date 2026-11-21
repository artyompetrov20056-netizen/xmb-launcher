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
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var playing: File? = null
    private var vw = 0
    private var vh = 0
    private var resumed = true
    private var tint = Color.WHITE
    private val layerPaint = Paint()
    private val box = Rect()

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
        visibility = GONE
    }

    fun setResumed(r: Boolean) {
        resumed = r
        if (!r) try { player?.pause() } catch (_: Exception) { }
        else sync()
    }

    /** Перечитать файлы (после выбора нового видео). */
    fun reload() { stop(); sync() }

    fun setTint(c: Int) {
        if (c == tint) return
        tint = c
        setBackgroundColor(c)
        layerPaint.colorFilter = if (c == Color.WHITE) null else LightingColorFilter(c, 0)
        tex.setLayerPaint(layerPaint)
    }

    // ---------------- размещение ----------------

    private fun place(w: Int, h: Int) {
        if (vw <= 0 || vh <= 0) { box.set(0, 0, w, h); return }
        val land = w > h
        if ((vw > vh) == land) {
            // ролик той же ориентации, что и экран — на весь экран
            val s = maxOf(w / vw.toFloat(), h / vh.toFloat())
            val dw = (vw * s).toInt(); val dh = (vh * s).toInt()
            box.set((w - dw) / 2, (h - dh) / 2, (w - dw) / 2 + dw, (h - dh) / 2 + dh)
            return
        }
        // вертикальный ролик: прижат к низу экрана, остальное — цвет фона
        val s = if (land) h / vh.toFloat() else h * 0.80f / vh
        val dw = (vw * s).toInt(); val dh = (vh * s).toInt()
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
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        place(r - l, b - t)
        tex.layout(box.left, box.top, box.right, box.bottom)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if ((w > h) != (oldw > oldh)) post { sync() }   // поворот — может понадобиться другой ролик
    }

    // ---------------- проигрывание ----------------

    private fun wanted(): File? {
        if (!active || !resumed || surface == null) return null
        val land = width > height
        return file(context, land).takeIf { land && it.exists() } ?: file(context, false).takeIf { it.exists() }
    }

    private fun sync() {
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
