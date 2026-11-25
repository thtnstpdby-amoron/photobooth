package com.photobooth.kiosk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.herohan.uvcapp.IImageCapture as UvcImageCapture
import com.herohan.uvcapp.VideoCapture as UvcVideoCapture
import com.serenegiant.utils.UVCUtils
import com.serenegiant.usb.Size as UvcSize
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Replacement for the old AUSBC bridge using com.herohan:UVCAndroid:1.0.13.
 * Public API is intentionally unchanged, so MainActivity need not change.
 *
 * USB selection / permission / native UVC opening are now handled by CameraHelper.
 * Unlike the original bridge, this implementation uses a SurfaceView and
 * the library's asynchronous onDeviceOpen -> onCameraOpen lifecycle.
 *
 * IMPORTANT: CameraHelper still uses libuvc/libusb internally. A successful
 * build does not prove that the EMEET camera can actually be opened.
 */
class UvcCameraBridge(private val context: Context) {
    val view = SurfaceView(context)

    private val handler = Handler(Looper.getMainLooper())
    // Persisted for this physical camera model (vendor/product pair).
    private val prefs = context.getSharedPreferences("pb-uvc-camera-v1", Context.MODE_PRIVATE)
    data class ControlSetting(val id: String, val label: String,
                              val min: Int, val max: Int, val value: Int)
    data class ToggleSetting(val id: String, val label: String, val checked: Boolean)
    private val cachedBounds = mutableMapOf<String, Pair<Int, Int>>()
    fun videoFps(): Int = prefs.getInt("fps_${deviceKey()}", 25).coerceIn(10, 30)
    fun videoBitrateMbps(): Int = prefs.getInt("bitrate_${deviceKey()}", 5).coerceIn(2, 12)
    fun setVideoFps(fps: Int) {
        prefs.edit().putInt("fps_${deviceKey()}", fps.coerceIn(10, 30)).apply()
        helper?.let { configureVideoCapture(it) }
    }
    fun setVideoBitrateMbps(mbps: Int) {
        prefs.edit().putInt("bitrate_${deviceKey()}", mbps.coerceIn(2, 12)).apply()
        helper?.let { configureVideoCapture(it) }
    }
    private fun configureVideoCapture(cameraHelper: ICameraHelper) {
        val fps = videoFps()
        val bitrate = videoBitrateMbps() * 1_000_000
        cameraHelper.setVideoCaptureConfig(
            cameraHelper.videoCaptureConfig
                .setAudioCaptureEnable(false)
                .setVideoFrameRate(fps)
                .setBitRate(bitrate)
                .setIFrameInterval(1)
        )
        Log.i(TAG, "UVC MP4 encoder: requested fps=$fps bitrate=$bitrate bps keyframeInterval=1s audio=false")
    }
    private val numericControls = listOf(
        "Brightness" to "Brightness", "Contrast" to "Contrast",
        "Saturation" to "Saturation", "Sharpness" to "Sharpness",
        "Gain" to "Gain (not ISO)", "Gamma" to "Gamma",
        "Hue" to "Hue", "WhiteBalance" to "White balance temperature",
        "Exposure" to "Exposure (UVC units)", "Focus" to "Focus (UVC units)",
        "Zoom" to "Zoom (UVC units)",
        "PowerlineFrequency" to "Anti-flicker / power line frequency"
    )
    private val booleanControls = listOf(
        "FocusAuto" to "Autofocus", "AutoFocus" to "Autofocus",
        "WhiteBalanceAuto" to "Automatic white balance",
        "AutoWhiteBalance" to "Automatic white balance",
        "ExposureTimeAuto" to "Automatic exposure"
    )
    private fun deviceKey(): String = device?.let { "${it.vendorId}_${it.productId}" } ?: "unknown"
    private fun resolutionPrefKey(): String = "resolution_${deviceKey()}"
    private fun profileKey(suffix: String): String = "control_${deviceKey()}_$suffix"
    fun selectedResolutionKey(): String = prefs.getString(resolutionPrefKey(), "auto") ?: "auto"
    fun setSelectedResolutionKey(key: String) {
        prefs.edit().putString(resolutionPrefKey(), key).apply()
        Log.i(TAG, "UVC resolution preference saved=$key; will apply on next camera open")
    }

