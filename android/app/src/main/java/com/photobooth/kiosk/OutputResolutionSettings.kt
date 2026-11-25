package com.photobooth.kiosk

import android.content.Context
import kotlin.math.floor

/**
 * Output size is a composition/export choice, not a USB stream-mode change.
 * A tier is a maximum 16:9 envelope; portrait envelopes are rotated.
 * A square image uses the shorter side of the envelope (e.g. 1080x1080).
 */
class OutputResolutionSettings(context: Context) {
    data class Size(val width: Int, val height: Int) {
        val pixels: Long get() = width.toLong() * height
        val longEdge: Int get() = maxOf(width, height)
        override fun toString() = "${width} x ${height}"
    }

    private val prefs = context.getSharedPreferences("pb_output_resolution", Context.MODE_PRIVATE)

    var jpegHeight: Int
        get() = accepted(prefs.getInt("jpeg_tier", 2160))
        set(value) { prefs.edit().putInt("jpeg_tier", accepted(value)).apply() }

    var liveHeight: Int
        get() = accepted(prefs.getInt("live_tier", 1080))
        set(value) { prefs.edit().putInt("live_tier", accepted(value)).apply() }

    private fun accepted(tier: Int): Int = if (tier in listOf(720, 1080, 2160)) tier else 1080

    fun tiersSupportedByCamera(maxCameraSize: Size?): List<Int> {
        if (maxCameraSize == null) return emptyList()
        // A camera may advertise a 4:3 JPEG mode; it still has enough source pixels
        // for a 16:9 UHD composition (cropping may lose some pixels).
        return listOf(720, 1080, 2160).filter { tier ->
            val bounds = envelope(tier)
            maxCameraSize.longEdge >= bounds.width && maxCameraSize.pixels >= bounds.pixels
        }
    }

    fun effectiveTier(requested: Int, maxCameraSize: Size?): Int {
        val supported = tiersSupportedByCamera(maxCameraSize)
        if (supported.isEmpty()) return 720 // Unknown hardware: safe output, not a fabricated capability
        return supported.lastOrNull { it <= requested } ?: supported.first()
    }

    fun envelope(tier: Int): Size = when (accepted(tier)) {
        720 -> Size(1280, 720)
        1080 -> Size(1920, 1080)
        else -> Size(3840, 2160)
    }

    fun fittedSize(sourceWidth: Int, sourceHeight: Int, tier: Int,
                   maxCameraSize: Size? = null): Size {
        require(sourceWidth > 0 && sourceHeight > 0) { "Invalid Canva frame dimensions" }
        val bounds = envelope(tier)
        val portrait = sourceHeight > sourceWidth
        val boundW = if (portrait) bounds.height else bounds.width
        val boundH = if (portrait) bounds.width else bounds.height
        var scale = minOf(boundW.toDouble() / sourceWidth, boundH.toDouble() / sourceHeight)
        if (maxCameraSize != null) {
            // Never export more pixels or a longer edge than the camera advertises,
            // even when its maximum mode is below 720p or the frame is very narrow.
            val capLong = maxCameraSize.longEdge.toDouble()
            val capShort = minOf(maxCameraSize.width, maxCameraSize.height).toDouble()
            val orientedW = if (portrait) capShort else capLong
            val orientedH = if (portrait) capLong else capShort
            scale = minOf(scale, orientedW / sourceWidth, orientedH / sourceHeight,
                kotlin.math.sqrt(maxCameraSize.pixels.toDouble() /
                    (sourceWidth.toDouble() * sourceHeight)))
        }
        fun even(v: Double) = (floor(v / 2).toInt() * 2).coerceAtLeast(2)
        return Size(even(sourceWidth * scale), even(sourceHeight * scale))
    }
}
