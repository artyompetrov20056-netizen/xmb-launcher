package com.xmb.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.text.TextPaint
import android.util.Size
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Камера XMB: фото и видео, весь интерфейс рисуется в стиле текущей темы. */
class CameraActivity : Activity(), CameraEngine.Listener {

    private lateinit var texture: TextureView
    private lateinit var hud: CameraHud
    private lateinit var engine: CameraEngine
    private lateinit var sounds: XmbSounds
    private val actionSound = MediaActionSound()
    private var orientation: OrientationEventListener? = null
    private var forResult = false
    private var outputUri: Uri? = null
    private var askedPerms = false
    private val prefs by lazy { getSharedPreferences("xmb_camera", MODE_PRIVATE) }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val p = getSharedPreferences("xmb", MODE_PRIVATE)
        sounds = XmbSounds()
        sounds.level = p.getInt("sound_level", 2)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC

        forResult = intent?.action == MediaStore.ACTION_IMAGE_CAPTURE
        outputUri = if (forResult) {
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri::class.java)
            else intent.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)
        } else null

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        texture = TextureView(this)
        root.addView(texture, FrameLayout.LayoutParams(1, 1))

        engine = CameraEngine(this, texture)
        engine.listener = this
        engine.captureForResult = forResult
        engine.facingFront = prefs.getBoolean("front", false)
        engine.flash = prefs.getInt("flash", 0)
        engine.video = !forResult && intent?.action == MediaStore.INTENT_ACTION_VIDEO_CAMERA

        hud = CameraHud(this, engine, sounds)
        hud.forResult = forResult
        hud.gridOn = prefs.getBoolean("grid", false)
        hud.timerSec = prefs.getInt("timer", 0)
        hud.padStyle = p.getInt("pad_style", PadIcons.STYLE_PS)
        hud.reload(this)
        root.addView(hud, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        hud.onPreviewRect = { r -> placeTexture(r) }
        hud.onClose = { finish() }
        hud.onGallery = { openGallery() }
        hud.onPermission = { askPermissions(force = true) }
        hud.onPrefs = { savePrefs() }
        hud.setOnApplyWindowInsetsListener { _, insets -> hud.applyInsets(insets); insets }

        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { engine.open() }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        setContentView(root)
        hud.isFocusable = true
        hud.isFocusableInTouchMode = true
        hud.requestFocus()
        hideBars()

        orientation = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val d = ((o + 45) / 90 * 90) % 360
                engine.deviceDegrees = d
                hud.deviceDegrees = d
            }
        }
        try { actionSound.load(MediaActionSound.SHUTTER_CLICK) } catch (_: Exception) {}
        if (!forResult) loadLastThumb()
    }

    override fun onResume() {
        super.onResume()
        sounds.start()
        hud.reload(this)
        orientation?.enable()
        engine.startThread()
        if (hasCamera()) { if (texture.isAvailable) engine.open() } else if (!askedPerms) askPermissions(false)
        hud.hasPermission = hasCamera()
    }

    override fun onPause() {
        hud.cancelCountdown()
        engine.close()
        engine.stopThread()
        orientation?.disable()
        sounds.stopSoon()
        super.onPause()
    }

    override fun onDestroy() {
        sounds.release()
        try { actionSound.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun hasCamera() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun askPermissions(force: Boolean) {
        askedPerms = true
        val need = ArrayList<String>()
        if (!hasCamera()) need += Manifest.permission.CAMERA
        if (!forResult && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (need.isEmpty()) return
        if (force && !hasCamera() && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) && prefs.getBoolean("asked", false)) {
            // система больше не показывает запрос — открываем настройки приложения
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
            return
        }
        prefs.edit().putBoolean("asked", true).apply()
        requestPermissions(need.toTypedArray(), 7)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        hud.hasPermission = hasCamera()
        if (hasCamera() && texture.isAvailable) engine.open()
        hud.invalidate()
    }

    private fun placeTexture(r: RectF) {
        val lp = texture.layoutParams as FrameLayout.LayoutParams
        val l = r.left.toInt(); val t = r.top.toInt(); val w = r.width().toInt(); val h = r.height().toInt()
        if (lp.leftMargin == l && lp.topMargin == t && lp.width == w && lp.height == h) return
        lp.leftMargin = l; lp.topMargin = t; lp.width = w; lp.height = h
        texture.layoutParams = lp
    }

    private fun savePrefs() {
        prefs.edit()
            .putBoolean("front", engine.facingFront)
            .putInt("flash", engine.flash)
            .putBoolean("grid", hud.gridOn)
            .putInt("timer", hud.timerSec)
            .apply()
    }

    @Suppress("DEPRECATION")
    private fun hideBars() {
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (event.action == KeyEvent.ACTION_UP) {
                    if (engine.recording) engine.stopRecording() else { sounds.back(); finish() }
                }
                return true
            }
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_CAMERA -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) hud.pressShutter()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------- галерея и миниатюра ----------------

    private fun openGallery() {
        val s = prefs.getString("last_uri", null) ?: run { sounds.back(); return }
        val video = prefs.getBoolean("last_video", false)
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(s), if (video) "video/*" else "image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            sounds.ok()
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Нет приложения для просмотра", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadLastThumb() {
        val s = prefs.getString("last_uri", null) ?: return
        loadThumb(Uri.parse(s), prefs.getBoolean("last_video", false), pulse = false)
    }

    private fun loadThumb(uri: Uri, video: Boolean, pulse: Boolean) {
        Thread {
            val bmp: Bitmap? = try {
                if (Build.VERSION.SDK_INT >= 29) contentResolver.loadThumbnail(uri, Size(320, 320), null)
                else if (!video) {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                    var ss = 1
                    while (max(o.outWidth, o.outHeight) / (ss * 2) >= 320) ss *= 2
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = ss }) }
                } else null
            } catch (_: Exception) { null }
            runOnUiThread {
                if (bmp == null && !pulse) prefs.edit().remove("last_uri").apply()
                hud.setThumb(bmp ?: hud.thumb, pulse)
            }
        }.start()
    }

    // ---------------- события камеры ----------------

    override fun onCameraState() { hud.invalidate() }

    override fun onShutter() {
        try { actionSound.play(MediaActionSound.SHUTTER_CLICK) } catch (_: Exception) {}
        hud.flashNow()
    }

    override fun onPhotoBytes(bytes: ByteArray) {
        // снимок для другого приложения (ACTION_IMAGE_CAPTURE)
        val out = outputUri
        try {
            if (out != null) {
                contentResolver.openOutputStream(out)?.use { it.write(bytes) }
                setResult(RESULT_OK)
            } else {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var ss = 1
                while (max(o.outWidth, o.outHeight) / (ss * 2) >= 512) ss *= 2
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = ss })
                setResult(RESULT_OK, Intent().putExtra("data", bmp))
            }
        } catch (_: Exception) {
            setResult(RESULT_CANCELED)
        }
        finish()
    }

    override fun onSaved(uri: Uri?, video: Boolean) {
        if (video) try { actionSound.play(MediaActionSound.STOP_VIDEO_RECORDING) } catch (_: Exception) {}
        hud.invalidate()
        if (uri == null) {
            if (video) Toast.makeText(this, "Видео не сохранилось", Toast.LENGTH_SHORT).show()
            return
        }
        prefs.edit().putString("last_uri", uri.toString()).putBoolean("last_video", video).apply()
        loadThumb(uri, video, pulse = true)
    }

    override fun onCameraError(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        hud.invalidate()
    }

    fun playVideoStart() {
        try { actionSound.play(MediaActionSound.START_VIDEO_RECORDING) } catch (_: Exception) {}
    }

    companion object {
        fun open(ctx: Context) {
            ctx.startActivity(Intent(ctx, CameraActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

// =========================================================================
// Интерфейс камеры поверх превью
// =========================================================================

@SuppressLint("ViewConstructor")
class CameraHud(context: Context, private val cam: CameraEngine, private val sounds: XmbSounds) : View(context) {

    val bg = XmbBackground()
    var style: VisualStyle = VisualStyles.ALL[0]
    var padStyle = PadIcons.STYLE_PS
    var deviceDegrees = 0
    var forResult = false
    var hasPermission = true
    var gridOn = false
    var timerSec = 0
    var thumb: Bitmap? = null
        private set

    var onPreviewRect: (RectF) -> Unit = {}
    var onClose: () -> Unit = {}
    var onGallery: () -> Unit = {}
    var onPermission: () -> Unit = {}
    var onPrefs: () -> Unit = {}

    private var time = 0f
    private var lastFrame = 0L
    private var safeL = 0f
    private var safeT = 0f
    private var safeR = 0f
    private var safeB = 0f
    private var u = 1f
    private var w = 0f
    private var h = 0f

    private var iconRot = 0f
    private var modePos = 0f
    private var flashA = 0f
    private var thumbPulse = 0f
    private var countdownEnd = 0L
    private var lastCount = -1
    private var focusX = 0f
    private var focusY = 0f
    private var focusT = -10f
    private var zoomAnim = -1f

    private val preview = RectF()
    private val lastPreview = RectF()
    private val previewPath = Path()
    private val tmpPath = Path()
    private val rShutter = RectF()
    private val rThumb = RectF()
    private val rFlip = RectF()
    private val rFlash = RectF()
    private val rTimer = RectF()
    private val rGrid = RectF()
    private val rClose = RectF()
    private val rModes = Array(2) { RectF() }
    private val rZoom = Array(4) { RectF() }
    private var zoomStops = floatArrayOf(1f)
    private val tmp = RectF()

    private var pressedId = 0
    private var downX = 0f
    private var downY = 0f
    private var scaling = false
    private var moved = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val labelP = text("sans-serif").apply { textAlign = Paint.Align.CENTER }
    private val bigP = text("sans-serif-thin").apply { textAlign = Paint.Align.CENTER }
    private val smallP = text("sans-serif-condensed")

    private fun text(family: String) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(family, Typeface.NORMAL)
        setShadowLayer(6f, 0f, 1.5f, Color.argb(150, 0, 0, 0))
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { scaling = true; zoomAnim = -1f; return true }
        override fun onScale(d: ScaleGestureDetector): Boolean {
            cam.setZoom(cam.zoom * d.scaleFactor)
            invalidate()
            return true
        }
    })

    fun reload(ctx: Context) {
        bg.loadFromPrefs(ctx)
        style = VisualStyles.current(ctx)
        labelP.color = style.ink
        bigP.color = style.ink
        smallP.color = style.ink
        invalidate()
    }

    fun applyInsets(insets: WindowInsets) {
        if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            safeL = i.left.toFloat(); safeT = i.top.toFloat(); safeR = i.right.toFloat(); safeB = i.bottom.toFloat()
        } else {
            @Suppress("DEPRECATION")
            run {
                safeL = insets.systemWindowInsetLeft.toFloat(); safeT = insets.systemWindowInsetTop.toFloat()
                safeR = insets.systemWindowInsetRight.toFloat(); safeB = insets.systemWindowInsetBottom.toFloat()
            }
        }
    }

    fun setThumb(b: Bitmap?, pulse: Boolean) {
        thumb = b
        if (pulse) thumbPulse = 1f
        invalidate()
    }

    fun flashNow() { flashA = 1f; invalidate() }

    fun cancelCountdown() { countdownEnd = 0L; lastCount = -1 }

    private fun hasPad() = InputDevice.getDeviceIds().any { id ->
        val d = InputDevice.getDevice(id) ?: return@any false
        !d.isVirtual && (d.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
    }

    private fun mix(a: Int, b: Int, f: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt(),
    )

    private val accent get() = VisualStyles.accent

    // =====================================================================
    // Раскладка
    // =====================================================================

    private fun layout() {
        val topH = safeT + u * 0.16f
        val ratio = cam.previewAspect()
        var pw = w
        var ph = w * ratio
        val top: Float
        if (cam.video) {
            if (ph > h) { ph = h; pw = h / ratio }
            top = min(topH, h - ph).coerceAtLeast(0f)
        } else {
            val maxH = h - topH - safeB - u * 0.42f
            if (ph > maxH) { ph = maxH; pw = ph / ratio }
            top = topH
        }
        preview.set((w - pw) / 2f, top, (w + pw) / 2f, top + ph)
        if (preview != lastPreview) {
            lastPreview.set(preview)
            onPreviewRect(preview)
        }
        val rad = when (style.frame) {
            FrameKind.PEBBLE -> u * 0.07f
            FrameKind.BUBBLE, FrameKind.ROUNDED, FrameKind.CARD -> u * 0.045f
            FrameKind.GLOW, FrameKind.CRYSTAL -> u * 0.025f
            else -> 0f
        }
        previewPath.rewind()
        previewPath.addRoundRect(preview, rad, rad, Path.Direction.CW)

        // кнопки
        val sy = h - safeB - u * 0.21f
        val sr = u * 0.10f
        rShutter.set(w / 2f - sr * 1.3f, sy - sr * 1.3f, w / 2f + sr * 1.3f, sy + sr * 1.3f)
        val ts = u * 0.075f
        rThumb.set(w * 0.17f - ts, sy - ts, w * 0.17f + ts, sy + ts)
        rFlip.set(w * 0.83f - ts, sy - ts, w * 0.83f + ts, sy + ts)
        val ty = safeT + u * 0.08f
        val ti = u * 0.06f
        rFlash.set(w * 0.13f - ti, ty - ti, w * 0.13f + ti, ty + ti)
        rTimer.set(w * 0.37f - ti, ty - ti, w * 0.37f + ti, ty + ti)
        rGrid.set(w * 0.63f - ti, ty - ti, w * 0.63f + ti, ty + ti)
        rClose.set(w * 0.87f - ti, ty - ti, w * 0.87f + ti, ty + ti)
        val my = sy - u * 0.215f
        val sp = u * 0.24f
        for (i in 0..1) {
            val x = w / 2f + (i - modePos) * sp
            rModes[i].set(x - sp * 0.45f, my - u * 0.05f, x + sp * 0.45f, my + u * 0.05f)
        }
        // зум
        val stops = ArrayList<Float>()
        if (cam.minZoom < 0.95f) stops += cam.minZoom
        stops += 1f
        if (cam.maxZoom >= 2f) stops += 2f
        if (cam.maxZoom >= 5f) stops += 5f
        zoomStops = stops.toFloatArray()
        val zy = my - u * 0.105f
        val zsp = u * 0.115f
        val zr = u * 0.045f
        val x0 = w / 2f - (zoomStops.size - 1) * zsp / 2f
        for (i in rZoom.indices) {
            if (i < zoomStops.size) rZoom[i].set(x0 + i * zsp - zr, zy - zr, x0 + i * zsp + zr, zy + zr) else rZoom[i].setEmpty()
        }
    }

    // =====================================================================
    // Отрисовка
    // =====================================================================

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else (now - lastFrame).coerceIn(0L, 50L) / 1000f
        lastFrame = now
        time += dt
        bg.step(dt)
        w = width.toFloat(); h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        u = max(1f, min(w, h))

        // анимации
        var target = -deviceDegrees.toFloat()
        while (target - iconRot > 180f) target -= 360f
        while (target - iconRot < -180f) target += 360f
        iconRot += (target - iconRot) * (1f - exp(-dt * 10f))
        val mt = if (cam.video) 1f else 0f
        modePos += (mt - modePos) * (1f - exp(-dt * 12f))
        flashA = max(0f, flashA - dt * 3.5f)
        thumbPulse = max(0f, thumbPulse - dt * 1.8f)
        if (zoomAnim > 0f) {
            val z = cam.zoom + (zoomAnim - cam.zoom) * (1f - exp(-dt * 14f))
            if (abs(zoomAnim - z) < 0.01f) { cam.setZoom(zoomAnim); zoomAnim = -1f } else cam.setZoom(z)
        }
        tickCountdown()

        layout()

        // анимированный фон темы — всюду, кроме окна превью
        c.save()
        c.clipOutPath(previewPath)
        bg.draw(c, width, height, u, 1f, time)
        c.restore()
        VisualStyles.accent = bg.accent()

        drawPreviewState(c)
        if (gridOn) drawGrid(c)
        drawViewfinder(c)
        drawFocus(c)
        if (flashA > 0f) {
            c.save(); c.clipPath(previewPath)
            fill.color = mix(accent, Color.WHITE, 0.75f)
            fill.alpha = (210 * flashA).toInt()
            c.drawRect(preview, fill)
            c.restore()
        }
        drawTopBar(c)
        if (!cam.recording && !forResult) drawModes(c)
        drawZoom(c)
        drawShutter(c, rShutter.centerX(), rShutter.centerY(), u * 0.10f)
        if (!forResult) drawThumb(c)
        if (!cam.recording) drawRoundButton(c, Glyph.FLIP, rFlip, pressedId == ID_FLIP)
        drawCountdown(c)
        if (hasPad()) drawPadHints(c)
        postInvalidateOnAnimation()
    }

    private fun drawPreviewState(c: Canvas) {
        if (hasPermission && cam.ready) return
        c.save(); c.clipPath(previewPath)
        fill.color = mix(accent, Color.BLACK, 0.85f)
        fill.alpha = 255
        c.drawRect(preview, fill)
        c.restore()
        labelP.textSize = u * 0.042f
        labelP.alpha = 230
        if (!hasPermission) {
            Glyphs.draw(c, Glyph.PHOTO, preview.centerX(), preview.centerY() - u * 0.12f, u * 0.16f, 220)
            c.drawText("Нужен доступ к камере", preview.centerX(), preview.centerY() + u * 0.04f, labelP)
            labelP.textSize = u * 0.034f
            labelP.alpha = 160
            c.drawText("Нажмите, чтобы разрешить", preview.centerX(), preview.centerY() + u * 0.11f, labelP)
        } else {
            labelP.alpha = (120 + 100 * sin(time * 3f)).toInt()
            c.drawText("Камера запускается…", preview.centerX(), preview.centerY(), labelP)
        }
        labelP.alpha = 255
    }

    private fun drawGrid(c: Canvas) {
        line.color = style.ink
        line.strokeWidth = max(1f, u * 0.0015f)
        line.alpha = 90
        for (k in 1..2) {
            val x = preview.left + preview.width() * k / 3f
            val y = preview.top + preview.height() * k / 3f
            c.drawLine(x, preview.top, x, preview.bottom, line)
            c.drawLine(preview.left, y, preview.right, y, line)
        }
        if (style.frame == FrameKind.HUD) {
            val cx = preview.centerX(); val cy = preview.centerY(); val s = u * 0.03f
            line.alpha = 200
            c.drawLine(cx - s, cy, cx + s, cy, line)
            c.drawLine(cx, cy - s, cx, cy + s, line)
        }
        line.color = Color.WHITE
    }

    // ---------------- рамка видоискателя по стилю ----------------

    private fun corners(c: Canvas, len: Float, p: Paint) {
        val r = preview
        c.drawLine(r.left, r.top + len, r.left, r.top, p); c.drawLine(r.left, r.top, r.left + len, r.top, p)
        c.drawLine(r.right - len, r.top, r.right, r.top, p); c.drawLine(r.right, r.top, r.right, r.top + len, p)
        c.drawLine(r.left, r.bottom - len, r.left, r.bottom, p); c.drawLine(r.left, r.bottom, r.left + len, r.bottom, p)
        c.drawLine(r.right - len, r.bottom, r.right, r.bottom, p); c.drawLine(r.right, r.bottom, r.right, r.bottom - len, p)
    }

    private fun drawViewfinder(c: Canvas) {
        val r = preview
        line.color = style.ink
        when (style.frame) {
            FrameKind.NONE -> {
                line.strokeWidth = max(1f, u * 0.002f); line.alpha = 70
                c.drawRect(r, line)
            }
            FrameKind.GLOW -> {
                line.strokeWidth = u * 0.014f; line.alpha = 40
                c.drawPath(previewPath, line)
                line.strokeWidth = max(1.5f, u * 0.0025f); line.alpha = 150
                c.drawPath(previewPath, line)
            }
            FrameKind.BUBBLE -> {
                line.strokeWidth = max(2f, u * 0.004f); line.alpha = 150
                c.drawPath(previewPath, line)
                line.strokeWidth = u * 0.01f; line.alpha = 70
                tmp.set(r.left + u * 0.02f, r.top + u * 0.02f, r.left + u * 0.2f, r.top + u * 0.2f)
                c.drawArc(tmp, 190f, 70f, false, line)
            }
            FrameKind.BEVEL -> {
                val t = u * 0.009f
                line.strokeCap = Paint.Cap.SQUARE
                line.strokeWidth = t; line.color = Color.WHITE; line.alpha = 170
                c.drawLine(r.left, r.bottom, r.left, r.top, line); c.drawLine(r.left, r.top, r.right, r.top, line)
                line.color = Color.BLACK; line.alpha = 140
                c.drawLine(r.right, r.top, r.right, r.bottom, line); c.drawLine(r.right, r.bottom, r.left, r.bottom, line)
                line.strokeCap = Paint.Cap.ROUND
            }
            FrameKind.SQUARE -> {
                line.strokeWidth = max(1f, u * 0.002f); line.alpha = 110
                c.drawRect(r, line)
                fill.color = style.ink
                val s = u * 0.012f
                for (k in 0 until 4) {
                    val x = if (k % 2 == 0) r.left else r.right
                    val y = if (k < 2) r.top else r.bottom
                    c.save(); c.rotate(time * 40f + k * 90f, x, y)
                    fill.alpha = 200
                    c.drawRect(x - s, y - s, x + s, y + s, fill)
                    c.restore()
                }
            }
            FrameKind.ROUNDED -> {
                line.strokeWidth = max(1.5f, u * 0.0025f); line.alpha = 120
                c.drawPath(previewPath, line)
            }
            FrameKind.CARD -> {
                line.strokeWidth = max(2f, u * 0.004f); line.alpha = 210
                c.drawPath(previewPath, line)
            }
            FrameKind.CHROME -> {
                val len = u * 0.11f
                val t = u * 0.02f
                for (k in 0 until 4) {
                    val x = if (k % 2 == 0) r.left else r.right
                    val y = if (k < 2) r.top else r.bottom
                    val dx = if (k % 2 == 0) len else -len
                    val dy = if (k < 2) len else -len
                    tmpPath.rewind()
                    tmpPath.moveTo(x, y + dy); tmpPath.lineTo(x, y); tmpPath.lineTo(x + dx, y)
                    ChromeArt.tube(c, tmpPath, t, 0)
                    ChromeArt.boss(c, x, y, t * 0.9f, 6, 255, time * 25f + k * 30f)
                }
            }
            FrameKind.HUD -> {
                line.strokeWidth = max(1.5f, u * 0.003f); line.alpha = 220
                corners(c, u * 0.07f, line)
                // линейки по бокам
                line.strokeWidth = max(1f, u * 0.0016f)
                var y = r.top + u * 0.04f
                var k = 0
                while (y < r.bottom - u * 0.04f) {
                    val l = if (k % 5 == 0) u * 0.025f else u * 0.012f
                    line.alpha = if (k % 5 == 0) 170 else 90
                    c.drawLine(r.left + u * 0.012f, y, r.left + u * 0.012f + l, y, line)
                    c.drawLine(r.right - u * 0.012f, y, r.right - u * 0.012f - l, y, line)
                    y += u * 0.022f; k++
                }
                smallP.textSize = u * 0.026f
                smallP.alpha = 200
                smallP.textAlign = Paint.Align.LEFT
                c.drawText(if (cam.facingFront) "CAM-1 · FRONT" else "CAM-0 · MAIN", r.left + u * 0.05f, r.top + u * 0.06f, smallP)
                c.drawText(String.format(Locale.US, "AF-C · ×%.1f", cam.zoom), r.left + u * 0.05f, r.bottom - u * 0.035f, smallP)
                smallP.textAlign = Paint.Align.RIGHT
                c.drawText(if (cam.video) "16:9 · 30P" else "4:3 · JPEG", r.right - u * 0.05f, r.top + u * 0.06f, smallP)
                c.drawText(String.format(Locale.US, "%04X", ((time * 37).toInt() and 0xFFFF)), r.right - u * 0.05f, r.bottom - u * 0.035f, smallP)
                smallP.textAlign = Paint.Align.LEFT
                // бегущий ползунок вдоль низа
                val p = sin(time * 1.3f) * 0.5f + 0.5f
                fill.color = style.ink; fill.alpha = 200
                val sx = r.left + u * 0.3f + p * (r.width() - u * 0.6f)
                c.drawRect(sx - u * 0.03f, r.bottom - u * 0.006f, sx + u * 0.03f, r.bottom + u * 0.006f, fill)
            }
            FrameKind.PEBBLE -> {
                line.color = 0xFF101010.toInt()
                line.strokeWidth = u * 0.012f; line.alpha = 255
                c.drawPath(previewPath, line)
                line.color = 0xFF7CC22A.toInt()
                line.strokeWidth = u * 0.004f
                c.save(); c.clipPath(previewPath)
                c.drawPath(previewPath, line)
                c.restore()
            }
            FrameKind.CRYSTAL -> {
                line.color = mix(accent, Color.WHITE, 0.7f)
                line.strokeWidth = max(1.5f, u * 0.0025f); line.alpha = 150
                c.drawPath(previewPath, line)
                for (k in 0 until 4) {
                    val x = if (k % 2 == 0) r.left else r.right
                    val y = if (k < 2) r.top else r.bottom
                    val sx = if (k % 2 == 0) 1f else -1f
                    val sy = if (k < 2) 1f else -1f
                    shard(c, x, y, sx, sy, u * 0.07f, k)
                }
            }
        }
        line.color = Color.WHITE
        line.alpha = 255
    }

    /** Гранёный осколок кристалла в углу видоискателя. */
    private fun shard(c: Canvas, x: Float, y: Float, sx: Float, sy: Float, s: Float, k: Int) {
        val light = mix(accent, Color.WHITE, 0.75f)
        val mid = mix(accent, Color.WHITE, 0.3f)
        val deep = mix(accent, Color.BLACK, 0.45f)
        val wob = 0.5f + 0.5f * sin(time * 1.6f + k)
        fun tri(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, col: Int, a: Int) {
            tmpPath.rewind()
            tmpPath.moveTo(x + x1 * sx * s, y + y1 * sy * s)
            tmpPath.lineTo(x + x2 * sx * s, y + y2 * sy * s)
            tmpPath.lineTo(x + x3 * sx * s, y + y3 * sy * s)
            tmpPath.close()
            fill.color = col; fill.alpha = a
            c.drawPath(tmpPath, fill)
        }
        tri(-0.15f, -0.15f, 1.0f, -0.05f, 0.35f, 0.35f, mix(mid, light, wob), 235)
        tri(-0.15f, -0.15f, 0.35f, 0.35f, -0.05f, 1.0f, mix(deep, mid, wob), 235)
        tri(0.35f, 0.35f, 1.0f, -0.05f, 0.55f, 0.25f, light, 200)
        tri(0.35f, 0.35f, 0.25f, 0.55f, -0.05f, 1.0f, deep, 220)
        val tw = 0.5f + 0.5f * sin(time * 3.2f + k * 1.9f)
        sparkle(c, x + 0.35f * sx * s, y + 0.35f * sy * s, s * 0.22f * (0.5f + tw), (230 * tw).toInt())
    }

    private fun sparkle(c: Canvas, x: Float, y: Float, s: Float, alpha: Int) {
        line.color = Color.WHITE
        line.strokeWidth = max(1f, s * 0.16f)
        line.alpha = alpha.coerceIn(0, 255)
        c.drawLine(x - s, y, x + s, y, line)
        c.drawLine(x, y - s, x, y + s, line)
    }

    // ---------------- фокус ----------------

    private fun drawFocus(c: Canvas) {
        val k = time - focusT
        if (k > 1.4f) return
        val a = (255 * (if (k > 1f) 1f - (k - 1f) / 0.4f else 1f)).toInt().coerceIn(0, 255)
        val s = u * 0.075f * (1.35f - 0.35f * min(1f, k * 6f))
        when (style.frame) {
            FrameKind.CRYSTAL -> VisualStyles.drawFrame(c, style, focusX, focusY, s * 0.8f, a, 1f, time)
            FrameKind.CHROME -> {
                tmpPath.rewind(); tmpPath.addCircle(focusX, focusY, s, Path.Direction.CW)
                ChromeArt.tube(c, tmpPath, u * 0.012f, 0, a)
                ChromeArt.jewel(c, focusX, focusY, u * 0.012f, a)
            }
            FrameKind.NONE, FrameKind.GLOW -> {
                line.color = style.ink; line.strokeWidth = max(1.5f, u * 0.003f); line.alpha = a
                c.drawCircle(focusX, focusY, s, line)
                VisualStyles.drawFrame(c, style, focusX, focusY, s, a, 1f, time)
            }
            else -> VisualStyles.drawFrame(c, style, focusX, focusY, s * 1.3f, (a * 0.85f).toInt(), 1f, time)
        }
        line.color = Color.WHITE
    }

    // ---------------- кнопки ----------------

    private fun glyphK() = when (style.frame) {
        FrameKind.CHROME -> 0.85f
        FrameKind.CRYSTAL -> 0.55f
        FrameKind.NONE, FrameKind.GLOW -> 0.75f
        else -> 0.6f
    }

    private fun drawRoundButton(c: Canvas, g: Glyph, r: RectF, pressed: Boolean, active: Boolean = true) {
        val s = r.width() * 0.62f * (if (pressed) 0.9f else 1f)
        val sel = if (pressed) 1f else if (active) 0.45f else 0f
        VisualStyles.drawFrame(c, style, r.centerX(), r.centerY(), s, if (active) 255 else 150, sel, time, app = style.frame == FrameKind.CRYSTAL)
        c.save(); c.rotate(iconRot, r.centerX(), r.centerY())
        Glyphs.draw(c, g, r.centerX(), r.centerY(), s * glyphK(), if (active) 255 else 120)
        c.restore()
    }

    private fun drawTopBar(c: Canvas) {
        if (cam.recording) {
            val el = (SystemClock.elapsedRealtime() - cam.recordStart) / 1000
            val txt = String.format(Locale.US, "%02d:%02d", el / 60, el % 60)
            labelP.textSize = u * 0.05f
            val cy = rFlash.centerY()
            val tw = labelP.measureText(txt)
            fill.color = Color.rgb(235, 40, 50)
            fill.alpha = (150 + 105 * sin(time * 5f)).toInt().coerceIn(0, 255)
            c.drawCircle(w / 2f - tw / 2f - u * 0.035f, cy, u * 0.014f, fill)
            c.drawText(txt, w / 2f + u * 0.01f, cy + labelP.textSize * 0.36f, labelP)
            if (cam.hasFlash) drawRoundButton(c, Glyph.TORCH, rFlash, pressedId == ID_FLASH, cam.flash == 2)
            return
        }
        if (cam.hasFlash) {
            val g = when {
                cam.video -> Glyph.TORCH
                cam.flash == 1 -> Glyph.FLASH_AUTO
                cam.flash == 2 -> Glyph.FLASH
                else -> Glyph.FLASH_OFF
            }
            drawRoundButton(c, g, rFlash, pressedId == ID_FLASH, if (cam.video) cam.flash == 2 else true)
        }
        drawRoundButton(c, Glyph.HOURGLASS, rTimer, pressedId == ID_TIMER, timerSec > 0)
        if (timerSec > 0) {
            smallP.textSize = u * 0.03f
            smallP.textAlign = Paint.Align.LEFT
            smallP.alpha = 255
            c.drawText("${timerSec}с", rTimer.right - u * 0.02f, rTimer.bottom - u * 0.005f, smallP)
        }
        drawRoundButton(c, Glyph.GRID, rGrid, pressedId == ID_GRID, gridOn)
        drawRoundButton(c, Glyph.HOME, rClose, pressedId == ID_CLOSE)
    }

    private fun drawModes(c: Canvas) {
        val names = arrayOf("ФОТО", "ВИДЕО")
        labelP.textSize = u * 0.04f
        for (i in 0..1) {
            val r = rModes[i]
            val d = abs(i - modePos)
            val sel = (1f - min(1f, d)).coerceIn(0f, 1f)
            labelP.alpha = (130 + 125 * sel).toInt()
            labelP.isFakeBoldText = sel > 0.5f
            val y = r.centerY() + labelP.textSize * 0.36f
            c.drawText(names[i], r.centerX(), y, labelP)
            if (sel > 0.05f) {
                val a = (255 * sel).toInt()
                val mx = r.centerX()
                val my = r.centerY() + u * 0.045f
                when (style.frame) {
                    FrameKind.HUD -> {
                        smallP.textSize = labelP.textSize
                        smallP.alpha = a
                        val tw = labelP.measureText(names[i]) / 2f + u * 0.025f
                        smallP.textAlign = Paint.Align.CENTER
                        c.drawText("[", mx - tw, y, smallP)
                        c.drawText("]", mx + tw, y, smallP)
                        smallP.textAlign = Paint.Align.LEFT
                    }
                    FrameKind.CHROME -> ChromeArt.jewel(c, mx, my, u * 0.011f, a)
                    FrameKind.CRYSTAL -> {
                        tmpPath.rewind()
                        val s = u * 0.016f
                        tmpPath.moveTo(mx, my - s); tmpPath.lineTo(mx + s * 0.6f, my); tmpPath.lineTo(mx, my + s); tmpPath.lineTo(mx - s * 0.6f, my); tmpPath.close()
                        fill.color = mix(accent, Color.WHITE, 0.6f); fill.alpha = a
                        c.drawPath(tmpPath, fill)
                    }
                    FrameKind.PEBBLE -> {
                        fill.color = 0xFF7CC22A.toInt(); fill.alpha = a
                        c.drawCircle(mx, my, u * 0.01f, fill)
                        line.color = 0xFF101010.toInt(); line.strokeWidth = u * 0.003f; line.alpha = a
                        c.drawCircle(mx, my, u * 0.01f, line)
                        line.color = Color.WHITE
                    }
                    else -> {
                        fill.color = style.ink; fill.alpha = a
                        c.drawCircle(mx, my, u * 0.007f, fill)
                    }
                }
            }
        }
        labelP.isFakeBoldText = false
        labelP.alpha = 255
    }

    private fun nearestStop(): Int {
        var best = 0
        for (i in zoomStops.indices) if (abs(zoomStops[i] - cam.zoom) < abs(zoomStops[best] - cam.zoom)) best = i
        return best
    }

    private fun fmtZoom(z: Float): String {
        val s = if (abs(z - Math.round(z)) < 0.05f) Math.round(z).toString() else String.format(Locale.US, "%.1f", z)
        return s
    }

    private fun drawZoom(c: Canvas) {
        if (!cam.ready || zoomStops.size < 2 && cam.maxZoom <= 1.01f) return
        val curSeg = run {
            // активной считается ближайшая снизу ступень
            var k = 0
            for (i in zoomStops.indices) if (cam.zoom >= zoomStops[i] - 0.02f) k = i
            k
        }
        for (i in zoomStops.indices) {
            val r = rZoom[i]
            val active = i == curSeg
            val rad = r.width() / 2f * (if (active) 1.1f else 0.85f)
            fill.color = Color.BLACK
            fill.alpha = 90
            c.drawCircle(r.centerX(), r.centerY(), rad, fill)
            when (style.frame) {
                FrameKind.CHROME -> if (active) {
                    tmpPath.rewind(); tmpPath.addCircle(r.centerX(), r.centerY(), rad, Path.Direction.CW)
                    ChromeArt.tube(c, tmpPath, u * 0.009f, 0)
                }
                FrameKind.PEBBLE -> {
                    line.color = 0xFF101010.toInt(); line.strokeWidth = u * 0.005f; line.alpha = 255
                    fill.color = if (active) 0xFF7CC22A.toInt() else 0xFFB9E87E.toInt(); fill.alpha = 230
                    c.drawCircle(r.centerX(), r.centerY(), rad, fill)
                    c.drawCircle(r.centerX(), r.centerY(), rad, line)
                }
                FrameKind.HUD -> {
                    line.color = style.ink; line.strokeWidth = max(1f, u * 0.0025f); line.alpha = if (active) 230 else 110
                    c.drawRect(r.centerX() - rad, r.centerY() - rad, r.centerX() + rad, r.centerY() + rad, line)
                }
                FrameKind.CRYSTAL -> {
                    line.color = mix(accent, Color.WHITE, 0.7f); line.strokeWidth = max(1.5f, u * 0.003f); line.alpha = if (active) 230 else 110
                    tmpPath.rewind()
                    for (k in 0 until 6) {
                        val an = (k * 60 + 30) * Math.PI.toFloat() / 180f
                        val px = r.centerX() + rad * cos(an); val py = r.centerY() + rad * sin(an)
                        if (k == 0) tmpPath.moveTo(px, py) else tmpPath.lineTo(px, py)
                    }
                    tmpPath.close()
                    if (active) { fill.color = mix(accent, Color.BLACK, 0.2f); fill.alpha = 170; c.drawPath(tmpPath, fill) }
                    c.drawPath(tmpPath, line)
                }
                else -> {
                    line.color = style.ink; line.strokeWidth = max(1f, u * 0.0025f); line.alpha = if (active) 220 else 90
                    c.drawCircle(r.centerX(), r.centerY(), rad, line)
                }
            }
            line.color = Color.WHITE
            val txt = if (active && abs(cam.zoom - zoomStops[i]) > 0.04f) fmtZoom(cam.zoom) + "×" else fmtZoom(zoomStops[i]) + if (active) "×" else ""
            labelP.textSize = u * (if (active) 0.032f else 0.027f)
            val old = labelP.color
            if (style.frame == FrameKind.PEBBLE) labelP.color = style.glyphInk
            labelP.alpha = if (active) 255 else 200
            c.save(); c.rotate(iconRot, r.centerX(), r.centerY())
            c.drawText(txt, r.centerX(), r.centerY() + labelP.textSize * 0.36f, labelP)
            c.restore()
            labelP.color = old
            labelP.alpha = 255
        }
    }

    private fun drawShutter(c: Canvas, cx: Float, cy: Float, base: Float) {
        val press = pressedId == ID_SHUTTER
        val r = base * (if (press) 0.92f else 1f)
        val vid = cam.video
        val rec = cam.recording
        val red = Color.rgb(232, 44, 56)
        val core = if (vid) red else Color.WHITE
        when (style.frame) {
            FrameKind.CHROME -> {
                ChromeArt.boss(c, cx, cy, r * 0.74f, 12, 255, time * 18f)
                if (!rec) {
                    if (vid) { fill.color = red; fill.alpha = 255; c.drawCircle(cx, cy, r * 0.32f, fill) }
                    else ChromeArt.jewel(c, cx, cy, r * 0.32f)
                }
            }
            FrameKind.CRYSTAL -> {
                line.color = if (vid) red else mix(accent, Color.WHITE, 0.7f)
                line.strokeWidth = r * 0.07f; line.alpha = 230
                c.drawCircle(cx, cy, r, line)
                if (!rec) VisualStyles.drawFrame(c, style, cx, cy, r * 1.05f, 255, if (press) 1f else 0.6f, time)
            }
            FrameKind.HUD -> {
                line.color = if (vid) red else style.ink
                line.strokeWidth = max(1.5f, r * 0.04f)
                for (k in 0 until 36) {
                    val an = k * 10f * Math.PI.toFloat() / 180f
                    val l = if (k % 3 == 0) r * 0.16f else r * 0.08f
                    line.alpha = if (k % 3 == 0) 230 else 120
                    c.drawLine(cx + cos(an) * r, cy + sin(an) * r, cx + cos(an) * (r - l), cy + sin(an) * (r - l), line)
                }
                line.strokeWidth = r * 0.06f; line.alpha = 220
                tmp.set(cx - r * 0.72f, cy - r * 0.72f, cx + r * 0.72f, cy + r * 0.72f)
                val rot = time * 70f
                c.drawArc(tmp, rot, 70f, false, line)
                c.drawArc(tmp, rot + 180f, 70f, false, line)
                if (!rec) {
                    fill.color = if (vid) red else style.ink; fill.alpha = 230
                    c.drawRect(cx - r * 0.3f, cy - r * 0.3f, cx + r * 0.3f, cy + r * 0.3f, fill)
                }
            }
            FrameKind.PEBBLE -> {
                VisualStyles.drawFrame(c, style, cx, cy, r * 1.5f, 255, if (press) 1f else 0.5f, time)
                if (!rec) {
                    if (vid) {
                        fill.color = red; fill.alpha = 255; c.drawCircle(cx, cy, r * 0.32f, fill)
                        line.color = 0xFF101010.toInt(); line.strokeWidth = r * 0.06f; line.alpha = 255
                        c.drawCircle(cx, cy, r * 0.32f, line)
                    } else Glyphs.draw(c, Glyph.SHUTTER, cx, cy, r * 0.95f, 255)
                }
            }
            FrameKind.BUBBLE, FrameKind.BEVEL -> {
                VisualStyles.drawFrame(c, style, cx, cy, r * 1.6f, 255, if (press) 1f else 0.6f, time)
                if (!rec) { fill.color = core; fill.alpha = 235; c.drawCircle(cx, cy, r * 0.6f, fill) }
            }
            FrameKind.SQUARE -> {
                line.color = style.ink; line.strokeWidth = max(1.5f, r * 0.05f)
                for (k in 0..1) {
                    c.save(); c.rotate((if (k == 0) 1f else -1f) * time * 30f + k * 45f, cx, cy)
                    line.alpha = if (k == 0) 230 else 120
                    val s = r * (if (k == 0) 0.9f else 0.8f)
                    c.drawRect(cx - s, cy - s, cx + s, cy + s, line)
                    c.restore()
                }
                if (!rec) { fill.color = core; fill.alpha = 235; c.drawRect(cx - r * 0.45f, cy - r * 0.45f, cx + r * 0.45f, cy + r * 0.45f, fill) }
            }
            else -> {
                if (style.frame == FrameKind.GLOW) VisualStyles.drawFrame(c, style, cx, cy, r * 1.6f, 255, if (press) 1f else 0.5f, time)
                line.color = style.ink; line.strokeWidth = r * 0.09f; line.alpha = 240
                fill.color = core; fill.alpha = 240
                if (style.frame == FrameKind.ROUNDED || style.frame == FrameKind.CARD) {
                    tmp.set(cx - r, cy - r, cx + r, cy + r)
                    c.drawRoundRect(tmp, r * 0.38f, r * 0.38f, line)
                    tmp.inset(r * 0.2f, r * 0.2f)
                    if (!rec) c.drawRoundRect(tmp, r * 0.28f, r * 0.28f, fill)
                } else {
                    c.drawCircle(cx, cy, r, line)
                    if (!rec) c.drawCircle(cx, cy, r * 0.78f, fill)
                }
            }
        }
        if (rec) {
            fill.color = red; fill.alpha = 255
            val s = r * 0.34f
            tmp.set(cx - s, cy - s, cx + s, cy + s)
            c.drawRoundRect(tmp, s * 0.25f, s * 0.25f, fill)
        }
        if (countdownEnd > 0L) {
            val left = (countdownEnd - SystemClock.uptimeMillis()).coerceAtLeast(0L)
            line.color = mix(accent, Color.WHITE, 0.5f); line.strokeWidth = r * 0.08f; line.alpha = 255
            tmp.set(cx - r * 1.18f, cy - r * 1.18f, cx + r * 1.18f, cy + r * 1.18f)
            c.drawArc(tmp, -90f, 360f * left / (timerSec * 1000f), false, line)
        }
        line.color = Color.WHITE
    }

    private fun drawThumb(c: Canvas) {
        val r = rThumb
        val pressed = pressedId == ID_THUMB
        val s = r.width() * 0.62f * (if (pressed) 0.9f else 1f) * (1f + 0.15f * thumbPulse)
        val b = thumb
        if (b == null) {
            VisualStyles.drawFrame(c, style, r.centerX(), r.centerY(), s, 140, 0f, time, app = true)
            return
        }
        tmp.set(r.centerX() - s / 2f, r.centerY() - s / 2f, r.centerX() + s / 2f, r.centerY() + s / 2f)
        // миниатюра — центральный квадрат кадра
        val side = min(b.width, b.height)
        val src = android.graphics.Rect((b.width - side) / 2, (b.height - side) / 2, (b.width + side) / 2, (b.height + side) / 2)
        val sq = thumbSquare(b, src)
        c.save(); c.rotate(iconRot, r.centerX(), r.centerY())
        val shaped = if (style.iconShape == 0) VisualStyle(style.name, style.scene, style.palette, style.frame, style.weight, 2,
            iconFilter = style.iconFilter) else style
        VisualStyles.drawAppIcon(c, shaped, sq, tmp, 255)
        c.restore()
        VisualStyles.drawFrame(c, style, r.centerX(), r.centerY(), s, 255, 0.3f + 0.7f * thumbPulse, time, app = true)
        if (thumbPulse > 0f) {
            line.color = mix(accent, Color.WHITE, 0.6f); line.strokeWidth = u * 0.006f
            line.alpha = (255 * thumbPulse).toInt()
            c.drawCircle(r.centerX(), r.centerY(), s * (0.7f + 0.6f * (1f - thumbPulse)), line)
            line.color = Color.WHITE
        }
    }

    private var sqSrc: Bitmap? = null
    private var sqOut: Bitmap? = null
    private fun thumbSquare(b: Bitmap, src: android.graphics.Rect): Bitmap {
        if (sqSrc === b) return sqOut ?: b
        val side = src.width()
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(b, src, android.graphics.Rect(0, 0, side, side), null)
        sqSrc = b; sqOut = out
        return out
    }

    private fun drawCountdown(c: Canvas) {
        if (countdownEnd <= 0L) return
        val left = countdownEnd - SystemClock.uptimeMillis()
        val sec = ceil(left / 1000f).toInt().coerceAtLeast(1)
        val frac = (left % 1000) / 1000f
        val cx = preview.centerX(); val cy = preview.centerY()
        val sc = 1f + 0.25f * frac
        VisualStyles.drawFrame(c, style, cx, cy, u * 0.22f * sc, (200 * frac + 55).toInt(), 1f, time, app = style.frame == FrameKind.CRYSTAL)
        bigP.textSize = u * 0.2f * sc
        bigP.alpha = 255
        c.save(); c.rotate(iconRot, cx, cy)
        val old = bigP.color
        if (style.frame == FrameKind.PEBBLE) bigP.color = style.glyphInk
        c.drawText(sec.toString(), cx, cy + bigP.textSize * 0.35f, bigP)
        bigP.color = old
        c.restore()
    }

    private fun drawPadHints(c: Canvas) {
        val hints = listOf(PadBtn.SOUTH to "Снимок", PadBtn.WEST to "Режим", PadBtn.NORTH to "Камера", PadBtn.SHOULDERS to "Зум", PadBtn.EAST to "Выход")
        val r = u * 0.018f
        smallP.textSize = u * 0.025f
        smallP.textAlign = Paint.Align.LEFT
        var total = 0f
        for (hnt in hints) total += PadIcons.width(hnt.first, r) + r * 0.5f + smallP.measureText(hnt.second) + r * 1.4f
        var x = (w - total) / 2f
        val y = rFlash.bottom + u * 0.05f
        smallP.alpha = 220
        for (hnt in hints) {
            val iw = PadIcons.width(hnt.first, r)
            PadIcons.draw(c, hnt.first, x + iw / 2f, y, r, padStyle, 220)
            x += iw + r * 0.5f
            c.drawText(hnt.second, x, y + smallP.textSize * 0.36f, smallP)
            x += smallP.measureText(hnt.second) + r * 1.4f
        }
        smallP.alpha = 255
    }

    // =====================================================================
    // Действия
    // =====================================================================

    private fun tickCountdown() {
        if (countdownEnd <= 0L) return
        val left = countdownEnd - SystemClock.uptimeMillis()
        if (left <= 0L) {
            countdownEnd = 0L; lastCount = -1
            fire()
            return
        }
        val sec = ceil(left / 1000f).toInt()
        if (sec != lastCount) { lastCount = sec; sounds.tick() }
    }

    fun pressShutter() {
        if (!cam.ready) { sounds.back(); return }
        if (countdownEnd > 0L) { cancelCountdown(); sounds.back(); return }
        if (cam.recording) { fire(); return }
        if (timerSec > 0) {
            countdownEnd = SystemClock.uptimeMillis() + timerSec * 1000L
            lastCount = -1
            return
        }
        fire()
    }

    private fun fire() {
        if (!cam.ready) return
        if (cam.video) {
            if (cam.recording) cam.stopRecording()
            else { (context as? CameraActivity)?.playVideoStart(); cam.startRecording() }
        } else cam.takePhoto()
        invalidate()
    }

    private fun setMode(video: Boolean) {
        if (forResult || cam.recording || cam.video == video) return
        cancelCountdown()
        sounds.tick()
        cam.setVideoMode(video)
        if (!video && cam.flash == 2) { /* фонарик выключаем при выходе из видео */ cam.flash = 0 }
        cam.applySettings()
        onPrefs()
    }

    private fun cycleFlash() {
        if (!cam.hasFlash) return
        cam.flash = if (cam.video) (if (cam.flash == 2) 0 else 2) else (cam.flash + 1) % 3
        cam.applySettings()
        sounds.tick()
        onPrefs()
    }

    private fun cycleTimer() {
        timerSec = when (timerSec) { 0 -> 3; 3 -> 10; else -> 0 }
        sounds.tick()
        onPrefs()
    }

    private fun zoomStep(dir: Int) {
        if (zoomStops.isEmpty()) return
        var k = 0
        for (i in zoomStops.indices) if (cam.zoom >= zoomStops[i] - 0.02f) k = i
        if (dir > 0 && abs(cam.zoom - zoomStops[k]) > 0.04f) { } else k += dir
        k = k.coerceIn(0, zoomStops.lastIndex)
        zoomAnim = zoomStops[k]
        sounds.tick()
    }

    private fun hit(x: Float, y: Float): Int {
        if (rShutter.contains(x, y)) return ID_SHUTTER
        if (!forResult && rThumb.contains(x, y)) return ID_THUMB
        if (!cam.recording && rFlip.contains(x, y)) return ID_FLIP
        if (cam.hasFlash && rFlash.contains(x, y)) return ID_FLASH
        if (!cam.recording) {
            if (rTimer.contains(x, y)) return ID_TIMER
            if (rGrid.contains(x, y)) return ID_GRID
            if (rClose.contains(x, y)) return ID_CLOSE
            if (!forResult) for (i in 0..1) if (rModes[i].contains(x, y)) return ID_MODE0 + i
        }
        for (i in zoomStops.indices) {
            val r = rZoom[i]
            if (x > r.left - u * 0.02f && x < r.right + u * 0.02f && y > r.top - u * 0.02f && y < r.bottom + u * 0.02f) return ID_ZOOM0 + i
        }
        return 0
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                scaling = false; moved = false
                pressedId = hit(e.x, e.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> { pressedId = 0; scaling = true }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.x - downX) > u * 0.03f || abs(e.y - downY) > u * 0.03f) moved = true
                if (pressedId != 0 && hit(e.x, e.y) != pressedId) pressedId = 0
            }
            MotionEvent.ACTION_UP -> {
                val id = pressedId
                pressedId = 0
                if (id != 0 && hit(e.x, e.y) == id) {
                    performClick()
                    when (id) {
                        ID_SHUTTER -> pressShutter()
                        ID_THUMB -> onGallery()
                        ID_FLIP -> { sounds.ok(); cam.switchCamera(); onPrefs() }
                        ID_FLASH -> cycleFlash()
                        ID_TIMER -> cycleTimer()
                        ID_GRID -> { gridOn = !gridOn; sounds.tick(); onPrefs() }
                        ID_CLOSE -> { sounds.back(); onClose() }
                        ID_MODE0 -> setMode(false)
                        ID_MODE0 + 1 -> setMode(true)
                        else -> if (id >= ID_ZOOM0) { zoomAnim = zoomStops[id - ID_ZOOM0]; sounds.tick() }
                    }
                } else if (id == 0 && !scaling) {
                    val dx = e.x - downX
                    val dy = e.y - downY
                    if (!hasPermission && preview.contains(e.x, e.y)) onPermission()
                    else if (abs(dx) > u * 0.15f && abs(dx) > abs(dy) * 1.5f) setMode(dx < 0)
                    else if (!moved && preview.contains(e.x, e.y) && cam.ready) {
                        focusX = e.x; focusY = e.y; focusT = time
                        cam.focusAt((e.x - preview.left) / preview.width(), (e.y - preview.top) / preview.height())
                    }
                }
                scaling = false
            }
            MotionEvent.ACTION_CANCEL -> { pressedId = 0; scaling = false }
        }
        invalidate()
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> pressShutter()
            KeyEvent.KEYCODE_BUTTON_X -> setMode(!cam.video)
            KeyEvent.KEYCODE_DPAD_LEFT -> setMode(false)
            KeyEvent.KEYCODE_DPAD_RIGHT -> setMode(true)
            KeyEvent.KEYCODE_DPAD_UP -> cycleFlash()
            KeyEvent.KEYCODE_DPAD_DOWN -> cycleTimer()
            KeyEvent.KEYCODE_BUTTON_Y -> if (!cam.recording) { sounds.ok(); cam.switchCamera(); onPrefs() }
            KeyEvent.KEYCODE_BUTTON_L1 -> zoomStep(-1)
            KeyEvent.KEYCODE_BUTTON_R1 -> zoomStep(1)
            KeyEvent.KEYCODE_BUTTON_START -> { gridOn = !gridOn; sounds.tick(); onPrefs() }
            KeyEvent.KEYCODE_BUTTON_B -> { sounds.back(); onClose() }
            else -> return super.onKeyDown(keyCode, event)
        }
        invalidate()
        return true
    }

    companion object {
        const val ID_SHUTTER = 1
        const val ID_THUMB = 2
        const val ID_FLIP = 3
        const val ID_FLASH = 4
        const val ID_TIMER = 5
        const val ID_GRID = 6
        const val ID_CLOSE = 7
        const val ID_MODE0 = 10
        const val ID_ZOOM0 = 20
    }
}