    /** These are modes the connected camera actually advertises. */
    fun availableResolutions(): List<Pair<String, String>> {
        val sizes = runCatching { helper?.supportedSizeList.orEmpty() }.getOrElse { emptyList() }
        val result = mutableListOf(
            "auto" to "Camera default (automatic)",
            "safe720" to "Prefer 720p (if supported)"
        )
        sizes.distinctBy { "${it.type},${it.width},${it.height},${it.fps}" }
            .sortedWith(compareBy<UvcSize> { it.width * it.height }.thenBy { it.fps })
            .forEach {
                val key = "${it.type},${it.width},${it.height},${it.fps}"
                val name = if (it.type == 7) "MJPEG" else "UVC type ${it.type}"
                result.add(key to "${it.width} x ${it.height} @ ${it.fps} fps ($name)")
            }
        return result
    }
    fun activeResolutionLabel(): String = runCatching { helper?.previewSize?.toString() }
        .getOrNull() ?: "not reported"

    private fun selectResolution(cameraHelper: ICameraHelper) {
        val supported = runCatching { cameraHelper.supportedSizeList.orEmpty() }.getOrElse { emptyList() }
        Log.i(TAG, "Camera advertised ${supported.size} modes: ${supported.joinToString()}")
        val setting = selectedResolutionKey()
        if (setting == "auto" || supported.isEmpty()) {
            Log.i(TAG, "Using library-selected preview size=${cameraHelper.previewSize} (preference=$setting)")
            return
        }
        val selected = if (setting == "safe720") {
            supported.filter { it.width == 1280 && it.height == 720 }
                .sortedWith(compareBy<UvcSize> { if (it.type == 7) 0 else 1 }
                    .thenBy { kotlin.math.abs(it.fps - 25) })
                .firstOrNull()
        } else {
            supported.firstOrNull { "${it.type},${it.width},${it.height},${it.fps}" == setting }
        }
        if (selected == null) {
            Log.w(TAG, "Requested UVC mode '$setting' not supported; keeping default ${cameraHelper.previewSize}")
            return
        }
        Log.i(TAG, "Requesting UVC mode $selected before startPreview (previous=${cameraHelper.previewSize})")
        // CameraHelper serializes this and startPreview on the same background handler.
        cameraHelper.previewSize = selected
    }

    /** Reflection only for optional hardware controls: vendor cameras expose different subsets. */
    private fun controlObject(): Any? = try { if (cameraOpened) helper?.uvcControl else null }
        catch (e: Exception) { Log.w(TAG, "Cannot retrieve UVC controls", e); null }
    private fun zeroArg(target: Any, method: String): Any? =
        target.javaClass.methods.firstOrNull { it.name == method && it.parameterCount == 0 }
            ?.invoke(target)
    private fun supportedControl(target: Any, suffix: String): Boolean {
        val supported = runCatching { zeroArg(target, "is${suffix}Enable") }.getOrNull()
        return supported == true
    }
    private fun callSetter(target: Any, name: String, argument: Any): Boolean {
        return try {
        val m = target.javaClass.methods.firstOrNull {
            it.name == name && it.parameterCount == 1 &&
                (if (argument is Boolean)
                    it.parameterTypes[0] == java.lang.Boolean.TYPE || it.parameterTypes[0] == java.lang.Boolean::class.java
                 else it.parameterTypes[0] == Integer.TYPE || it.parameterTypes[0] == java.lang.Integer::class.java)
        } ?: return false
        m.invoke(target, argument)
        true
        } catch (e: Exception) { Log.e(TAG, "UVC control setter $name failed", e); false }
    }

