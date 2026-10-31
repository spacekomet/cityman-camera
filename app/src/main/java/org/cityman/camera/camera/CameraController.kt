package org.cityman.camera.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.media.MediaActionSound
import android.media.MediaRecorder
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import android.view.SurfaceHolder
import kotlinx.coroutines.flow.MutableStateFlow
import org.cityman.camera.processing.BurstSetup
import org.cityman.camera.processing.MultiFrameProcessor
import org.cityman.camera.processing.ProcessingOptions
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class CaptureMode { PHOTO, VIDEO }

data class CameraState(
    val caps: CameraCaps? = null,
    val cameraIndex: Int = 0,
    val cameraCount: Int = 0,
    val mode: CaptureMode = CaptureMode.PHOTO,
    val format: PhotoFormat = PhotoFormat.FULL,
    val saveDng: Boolean = false,
    val grid: Boolean = false,
    /** Multi-frame RAW processing for photos, and its options. */
    val multiFrame: Boolean = false,
    /** Burst length, 0 = chosen from the light. */
    val mfFrames: Int = 0,
    val mfOptions: ProcessingOptions = ProcessingOptions(),
    val processing: Boolean = false,
    /** The phone is resting: multi-frame photos use the long night exposures. */
    val still: Boolean = false,
    val showHistogram: Boolean = false,
    /** Exposure and white balance locked (auto exposure keeps its current values). */
    val aeLock: Boolean = false,
    /** Video: time-lapse factor (0 = off) and electronic stabilisation. */
    val timelapse: Int = 0,
    val stabilise: Boolean = false,
    /** Launched from the lock screen: only this session's photos may be shown. */
    val secure: Boolean = false,
    val level: Boolean = false,
    /** Follow Android's dynamic accent colour (Material You) instead of amber. */
    val systemAccent: Boolean = true,
    /** Gallery app chosen for the thumbnail button (package name), null = system default. */
    val galleryPackage: String? = null,
    val shutterSound: Boolean = true,
    /** Self-timer length in seconds (0 = off) and the seconds left while it runs. */
    val timerSeconds: Int = 0,
    val countdown: Int = 0,
    val zebra: Boolean = false,
    val peaking: Boolean = false,
    /** Clockwise rotation from the sensor image to the viewfinder, for overlays. */
    val previewRotation: Int = 0,
    val controls: ManualControls = ManualControls(),
    val live: LiveValues = LiveValues(),
    val previewSize: Size? = null,
    val zoom: Float = 1f,
    val capturing: Boolean = false,
    val recording: Boolean = false,
    val recordingSince: Long = 0L,
    val thumbnail: Bitmap? = null,
    val lastUri: Uri? = null,
    val lastIsVideo: Boolean = false,
    /** Tap point in normalised viewfinder coordinates, while a tap focus/metering lock is active. */
    val focusPoint: Pair<Float, Float>? = null,
    val shutterCount: Int = 0,
    val message: String? = null,
)

/**
 * Camera2 front end. All camera work happens on one handler thread; files are written on a
 * second one. The UI talks to it through the public methods (any thread) and reads [state].
 */
