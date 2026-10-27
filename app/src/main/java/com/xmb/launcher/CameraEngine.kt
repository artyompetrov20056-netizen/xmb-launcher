package com.xmb.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Работа с камерой через Camera2: превью, фото (JPEG), видео (MediaRecorder),
 * зум, вспышка, фокус касанием. Все вызовы камеры — в фоновом потоке.
 */
class CameraEngine(private val ctx: Context, private val texture: TextureView) {

    interface Listener {
        fun onCameraState()
        fun onShutter()
        fun onPhotoBytes(bytes: ByteArray)
        fun onSaved(uri: Uri?, video: Boolean)
        fun onCameraError(msg: String)
    }

    var listener: Listener? = null
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var bg: Handler? = null
    private val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    var facingFront = false
    @Volatile var video = false
    /** 0 — выкл, 1 — авто, 2 — вкл (в видео — фонарик). */
    @Volatile var flash = 0
    @Volatile var zoom = 1f
        private set
    @Volatile var minZoom = 1f
        private set
    @Volatile var maxZoom = 1f
        private set
    @Volatile var ready = false
        private set
    @Volatile var recording = false
        private set
    @Volatile var recordStart = 0L
        private set
    @Volatile var hasFlash = false
        private set
    @Volatile var deviceDegrees = 0
    /** Снимок вернуть вызвавшему приложению, а не сохранять в галерею. */
    var captureForResult = false

    private var sensorOrientation = 90
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var builder: CaptureRequest.Builder? = null
    private var reader: ImageReader? = null
    private var recorder: MediaRecorder? = null
    private var recPfd: ParcelFileDescriptor? = null
    private var recUri: Uri? = null
    private var previewSurface: Surface? = null
    private var streamMap: StreamConfigurationMap? = null
    private var activeArray: Rect? = null
    private var useZoomRatio = false
    private var afModes = IntArray(0)
    private var maxAf = 0
    private var maxAe = 0
    private var opening = false
    @Volatile private var wantOpen = false

    private var focusRegions: Array<MeteringRectangle>? = null
    private var focusLocked = false

    // ожидание предвспышки
    private var capState = 0
    private var capStart = 0L

    fun startThread() {
        if (thread != null) return
        thread = HandlerThread("xmb-camera").also { it.start() }
        bg = Handler(thread!!.looper)
    }

    fun stopThread() {
        thread?.quitSafely()
        try { thread?.join(800) } catch (_: InterruptedException) {}
        thread = null
        bg = null
    }

    private fun post(r: () -> Unit) { bg?.post { try { r() } catch (e: Exception) { fail(e) } } }
    private fun ui(r: () -> Unit) { main.post(r) }
    private fun fail(e: Exception) { ui { listener?.onCameraError("Камера: " + (e.message ?: e.javaClass.simpleName)) } }

    // =====================================================================
    // Открытие / закрытие
    // =====================================================================

