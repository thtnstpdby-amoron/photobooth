package com.photobooth.kiosk

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.core.content.ContextCompat
import android.util.Log
import java.util.concurrent.CancellationException
import kotlin.math.roundToInt

/**
 * Per-physical-camera tuning. All values are real CameraX / Camera2 requests, not
 * post-processing filters. Camera2 controls are applied only when the device advertises them.
 *
 * CameraX CameraControl manages EV and zoom. Camera2 interop manages the controls
 * CameraX doesn't directly expose. No device is promised to honor unsupported keys.
 */
data class CameraTuningCapabilities(
    val cameraId: String,
    val exposureMin: Int,
    val exposureMax: Int,
    val exposureStepEv: Float,
    val zoomMin: Float,
    val zoomMax: Float,
    val manualExposure: Boolean,
    val isoMin: Int,
    val isoMax: Int,
    val shutterMinNs: Long,
    val shutterMaxNs: Long,
    val whiteBalanceModes: List<Int>,
    val antiBandingModes: List<Int>,
    val manualFocus: Boolean,
    val maxFocusDiopters: Float
) {
    val exposureCompensation: Boolean get() = exposureMax > exposureMin
    val whiteBalance: Boolean get() = whiteBalanceModes.size > 1
    val antiBanding: Boolean get() = antiBandingModes.size > 1
    val zoom: Boolean get() = zoomMax - zoomMin > 0.01f
}

