package com.photobooth.kiosk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.view.TextureView
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack
import com.jiangdg.usb.USBMonitor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Direct USB Video Class backend. CameraX remains the built-in camera backend.
 * Device permissions are requested by AUSBC's USBMonitor, not CameraX.
 * The UVC library is intentionally isolated from MainActivity.
 */
class UvcCameraBridge(private val context: Context) : IDeviceConnectCallBack, ICameraStateCallBack {
    val view: TextureView = TextureView(context)
    private var client: MultiCameraClient? = null
    private var camera: CameraUVC? = null
    private var requestedUsbId: Int? = null
    private var opening: ((Boolean, String?) -> Unit)? = null
    private var recordingStart: CompletableDeferred<Long?>? = null
    private var recordingEnd: CompletableDeferred<File?>? = null
    private var recordingFile: File? = null
    private var recordingStartNanos: Long = 0
    var isOpen = false
        private set

    companion object {
        fun isUvcDevice(device: UsbDevice): Boolean =
            device.deviceClass == 14 || (0 until device.interfaceCount).any {
                device.getInterface(it).interfaceClass == 14
            }

        fun hasUsbCamera(context: Context): Boolean {
            val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
            return manager.deviceList.values.any(::isUvcDevice)
        }
    }

    fun open(onOpened: (Boolean, String?) -> Unit) {
        close()
        opening = onOpened
        try {
            val c = MultiCameraClient(context, this)
            client = c
            c.register()
            val device = c.getDeviceList()?.firstOrNull(::isUvcDevice)
            if (device == null) { fail("No USB video camera found"); return }
            requestUsbPermissionOnce(device)
            // Some USB monitors send a connect callback immediately for already-authorized devices.
        } catch (e: Exception) { fail("USB camera: ${e.message}") }
    }

    private fun fail(message: String) {
        val cb = opening
        opening = null
        cb?.invoke(false, message)
    }

    private fun requestUsbPermissionOnce(device: UsbDevice) {
        if (requestedUsbId == device.deviceId) return
        requestedUsbId = device.deviceId
        if (client?.requestPermission(device) != true) fail("USB permission request could not start")
    }

    override fun onAttachDev(device: UsbDevice?) {
        if (device != null && isUvcDevice(device) && !isOpen && opening != null)
            requestUsbPermissionOnce(device)
    }

    override fun onDetachDec(device: UsbDevice?) {
        if (device != null && isUvcDevice(device)) { isOpen = false; camera?.closeCamera(); camera = null }
    }

    override fun onConnectDev(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
        if (device == null || ctrlBlock == null || !isUvcDevice(device) || camera != null || opening == null) return
        try {
            val cam = CameraUVC(context, device)
            camera = cam
            cam.setUsbControlBlock(ctrlBlock)
            cam.setCameraStateCallBack(this)
            val request = CameraRequest.Builder().setPreviewWidth(1280).setPreviewHeight(720)
                .setAudioSource(CameraRequest.AudioSource.NONE).create()
            // TextureView is supported by AUSBC 3.3.3; it also permits a still fallback
            // if a UVC device rejects simultaneous photo+video capture.
            cam.openCamera(view, request)
        } catch (e: Exception) { fail("Could not open UVC: ${e.message}") }
    }

    override fun onDisConnectDec(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
        isOpen = false
        camera?.closeCamera(); camera = null
    }

    override fun onCancelDev(device: UsbDevice?) { fail("USB camera permission denied") }

    override fun onCameraState(self: MultiCameraClient.ICamera, code: ICameraStateCallBack.State, msg: String?) {
        if (self !== camera) return
        when (code) {
            ICameraStateCallBack.State.OPENED -> {
                isOpen = true
                val cb = opening; opening = null; cb?.invoke(true, null)
            }
            ICameraStateCallBack.State.ERROR -> fail(msg ?: "UVC camera failed")
            ICameraStateCallBack.State.CLOSED -> isOpen = false
        }
    }

    suspend fun takePhoto(): Bitmap? {
        val cam = camera ?: return null
        val dst = File(context.cacheDir, "pb-uvc-photo-${SystemClock.elapsedRealtimeNanos()}.jpg")
        val done = CompletableDeferred<Bitmap?>()
        try {
            cam.captureImage(object : ICaptureCallBack {
                override fun onBegin() { }
                override fun onError(error: String?) { if (!done.isCompleted) done.complete(null) }
                override fun onComplete(path: String?) {
                    val file = path?.let(::File) ?: dst
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    if (!done.isCompleted) done.complete(bitmap)
                    file.delete()
                }
            }, dst.absolutePath)
            val result = withTimeoutOrNull(4500L) { done.await() }
            dst.delete()
            // Some UVC drivers refuse still capture during MP4 recording; keep still capture
            // possible via current preview. This fallback has preview (not sensor-photo) resolution.
            return result ?: view.bitmap
        } catch (_: Exception) { dst.delete(); return view.bitmap }
    }

    suspend fun startVideo(): Boolean {
        val cam = camera ?: return false
        val started = CompletableDeferred<Long?>()
        val ended = CompletableDeferred<File?>()
        val dst = File(context.cacheDir, "pb-live-uvc-${SystemClock.elapsedRealtimeNanos()}.mp4")
        recordingStart = started; recordingEnd = ended; recordingFile = dst
        return try {
            cam.captureVideoStart(object : ICaptureCallBack {
                override fun onBegin() {
                    val at = SystemClock.elapsedRealtimeNanos()
                    recordingStartNanos = at
                    if (!started.isCompleted) started.complete(at)
                }
                override fun onError(error: String?) {
                    if (!started.isCompleted) started.complete(null)
                    if (!ended.isCompleted) ended.complete(null)
                }
                override fun onComplete(path: String?) {
                    val file = path?.let(::File) ?: dst
                    if (!started.isCompleted) started.complete(null)
                    if (!ended.isCompleted) ended.complete(file.takeIf { it.isFile && it.length() > 0 })
                    else file.delete()
                }
            }, dst.absolutePath)
            val ok = withTimeoutOrNull(2500L) { started.await() } != null
            if (!ok) {
                try { cam.captureVideoStop() } catch (_: Exception) { }
                dst.delete()
                if (!ended.isCompleted) ended.complete(null)
            }
            ok
        } catch (_: Exception) {
            try { cam.captureVideoStop() } catch (_: Exception) { }
            dst.delete()
            false
        }
    }

    suspend fun stopVideo(shutterNanos: Long): MotionClip? {
        try { camera?.captureVideoStop() } catch (_: Exception) { }
        val future = recordingEnd
        val file = if (future != null) withTimeoutOrNull(6000L) { future.await() } else null
        if (future != null && !future.isCompleted) future.complete(null)
        recordingStart = null; recordingEnd = null
        val attemptedFile = recordingFile
        recordingFile = null
        if (file == null) attemptedFile?.delete()
        return if (file?.isFile == true && file.length() > 0L)
            MotionClip(file, ((shutterNanos - recordingStartNanos) / 1000L).coerceAtLeast(0))
        else { file?.delete(); null }
    }

    fun close() {
        opening = null
        try { camera?.captureVideoStop() } catch (_: Exception) { }
        try { camera?.closeCamera() } catch (_: Exception) { }
        camera = null; isOpen = false
        try { client?.unRegister() } catch (_: Exception) { }
        try { client?.destroy() } catch (_: Exception) { }
        client = null
        requestedUsbId = null
        recordingFile?.delete(); recordingFile = null
        recordingStart?.complete(null); recordingEnd?.complete(null)
        recordingStart = null; recordingEnd = null
    }
}