class CameraController(private val context: Context) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val camThread = HandlerThread("arco-camera").apply { start() }
    private val camHandler = Handler(camThread.looper)
    private val ioThread = HandlerThread("arco-io").apply { start() }
    private val ioHandler = Handler(ioThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val camExecutor = Executor { camHandler.post(it) }
    private val store = MediaStoreWriter(context)
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val gpuThread = HandlerThread("arco-gpu").apply { start() }
    private val gpuHandler = Handler(gpuThread.looper)
    private val processor = MultiFrameProcessor()
    private val sound = MediaActionSound().apply {
        load(MediaActionSound.SHUTTER_CLICK)
        load(MediaActionSound.START_VIDEO_RECORDING)
        load(MediaActionSound.STOP_VIDEO_RECORDING)
    }

    private val _analysis = MutableStateFlow<Analysis?>(null)
    /** Latest viewfinder analysis (histogram, clipping, zebra/peaking overlay). */
    val analysis: StateFlow<Analysis?> = _analysis

    private val _state = MutableStateFlow(
        CameraState(
            saveDng = prefs.getBoolean("dng", false),
            grid = prefs.getBoolean("grid", false),
            format = runCatching { PhotoFormat.valueOf(prefs.getString("format", null) ?: "FULL") }.getOrDefault(PhotoFormat.FULL),
            multiFrame = prefs.getBoolean("mf", false),
            showHistogram = prefs.getBoolean("histogram", false),
            timelapse = prefs.getInt("timelapse", 0),
            stabilise = prefs.getBoolean("stabilise", false),
            level = prefs.getBoolean("level", false),
            mode = if (prefs.getString("mode", null) == "VIDEO") CaptureMode.VIDEO else CaptureMode.PHOTO,
            cameraIndex = prefs.getInt("camera", 0),
            systemAccent = prefs.getBoolean("systemAccent", true),
            galleryPackage = prefs.getString("gallery", null),
            shutterSound = prefs.getBoolean("sound", true),
            timerSeconds = prefs.getInt("timer", 0),
            zebra = prefs.getBoolean("zebra", false),
            peaking = prefs.getBoolean("peaking", false),
            mfFrames = prefs.getInt("mfFrames", 0),
            mfOptions = ProcessingOptions(
                denoise = prefs.getBoolean("mfDenoise", true),
                vivid = prefs.getBoolean("mfVivid", false),
                localTone = prefs.getBoolean("mfLocalTone", true),
                chromaDenoise = prefs.getBoolean("mfChroma", true),
                sharpen = prefs.getBoolean("mfSharpen", true),
            ),
        ),
    )
    val state: StateFlow<CameraState> = _state

    /** Back cameras first, then front ones. */
    private val cameraIds: List<String> = runCatching {
        manager.cameraIdList.sortedBy {
            if (manager.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] == CameraMetadata.LENS_FACING_FRONT) 1 else 0
        }
    }.getOrDefault(emptyList())

    // Device orientation in degrees (OrientationEventListener convention) and display rotation.
    @Volatile var deviceOrientation: Int = OrientationEventListener.ORIENTATION_UNKNOWN
    @Volatile var displayRotationDegrees: Int = 90
        set(value) {
            field = value
            caps?.let { c -> _state.update { it.copy(previewRotation = previewRotation(c)) } }
        }

    // ---- main thread: viewfinder surface -------------------------------------------------
    private var holder: SurfaceHolder? = null
    private var surfaceSize: Size? = null
    private var wantedSize: Size? = null
    private var pendingGen = -1
    private var configuredGen = -1
    private var started = false

    val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(h: SurfaceHolder) {
            holder = h
            wantedSize?.let { h.setFixedSize(it.width, it.height) }
        }

        override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {
            holder = h
            surfaceSize = Size(width, height)
            maybeConfigure()
        }

        override fun surfaceDestroyed(h: SurfaceHolder) {
            holder = null
            surfaceSize = null
            configuredGen = -1
            // The camera must stop drawing into the surface before it goes away.
            runOnCamAndWait { closeSession() }
        }
    }

    private fun maybeConfigure() {
        val h = holder ?: return
        val want = wantedSize ?: return
        if (surfaceSize != want || pendingGen == configuredGen) return
        configuredGen = pendingGen
        val gen = pendingGen
        val surface = h.surface
        camHandler.post { createSession(surface, gen) }
    }

    // ---- camera thread state -----------------------------------------------------------
    private var device: CameraDevice? = null
    private var opening = false
    private var session: CameraCaptureSession? = null
    private var caps: CameraCaps? = null
    private var previewSurface: Surface? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var video: VideoPipeline? = null
    private var analyzer: FrameAnalyzer? = null
    /** The HAL refused a session with the analysis stream on this camera. */
    private var analysisRefused = false
    /** 0 = normal photo streams; each camera crash in photo mode moves one step down (see recoverCamera). */
    private var photoStep = 0
    /** The photo fallback is only for the Lumia 950 (talkman); the 950 XL (cityman) keeps the original behaviour. */
    private val photoFallbackEnabled: Boolean =
        android.os.Build.MODEL.trim().equals("Lumia 950", ignoreCase = true) ||
            android.os.Build.DEVICE.trim().equals("talkman", ignoreCase = true)
    private var recorder: MediaRecorder? = null
    private var recordingUri: Uri? = null
    private var recordingFd: ParcelFileDescriptor? = null
    private var configGen = 0
    private var controls = ManualControls()
    private var zoom = 1f
    private var focusRegion: MeteringRectangle? = null
    private var meterRegion: MeteringRectangle? = null
    private var afState = CaptureResult.CONTROL_AF_STATE_INACTIVE
    private var autoIso = 100
    private var autoExposureNs = 33_000_000L
    private var lastLiveUpdate = 0L
    private var waitingAf = false
    private var afTriggeredForShot = false
    private var shot: Shot? = null

    private class Shot(val orientation: Int, val rawExpected: Boolean, val startedAt: Long, val burst: Boolean = false) {
        var jpegDone = false
        var rawDone = false
        var raw: Image? = null
        var result: TotalCaptureResult? = null
    }

    // ---- lifecycle ----------------------------------------------------------------------

    fun start() {
        started = true
        startGyro()
        if (_state.value.level) startTilt()
        _state.update { it.copy(cameraCount = cameraIds.size) }
        camHandler.post { openCamera() }
        ioHandler.post { loadLatestThumbnail() }
    }

    fun stop() {
        started = false
        stopGyro()
        stopTilt()
        wantedSize = null
        configuredGen = -1
        runOnCamAndWait(3000) { closeCamera() }
    }

    fun release() {
        gpuHandler.post { processor.release() }
        gpuThread.quitSafely()
        camThread.quitSafely()
        ioThread.quitSafely()
        sound.release()
    }

    // ---- public actions -----------------------------------------------------------------

    fun switchCamera() = camHandler.post {
        if (cameraIds.size < 2) return@post
        closeCamera()
        analysisRefused = false
        photoStep = 0
        controls = ManualControls()
        zoom = 1f
        _state.update {
            it.copy(
                cameraIndex = ((it.cameraIndex + 1) % cameraIds.size).also { i -> prefs.edit().putInt("camera", i).apply() },
                aeLock = false,
                controls = controls, zoom = 1f, focusPoint = null, live = LiveValues(),
            )
        }
        if (started) openCamera()
    }

    fun setMode(mode: CaptureMode) = camHandler.post {
        if (_state.value.mode == mode) return@post
        if (recorder != null) stopRecording(restartPreview = false)
        clearTapRegions()
        _state.update { it.copy(mode = mode) }
        prefs.edit().putString("mode", mode.name).apply()
        reconfigure()
    }

    fun setFormat(format: PhotoFormat) = camHandler.post {
        if (_state.value.format == format) return@post
        prefs.edit().putString("format", format.name).apply()
        _state.update { it.copy(format = format) }
        if (_state.value.mode == CaptureMode.PHOTO) reconfigure()
    }

    fun setAnalysisTools(histogram: Boolean, zebra: Boolean, peaking: Boolean) {
        prefs.edit().putBoolean("histogram", histogram).putBoolean("zebra", zebra).putBoolean("peaking", peaking).apply()
        _state.update { it.copy(showHistogram = histogram, zebra = zebra, peaking = peaking) }
        analyzer?.zebra = zebra
        analyzer?.peaking = peaking
    }

    fun setGrid(enabled: Boolean) {
        prefs.edit().putBoolean("grid", enabled).apply()
        _state.update { it.copy(grid = enabled) }
    }

    fun setMultiFrame(enabled: Boolean) = camHandler.post {
        if (_state.value.multiFrame == enabled) return@post
        prefs.edit().putBoolean("mf", enabled).apply()
        _state.update { it.copy(multiFrame = enabled) }
        if (_state.value.mode == CaptureMode.PHOTO) reconfigure()
    }

    fun setMultiFrameFrames(frames: Int) {
        prefs.edit().putInt("mfFrames", frames).apply()
        _state.update { it.copy(mfFrames = frames) }
    }

    fun setMultiFrameOptions(o: ProcessingOptions) {
        prefs.edit()
            .putBoolean("mfDenoise", o.denoise)
            .putBoolean("mfVivid", o.vivid)
            .putBoolean("mfLocalTone", o.localTone)
            .putBoolean("mfChroma", o.chromaDenoise)
            .putBoolean("mfSharpen", o.sharpen)
            .apply()
        _state.update { it.copy(mfOptions = o) }
    }

    fun setSaveDng(enabled: Boolean) = camHandler.post {
        if (_state.value.saveDng == enabled) return@post
        prefs.edit().putBoolean("dng", enabled).apply()
        _state.update { it.copy(saveDng = enabled) }
        if (_state.value.mode == CaptureMode.PHOTO) reconfigure()
    }

    fun setControls(c: ManualControls) = camHandler.post {
        controls = c
        Log.i(TAG, "controls $c")
        if (c.focusDiopters != null) {
            focusRegion = null
            _state.update { it.copy(focusPoint = if (meterRegion != null) it.focusPoint else null) }
        }
        _state.update { it.copy(controls = c) }
        startRepeating()
    }

    fun setZoom(z: Float) = camHandler.post {
        val c = caps ?: return@post
        zoom = z.coerceIn(1f, c.maxZoom)
        _state.update { it.copy(zoom = zoom) }
        startRepeating()
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    /** Focus and meter on a viewfinder point, given in normalised (0..1) view coordinates. */
    fun tapToFocus(nx: Float, ny: Float) = camHandler.post {
        val c = caps ?: return@post
        val preview = _state.value.previewSize ?: return@post
        val crop = cropRegion(c)
        var u = nx.coerceIn(0f, 1f)
        var v = ny.coerceIn(0f, 1f)
        if (c.front) u = 1f - u
        repeat(previewRotation(c) / 90) {
            val nu = v
            val nv = 1f - u
            u = nu
            v = nv
        }
        val pa = preview.width.toFloat() / preview.height
        var vw = crop.width().toFloat()
        var vh = crop.height().toFloat()
        if (vw / vh > pa) vw = vh * pa else vh = vw / pa
        val left = crop.centerX() - vw / 2f
        val top = crop.centerY() - vh / 2f
        val sx = left + u * vw
        val sy = top + v * vh
        val half = min(vw, vh) * 0.08f
        val aw = c.activeArray.width()
        val ah = c.activeArray.height()
        val r = Rect(
            (sx - half).roundToInt().coerceIn(0, aw - 1),
            (sy - half).roundToInt().coerceIn(0, ah - 1),
            (sx + half).roundToInt().coerceIn(1, aw),
            (sy + half).roundToInt().coerceIn(1, ah),
        )
        val region = MeteringRectangle(r, MeteringRectangle.METERING_WEIGHT_MAX - 1)
        meterRegion = if (c.maxAeRegions > 0) region else null
        focusRegion = if (c.hasAutoFocus && c.maxAfRegions > 0 && controls.focusDiopters == null) region else null
        _state.update { it.copy(focusPoint = nx to ny) }
        startRepeating()
        if (focusRegion != null) triggerAf()
    }

    fun clearFocusPoint() = camHandler.post {
        clearTapRegions()
        startRepeating()
    }

    /** Half press of the camera key. */
    fun focusKey() = camHandler.post {
        val c = caps ?: return@post
        if (c.hasAutoFocus && controls.focusDiopters == null) triggerAf()
    }

    /** Lock-screen launch: hide earlier photos (thumbnail) until the app is opened normally. */
    fun setSecure(secure: Boolean) {
        if (_state.value.secure == secure) return
        _state.update { it.copy(secure = secure, thumbnail = if (secure) null else it.thumbnail, lastUri = if (secure) null else it.lastUri) }
        if (!secure) ioHandler.post { loadLatestThumbnail() }
    }

    fun setLevel(on: Boolean) {
        prefs.edit().putBoolean("level", on).apply()
        _state.update { it.copy(level = on) }
        if (on && started) startTilt() else stopTilt()
    }

    // ---- level: device tilt from the accelerometer --------------------------------------

    private val _tilt = MutableStateFlow<Float?>(null)
    /** Roll of the phone relative to the screen, degrees (null when lying flat). */
    val tilt: StateFlow<Float?> = _tilt
    private var gx = 0f
    private var gy = 0f
    private var gz = 0f
    private var lastTilt = 0L
    private val tiltListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            gx = gx * 0.85f + e.values[0] * 0.15f
            gy = gy * 0.85f + e.values[1] * 0.15f
            gz = gz * 0.85f + e.values[2] * 0.15f
            val now = SystemClock.elapsedRealtime()
            if (now - lastTilt < 60) return
            lastTilt = now
            val planar = kotlin.math.sqrt(gx * gx + gy * gy)
            if (planar < 0.5f * kotlin.math.abs(gz)) {
                _tilt.value = null
                return
            }
            val roll = Math.toDegrees(kotlin.math.atan2(gx.toDouble(), gy.toDouble())).toFloat()
            var t = (roll - displayRotationDegrees + 540f) % 360f - 180f
            t -= Math.round(t / 90f) * 90f
            _tilt.value = t
        }

        override fun onAccuracyChanged(s: android.hardware.Sensor, accuracy: Int) {}
    }

    private fun startTilt() {
        val a = sensors?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) ?: return
        sensors.registerListener(tiltListener, a, android.hardware.SensorManager.SENSOR_DELAY_UI)
    }

    private fun stopTilt() {
        sensors?.unregisterListener(tiltListener)
        _tilt.value = null
    }

    fun setAeLock(on: Boolean) = camHandler.post {
        _state.update { it.copy(aeLock = on) }
        startRepeating()
    }

    fun setTimelapse(factor: Int) {
        prefs.edit().putInt("timelapse", factor).apply()
        _state.update { it.copy(timelapse = factor) }
    }

    fun setStabilise(on: Boolean) {
        prefs.edit().putBoolean("stabilise", on).apply()
        _state.update { it.copy(stabilise = on) }
    }

    fun setSystemAccent(on: Boolean) {
        prefs.edit().putBoolean("systemAccent", on).apply()
        _state.update { it.copy(systemAccent = on) }
    }

    /** Colour of the focus peaking marks (ARGB), following the UI accent. */
    @Volatile var peakingColor: Int = 0xFFFFB300.toInt()
        set(value) {
            field = value
            analyzer?.peakingColor = value
        }

    fun setGallery(pkg: String?) {
        prefs.edit().putString("gallery", pkg).apply()
        _state.update { it.copy(galleryPackage = pkg) }
    }

    fun setShutterSound(on: Boolean) {
        prefs.edit().putBoolean("sound", on).apply()
        _state.update { it.copy(shutterSound = on) }
    }

    fun setTimer(seconds: Int) {
        prefs.edit().putInt("timer", seconds).apply()
        _state.update { it.copy(timerSeconds = seconds) }
    }

    private val countdownTick = object : Runnable {
        override fun run() {
            val left = _state.value.countdown - 1
            if (left <= 0) {
                _state.update { it.copy(countdown = 0) }
                takePicture()
            } else {
                _state.update { it.copy(countdown = left) }
                camHandler.postDelayed(this, 1000)
            }
        }
    }

    fun shutter() = camHandler.post {
        when (_state.value.mode) {
            CaptureMode.PHOTO -> {
                val st = _state.value
                when {
                    // A second press cancels the self-timer.
                    st.countdown > 0 -> {
                        camHandler.removeCallbacks(countdownTick)
                        _state.update { it.copy(countdown = 0) }
                    }
                    st.timerSeconds > 0 -> {
                        _state.update { it.copy(countdown = st.timerSeconds) }
                        camHandler.postDelayed(countdownTick, 1000)
                    }
                    else -> takePicture()
                }
            }
            CaptureMode.VIDEO -> if (recorder == null) startRecording() else stopRecording(restartPreview = true)
        }
    }

    // ---- camera device / session --------------------------------------------------------

    private fun openCamera() {
        if (device != null || opening || !started || cameraIds.isEmpty()) return
        val index = _state.value.cameraIndex.coerceIn(0, cameraIds.size - 1)
        val id = cameraIds[index]
        val c = try {
            CameraCaps(id, manager.getCameraCharacteristics(id))
        } catch (e: Exception) {
            report("Camera $id: ${e.message}")
            return
        }
        c.jpegPixelLimit = if (photoStep >= 2) 8_000_000L else Long.MAX_VALUE
        c.previewWidthLimit = if (photoStep >= 3) 1280 else 1920
        caps = c
        _state.update { it.copy(caps = c) }
        try {
            opening = true
            manager.openCamera(id, camExecutor, deviceCallback)
        } catch (e: SecurityException) {
            opening = false
            report("Camera permission missing")
        } catch (e: CameraAccessException) {
            opening = false
            report("Camera $id unavailable: ${e.message}")
        }
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            opening = false
            if (!started) {
                camera.close()
                return
            }
            device = camera
            _state.update { it.copy(message = null) }
            reconfigure()
        }

        override fun onDisconnected(camera: CameraDevice) {
            opening = false
            camera.close()
            if (device == camera) device = null
            closeSession()
            report("The camera is in use by another app")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            opening = false
            camera.close()
            if (device == camera) device = null
            closeSession()
            closingSession = null
            pendingCreate = null
            releaseRetired()
            recoverCamera("camera error $error")
        }
    }

    private fun closeCamera() {
        camHandler.removeCallbacks(countdownTick)
        _state.update { it.copy(countdown = 0) }
        if (recorder != null) stopRecording(restartPreview = false)
        closeSession()
        pendingCreate = null
        device?.close()
        device = null
        // The device is closed: nothing waits for the old session any more.
        closingSession = null
        camHandler.removeCallbacks(closeTimeout)
        releaseRetired()
        video?.release()
        video = null
        analyzer?.release()
        analyzer = null
        _analysis.value = null
        waitingAf = false
        camHandler.removeCallbacks(afTimeout)
    }

    /**
     * Stops the current session cleanly. This HAL (2015 Qualcomm HAL3) cannot reconfigure streams
     * while frames are in flight: repeating requests are stopped and in-flight captures aborted,
     * and the next session is only created once this one reports onClosed. Its image readers are
     * closed then too, not while the HAL may still write into them.
     */
    private fun closeSession() {
        if (recorder != null) stopRecording(restartPreview = false)
        val s = session
        session = null
        if (s != null) {
            try { s.stopRepeating() } catch (_: Exception) {}
            try { s.abortCaptures() } catch (_: Exception) {}
            try { s.close() } catch (_: Exception) {}
            closingSession = s
            camHandler.removeCallbacks(closeTimeout)
            camHandler.postDelayed(closeTimeout, 2500)
        }
        previewSurface = null
        jpegReader?.let { retiredReaders += it }
        jpegReader = null
        // A burst frame may be on the GPU right now: stop the burst and let the GPU thread
        // finish that frame before the reader (and the frame's buffer) goes away.
        burst?.let { job ->
            job.cancelled = true
            job.failed = true
            runOnThreadAndWait(gpuHandler, 3000) {}
        }
        rawReader?.let { retiredReaders += it }
        rawReader = null
        finishShot(force = true)
        if (closingSession == null) releaseRetired()
    }

    /** The session being closed; new sessions wait for its onClosed. */
    private var closingSession: CameraCaptureSession? = null
    private var pendingCreate: (() -> Unit)? = null
    private val retiredReaders = ArrayList<ImageReader>()

    private val closeTimeout = Runnable {
        if (closingSession != null) {
            Log.w(TAG, "old session did not report onClosed in time")
            sessionClosed(closingSession!!)
        }
    }

    /** cam thread: the old session is gone, run what waited for it. */
    private fun sessionClosed(s: CameraCaptureSession) {
        if (s !== closingSession) return
        closingSession = null
        camHandler.removeCallbacks(closeTimeout)
        releaseRetired()
        pendingCreate?.let {
            pendingCreate = null
            it()
        }
    }

    private fun releaseRetired() {
        retiredReaders.forEach { runCatching { it.close() } }
        retiredReaders.clear()
    }

    /** New stream set: pick sizes, resize the viewfinder surface, then [createSession]. */
    private fun reconfigure() {
        val c = caps ?: return
        if (device == null) return
        closeSession()
        configGen++
        val gen = configGen
        val st = _state.value
        val preview = if (st.mode == CaptureMode.VIDEO) c.previewSize(c.videoSize()) else c.previewSize(c.jpegSize(st.format))
        _state.update { it.copy(previewSize = preview) }
        mainHandler.post {
            wantedSize = preview
            pendingGen = gen
            holder?.setFixedSize(preview.width, preview.height)
            maybeConfigure()
        }
    }

    private fun createSession(surface: Surface, gen: Int, onReady: (() -> Unit)? = null) {
        val dev = device ?: return
        val c = caps ?: return
        if (gen != configGen || !surface.isValid) return
        if (closingSession != null) {
            // Wait for the old session to drain and close first (latest request wins).
            pendingCreate = { createSession(surface, gen, onReady) }
            return
        }
        val st = _state.value
        previewSurface = surface
        val outputs = mutableListOf(OutputConfiguration(surface))
        var withRaw = false
        if (st.mode == CaptureMode.PHOTO) {
            val js = c.jpegSize(st.format)
            jpegReader = ImageReader.newInstance(js.width, js.height, ImageFormat.JPEG, 2).also {
                it.setOnImageAvailableListener(::onJpeg, ioHandler)
                outputs += OutputConfiguration(it.surface)
            }
            val rs = c.rawSize
            if ((st.saveDng || st.multiFrame) && rs != null) {
                // A burst can queue its frames here while the GPU merges them one by one.
                val images = if (st.multiFrame) MAX_BURST + 1 else 2
                rawReader = ImageReader.newInstance(rs.width, rs.height, ImageFormat.RAW_SENSOR, images).also {
                    it.setOnImageAvailableListener(::onRaw, ioHandler)
                    outputs += OutputConfiguration(it.surface)
                }
                withRaw = true
            }
        }
        var withAnalysis = false
        if (st.mode == CaptureMode.PHOTO && !analysisRefused) {
            val preview = _state.value.previewSize
            val asz = preview?.let { c.analysisSize(it) }
            if (asz != null) {
                val a = analyzer?.takeIf { it.size == asz } ?: run {
                    analyzer?.release()
                    FrameAnalyzer(asz) { _analysis.value = it }.also { analyzer = it }
                }
                a.zebra = st.zebra
                a.peaking = st.peaking
                a.peakingColor = peakingColor
                outputs += OutputConfiguration(a.reader.surface)
                withAnalysis = true
            }
        }
        if (st.mode == CaptureMode.VIDEO) {
            val size = c.videoSize()
            val vp = video?.takeIf { it.size == size } ?: run {
                video?.release()
                VideoPipeline(size).also { video = it }
            }
            outputs += OutputConfiguration(vp.reader.surface)
        }
        Log.i(TAG, "session gen=$gen camera=${c.id} mode=${st.mode} preview=${_state.value.previewSize} jpeg=${c.jpegSize(st.format)} step=$photoStep raw=$withRaw analysis=$withAnalyss"i)
        _state.update { it.copy(previewRotation = previewRotation(c)) }
        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR, outputs, camExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onClosed(s: CameraCaptureSession) {
                    sessionClosed(s)
                }

                override fun onConfigured(s: CameraCaptureSession) {
                    if (gen != configGen || device == null) {
                        closingSession = s
                        s.close()
                        return
                    }
                    session = s
                    recoveries = 0
                    startRepeating()
                    onReady?.invoke()
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    Log.e(TAG, "session configuration failed (raw=$withRaw, analysis=$withAnalysis)")
                    if (gen != configGen) return
                    if (withAnalysis) {
                        // Try again without the analysis stream (histogram, zebra, peaking).
                        analysisRefused = true
                        analyzer?.release()
                        analyzer = null
                        reconfigure()
                    } else if (withRaw) {
                        _state.update { it.copy(saveDng = false, multiFrame = false) }
                        report("RAW is not available together with this photo size")
                        reconfigure()
                    } else {
                        report("Could not start the camera session")
                        if (recorder != null) stopRecording(restartPreview = false)
                    }
                }
            },
        )
        try {
            dev.createCaptureSession(config)
        } catch (e: Exception) {
            Log.e(TAG, "createCaptureSession", e)
            recoverCamera("createCaptureSession: ${e.message}")
        }
    }

    private var recoveries = 0

    /**
     * The HAL refused or errored: close the device and open it again, once per launch, before
     * telling the user.
     */
    private fun recoverCamera(why: String) {
        // Some camera HALs crash with the full set of photo streams. Simplify them step by step:
        // 1 = no analysis stream, 2 = JPEG of at most 8 MP, 3 = preview of at most 1280 px wide.
        if (photoFallbackEnabled && _state.value.mode == CaptureMode.PHOTO && photoStep < 3) {
            photoStep++
            if (photoStep == 1) {
                analysisRefused = true
                analyzer?.release()
                analyzer = null
            }
            Log.w(TAG, "photo fallback step $photoStep after: $why")
            recoveries = 0
        }

        if (recoveries >= 1 || !started) {
            report("Could not start the camera session: $why")
            return
        }
        recoveries++
        Log.w(TAG, "reopening the camera after: $why")
        closeCamera()
        camHandler.postDelayed({ if (started) openCamera() }, 700)
    }

    private fun previewRequest(): CaptureRequest.Builder? {
        val dev = device ?: return null
        val surface = previewSurface ?: return null
        val recording = recorder != null
        val b = dev.createCaptureRequest(if (recording) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
        b.addTarget(surface)
        if (recording) video?.let { b.addTarget(it.reader.surface) }
        if (_state.value.mode == CaptureMode.PHOTO && session != null) analyzer?.let { b.addTarget(it.reader.surface) }
        if (zslProbe && _state.value.mode == CaptureMode.PHOTO) rawReader?.let { b.addTarget(it.surface) }
        applyControls(b, still = false)
        return b
    }

    private fun startRepeating() {
        val s = session ?: return
        val b = previewRequest() ?: return
        try {
            s.setRepeatingRequest(b.build(), repeatingCallback, camHandler)
        } catch (e: Exception) {
            Log.e(TAG, "setRepeatingRequest", e)
        }
    }

    private fun triggerAf() {
        val s = session ?: return
        val b = previewRequest() ?: return
        try {
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            s.capture(b.build(), null, camHandler)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            s.capture(b.build(), null, camHandler)
        } catch (e: Exception) {
            Log.e(TAG, "AF trigger", e)
        }
    }

    private fun cancelAfTrigger() {
        val s = session ?: return
        val b = previewRequest() ?: return
        try {
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            s.capture(b.build(), null, camHandler)
        } catch (_: Exception) {
        }
    }

    private fun clearTapRegions() {
        focusRegion = null
        meterRegion = null
        _state.update { it.copy(focusPoint = null) }
    }

    // ---- request settings ---------------------------------------------------------------

    private fun applyControls(b: CaptureRequest.Builder, still: Boolean) {
        val c = caps ?: return
        val video = _state.value.mode == CaptureMode.VIDEO
        val m = controls
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

        // Focus
        val fd = m.focusDiopters
        when {
            fd != null && c.hasFocus -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, fd.coerceIn(0f, c.minFocusDiopters))
            }
            focusRegion != null -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(focusRegion))
            }
            else -> {
                val wanted = if (video) CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO else CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                val mode = when {
                    wanted in c.afModes -> wanted
                    CameraMetadata.CONTROL_AF_MODE_AUTO in c.afModes -> CameraMetadata.CONTROL_AF_MODE_AUTO
                    else -> CameraMetadata.CONTROL_AF_MODE_OFF
                }
                b.set(CaptureRequest.CONTROL_AF_MODE, mode)
            }
        }

        // Exposure
        if ((m.iso == null && m.exposureNs == null) || !c.manualSensor) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, m.evSteps.coerceIn(c.evRange.lower, c.evRange.upper))
            c.fpsRange(video)?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            if (_state.value.aeLock) b.set(CaptureRequest.CONTROL_AE_LOCK, true)
            meterRegion?.let { b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(it)) }
        } else {
            val (iso, exposure) = manualExposure(c, m)
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, if (video) max(exposure, 33_333_333L) else exposure)
        }

        // White balance
        val k = m.wbKelvin
        val cal = c.color
        if (k != null && cal != null && c.manualPostProcessing) {
            val corr = cal.forKelvin(k)
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            b.set(CaptureRequest.COLOR_CORRECTION_GAINS, corr.gains)
            b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, corr.transform)
        } else {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            if (_state.value.aeLock) b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
        }

        b.set(CaptureRequest.SCALER_CROP_REGION, cropRegion(c))

        if (still) {
            b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
            b.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            b.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation(c))
        }
    }

    /**
     * ISO and shutter for manual exposure. When only one of them is set, the other keeps the
     * exposure that auto exposure last measured, shifted by the exposure compensation.
     */
    private fun manualExposure(c: CameraCaps, m: ManualControls): Pair<Int, Long> {
        val isoRange = c.isoRange
        val expRange = c.exposureRange
        val ev = 2.0.pow(m.evSteps * c.evStep.toDouble())
        val product = autoIso.toDouble() * autoExposureNs * ev
        val iso = m.iso ?: (product / (m.exposureNs ?: autoExposureNs)).roundToInt()
        val clampedIso = if (isoRange != null && m.iso == null) iso.coerceIn(isoRange.lower, isoRange.upper) else iso
        val exposure = m.exposureNs ?: (product / clampedIso).roundToLong().coerceAtMost(MAX_AUTO_SHUTTER_NS)
        val clampedExp = if (expRange != null) exposure.coerceIn(expRange.lower, expRange.upper) else exposure
        return clampedIso to clampedExp
    }

    private fun cropRegion(c: CameraCaps): Rect {
        val w = c.activeArray.width()
        val h = c.activeArray.height()
        val z = zoom.coerceIn(1f, max(1f, c.maxZoom))
        val cw = (w / z).roundToInt()
        val ch = (h / z).roundToInt()
        val left = (w - cw) / 2
        val top = (h - ch) / 2
        return Rect(left, top, left + cw, top + ch)
    }

    /** Clockwise rotation from the sensor image to the viewfinder on this display. */
    private fun previewRotation(c: CameraCaps): Int =
        if (c.front) (c.sensorOrientation + displayRotationDegrees) % 360
        else (c.sensorOrientation - displayRotationDegrees + 360) % 360

    private fun jpegOrientation(c: CameraCaps): Int {
        var d = deviceOrientation
        if (d == OrientationEventListener.ORIENTATION_UNKNOWN) d = (360 - displayRotationDegrees) % 360
        d = ((d + 45) / 90 * 90) % 360
        if (c.front) d = -d
        return (c.sensorOrientation + d + 360) % 360
    }

    // ---- results ------------------------------------------------------------------------

    /** Debug: RAW in every viewfinder frame, to measure whether zero shutter lag is possible. */
    @Volatile var zslProbe = false
        set(value) {
            field = value
            camHandler.post { startRepeating() }
        }
    private var probeLast = 0L
    private var probeCount = 0
    private var probeSum = 0L

    private val repeatingCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val ts = result[CaptureResult.SENSOR_TIMESTAMP] ?: 0L
            if (probeLast != 0L) { probeSum += ts - probeLast; probeCount++ }
            probeLast = ts
            if (probeCount == 40) {
                Log.i(TAG, "viewfinder ${String.format(java.util.Locale.US, "%.1f", 1e9 * probeCount / probeSum)} fps (raw in repeating: $zslProbe)")
                probeCount = 0; probeSum = 0
            }
            val iso = result[CaptureResult.SENSOR_SENSITIVITY]
            val exp = result[CaptureResult.SENSOR_EXPOSURE_TIME]
            if (result[CaptureResult.CONTROL_AE_MODE] == CameraMetadata.CONTROL_AE_MODE_ON && iso != null && exp != null && iso > 0 && exp > 0) {
                autoIso = iso
                autoExposureNs = exp
            }
            val af = result[CaptureResult.CONTROL_AF_STATE] ?: CaptureResult.CONTROL_AF_STATE_INACTIVE
            afState = af
            if (waitingAf && (af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED)) {
                waitingAf = false
                fireStill()
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastLiveUpdate < 150) return
            lastLiveUpdate = now
            val awbAuto = result[CaptureResult.CONTROL_AWB_MODE] == CameraMetadata.CONTROL_AWB_MODE_AUTO
            val gains = result[CaptureResult.COLOR_CORRECTION_GAINS]
            val kelvin = if (awbAuto && gains != null) caps?.color?.kelvinFor(gains) else null
            _state.update {
                it.copy(
                    live = LiveValues(
                        iso = iso ?: 0,
                        exposureNs = exp ?: 0,
                        focusDiopters = result[CaptureResult.LENS_FOCUS_DISTANCE] ?: 0f,
                        kelvin = kelvin,
                        afState = af,
                    ),
                )
            }
        }
    }

    // ---- photo --------------------------------------------------------------------------

    private val afTimeout = Runnable {
        if (waitingAf) {
            waitingAf = false
            fireStill()
        }
    }

    private fun takePicture() {
        val c = caps ?: return
        if (session == null || jpegReader == null) return
        val current = shot
        if (current != null) {
            if (SystemClock.elapsedRealtime() - current.startedAt < 30_000) return
            finishShot(force = true)
        }
        val mf = _state.value.multiFrame && rawReader != null
        shot = Shot(jpegOrientation(c), rawReader != null && !mf, SystemClock.elapsedRealtime(), mf)
        _state.update { it.copy(capturing = true) }
        analyzer?.paused = true
        val needAf = c.hasAutoFocus && controls.focusDiopters == null && focusRegion == null &&
            afState != CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED &&
            afState != CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
        if (needAf) {
            afTriggeredForShot = true
            waitingAf = true
            triggerAf()
            camHandler.postDelayed(afTimeout, 1200)
        } else {
            fireStill()
        }
    }

    private fun fireStill() {
        camHandler.removeCallbacks(afTimeout)
        if (shot?.burst == true) {
            fireBurst()
            return
        }
        val s = session
        val dev = device
        val jpeg = jpegReader
        val preview = previewSurface
        if (s == null || dev == null || jpeg == null || preview == null) {
            finishShot(force = true)
            return
        }
        try {
            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(jpeg.surface)
            rawReader?.let { b.addTarget(it.surface) }
            b.addTarget(preview)
            applyControls(b, still = true)
            s.capture(b.build(), stillCallback, camHandler)
        } catch (e: Exception) {
            Log.e(TAG, "still capture", e)
            report("Capture failed: ${e.message}")
            finishShot(force = true)
        }
    }

    private val stillCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(s: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
            if (_state.value.shutterSound) sound.play(MediaActionSound.SHUTTER_CLICK)
            _state.update { it.copy(shutterCount = it.shutterCount + 1) }
        }

        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val current = shot
            if (current != null) {
                synchronized(current) { current.result = result }
                ioHandler.post { writeDngIfReady(current) }
            }
            if (afTriggeredForShot) {
                afTriggeredForShot = false
                cancelAfTrigger()
            }
        }

        override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            Log.e(TAG, "still capture failed: reason ${failure.reason}")
            report("Capture failed")
            if (afTriggeredForShot) {
                afTriggeredForShot = false
                cancelAfTrigger()
            }
            finishShot(force = true)
        }
    }

    private fun onJpeg(reader: ImageReader) {
        val image = try {
            reader.acquireNextImage()
        } catch (e: Exception) {
            null
        } ?: return
        val bytes = try {
            val buffer = image.planes[0].buffer
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } finally {
            image.close()
        }
        val current = shot
        val orientation = current?.orientation ?: 0
        val name = store.baseName("IMG")
        try {
            val uri = store.createImage("$name.jpg", "image/jpeg") ?: error("MediaStore insert failed")
            store.write(uri) { it.write(bytes) }
            store.publish(uri)
            val thumb = MediaStoreWriter.thumbnailFromJpeg(bytes)
            _state.update { it.copy(thumbnail = thumb ?: it.thumbnail, lastUri = uri, lastIsVideo = false) }
            Log.i(TAG, "saved $uri (${bytes.size} bytes, orientation $orientation)")
        } catch (e: Exception) {
            Log.e(TAG, "save JPEG", e)
            report("Could not save the photo: ${e.message}")
        }
        if (current != null) {
            synchronized(current) { current.jpegDone = true }
            camHandler.post { if (shot === current) finishShot(force = false) }
        }
    }

    private fun onRaw(reader: ImageReader) {
        val image = try {
            reader.acquireNextImage()
        } catch (e: Exception) {
            null
        } ?: return
        val job = burst
        if (job != null) {
            gpuHandler.post { burstFrame(job, image) }
            return
        }
        val current = shot
        if (current == null) {
            image.close()
            return
        }
        synchronized(current) { current.raw = image }
        writeDngIfReady(current)
    }

    /** io thread */
    private fun writeDngIfReady(current: Shot) {
        val (raw, result) = synchronized(current) {
            val r = current.raw ?: return
            val res = current.result ?: return
            current.raw = null
            r to res
        }
        val c = caps
        try {
            if (c != null) {
                val uri = store.createImage(store.baseName("IMG") + ".dng", "image/x-adobe-dng") ?: error("MediaStore insert failed")
                DngCreator(c.chars, result).use { dng ->
                    dng.setOrientation(exifOrientation(current.orientation))
                    store.write(uri) { dng.writeImage(it, raw) }
                }
                store.publish(uri)
                Log.i(TAG, "saved $uri")
            }
        } catch (e: Exception) {
            Log.e(TAG, "save DNG", e)
            report("Could not save the DNG: ${e.message}")
        } finally {
            raw.close()
        }
        synchronized(current) { current.rawDone = true }
        camHandler.post { if (shot === current) finishShot(force = false) }
    }

    /** cam thread */
    private fun finishShot(force: Boolean) {
        val current = shot ?: return
        val done = synchronized(current) { current.jpegDone && (!current.rawExpected || current.rawDone) }
        if (!done && !force) return
        if (force) synchronized(current) { current.raw?.close(); current.raw = null }
        if (force && current.burst) {
            burst?.let { job ->
                job.failed = true
                gpuHandler.post { maybeFinishBurst(job) }
            }
            burst = null
        }
        shot = null
        analyzer?.paused = false
        waitingAf = false
        camHandler.removeCallbacks(afTimeout)
        _state.update { it.copy(capturing = false, processing = false) }
    }

    // ---- multi-frame burst ------------------------------------------------------------

    /** What goes into the EXIF of a multi-frame photo. */
    private data class ShotInfo(val iso: Int, val exposureNs: Long, val frames: Int, val fNumber: Float?, val focalMm: Float?)

    private class Burst(val total: Int, val setup: BurstSetup, val info: ShotInfo) {
        /** gpu thread */
        var received = 0
        @Volatile var failed = false
        /** Stopped on purpose (app paused, camera closed): no error message. */
        @Volatile var cancelled = false
        var finished = false
        @Volatile var expected = total
        @Volatile var firstResult: TotalCaptureResult? = null
    }

    @Volatile private var burst: Burst? = null

    private data class BurstPlan(
        val iso: Int,
        val exposureNs: Long,
        val gain: Float,
        val frames: Int,
        val underEv: Float = BURST_UNDER_EV.toFloat(),
        /** Extra highlight frames (bracketing) at [shortExposureNs], appended after [frames]. */
        val shortFrames: Int = 0,
        val shortExposureNs: Long = 0,
    )

    /**
     * Exposure of the burst frames. Automatic: 0.7 EV under what auto exposure measured (to keep
     * highlights), shutter at most 1/15 s, ISO within what the HAL accepts manually; whatever is
     * missing becomes digital gain after the merge, where the noise is lower.
     */
    private fun burstPlan(c: CameraCaps): BurstPlan {
        val m = controls
        val isoRange = c.isoRange
        val expRange = c.exposureRange
        val omega = gyroRate
        // Focal length in RAW pixels (both lenses are about 70° wide) and the blur we accept per
        // frame: 2 px, twice that behind the rear lens' optical stabiliser.
        val rawWidth = c.rawSize?.width ?: 4000
        val focalPx = rawWidth / 1.4
        val blurPx = if (c.front) 2.0 else 4.0
        val tripod = isStill()
        val shakeLimit = when {
            tripod -> NIGHT_SHUTTER_NS.toDouble()
            gyroSamples < 20 || gyroMaxSeen <= 0.0 -> BURST_LOW_LIGHT_SHUTTER_NS.toDouble()
            omega <= 0.0 -> BURST_LOW_LIGHT_SHUTTER_NS.toDouble()
            else -> (blurPx / (omega * focalPx) * 1e9).coerceIn(1e9 / 60, BURST_LOW_LIGHT_SHUTTER_NS.toDouble())
        }.toLong()
        var underUsed = 0f
        val (iso, exposure, gain) = if (m.iso != null || m.exposureNs != null || isoRange == null || expRange == null) {
            val (i, e) = manualExposure(c, m)
            Triple(i, e, 1f)
        } else {
            val ev = 2.0.pow(m.evSteps * c.evStep.toDouble())
            val product = autoIso.toDouble() * autoExposureNs * ev
            // Under-exposure from the viewfinder: more when highlights are blown, less when not.
            val clipped = analyzer?.latestClipped ?: -1f
            val underEv = when {
                clipped < 0f -> BURST_UNDER_EV
                clipped > 0.04f -> 1.6
                clipped > 0.01f -> 1.1
                clipped < 0.002f -> 0.4
                else -> BURST_UNDER_EV
            }
            underUsed = underEv.toFloat()
            val target = product * 2.0.pow(-underEv)
            // Short frames keep hand shake out of each frame (merging takes care of the noise):
            // 1/30 s or what the gyroscope allows, longer only once ISO is at its maximum.
            // Manual ISO stops at the sensor's analogue maximum (342 here, the HAL clamps higher
            // values); handheld, up to 2× digital gain after the merge costs less than shake.
            // On a tripod (gyroscope still) the frames grow up to 1/2 s instead of gaining.
            // The gyroscope only shortens frames when there is light to spare: it never pushes
            // the digital gain past 2× (handheld frames may still grow to 1/10 s for that).
            val base = min(BURST_MAX_SHUTTER_NS, shakeLimit)
            val atMaxIso = (target / (isoRange.upper * if (tripod) 1.0 else BURST_DIGITAL_GAIN_FIRST)).toLong()
            val cap = atMaxIso.coerceIn(base, if (tripod) NIGHT_SHUTTER_NS else max(base, BURST_LOW_LIGHT_SHUTTER_NS))
            val exp = (target / isoRange.lower).toLong().coerceIn(expRange.lower, min(cap, expRange.upper))
            val i = (target / exp).roundToInt().coerceIn(isoRange.lower, isoRange.upper)
            Triple(i, exp, (product / (i.toDouble() * exp)).toFloat().coerceIn(1f, 64f))
        }
        // Fewer frames when the phone is getting hot.
        val headroom = runCatching { powerManager?.getThermalHeadroom(10) ?: 0f }.getOrDefault(0f)
        val hot = headroom.isFinite() && headroom >= 0.9f ||
            (powerManager?.currentThermalStatus ?: 0) >= android.os.PowerManager.THERMAL_STATUS_MODERATE
        val chosen = _state.value.mfFrames
        val frames = if (hot) 4 else when {
            chosen > 0 -> chosen
            tripod && (gain >= 1.5f || exposure >= 100_000_000L) -> MAX_BURST
            gain >= 1.8f || (isoRange != null && iso >= isoRange.upper * 0.9) -> 8
            else -> 6
        }.coerceIn(2, MAX_BURST)
        if (hot) Log.w(TAG, "thermal headroom $headroom: burst limited to $frames frames")
        Log.i(TAG, "plan: accel jitter ${String.format(java.util.Locale.US, "%.3f", accelJitter)}, gyro ${String.format(java.util.Locale.US, "%.4f", omega)} rad/s${if (tripod) " (still)" else ""}, shake limit ${shakeLimit / 1000} µs, clipped ${analyzer?.latestClipped}")
        // Backlit scenes: two frames 2 EV shorter recover what is blown in the others.
        val clippedNow = analyzer?.latestClipped ?: 0f
        val shortExp = exposure / 4
        val bracket = m.iso == null && m.exposureNs == null && !tripod && clippedNow > 0.02f &&
            expRange != null && shortExp >= expRange.lower && frames + 2 <= MAX_BURST
        return BurstPlan(
            iso, exposure, gain, frames, underUsed,
            shortFrames = if (bracket) 2 else 0, shortExposureNs = if (bracket) shortExp else 0,
        )
    }

    // ---- gyroscope: how much the hand shakes, for the burst exposure ----------------------

    private val sensors = context.getSystemService(android.hardware.SensorManager::class.java)
    private val powerManager = context.getSystemService(android.os.PowerManager::class.java)
    @Volatile private var gyroRate = 0.0
    @Volatile private var gyroSamples = 0
    /** Largest rate seen since the camera started: a gyroscope that never moves is not trusted. */
    @Volatile private var gyroMaxSeen = 0.0
    @Volatile private var stillSince = 0L

    /**
     * Phone resting (tripod, table): no rotation above the threshold for 1.5 s. A gyroscope that
     * has not shown any movement yet is not trusted alone: then the accelerometer's jitter decides.
     */
    private fun isStill(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val accelStill = accelStillSince > 0L && now - accelStillSince > 1500
        if (gyroSamples > 20 && gyroMaxSeen > 0.0) {
            return stillSince > 0L && now - stillSince > 1500 && (accelSamples < 20 || accelStill)
        }
        return accelSamples > 20 && accelStill
    }

    // Accelerometer jitter (deviation from its slow average), for the still test.
    @Volatile private var accelStillSince = 0L
    @Volatile private var accelSamples = 0
    @Volatile private var accelJitter = 0f
    private var ax = 0f
    private var ay = 0f
    private var az = 0f
    private val accelListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            if (accelSamples == 0) { ax = e.values[0]; ay = e.values[1]; az = e.values[2] }
            val dx = e.values[0] - ax
            val dy = e.values[1] - ay
            val dz = e.values[2] - az
            ax += dx * 0.05f; ay += dy * 0.05f; az += dz * 0.05f
            accelSamples++
            val jitter = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            // Average the jitter: single samples of sensor noise reach 0.1 m/s² even at rest.
            accelJitter = if (accelSamples == 1) jitter else accelJitter * 0.9f + jitter * 0.1f
            val now = SystemClock.elapsedRealtime()
            if (accelJitter > ACCEL_STILL_MS2 || accelStillSince == 0L) accelStillSince = now
            val still = isStill()
            if (still != _state.value.still) _state.update { it.copy(still = still) }
        }

        override fun onAccuracyChanged(s: android.hardware.Sensor, accuracy: Int) {}
    }
    private val gyroListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            val w = kotlin.math.sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble())
            // Mean over about 0.4 s at 50 Hz: the hand's tremor, not the peak of the tap.
            gyroRate = if (gyroSamples == 0) w else gyroRate * 0.95 + w * 0.05
            video?.addGyro(e.timestamp, e.values[0], e.values[1], e.values[2])
            gyroSamples++
            if (w > gyroMaxSeen) gyroMaxSeen = w
            val now = SystemClock.elapsedRealtime()
            if (w > TRIPOD_RAD_S || stillSince == 0L) stillSince = now
            val still = isStill()
            if (still != _state.value.still) _state.update { it.copy(still = still) }
        }

        override fun onAccuracyChanged(s: android.hardware.Sensor, accuracy: Int) {}
    }

    private fun startGyro() {
        val g = sensors?.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE) ?: return
        gyroSamples = 0
        gyroRate = 0.0
        gyroMaxSeen = 0.0
        stillSince = 0L
        accelSamples = 0
        accelStillSince = 0L
        sensors.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)?.let {
            sensors.registerListener(accelListener, it, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        }
        sensors.registerListener(gyroListener, g, android.hardware.SensorManager.SENSOR_DELAY_GAME)
    }

    private fun stopGyro() {
        sensors?.unregisterListener(gyroListener)
        sensors?.unregisterListener(accelListener)
        gyroSamples = 0
        gyroRate = 0.0
    }

    private fun cfaChannels(c: CameraCaps): IntArray = when (c.chars[CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT]) {
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> intArrayOf(1, 0, 3, 2)
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> intArrayOf(1, 3, 0, 2)
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> intArrayOf(3, 1, 2, 0)
        else -> intArrayOf(0, 1, 2, 3)
    }

    /** cam thread */
    private fun fireBurst() {
        val c = caps
        val s = session
        val dev = device
        val raw = rawReader
        val preview = previewSurface
        val current = shot
        if (c == null || s == null || dev == null || raw == null || preview == null || current == null) {
            finishShot(force = true)
            return
        }
        val plan = burstPlan(c)
        val pattern = c.chars[CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN]
        val black = FloatArray(4) { i -> (pattern?.getOffsetForIndex(i % 2, i / 2) ?: 64).toFloat() }
        val white = (c.chars[CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL] ?: 1023).toFloat()
        val setup = BurstSetup(
            width = raw.width, height = raw.height, chanAt = cfaChannels(c), black = black, white = white,
            gain = plan.gain, rotation = current.orientation, iso = plan.iso, options = _state.value.mfOptions,
            frames = plan.frames + plan.shortFrames,
            underEv = plan.underEv,
            zoom = zoom,
            shortFrames = plan.shortFrames,
            crop = if (raw.width == c.activeArray.width() && raw.height == c.activeArray.height()) 0 else 16,
        )
        val info = ShotInfo(
            iso = plan.iso, exposureNs = plan.exposureNs, frames = plan.frames,
            fNumber = c.chars[CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES]?.firstOrNull(),
            focalMm = c.chars[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.firstOrNull()?.takeIf { it > 0f },
        )
        val job = Burst(plan.frames + plan.shortFrames, setup, info)
        Log.i(TAG, "burst ${plan.frames} × ISO ${plan.iso} ${plan.exposureNs / 1000} µs + ${plan.shortFrames} highlight frames, gain ${plan.gain}, zoom $zoom")
        gpuHandler.post {
            try {
                processor.begin(setup)
            } catch (e: Throwable) {
                Log.e(TAG, "GPU setup", e)
                job.failed = true
            }
        }
        burst = job
        try {
            val awbAuto = controls.wbKelvin == null
            val requests = (0 until plan.frames + plan.shortFrames).map { i ->
                val exposure = if (i < plan.frames) plan.exposureNs else plan.shortExposureNs
                val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                b.addTarget(raw.surface)
                b.addTarget(preview)
                applyControls(b, still = false)
                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, plan.iso)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, exposure)
                if (awbAuto) b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
                b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
                b.build()
            }
            s.captureBurst(requests, burstCallback(job), camHandler)
        } catch (e: Exception) {
            Log.e(TAG, "burst capture", e)
            report("Capture failed: ${e.message}")
            burst = null
            gpuHandler.post { processor.abort() }
            finishShot(force = true)
        }
    }

    private fun burstCallback(job: Burst) = object : CameraCaptureSession.CaptureCallback() {
        private var started = false

        override fun onCaptureStarted(s: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
            if (started) return
            started = true
            if (_state.value.shutterSound) sound.play(MediaActionSound.SHUTTER_CLICK)
            _state.update { it.copy(shutterCount = it.shutterCount + 1, processing = true) }
        }

        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (job.firstResult == null) {
                job.firstResult = result
                gpuHandler.post { maybeFinishBurst(job) }
            }
        }

        override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            Log.w(TAG, "burst frame failed: reason ${failure.reason}")
            job.expected--
            gpuHandler.post { maybeFinishBurst(job) }
        }

        override fun onCaptureSequenceCompleted(s: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
            if (afTriggeredForShot) {
                afTriggeredForShot = false
                cancelAfTrigger()
            }
        }
    }

    /** gpu thread */
    private fun burstFrame(job: Burst, image: Image) {
        try {
            if (!job.failed && !job.finished && job.received < job.total) processor.addFrame(image)
        } catch (e: Throwable) {
            Log.e(TAG, "merge frame", e)
            job.failed = true
        } finally {
            image.close()
        }
        job.received++
        maybeFinishBurst(job)
    }

    /** gpu thread */
    private fun maybeFinishBurst(job: Burst) {
        if (job.finished) return
        val result = job.firstResult
        if (!job.failed && (job.received < job.expected || result == null)) return
        job.finished = true
        burst = null
        if (job.failed || result == null || job.received == 0) {
            processor.abort()
            if (!job.cancelled) report("Multi-frame processing failed")
            camHandler.post { if (shot?.burst == true) finishShot(force = true) }
            return
        }
        val bitmap = try {
            val gains = result[CaptureResult.COLOR_CORRECTION_GAINS] ?: RggbChannelVector(2f, 1f, 1f, 1.6f)
            val ccm = result[CaptureResult.COLOR_CORRECTION_TRANSFORM]
                ?: ColorSpaceTransform(intArrayOf(1, 1, 0, 1, 0, 1, 0, 1, 1, 1, 0, 1, 0, 1, 0, 1, 1, 1))
            // Merged DNG first (it needs the accumulator, which finishing frees).
            if (_state.value.saveDng) {
                val raw = runCatching { processor.mergedRaw() }.onFailure { Log.e(TAG, "merged raw", it) }.getOrNull()
                val c = caps
                if (raw != null && c != null) ioHandler.post { saveMergedDng(c, result, raw, job.setup) }
            }
            processor.finish(gains, ccm, result[CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP])
        } catch (e: Throwable) {
            Log.e(TAG, "finish burst", e)
            processor.abort()
            null
        }
        // The GPU is free again: allow the next photo while this one is encoded and saved.
        camHandler.post {
            val current = shot
            if (current?.burst == true) {
                synchronized(current) { current.jpegDone = true }
                finishShot(force = false)
            }
        }
        ioHandler.post {
            if (bitmap != null) saveFinished(bitmap, job.info) else report("Multi-frame processing failed")
        }
    }

    /** io thread */
    private fun saveFinished(img: org.cityman.camera.processing.FinishedImage, info: ShotInfo) {
        try {
            val uri = store.createImage(store.baseName("IMG") + "_MF.jpg", "image/jpeg") ?: error("MediaStore insert failed")
            val t0 = SystemClock.elapsedRealtime()
            store.write(uri) { org.cityman.camera.processing.ParallelJpeg.encodeNv21(img.nv21, img.width, img.height, 95, it) }
            Log.i(TAG, "JPEG encode ${SystemClock.elapsedRealtime() - t0} ms")
            runCatching { writeExif(uri, img.width, img.height, info) }
                .onFailure { Log.w(TAG, "EXIF", it) }
            store.publish(uri)
            _state.update { it.copy(thumbnail = img.thumbnail, lastUri = uri, lastIsVideo = false) }
            Log.i(TAG, "saved $uri (multi-frame)")
        } catch (e: Exception) {
            Log.e(TAG, "save multi-frame", e)
            report("Could not save the photo: ${e.message}")
        }
    }

    /** io thread: the merged RAW as a DNG, with the reference frame's metadata. */
    private fun saveMergedDng(c: CameraCaps, result: TotalCaptureResult, raw: java.nio.ByteBuffer, setup: BurstSetup) {
        try {
            val uri = store.createImage(store.baseName("IMG") + "_MF.dng", "image/x-adobe-dng") ?: error("MediaStore insert failed")
            DngCreator(c.chars, result).use { dng ->
                dng.setOrientation(exifOrientation(setup.rotation))
                store.write(uri) { dng.writeByteBuffer(it, Size(setup.width, setup.height), raw, 0) }
            }
            store.publish(uri)
            Log.i(TAG, "saved $uri (merged DNG)")
        } catch (e: Exception) {
            Log.e(TAG, "save merged DNG", e)
            report("Could not save the DNG: ${e.message}")
        }
    }

    /** io thread: capture details for a multi-frame JPEG (the pixels are already upright). */
    private fun writeExif(uri: Uri, width: Int, height: Int, info: ShotInfo) {
        context.contentResolver.openFileDescriptor(uri, "rw")?.use { fd ->
            val exif = ExifInterface(fd.fileDescriptor)
            val now = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            exif.setAttribute(ExifInterface.TAG_MAKE, android.os.Build.MANUFACTURER)
            exif.setAttribute(ExifInterface.TAG_MODEL, android.os.Build.MODEL)
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Arco (multi-frame, ${info.frames} frames)")
            exif.setAttribute(ExifInterface.TAG_DATETIME, now)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, now)
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, now)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            exif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, width.toString())
            exif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, height.toString())
            exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, info.iso.toString())
            exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, String.format(java.util.Locale.US, "%.6f", info.exposureNs / 1e9))
            info.fNumber?.let { exif.setAttribute(ExifInterface.TAG_F_NUMBER, String.format(java.util.Locale.US, "%.1f", it)) }
            info.focalMm?.let { exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "${(it * 100).roundToInt()}/100") }
            exif.saveAttributes()
        }
    }

    // ---- video --------------------------------------------------------------------------

    private fun startRecording() {
        val c = caps ?: return
        val vp = video ?: return
        if (session == null || recorder != null) return
        val size = vp.size
        val audio = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val uri = store.createVideo(store.baseName("VID") + ".mp4") ?: run {
            report("Could not create the video file")
            return
        }
        val fd = store.openFd(uri)
        val fps = c.fpsRange(video = true)?.upper ?: 30
        val r = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        try {
            val lapse = _state.value.timelapse
            val withAudio = audio && lapse <= 1
            if (withAudio) r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setOutputFile(fd.fileDescriptor)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setVideoEncodingBitRate(if (size.width >= 1920) 20_000_000 else 10_000_000)
            r.setVideoFrameRate(if (lapse > 1) 30 else fps)
            r.setVideoSize(size.width, size.height)
            if (withAudio) {
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioEncodingBitRate(192_000)
                r.setAudioSamplingRate(48_000)
            }
            r.setOrientationHint(jpegOrientation(c))
            r.prepare()
            r.start()
            vp.attach(
                r.surface,
                VideoPipeline.Options(
                    every = _state.value.timelapse.coerceAtLeast(1),
                    stabilise = _state.value.stabilise,
                    sensorOrientation = c.sensorOrientation,
                    front = c.front,
                ),
            )
        } catch (e: Exception) {
            Log.e(TAG, "start recording", e)
            runCatching { vp.detach() }
            r.release()
            fd.close()
            store.discard(uri)
            report("Could not start recording: ${e.message}")
            return
        }
        recorder = r
        recordingUri = uri
        recordingFd = fd
        startRepeating()
        if (_state.value.shutterSound) sound.play(MediaActionSound.START_VIDEO_RECORDING)
        _state.update { it.copy(recording = true, recordingSince = SystemClock.elapsedRealtime()) }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun stopRecording(restartPreview: Boolean) {
        val r = recorder ?: return
        recorder = null
        // Stop sending frames to the recorder before stopping it.
        startRepeating()
        runCatching { video?.detach() }
        val drawn = video?.framesDrawn ?: 0
        val uri = recordingUri
        val ok = try {
            r.stop()
            true
        } catch (e: Exception) {
            Log.e(TAG, "stop recording ($drawn frames drawn)", e)
            false
        }
        r.release()
        recordingFd?.close()
        recordingFd = null
        recordingUri = null
        if (_state.value.shutterSound) sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
        _state.update { it.copy(recording = false) }
        if (uri != null) {
            if (ok) {
                store.publish(uri)
                Log.i(TAG, "saved $uri (video, $drawn frames)")
                ioHandler.post {
                    val thumb = store.thumbnail(uri, true)
                    _state.update { it.copy(thumbnail = thumb ?: it.thumbnail, lastUri = uri, lastIsVideo = true) }
                }
            } else {
                store.discard(uri)
                report("The video was too short and was not saved")
            }
        }
    }

    // ---- helpers ------------------------------------------------------------------------

    private fun loadLatestThumbnail() {
        if (_state.value.lastUri != null || _state.value.secure) return
        val (uri, video) = store.latest() ?: return
        val thumb = store.thumbnail(uri, video) ?: return
        _state.update { if (it.lastUri == null) it.copy(thumbnail = thumb, lastUri = uri, lastIsVideo = video) else it }
    }

    private fun report(msg: String) {
        Log.w(TAG, msg)
        _state.update { it.copy(message = msg) }
    }

    private fun runOnThreadAndWait(handler: Handler, timeoutMs: Long, block: () -> Unit) {
        if (Looper.myLooper() == handler.looper) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        handler.post {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun runOnCamAndWait(timeoutMs: Long = 1000, block: () -> Unit) {
        if (Looper.myLooper() == camThread.looper) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        camHandler.post {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    companion object {
        private const val TAG = "ArcoCamera"
        private const val MAX_AUTO_SHUTTER_NS = 250_000_000L
        private const val MAX_BURST = 12
        private const val NIGHT_SHUTTER_NS = 500_000_000L
        private const val TRIPOD_RAD_S = 0.008
        private const val ACCEL_STILL_MS2 = 0.12f
        private const val BURST_UNDER_EV = 0.7
        private const val BURST_MAX_SHUTTER_NS = 33_333_333L
        private const val BURST_LOW_LIGHT_SHUTTER_NS = 100_000_000L
        private const val BURST_DIGITAL_GAIN_FIRST = 2.0

        fun exifOrientation(degrees: Int) = when (degrees) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }
    }
}