    @SuppressLint("MissingPermission")
    fun open() {
        if (device != null || opening) return
        if (ctx.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (!texture.isAvailable || bg == null) return
        val id = pickCamera(facingFront) ?: pickCamera(!facingFront)?.also { facingFront = !facingFront }
        if (id == null) { listener?.onCameraError("Камера не найдена"); return }
        try {
            val ch = manager.getCameraCharacteristics(id)
            sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            hasFlash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            activeArray = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
            maxAf = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
            maxAe = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
            useZoomRatio = false
            minZoom = 1f
            maxZoom = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            if (Build.VERSION.SDK_INT >= 30) {
                ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let {
                    useZoomRatio = true; minZoom = it.lower; maxZoom = it.upper
                }
            }
            maxZoom = min(maxZoom, 10f)
            zoom = 1f.coerceIn(minZoom, maxZoom)
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: throw IllegalStateException("нет форматов")
            streamMap = map
            val jpeg = pickSize(map.getOutputSizes(ImageFormat.JPEG), 4f / 3f, Int.MAX_VALUE)
            reader?.close()
            reader = ImageReader.newInstance(jpeg.width, jpeg.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ r -> onJpeg(r) }, bg)
            }
            opening = true
            wantOpen = true
            manager.openCamera(id, stateCb, bg)
        } catch (e: Exception) {
            opening = false
            fail(e)
        }
    }

    fun close() {
        wantOpen = false
        if (recording) stopRecordingNow()
        ready = false
        opening = false
        capState = 0
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { device?.close() } catch (_: Exception) {}
        device = null
        reader?.close(); reader = null
        previewSurface?.release(); previewSurface = null
    }

    fun switchCamera() {
        if (recording) return
        close()
        facingFront = !facingFront
        open()
        listener?.onCameraState()
    }

    fun setVideoMode(v: Boolean) {
        if (recording || video == v) return
        video = v
        if (flash == 1) flash = 0
        ready = false
        post { startPreviewSession() }
    }

    private fun pickCamera(front: Boolean): String? {
        var best: String? = null
        var bestMin = Float.MAX_VALUE
        for (id in manager.cameraIdList) {
            val ch = try { manager.getCameraCharacteristics(id) } catch (_: Exception) { continue }
            val f = ch.get(CameraCharacteristics.LENS_FACING) ?: continue
            if (f != (if (front) CameraMetadata.LENS_FACING_FRONT else CameraMetadata.LENS_FACING_BACK)) continue
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            if (!caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) continue
            val mn = if (Build.VERSION.SDK_INT >= 30) ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.lower ?: 1f else 1f
            if (best == null || mn < bestMin - 0.01f) { best = id; bestMin = mn }
        }
        return best
    }

    private fun pickSize(sizes: Array<Size>?, ratio: Float, maxW: Int): Size {
        if (sizes.isNullOrEmpty()) return Size(1280, 960)
        val fit = sizes.filter { abs(it.width.toFloat() / it.height - ratio) < 0.02f && it.width <= maxW }
        return fit.maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.filter { it.width <= maxW }.maxByOrNull { it.width.toLong() * it.height }
            ?: sizes[0]
    }

    /** Размер кадра превью (в ориентации сенсора — альбомный). */
    fun previewAspect(): Float = if (video) 16f / 9f else 4f / 3f

    private val stateCb = object : CameraDevice.StateCallback() {
        override fun onOpened(d: CameraDevice) {
            opening = false
            if (!wantOpen) { d.close(); return }
            device = d
            try { startPreviewSession() } catch (e: Exception) { fail(e) }
        }

        override fun onDisconnected(d: CameraDevice) {
            opening = false
            d.close()
            if (device === d) device = null
            ready = false
            ui { listener?.onCameraState() }
        }

        override fun onError(d: CameraDevice, error: Int) {
            opening = false
            d.close()
            if (device === d) device = null
            ready = false
            ui { listener?.onCameraError(if (error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE || error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE) "Камера занята другим приложением" else "Камера недоступна (код $error)") }
        }
    }

    // =====================================================================
    // Превью
    // =====================================================================

    @Suppress("DEPRECATION")
    private fun startPreviewSession() {
        val dev = device ?: return
        val st: SurfaceTexture = texture.surfaceTexture ?: return
        val map = streamMap ?: return
        try { session?.close() } catch (_: Exception) {}
        session = null
        val ps = pickSize(map.getOutputSizes(SurfaceTexture::class.java), previewAspect(), 1920)
        st.setDefaultBufferSize(ps.width, ps.height)
        val surf = Surface(st)
        previewSurface?.release()
        previewSurface = surf
        val rd = reader
        val targets = if (video || rd == null) listOf(surf) else listOf(surf, rd.surface)
        dev.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (device !== dev) { s.close(); return }
                session = s
                try {
                    builder = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surf)
                        applyCommon(this)
                    }
                    repeat()
                    ready = true
                } catch (e: Exception) { fail(e) }
                ui { listener?.onCameraState() }
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                ui { listener?.onCameraError("Не удалось запустить превью") }
            }
        }, bg)
    }

    private fun repeat() {
        val s = session ?: return
        val b = builder ?: return
        try { s.setRepeatingRequest(b.build(), progressCb, bg) } catch (e: Exception) { fail(e) }
    }

    private fun applyCommon(b: CaptureRequest.Builder) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        val want = when {
            focusLocked -> CameraMetadata.CONTROL_AF_MODE_AUTO
            video -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            else -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        }
        if (afModes.contains(want)) b.set(CaptureRequest.CONTROL_AF_MODE, want)
        else if (afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO)) b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
        when {
            hasFlash && video && flash == 2 -> {
                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
            }
            hasFlash && !video && flash == 1 -> {
                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH)
            }
            hasFlash && !video && flash == 2 -> {
                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
            }
            else -> {
                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                if (hasFlash) b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
            }
        }
        if (useZoomRatio && Build.VERSION.SDK_INT >= 30) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
        else activeArray?.let { b.set(CaptureRequest.SCALER_CROP_REGION, cropRect(it)) }
        val fr = focusRegions
        if (fr != null) {
            if (maxAf > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, fr)
            if (maxAe > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, fr)
        } else {
            b.set(CaptureRequest.CONTROL_AF_REGIONS, null)
            b.set(CaptureRequest.CONTROL_AE_REGIONS, null)
        }
    }

    private fun cropRect(aa: Rect): Rect {
        val z = max(1f, zoom)
        val cw = (aa.width() / z).toInt()
        val chh = (aa.height() / z).toInt()
        val l = (aa.width() - cw) / 2
        val t = (aa.height() - chh) / 2
        return Rect(l, t, l + cw, t + chh)
    }

    fun setZoom(z: Float) {
        val nz = z.coerceIn(minZoom, maxZoom)
        if (abs(nz - zoom) < 0.001f) return
        zoom = nz
        post {
            val b = builder ?: return@post
            applyCommon(b)
            repeat()
        }
    }

    fun applySettings() {
        post {
            val b = builder ?: return@post
            applyCommon(b)
            repeat()
        }
    }

    // =====================================================================
    // Фокус касанием
    // =====================================================================

    private val unlockFocus = Runnable {
        try {
            focusLocked = false
            focusRegions = null
            val s = session
            val b = builder
            if (s != null && b != null) {
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                s.capture(b.build(), null, bg)
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                applyCommon(b)
                repeat()
            }
        } catch (e: Exception) { fail(e) }
    }

    /** nx, ny — точка в превью (0..1, как видит пользователь). */
    fun focusAt(nx0: Float, ny0: Float) {
        post {
            val s = session ?: return@post
            val b = builder ?: return@post
            val aa = activeArray ?: return@post
            if (maxAf <= 0 && maxAe <= 0) return@post
            val nx = if (facingFront) 1f - nx0 else nx0
            val ny = ny0
            val sx: Float
            val sy: Float
            when (sensorOrientation) {
                90 -> { sx = ny; sy = 1f - nx }
                270 -> { sx = 1f - ny; sy = nx }
                180 -> { sx = 1f - nx; sy = 1f - ny }
                else -> { sx = nx; sy = ny }
            }
            val base = if (useZoomRatio) Rect(0, 0, aa.width(), aa.height()) else cropRect(aa)
            val half = (min(base.width(), base.height()) * 0.08f).toInt()
            val cx = base.left + (sx * base.width()).toInt()
            val cy = base.top + (sy * base.height()).toInt()
            val r = Rect(max(base.left, cx - half), max(base.top, cy - half), min(base.right, cx + half), min(base.bottom, cy + half))
            focusRegions = arrayOf(MeteringRectangle(r, MeteringRectangle.METERING_WEIGHT_MAX - 1))
            focusLocked = afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO)
            bg?.removeCallbacks(unlockFocus)
            applyCommon(b)
            if (focusLocked) {
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                s.capture(b.build(), null, bg)
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            }
            repeat()
            bg?.postDelayed(unlockFocus, 4000)
        }
    }

    // =====================================================================
    // Фото
    // =====================================================================

    private val progressCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            process(result)
        }
    }

    private fun process(r: CaptureResult) {
        if (capState != 1 && capState != 2) return
        val ae = r.get(CaptureResult.CONTROL_AE_STATE)
        val el = SystemClock.elapsedRealtime() - capStart
        if (el > 1600) { shoot(); return }
        if (capState == 1) {
            if (ae == null || ae == CaptureResult.CONTROL_AE_STATE_PRECAPTURE || el > 350) capState = 2
        } else {
            if (ae == null || ae != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) shoot()
        }
    }

    fun takePhoto() {
        post {
            if (session == null || video || capState != 0) return@post
            val b = builder ?: return@post
            if (hasFlash && flash != 0) {
                capState = 1
                capStart = SystemClock.elapsedRealtime()
                b.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                session?.capture(b.build(), progressCb, bg)
                b.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
            } else shoot()
        }
    }

    private fun shoot() {
        capState = 3
        val dev = device
        val s = session
        val rd = reader
        val ps = previewSurface
        if (dev == null || s == null || rd == null) { capState = 0; return }
        try {
            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(rd.surface)
            if (ps != null) b.addTarget(ps)
            applyCommon(b)
            b.set(CaptureRequest.JPEG_ORIENTATION, outputOrientation())
            b.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(ss: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) { capState = 0 }
                override fun onCaptureFailed(ss: CameraCaptureSession, r: CaptureRequest, f: android.hardware.camera2.CaptureFailure) { capState = 0 }
            }, bg)
            ui { listener?.onShutter() }
        } catch (e: Exception) {
            capState = 0
            fail(e)
        }
    }

    private fun outputOrientation(): Int {
        val d = deviceDegrees
        return if (facingFront) (sensorOrientation - d + 360) % 360 else (sensorOrientation + d) % 360
    }

    private fun onJpeg(r: ImageReader) {
        val img = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return
        val bytes: ByteArray
        try {
            val buf = img.planes[0].buffer
            bytes = ByteArray(buf.remaining())
            buf.get(bytes)
        } finally {
            img.close()
        }
        if (captureForResult) { ui { listener?.onPhotoBytes(bytes) }; return }
        val uri = try { savePhoto(bytes) } catch (e: Exception) { fail(e); null }
        ui { listener?.onSaved(uri, false) }
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    @Suppress("DEPRECATION")
    private fun newMedia(video: Boolean): Uri? {
        val name = "XMB_" + stamp() + if (video) ".mp4" else ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (video) "video/mp4" else "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/XMB Camera")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "XMB Camera")
                dir.mkdirs()
                put(MediaStore.MediaColumns.DATA, File(dir, name).absolutePath)
            }
        }
        val coll = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        return ctx.contentResolver.insert(coll, values)
    }

    private fun publish(uri: Uri) {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            ctx.contentResolver.update(uri, v, null, null)
        }
    }

    private fun savePhoto(bytes: ByteArray): Uri? {
        val uri = newMedia(false) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        publish(uri)
        return uri
    }

    // =====================================================================
    // Видео
    // =====================================================================

    @Suppress("DEPRECATION")
    fun startRecording() {
        post {
            if (recording || !video) return@post
            val dev = device ?: return@post
            val st = texture.surfaceTexture ?: return@post
            val map = streamMap ?: return@post
            val vs = pickSize(map.getOutputSizes(MediaRecorder::class.java), 16f / 9f, 1920)
            val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
            try {
                val audio = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (audio) rec.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                val uri = newMedia(true) ?: throw IllegalStateException("нет места для видео")
                recUri = uri
                val pfd = ctx.contentResolver.openFileDescriptor(uri, "w") ?: throw IllegalStateException("файл не открылся")
                recPfd = pfd
                rec.setOutputFile(pfd.fileDescriptor)
                rec.setVideoEncodingBitRate(if (vs.width >= 1920) 17_000_000 else 10_000_000)
                rec.setVideoFrameRate(30)
                rec.setVideoSize(vs.width, vs.height)
                rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                if (audio) {
                    rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    rec.setAudioEncodingBitRate(192_000)
                    rec.setAudioSamplingRate(48_000)
                }
                rec.setOrientationHint(outputOrientation())
                rec.prepare()
            } catch (e: Exception) {
                rec.release()
                dropRecording()
                fail(e)
                return@post
            }
            recorder = rec
            try { session?.close() } catch (_: Exception) {}
            session = null
            val surf = Surface(st)
            previewSurface?.release()
            previewSurface = surf
            val rs = rec.surface
            dev.createCaptureSession(listOf(surf, rs), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (device !== dev) { s.close(); return }
                    session = s
                    try {
                        builder = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(surf); addTarget(rs)
                            applyCommon(this)
                        }
                        repeat()
                        rec.start()
                        recordStart = SystemClock.elapsedRealtime()
                        recording = true
                        ui { listener?.onCameraState() }
                    } catch (e: Exception) {
                        recorder = null
                        rec.release()
                        dropRecording()
                        fail(e)
                        startPreviewSession()
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    recorder = null
                    rec.release()
                    dropRecording()
                    ui { listener?.onCameraError("Не удалось начать запись") }
                    startPreviewSession()
                }
            }, bg)
        }
    }

    private fun dropRecording() {
        try { recPfd?.close() } catch (_: Exception) {}
        recPfd = null
        recUri?.let { try { ctx.contentResolver.delete(it, null, null) } catch (_: Exception) {} }
        recUri = null
    }

    fun stopRecording() {
        post {
            if (!recording) return@post
            stopRecordingNow()
            startPreviewSession()
        }
    }

    private fun stopRecordingNow() {
        recording = false
        try { session?.stopRepeating() } catch (_: Exception) {}
        var ok = true
        try { recorder?.stop() } catch (_: RuntimeException) { ok = false }
        try { recorder?.reset() } catch (_: Exception) {}
        recorder?.release()
        recorder = null
        try { recPfd?.close() } catch (_: Exception) {}
        recPfd = null
        val uri = recUri
        recUri = null
        if (uri != null) {
            if (ok) try { publish(uri) } catch (_: Exception) {}
            else try { ctx.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
        }
        ui { listener?.onSaved(if (ok) uri else null, true) }
    }
}
