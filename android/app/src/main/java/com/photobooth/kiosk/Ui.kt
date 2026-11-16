package com.photobooth.kiosk

import android.animation.ValueAnimator
import android.content.Context
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import android.view.animation.LinearInterpolator
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import androidx.core.graphics.PathParser


object Theme {
    val MAROON = Color.parseColor("#ff0202")
    val CREAM = Color.parseColor("#FFFFFF")
    val CREAM_DARK = Color.parseColor("#FAF6F6")

    //new brand color(not removing the last color yet)
    val BRAND_BG = Color.parseColor("#e9fdff")
    val SIDEBAR_BTN = Color.parseColor("#A1E3FF")
    val SURFACE_WHITE = Color.parseColor("#FFFFFF")
    val TEXT_DARK = Color.parseColor("#000E3B")

    val WHITE80 = Color.argb(204, 255, 255, 255)
    val PREVIEW = Color.parseColor("#5A3F3F")

    val GUEST_BTN_H = 58f
    val GUEST_BTN_W = 260f
    
    var serif: Typeface = Typeface.SERIF
    var serifBold: Typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    var sans: Typeface = Typeface.SANS_SERIF
    var logo: Bitmap? = null
    
    fun load(c: Context) {
        try { 
            serif = Typeface.createFromAsset(c.assets, "fonts/poppins_regular.ttf") 
            serifBold = Typeface.createFromAsset(c.assets, "fonts/poppins_bold.ttf") 
            sans = Typeface.createFromAsset(c.assets, "fonts/poppins_regular.ttf") 
        } catch (_: Exception) { }
        
        try { logo = BitmapFactory.decodeStream(c.assets.open("logo.png")) } catch (_: Exception) { }
    }
}

object Icons {
    const val CALENDAR = "M20,3h-1V1h-2v2H7V1H5v2H4c-1.1,0 -2,0.9 -2,2v16c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2zM20,21H4V8h16v13z"
    const val IMAGE = "M19,5v14H5V5h14m0,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2zM14.14,11.86l-3,3.87L9,13.14 6,17h12l-3.86,-5.14z"
    const val CAMERA = "M9,2L7.17,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2h-3.17L15,2H9zM20,18H4V6h4.05l1.83,-2h4.24l1.83,2H20V18zM12,7c-2.76,0 -5,2.24 -5,5s2.24,5 5,5 5,-2.24 5,-5 -2.24,-5 -5,-5zM12,15c-1.65,0 -3,-1.35 -3,-3s1.35,-3 3,-3 3,1.35 3,3 -1.35,3 -3,3z"
    const val LINK = "M3.9,12c0,-1.71 1.39,-3.1 3.1,-3.1h4V7H7c-2.76,0 -5,2.24 -5,5s2.24,5 5,5h4v-1.9H7c-1.71,0 -3.1,-1.39 -3.1,-3.1zM8,13h8v-2H8v2zM17,7h-4v1.9h4c1.71,0 3.1,1.39 3.1,3.1s-1.39,3.1 -3.1,3.1h-4V17h4c2.76,0 5,-2.24 5,-5s-2.24,-5 -5,-5z"
    const val SYNC = "M12,4V1L8,5l4,4V6c3.31,0 6,2.69 6,6 0,1.01 -0.25,1.97 -0.7,2.8l1.46,1.46C19.54,15.03 20,13.57 20,12c0,-4.42 -3.58,-8 -8,-8zM12,18c-3.31,0 -6,-2.69 -6,-6 0,-1.01 0.25,-1.97 0.7,-2.8L5.24,7.74C4.46,8.97 4,10.43 4,12c0,4.42 3.58,8 8,8v3l4,-4 -4,-4v3z"
    const val EDIT = "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z"
    const val DELETE = "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z"
    const val CLOCK = "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2zM12,20c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8zM12.5,7H11v6l5.25,3.15 0.75,-1.23 -4.5,-2.67z"
    const val CLOUD = "M19.35,10.04C18.67,6.59 15.64,4 12,4 9.11,4 6.6,5.64 5.35,8.04 2.34,8.36 0,10.91 0,14c0,3.31 2.69,6 6,6h13c2.76,0 5,-2.24 5,-5 0,-2.64 -2.05,-4.78 -4.65,-4.96zM19,18H6c-2.21,0 -4,-1.79 -4,-4 0,-2.05 1.53,-3.76 3.56,-3.97l1.07,-0.11 0.5,-0.95C8.08,7.14 9.94,6 12,6c2.62,0 4.88,1.86 5.39,4.43l0.3,1.5 1.53,0.11c1.56,0.1 2.78,1.41 2.78,2.96 0,1.65 -1.35,3 -3,3z"
    const val PRINT = "M19,8H5c-1.66,0 -3,1.34 -3,3v6h4v4h12v-4h4v-6c0,-1.66 -1.34,-3 -3,-3zM16,19H8v-5h8v5zM19,12c-0.55,0 -1,-0.45 -1,-1s0.45,-1 1,-1 1,0.45 1,1 -0.45,1 -1,1zM18,3H6v4h12V3z"
    const val ATTACH = "M16.5,6v11.5c0,2.21 -1.79,4 -4,4s-4,-1.79 -4,-4V5c0,-1.38 1.12,-2.5 2.5,-2.5s2.5,1.12 2.5,2.5v10.5c0,0.55 -0.45,1 -1,1s-1,-0.45 -1,-1V6H10v9.5c0,1.38 1.12,2.5 2.5,2.5s2.5,-1.12 2.5,-2.5V5c0,-2.21 -1.79,-4 -4,-4S7,2.79 7,5v12.5c0,3.04 2.46,5.5 5.5,5.5s5.5,-2.46 5.5,-5.5V6h-1.5z"
    const val FILTER = "M10,18h4v-2h-4v2zM3,6v2h18V6H3zM6,13h12v-2H6v2z"
    const val CHEVRON_LEFT = "M15.41,7.41L14,6l-6,6 6,6 1.41,-1.41L10.83,12z"
    const val CHEVRON_RIGHT = "M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z"
    const val EXPAND_MORE = "M16.59,8.59L12,13.17 7.41,8.59 6,10l6,6 6,-6z"
    const val ADD = "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"
    const val PLAY = "M8,5v14l11,-7z"
    const val CHECK = "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z"
}

