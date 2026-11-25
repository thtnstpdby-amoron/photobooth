package com.photobooth.kiosk

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter

/**
 * Optional fallback for projects which do not already contain PhotoGrade.
 * Color effects work identically for still photographs and decoded Live Photo frames
 * because both pipelines render via Canvas with a ColorFilter.
 * "Soft Portrait" is a subtle tonal treatment, NOT facial landmark detection or
 * skin-specific retouching.
 */
object PhotoGrade {
    data class Preset(val id: String, val title: String)

    val presets = listOf(
        Preset("original", "Original"),
        Preset("warm", "Warm"),
        Preset("cool", "Cool"),
        Preset("mono", "B&W"),
        Preset("vintage", "Vintage"),
        Preset("soft", "Soft Portrait")
    )

    fun colorFilter(id: String, strength: Float): ColorMatrixColorFilter? {
        val k = strength.coerceIn(0f, 1f)
        if (id == "original" || k <= 0f) return null
        // Four rows: RGB transformation + alpha identity. Interpolate each effect
        // with the identity matrix so the SeekBar intensity remains predictable.
        val base = when (id) {
            "warm" -> floatArrayOf(
                1.08f, 0.02f, 0f, 0f, 7f,
                0.01f, 1.01f, 0f, 0f, 3f,
                0f, 0f, 0.90f, 0f, -2f,
                0f, 0f, 0f, 1f, 0f
            )
            "cool" -> floatArrayOf(
                0.96f, 0f, 0f, 0f, -2f,
                0f, 1.00f, 0f, 0f, 1f,
                0f, 0.03f, 1.06f, 0f, 4f,
                0f, 0f, 0f, 1f, 0f
            )
            "mono" -> floatArrayOf(
                .213f, .715f, .072f, 0f, 0f,
                .213f, .715f, .072f, 0f, 0f,
                .213f, .715f, .072f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
            "vintage" -> floatArrayOf(
                .93f, .08f, .03f, 0f, 4f,
                .02f, .91f, .04f, 0f, 2f,
                .02f, .07f, .78f, 0f, 3f,
                0f, 0f, 0f, 1f, 0f
            )
            "soft" -> floatArrayOf(
                .95f, .02f, .01f, 0f, 7f,
                .02f, .96f, .01f, 0f, 6f,
                .01f, .02f, .96f, 0f, 6f,
                0f, 0f, 0f, 1f, 0f
            )
            else -> return null
        }
        val identity = ColorMatrix().array
        val matrix = FloatArray(20) { i -> identity[i] * (1f - k) + base[i] * k }
        return ColorMatrixColorFilter(matrix)
    }
}