    fun availableControls(): List<ControlSetting> {
        val ctrl = controlObject() ?: return emptyList()
        return numericControls.mapNotNull { (suffix, title) ->
            try {
                if (!supportedControl(ctrl, suffix)) return@mapNotNull null
                val limit = zeroArg(ctrl, "update${suffix}Limit") as? IntArray ?: return@mapNotNull null
                if (limit.size < 2 || limit[0] >= limit[1]) return@mapNotNull null
                // Hide a slider if this version of UVCControl has no setter/getter.
                val canWrite = ctrl.javaClass.methods.any {
                    it.name == "set$suffix" && it.parameterCount == 1 &&
                        (it.parameterTypes[0] == Integer.TYPE || it.parameterTypes[0] == java.lang.Integer::class.java)
                }
                if (!canWrite) return@mapNotNull null
                val value = (zeroArg(ctrl, "get$suffix") as? Number)?.toInt() ?: return@mapNotNull null
                cachedBounds[suffix] = limit[0] to limit[1]
                ControlSetting(suffix, title, limit[0], limit[1], value.coerceIn(limit[0], limit[1]))
            } catch (e: Exception) {
                Log.w(TAG, "UVC control $suffix cannot be read", e)
                null
            }
        }
    }
    fun availableToggles(): List<ToggleSetting> {
        val ctrl = controlObject() ?: return emptyList()
        return booleanControls.mapNotNull { (suffix, label) ->
            try {
                if (!supportedControl(ctrl, suffix)) return@mapNotNull null
                val value = zeroArg(ctrl, "get$suffix") as? Boolean ?: return@mapNotNull null
                val canWrite = ctrl.javaClass.methods.any {
                    it.name == "set$suffix" && it.parameterCount == 1 &&
                        (it.parameterTypes[0] == java.lang.Boolean.TYPE || it.parameterTypes[0] == java.lang.Boolean::class.java)
                }
                if (!canWrite) return@mapNotNull null
                ToggleSetting(suffix, label, value)
            } catch (e: Exception) { Log.w(TAG, "UVC toggle $suffix cannot be read", e); null }
        }.distinctBy { it.label }
    }
    fun setControlValue(id: String, value: Int): Boolean {
        val bounds = cachedBounds[id] ?: availableControls().firstOrNull { it.id == id }
            ?.let { it.min to it.max } ?: return false
        val target = controlObject() ?: return false
        val safe = value.coerceIn(bounds.first, bounds.second)
        if (!callSetter(target, "set$id", safe)) return false
        prefs.edit().putInt(profileKey(id), safe).apply()
        Log.i(TAG, "UVC control $id=$safe")
        return true
    }
    fun setToggleValue(id: String, enabled: Boolean): Boolean {
        if (availableToggles().none { it.id == id }) return false
        val target = controlObject() ?: return false
        if (!callSetter(target, "set$id", enabled)) return false
        prefs.edit().putBoolean(profileKey(id), enabled).apply()
        Log.i(TAG, "UVC control $id=$enabled")
        return true
    }
    private fun restoreSavedControls() {
        val ctrl = controlObject() ?: return
        availableToggles().forEach { toggle ->
            if (prefs.contains(profileKey(toggle.id))) {
                val value = prefs.getBoolean(profileKey(toggle.id), toggle.checked)
                if (!callSetter(ctrl, "set${toggle.id}", value)) Log.w(TAG, "Could not restore ${toggle.id}")
            }
        }
        availableControls().forEach { setting ->
            if (prefs.contains(profileKey(setting.id))) {
                val value = prefs.getInt(profileKey(setting.id), setting.value)
                    .coerceIn(setting.min, setting.max)
                if (!callSetter(ctrl, "set${setting.id}", value)) Log.w(TAG, "Could not restore ${setting.id}")
            }
        }
    }
    fun resetControls() {
        val ctrl = controlObject()
        for ((suffix, _) in numericControls + booleanControls) {
            prefs.edit().remove(profileKey(suffix)).apply()
            if (ctrl != null) runCatching { zeroArg(ctrl, "reset$suffix") }
        }
        Log.i(TAG, "Cleared stored USB camera hardware controls for ${deviceKey()}")
    }

    private var helper: ICameraHelper? = null
    private var device: UsbDevice? = null
    private var closed = false
    private var cameraOpened = false
    private var surfaceReady = false
    private var previewStarted = false
    private var openResult: ((Boolean, String?) -> Unit)? = null

    @Volatile private var recordingStartNanos = 0L
    private var recordingFile: File? = null
    private var recordingEnd: CompletableDeferred<File?>? = null