class IconDrawable(data: String, color: Int) : Drawable() {
    private val path: Path = try { PathParser.createPathFromPathData(data).apply { fillType = Path.FillType.EVEN_ODD } } catch (_: Exception) { Path() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
    override fun draw(c: Canvas) { val b = bounds; c.save(); c.translate(b.left.toFloat(), b.top.toFloat()); c.scale(b.width() / 24f, b.height() / 24f); c.drawPath(path, paint); c.restore() }
    override fun setAlpha(a: Int) { paint.alpha = a }
    override fun setColorFilter(f: ColorFilter?) { paint.colorFilter = f }
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = 96
    override fun getIntrinsicHeight() = 96
}


class RatioImageView(ctx: Context, private val ratio: Float, private val radiusPx: Float) : ImageView(ctx) {
    init {
        scaleType = ScaleType.CENTER_CROP; clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, radiusPx) } }
    }
    override fun onMeasure(w: Int, h: Int) { val width = MeasureSpec.getSize(w); setMeasuredDimension(width, (width * ratio).toInt()) }
}

class LoadingSpinner(ctx: Context, color: Int = Theme.MAROON) : View(ctx) {
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; this.color = color }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; this.color = Color.argb(45, 128, 0, 0) }
    private var angle = 0f
    private var sweep = 90f
    private val anim = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1100; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { angle = it.animatedValue as Float; sweep = 100f + 80f * Math.sin(Math.toRadians(angle.toDouble())).toFloat(); invalidate() }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (!anim.isStarted) anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val sw = w * 0.11f; arc.strokeWidth = sw; track.strokeWidth = sw
        val r = RectF(sw / 2, sw / 2, w - sw / 2, height - sw / 2)
        c.drawOval(r, track); c.drawArc(r, angle * 1.6f, sweep, false, arc)
    }
}


class CoverVideoView(ctx: Context, private val path: String) : TextureView(ctx), TextureView.SurfaceTextureListener {
    private var mp: MediaPlayer? = null
    private var vw = 0
    private var vh = 0
    init { surfaceTextureListener = this }
    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        try {
            val m = MediaPlayer(); mp = m
            m.setDataSource(path); m.setSurface(Surface(st)); m.isLooping = true; m.setVolume(0f, 0f)
            m.setOnVideoSizeChangedListener { _, w2, h2 -> vw = w2; vh = h2; fit() }
            m.setOnPreparedListener { it.start() }
            m.prepareAsync()
        } catch (_: Exception) { }
    }
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { fit() }
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { release(); return true }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) { }
    private fun fit() {
        if (vw == 0 || vh == 0 || width == 0 || height == 0) return
        val sc = maxOf(width.toFloat() / vw, height.toFloat() / vh)
        val m = Matrix(); m.setScale(vw * sc / width, vh * sc / height, width / 2f, height / 2f); setTransform(m)
    }
    private fun release() { try { mp?.stop() } catch (_: Exception) { }; try { mp?.release() } catch (_: Exception) { }; mp = null }
    override fun onWindowVisibilityChanged(v: Int) { super.onWindowVisibilityChanged(v); try { if (v == View.VISIBLE) mp?.start() else mp?.pause() } catch (_: Exception) { } }
}