data class CameraTuningValues(
    var manualExposure: Boolean = false,
    var exposureIndex: Int = 0,
    var iso: Int = 200,
    var shutterNs: Long = 16_666_667L,
    var whiteBalanceMode: Int = CaptureRequest.CONTROL_AWB_MODE_AUTO,
    var antiBandingMode: Int = CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO,
    var zoomRatio: Float = 1f,
    var manualFocus: Boolean = false,
    var focusDiopters: Float = 0f
)

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class CameraTuning(context: Context) {
    private val uiExecutor = ContextCompat.getMainExecutor(context)
    var onControlFailure: ((String) -> Unit)? = null

    private fun reportFailure(name: String, error: Throwable) {
        if (error is CancellationException || error.cause is CancellationException) return
        Log.w("PhotoboothCamera", "$name request failed", error)
        onControlFailure?.invoke("Camera rejected $name; try Auto or another camera")
    }

    private fun <T> observeRequest(name: String, future: com.google.common.util.concurrent.ListenableFuture<T>) {
        future.addListener({
            try { future.get() } catch (e: Exception) { reportFailure(name, e) }
        }, uiExecutor)
    }

    private val preferences = context.getSharedPreferences("pb_camera_tuning", Context.MODE_PRIVATE)
    private var camera: Camera? = null
    var capabilities: CameraTuningCapabilities? = null
        private set
    var values = CameraTuningValues()
        private set

    fun detach() { camera = null; capabilities = null }

    fun attach(boundCamera: Camera): CameraTuningCapabilities {
        camera = boundCamera
        val info = Camera2CameraInfo.from(boundCamera.cameraInfo)
        fun <T> characteristic(key: CameraCharacteristics.Key<T>): T? =
            try { info.getCameraCharacteristic(key) } catch (_: Exception) { null }

        val ev = boundCamera.cameraInfo.exposureState
        val zoom = boundCamera.cameraInfo.zoomState.value
        val available = characteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val aeModes = characteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) ?: intArrayOf()
        val isoRange = characteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val shutterRange = characteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val manual = available.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) &&
            aeModes.contains(CaptureRequest.CONTROL_AE_MODE_OFF) && isoRange != null && shutterRange != null
        val afModes = characteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        val maxFocus = characteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val caps = CameraTuningCapabilities(
            cameraId = info.cameraId,
            exposureMin = if (ev.isExposureCompensationSupported) ev.exposureCompensationRange.lower else 0,
            exposureMax = if (ev.isExposureCompensationSupported) ev.exposureCompensationRange.upper else 0,
            exposureStepEv = if (ev.isExposureCompensationSupported) ev.exposureCompensationStep.toFloat() else 0f,
            zoomMin = zoom?.minZoomRatio ?: 1f,
            zoomMax = zoom?.maxZoomRatio ?: 1f,
            manualExposure = manual,
            isoMin = isoRange?.lower ?: 100,
            isoMax = isoRange?.upper ?: 100,
            shutterMinNs = shutterRange?.lower ?: 1_000_000L,
            shutterMaxNs = shutterRange?.upper ?: 1_000_000L,
            whiteBalanceModes = (characteristic(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf()).toList(),
            antiBandingModes = (characteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES) ?: intArrayOf()).toList(),
            manualFocus = maxFocus > 0f && afModes.contains(CaptureRequest.CONTROL_AF_MODE_OFF),
            maxFocusDiopters = maxFocus
        )
        capabilities = caps
        val key = storageKey(caps.cameraId)
        values = CameraTuningValues(
            manualExposure = preferences.getBoolean("$key.manual", false) && caps.manualExposure,
            exposureIndex = preferences.getInt("$key.ev", 0).coerceIn(caps.exposureMin, caps.exposureMax),
            iso = preferences.getInt("$key.iso", 200).coerceIn(caps.isoMin, caps.isoMax),
            shutterNs = preferences.getLong("$key.shutter", 16_666_667L).coerceIn(caps.shutterMinNs, caps.shutterMaxNs),
            whiteBalanceMode = preferences.getInt("$key.wb", CaptureRequest.CONTROL_AWB_MODE_AUTO)
                .let { if (it in caps.whiteBalanceModes) it else caps.whiteBalanceModes.firstOrNull() ?: CaptureRequest.CONTROL_AWB_MODE_AUTO },
            antiBandingMode = preferences.getInt("$key.band", CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)
                .let { if (it in caps.antiBandingModes) it else caps.antiBandingModes.firstOrNull() ?: CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO },
            zoomRatio = preferences.getFloat("$key.zoom", 1f).coerceIn(caps.zoomMin, caps.zoomMax),
            manualFocus = preferences.getBoolean("$key.focusManual", false) && caps.manualFocus,
            focusDiopters = preferences.getFloat("$key.focus", 0f).coerceIn(0f, maxFocus.coerceAtLeast(0f))
        )
        apply()
        return caps
    }

    private fun storageKey(cameraId: String) = "device_${cameraId.length}_${cameraId.hashCode()}"

    private fun update(block: (CameraTuningValues) -> Unit) {
        val caps = capabilities ?: return
        block(values)
        val key = storageKey(caps.cameraId)
        preferences.edit()
            .putBoolean("$key.manual", values.manualExposure)
            .putInt("$key.ev", values.exposureIndex)
            .putInt("$key.iso", values.iso)
            .putLong("$key.shutter", values.shutterNs)
            .putInt("$key.wb", values.whiteBalanceMode)
            .putInt("$key.band", values.antiBandingMode)
            .putFloat("$key.zoom", values.zoomRatio)
            .putBoolean("$key.focusManual", values.manualFocus)
            .putFloat("$key.focus", values.focusDiopters)
            .apply()
        apply()
    }

    fun setExposureIndex(index: Int) = update { it.exposureIndex = index }
    fun setManualExposure(on: Boolean) = update { it.manualExposure = on && capabilities?.manualExposure == true }
    fun setIso(iso: Int) = update { it.iso = iso }
    fun setShutterNs(nanos: Long) = update { it.shutterNs = nanos }
    fun setWhiteBalance(mode: Int) = update { it.whiteBalanceMode = mode }
    fun setAntiBanding(mode: Int) = update { it.antiBandingMode = mode }
    fun setZoom(ratio: Float) = update { it.zoomRatio = ratio }
    fun setManualFocus(on: Boolean) = update { it.manualFocus = on && capabilities?.manualFocus == true }
    fun setFocus(diopters: Float) = update { it.focusDiopters = diopters }

    fun reset() {
        val caps = capabilities ?: return
        update {
            it.manualExposure = false
            it.exposureIndex = 0
            it.iso = 200.coerceIn(caps.isoMin, caps.isoMax)
            it.shutterNs = 16_666_667L.coerceIn(caps.shutterMinNs, caps.shutterMaxNs)
            it.whiteBalanceMode = if (CaptureRequest.CONTROL_AWB_MODE_AUTO in caps.whiteBalanceModes)
                CaptureRequest.CONTROL_AWB_MODE_AUTO else caps.whiteBalanceModes.firstOrNull() ?: CaptureRequest.CONTROL_AWB_MODE_AUTO
            it.antiBandingMode = if (CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO in caps.antiBandingModes)
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO else caps.antiBandingModes.firstOrNull() ?: CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
            it.zoomRatio = 1f.coerceIn(caps.zoomMin, caps.zoomMax)
            it.manualFocus = false
            it.focusDiopters = 0f
        }
    }

    private fun apply() {
        val cam = camera ?: return
        val caps = capabilities ?: return
        val v = values
        // Camera2 options are built from scratch on every change, so disabling manual
        // controls also clears the previously requested Camera2 override.
        try {
            val b = CaptureRequestOptions.Builder()
            if (caps.manualExposure && v.manualExposure) {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, v.iso.coerceIn(caps.isoMin, caps.isoMax))
                b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, v.shutterNs.coerceIn(caps.shutterMinNs, caps.shutterMaxNs))
            }
            if (v.whiteBalanceMode in caps.whiteBalanceModes) {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, v.whiteBalanceMode)
            }
            if (v.antiBandingMode in caps.antiBandingModes) {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, v.antiBandingMode)
            }
            if (caps.manualFocus && v.manualFocus) {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, v.focusDiopters.coerceIn(0f, caps.maxFocusDiopters))
            }
            observeRequest("white balance / manual exposure / focus", Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(b.build()))
        } catch (e: Exception) {
            reportFailure("Camera2 controls", e)
        }
        if (!v.manualExposure && caps.exposureCompensation) {
            try { observeRequest("exposure compensation", cam.cameraControl.setExposureCompensationIndex(v.exposureIndex.coerceIn(caps.exposureMin, caps.exposureMax))) }
            catch (e: Exception) { reportFailure("exposure compensation", e) }
        }
        if (caps.zoom) {
            try { observeRequest("zoom", cam.cameraControl.setZoomRatio(v.zoomRatio.coerceIn(caps.zoomMin, caps.zoomMax))) }
            catch (e: Exception) { reportFailure("zoom", e) }
        }
    }

    companion object {
        fun whiteBalanceLabel(mode: Int): String = when (mode) {
            CaptureRequest.CONTROL_AWB_MODE_AUTO -> "Auto"
            CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT -> "Tungsten / Indoor"
            CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT -> "Fluorescent"
            CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "Warm fluorescent"
            CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT -> "Daylight"
            CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "Cloudy"
            CaptureRequest.CONTROL_AWB_MODE_TWILIGHT -> "Twilight"
            CaptureRequest.CONTROL_AWB_MODE_SHADE -> "Shade"
            else -> "Mode $mode"
        }
        fun antiBandingLabel(mode: Int): String = when (mode) {
            CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO -> "Auto"
            CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ -> "50 Hz"
            CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ -> "60 Hz"
            CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF -> "Off"
            else -> "Mode $mode"
        }
        fun shutterLabel(nanos: Long): String {
            val seconds = nanos / 1_000_000_000.0
            return if (seconds < 1.0) "1/${(1.0 / seconds).roundToInt()} s" else "%.2f s".format(seconds)
        }
    }
}