    companion object {
        private const val TAG = "PB-UVC-Hero"

        private fun isVideoDevice(d: UsbDevice): Boolean {
            if (d.deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
            for (i in 0 until d.interfaceCount) {
                if (d.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO) return true
            }
            return false
        }

        fun hasUsbCamera(context: Context): Boolean {
            val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
                ?: return false
            return manager.deviceList.values.any(::isVideoDevice)
        }
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            onMain {
                if (closed) return@onMain
                surfaceReady = true
                maybeStartPreview()
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            onMain {
                if (closed) return@onMain
                surfaceReady = holder.surface?.isValid == true
                maybeStartPreview()
            }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            onMain {
                surfaceReady = false
                if (previewStarted && !closed) {
                    try {
                        helper?.stopPreview()
                        helper?.removeSurface(holder)
                    } catch (e: Exception) {
                        Log.w(TAG, "Stop preview after surface destruction failed", e)
                    }
                    previewStarted = false
                }
            }
        }
    }

    init {
        view.holder.addCallback(surfaceCallback)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else handler.post(block)
    }

    private fun sameDevice(d: UsbDevice?): Boolean =
        d != null && d.deviceName == device?.deviceName

    private fun sendOpenResult(success: Boolean, message: String?) {
        onMain {
            if (closed) return@onMain
            val callback = openResult ?: return@onMain
            openResult = null
            if (success) Log.i(TAG, "Camera and preview requested successfully")
            else Log.e(TAG, "UVCAndroid open failed: $message")
            callback(success, message)
        }
    }

    fun open(callback: (Boolean, String?) -> Unit) {
        onMain {
            if (closed) {
                callback(false, "UVC adapter already closed")
                return@onMain
            }
            if (helper != null || openResult != null) {
                callback(false, "UVC open already started")
                return@onMain
            }
            openResult = callback
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            if (usbManager == null) {
                sendOpenResult(false, "Android USB manager is unavailable")
                return@onMain
            }
            val selected = usbManager.deviceList.values
                .filter(::isVideoDevice)
                .sortedBy { it.deviceName }
                .firstOrNull()
            if (selected == null) {
                sendOpenResult(false, "No USB Video Class camera detected")
                return@onMain
            }
            device = selected
            Log.i(TAG, "Selecting ${selected.deviceName}, VID=${selected.vendorId}, " +
                "PID=${selected.productId}, permission=${usbManager.hasPermission(selected)}")

            try {
                // UVCUtils is required before CameraHelper's constructor.
                UVCUtils.init(context.applicationContext)
                val cameraHelper = CameraHelper()
                helper = cameraHelper
                // Photobooth Live Photo clips do not need microphone access.
                configureVideoCapture(cameraHelper)
                cameraHelper.setStateCallback(object : ICameraHelper.StateCallback {
                    override fun onAttach(device: UsbDevice) {
                        if (sameDevice(device)) Log.i(TAG, "USB camera attached")
                    }
                    override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
                        if (closed || !sameDevice(device)) return
                        Log.i(TAG, "USB control connection ready; opening native UVC")
                        try {
                            // Let the library negotiate its default supported format/size.
                            // Do not force a preview resolution before native open succeeds.
                            cameraHelper.openCamera()
                        } catch (e: Exception) {
                            sendOpenResult(false, "Native camera open request failed: ${e.message}")
                        }
                    }
                    override fun onCameraOpen(device: UsbDevice) {
                        if (closed || !sameDevice(device)) return
                        cameraOpened = true
                        Log.i(TAG, "Native UVC camera opened; default size=${cameraHelper.previewSize}")
                        selectResolution(cameraHelper)
                        restoreSavedControls()
                        maybeStartPreview()
                    }
                    override fun onCameraClose(device: UsbDevice) {
                        if (sameDevice(device)) {
                            Log.i(TAG, "Native UVC camera closed")
                            cameraOpened = false
                            previewStarted = false
                            sendOpenResult(false, "USB camera closed before preview")
                        }
                    }
                    override fun onDeviceClose(device: UsbDevice) {
                        if (sameDevice(device)) {
                            Log.w(TAG, "USB camera device connection closed")
                            sendOpenResult(false, "USB device connection closed")
                        }
                    }
                    override fun onDetach(device: UsbDevice) {
                        if (sameDevice(device)) {
                            Log.e(TAG, "USB camera detached")
                            cameraOpened = false
                            previewStarted = false
                            sendOpenResult(false, "USB camera was disconnected")
                        }
                    }
                    override fun onCancel(device: UsbDevice) {
                        if (sameDevice(device)) sendOpenResult(false, "USB permission was cancelled")
                    }
                    override fun onError(device: UsbDevice, e: CameraException) {
                        if (!sameDevice(device)) return
                        Log.e(TAG, "UVCAndroid error code=${e.code}: ${e.message}", e)
                        sendOpenResult(false, "UVCAndroid error ${e.code}: ${e.message}")
                    }
                })
                // selectDevice performs USB permission/UsbControlBlock handling.
                cameraHelper.selectDevice(selected)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize UVCAndroid", e)
                sendOpenResult(false, "UVCAndroid initialization failed: ${e.message}")
            }
        }
    }

