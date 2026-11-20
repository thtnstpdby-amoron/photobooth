package com.photobooth.kiosk

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter

/**
 * Session-level color grades, applied only to photo content (not decorative frames).
 * These are matrix-based color looks; they are NOT imported .cube/3D LUTs.
 * They work uniformly for JPEG, GIF, and the framed motion compositing path.
 */
object PhotoGrade {
    data class Preset(val id: String, val title: String, val matrix: FloatArray)
    val presets = listOf(
        Preset("original", "Original", identity()),
        Preset("warm", "Warm", floatArrayOf(
            1.08f, 0.02f, 0f, 0f, 4f,
            0.01f, 1.01f, 0f, 0f, 0f,
            0f, 0f, 0.91f, 0f, -3f,
            0f, 0f, 0f, 1f, 0f
        )),
        Preset("cool", "Cool", floatArrayOf(
            0.93f, 0f, 0f, 0f, -2f,
            0f, 1.01f, 0f, 0f, 0f,
            0f, 0.02f, 1.09f, 0f, 4f,
            0f, 0f, 0f, 1f, 0f
        )),
        Preset("film", "Soft Film", floatArrayOf(
            0.99f, 0.055f, 0f, 0f, 9f,
            0.01f, 0.96f, 0.01f, 0f, 6f,
            0.01f, 0.03f, 0.90f, 0f, 9f,
            0f, 0f, 0f, 1f, 0f
        )),
        Preset("mono", "Black & White", floatArrayOf(
            .213f, .715f, .072f, 0f, 0f,
            .213f, .715f, .072f, 0f, 0f,
            .213f, .715f, .072f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))
    )

    private fun identity() = floatArrayOf(
        1f,0f,0f,0f,0f, 0f,1f,0f,0f,0f, 0f,0f,1f,0f,0f, 0f,0f,0f,1f,0f
    )
    fun colorFilter(id: String, strength: Float): ColorMatrixColorFilter? {
        val look = presets.firstOrNull { it.id == id } ?: return null
        if (look.id == "original") return null
        val k = strength.coerceIn(0f, 1f)
        if (k <= 0f) return null
        val base = identity()
        return ColorMatrixColorFilter(ColorMatrix(FloatArray(20) { i ->
            base[i] + (look.matrix[i] - base[i]) * k
        }))
    }
}