    private fun maybeStartPreview() {
        if (closed || !cameraOpened || !surfaceReady || previewStarted) return
        val cameraHelper = helper ?: return
        if (view.holder.surface?.isValid != true) return
        try {
            // Enable preview in the library's renderer; no video encoder required.
            cameraHelper.addSurface(view, false)
            cameraHelper.startPreview()
            previewStarted = true
            handler.postDelayed({
                if (!closed && cameraOpened) Log.i(TAG,
                    "UVC active mode after preview start=${cameraHelper.previewSize}; requested=${selectedResolutionKey()}")
            }, 800L)
            sendOpenResult(true, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach/start preview", e)
            sendOpenResult(false, "UVC preview failed: ${e.message}")
        }
    }

    suspend fun takePhoto(): Bitmap? {
        val cameraHelper = helper ?: return null
        if (closed || !cameraHelper.isCameraOpened) return null
        val file = File(context.cacheDir, "pb-uvc-still-${System.nanoTime()}.jpg")
        val result = CompletableDeferred<Boolean>()
        Log.i(TAG, "still REQUEST while recording=${cameraHelper.isRecording} preview=${cameraHelper.previewSize}")
        try {
            cameraHelper.takePicture(
                UvcImageCapture.OutputFileOptions.Builder(file).build(),
                object : UvcImageCapture.OnImageCaptureCallback {
                    override fun onImageSaved(output: UvcImageCapture.OutputFileResults) {
                        Log.i(TAG, "still SAVED bytes=${file.length()} while recording=${cameraHelper.isRecording}")
                        if (!result.isCompleted) result.complete(true)
                    }
                    override fun onError(error: Int, message: String, cause: Throwable?) {
                        Log.e(TAG, "Still capture failed [$error]: $message", cause)
                        if (!result.isCompleted) result.complete(false)
                    }
                }
            )
            if (withTimeoutOrNull(8_000L) { result.await() } != true) {
                Log.e(TAG, "still FAILED or timed out; recording=${cameraHelper.isRecording}")
                return null
            }
            return withContext(Dispatchers.IO) {
                if (file.isFile && file.length() > 0) BitmapFactory.decodeFile(file.path) else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Still capture exception", e)
            return null
        } finally {
            file.delete()
        }
    }

    private var recordingWasAborted: AtomicBoolean? = null

    suspend fun startVideo(): Boolean {
        val cameraHelper = helper
        if (cameraHelper == null || closed || !cameraHelper.isCameraOpened || recordingEnd != null) {
            Log.e(TAG, "rec-start REJECTED helper=${cameraHelper != null} closed=$closed " +
                "cameraOpened=${cameraHelper?.isCameraOpened} pendingRecording=${recordingEnd != null}")
            return false
        }
        val file = File(context.cacheDir, "pb-live-uvc-${System.nanoTime()}.mp4")
        val started = CompletableDeferred<Boolean>()
        val finished = CompletableDeferred<File?>()
        val aborted = AtomicBoolean(false)
        recordingFile = file
        recordingEnd = finished
        recordingWasAborted = aborted
        recordingStartNanos = 0L
        configureVideoCapture(cameraHelper)
        Log.i(TAG, "rec-start REQUEST file=${file.name}, mode=${cameraHelper.previewSize}, " +
            "isRecording=${cameraHelper.isRecording}")
        try {
            cameraHelper.startRecording(
                UvcVideoCapture.OutputFileOptions.Builder(file).build(),
                object : UvcVideoCapture.OnVideoCaptureCallback {
                    override fun onStart() {
                        recordingStartNanos = SystemClock.elapsedRealtimeNanos()
                        Log.i(TAG, "rec-start CALLBACK onStart file=${file.name}; " +
                            "isRecording=${runCatching { cameraHelper.isRecording }.getOrNull()}")
                        if (!started.isCompleted) started.complete(true)
                    }
                    override fun onVideoSaved(result: UvcVideoCapture.OutputFileResults) {
                        val length = if (file.isFile) file.length() else -1L
                        Log.i(TAG, "rec-finish CALLBACK onVideoSaved file=${file.name}, bytes=$length, " +
                            "aborted=${aborted.get()}")
                        if (!started.isCompleted) started.complete(false)
                        if (aborted.get()) {
                            file.delete()
                            if (!finished.isCompleted) finished.complete(null)
                        } else if (!finished.isCompleted) {
                            finished.complete(file.takeIf { it.isFile && it.length() > 0L })
                        }
                    }
                    override fun onError(error: Int, message: String, cause: Throwable?) {
                        Log.e(TAG, "rec-finish CALLBACK ERROR code=$error message=$message " +
                            "file=${file.name} bytes=${file.length()}", cause)
                        if (!started.isCompleted) started.complete(false)
                        if (!finished.isCompleted) finished.complete(null)
                        if (aborted.get()) file.delete()
                    }
                }
            )
            val didStart = withTimeoutOrNull(6_000L) { started.await() }
            if (didStart == true) {
                Log.i(TAG, "rec-start accepted; awaiting video frames and MP4 finalization")
                return true
            }
            Log.e(TAG, "rec-start ${if (didStart == null) "TIMED OUT after 6000ms" else "callback returned failure"}; " +
                "isRecording=${cameraHelper.isRecording} bytes=${file.length()}")
            aborted.set(true)
            cameraHelper.stopRecording()
        } catch (e: Exception) {
            Log.e(TAG, "rec-start EXCEPTION", e)
            aborted.set(true)
            try { cameraHelper.stopRecording() } catch (stopEx: Exception) {
                Log.w(TAG, "rec-start cleanup stop failed", stopEx)
            }
        }
        recordingFile = null
        recordingEnd = null
        recordingWasAborted = null
        // Late onVideoSaved callback will also delete the abandoned file.
        file.delete()
        return false
    }

    private suspend fun validateMp4(file: File): Boolean = withContext(Dispatchers.IO) {
        if (!file.isFile || file.length() == 0L) {
            Log.e(TAG, "rec-verify FAILED: empty/missing MP4 ${file.name}")
            return@withContext false
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            Log.i(TAG, "rec-verify file=${file.name} bytes=${file.length()} " +
                "hasVideo=$hasVideo durationMs=$durationMs width=$width height=$height")
            // A file header alone is not enough; require decodable video metadata.
            val valid = hasVideo == "yes" && durationMs >= 250L
            if (!valid) Log.e(TAG, "rec-verify INVALID video track or duration; discarding MP4")
            valid
        } catch (e: Exception) {
            Log.e(TAG, "rec-verify MediaMetadataRetriever failed for ${file.name}", e)
            false
        } finally {
            runCatching { retriever.release() }
        }
    }

    suspend fun stopVideo(shutterAtNanos: Long): MotionClip? {
        val cameraHelper = helper
        val completion = recordingEnd
        val file = recordingFile
        val startAt = recordingStartNanos
        if (cameraHelper == null || completion == null) {
            Log.e(TAG, "rec-stop REJECTED no pending recording (helper=${cameraHelper != null})")
            return null
        }
        // Keep the pending deferred until the muxer has finished. A concurrent
        // second recording must not start while the first recording finalizes.
        Log.i(TAG, "rec-stop REQUEST file=${file?.name} isRecording=${cameraHelper.isRecording} " +
            "timeSinceStartMs=${(SystemClock.elapsedRealtimeNanos() - startAt) / 1_000_000L}")
        try {
            cameraHelper.stopRecording()
            val saved = withTimeoutOrNull(12_000L) { completion.await() }
            if (saved == null) {
                Log.e(TAG, "rec-stop FAILED: no valid onVideoSaved within 12s; bytes=${file?.length()}")
                recordingWasAborted?.set(true)
                file?.delete()
                return null
            }
            if (startAt <= 0L) {
                Log.e(TAG, "rec-stop FAILED: onStart never supplied a timestamp")
                saved.delete()
                return null
            }
            if (!validateMp4(saved)) {
                saved.delete()
                return null
            }
            val shutterOffsetUs = ((shutterAtNanos - startAt) / 1000L).coerceAtLeast(0L)
            Log.i(TAG, "rec-stop SUCCESS file=${saved.name} bytes=${saved.length()} " +
                "shutterOffsetUs=$shutterOffsetUs")
            return MotionClip(saved, shutterOffsetUs)
        } catch (e: Exception) {
            Log.e(TAG, "rec-stop EXCEPTION", e)
            recordingWasAborted?.set(true)
            file?.delete()
            return null
        } finally {
            recordingFile = null
            recordingEnd = null
            recordingWasAborted = null
        }
    }

    fun close() {
        if (closed) return
        closed = true
        openResult = null
        view.holder.removeCallback(surfaceCallback)
        try { helper?.stopRecording() } catch (_: Exception) { }
        try { helper?.stopPreview() } catch (_: Exception) { }
        try { helper?.closeCamera() } catch (_: Exception) { }
        try { helper?.release() } catch (e: Exception) {
            Log.w(TAG, "Release failed", e)
        }
        helper = null
        previewStarted = false
        cameraOpened = false
        recordingWasAborted?.set(true)
        recordingEnd?.let { if (!it.isCompleted) it.complete(null) }
        recordingFile?.delete()
        recordingFile = null
        recordingEnd = null
        recordingWasAborted = null
        device = null
    }
}
