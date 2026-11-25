package com.photobooth.kiosk

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.util.Log
import android.os.SystemClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** An app-private, temporary recording for one still-photo capture. */
data class MotionClip(val file: File, val shutterUs: Long)

/**
 * Generates a normal silent H.264 MP4, not an Apple Live Photo asset.
 *
 * 0..2 seconds: motion before still capture (per-shot recordings synchronized by shutter time).
 * 2..3 seconds: motion after still capture, with a crossfade from motion to the
 *               captured still in the final 0.4 seconds (2.6..3.0).
 * 3..4 seconds: the same framed still-photo arrangement, held for one second.
 *
 * Uses Android framework MediaMetadataRetriever, MediaCodec, EGL/GLES and MediaMuxer;
 * no FFmpeg binary or additional media dependency is needed. Called from a worker thread.
 */
object FramedLiveVideo {
    private const val FPS = 12 // Final MP4 only: unchanged.
    private const val PREVIEW_FPS = 10 // Review playback target; drop late frames rather than slow time.
    private const val PREVIEW_FRAME_LIMIT = 640 // Max decoded source dimensions for Review.
    private const val PREVIEW_DURATION_MS = 4_000L
    // Output dimensions are chosen per session from the admin's resolution tier
    // and the Canva frame's aspect ratio; no fixed output width.
    private const val MOTION_US = 3_000_000L
    private const val CROSSFADE_US = 400_000L
    private const val CROSSFADE_START_US = MOTION_US - CROSSFADE_US
    private const val TOTAL_FRAMES = FPS * 4

    fun encode(
        destination: File,
        width: Int,
        height: Int,
        rawPhotos: List<Bitmap>,
        usedPhotoIndices: Set<Int>,
        clips: List<MotionClip?>,
        render: (List<Bitmap>) -> Bitmap
    ) {
        require(usedPhotoIndices.isNotEmpty())
        require(usedPhotoIndices.all { it in rawPhotos.indices && clips.getOrNull(it)?.file?.isFile == true }) {
            "Not every selected photo has a motion recording"
        }
        val retrievers = mutableMapOf<Int, MediaMetadataRetriever>()
        try {
            for (index in usedPhotoIndices) {
                retrievers[index] = MediaMetadataRetriever().apply {
                    setDataSource(clips[index]!!.file.absolutePath)
                }
            }
            // Render the actual framed still once. It is both the crossfade target and
            // every frame of the final one-second hold, so neither changes over time.
            val still = render(rawPhotos)
            try {
                require(still.width == width && still.height == height) {
                    "Live Photo still size does not match the video encoder"
                }
                val fadePaint = Paint(Paint.FILTER_BITMAP_FLAG)
                Log.i("PB-Processing", "Live Photo requested ${width}x${height} @${FPS}fps")
                val encoder = H264Writer(destination, width, height, FPS)
                try {
                    for (index in 0 until TOTAL_FRAMES) {
                        val videoTimeUs = index * 1_000_000L / FPS
                        if (videoTimeUs >= MOTION_US) {
                            // 3..4s: hold the exact still composition, without re-rendering.
                            encoder.addFrame(still, videoTimeUs)
                            continue
                        }

                        val frames = rawPhotos.toMutableList()
                        val temporary = ArrayList<Bitmap>()
                        try {
                            for (photoIndex in usedPhotoIndices) {
                                val clip = clips[photoIndex]!!
                                val r = retrievers.getValue(photoIndex)
                                val durationUs = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                    ?.toLongOrNull() ?: 3000L) * 1000L
                                // Align recordings to their individual shutter moments.
                                val sampleUs = (clip.shutterUs + videoTimeUs - 2_000_000L)
                                    .coerceIn(0L, (durationUs - 1).coerceAtLeast(0L))
                                val motionFrame = if (Build.VERSION.SDK_INT >= 27) {
                                    try {
                                        r.getScaledFrameAtTime(sampleUs,
                                            MediaMetadataRetriever.OPTION_CLOSEST,
                                            width, height)
                                    } catch (_: RuntimeException) {
                                        r.getFrameAtTime(sampleUs, MediaMetadataRetriever.OPTION_CLOSEST)
                                    }
                                } else r.getFrameAtTime(sampleUs, MediaMetadataRetriever.OPTION_CLOSEST)
                                if (motionFrame != null) {
                                    frames[photoIndex] = motionFrame
                                    temporary.add(motionFrame)
                                }
                            }

                            val rendered = render(frames)
                            try {
                                // 2.6..3.0s: ease-in/ease-out dissolve from the moving
                                // framed image to the actual framed still, including its
                                // photograph layout and decorative frame.
                                val alpha = crossfadeAlpha(videoTimeUs)
                                if (alpha > 0) {
                                    // compose() currently returns mutable ARGB_8888; handle
                                    // an immutable renderer result defensively as well.
                                    val output = if (rendered.isMutable) rendered
                                        else rendered.copy(Bitmap.Config.ARGB_8888, true)
                                    try {
                                        fadePaint.alpha = alpha
                                        Canvas(output).drawBitmap(still, 0f, 0f, fadePaint)
                                        encoder.addFrame(output, videoTimeUs)
                                    } finally {
                                        if (output !== rendered) output.recycle()
                                    }
                                } else {
                                    encoder.addFrame(rendered, videoTimeUs)
                                }
                            } finally {
                                rendered.recycle()
                            }
                        } finally {
                            temporary.forEach { if (!it.isRecycled) it.recycle() }
                        }
                    }
                    encoder.finish()
                } finally {
                    encoder.close()
                }
            } finally {
                still.recycle()
            }
        } catch (e: Exception) {
            destination.delete()
            throw e
        } finally {
            retrievers.values.forEach { try { it.release() } catch (_: Exception) {} }
        }
    }

    /**
     * Review-only, best-effort animation. Does not encode an MP4.
     *
     * The display clock is authoritative: slow decodes skip late preview frames rather
     * than stretching the 4-second animation into slow motion. The export encoder above
     * deliberately keeps its separate, unchanged FPS and resolution.
     *
     * MediaMetadataRetriever still performs random-access frame extraction here;
     * a sequential decoder/GPU compositor will be needed for genuinely smooth playback
     * on weak tablets, especially with several motion clips.
     */
    suspend fun preview(
        rawPhotos: List<Bitmap>,
        usedPhotoIndices: Set<Int>,
        clips: List<MotionClip?>,
        render: (List<Bitmap>) -> Bitmap,
        onFrame: (Bitmap) -> Unit,
        onFirstFrame: () -> Unit
    ) {
        require(usedPhotoIndices.isNotEmpty())
        require(usedPhotoIndices.all { it in rawPhotos.indices && clips.getOrNull(it)?.file?.isFile == true }) {
            "Missing motion recording for a selected photograph"
        }
        val retrievers = usedPhotoIndices.associateWith { index ->
            MediaMetadataRetriever().apply { setDataSource(clips[index]!!.file.absolutePath) }
        }
        try {
            val durations = retrievers.mapValues { (_, r) ->
                ((r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?: 3000L) * 1000L).coerceAtLeast(1L)
            }
            val scaledSupported = mutableMapOf<Int, Boolean>()
            val still = render(rawPhotos)
            try {
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                var first = true
                var reported = false
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val loopStart = SystemClock.elapsedRealtime()
                    var lastSlot = -1
                    var drawn = 0
                    var stillDrawn = false
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val elapsedMs = SystemClock.elapsedRealtime() - loopStart
                        if (elapsedMs >= PREVIEW_DURATION_MS) break

                        // Don't decode the same target slot twice. If composition is slow,
                        // the next iteration jumps straight to the current wall-clock slot.
                        val slot = (elapsedMs * PREVIEW_FPS / 1000L).toInt()
                        if (slot == lastSlot) {
                            val nextSlotMs = (slot + 1L) * 1000L / PREVIEW_FPS
                            delay((nextSlotMs - elapsedMs).coerceIn(1L, 16L))
                            continue
                        }
                        lastSlot = slot
                        val timeUs = elapsedMs * 1000L
                        if (timeUs >= MOTION_US) {
                            // One draw is enough for the still hold, even though it lasts 1 s.
                            if (!stillDrawn) {
                                onFrame(still)
                                stillDrawn = true
                                drawn++
                            }
                        } else {
                            val frames = rawPhotos.toMutableList()
                            val disposable = ArrayList<Bitmap>()
                            try {
                                for (photoIndex in usedPhotoIndices) {
                                    val clip = clips[photoIndex]!!
                                    val sampleUs = (clip.shutterUs + timeUs - 2_000_000L)
                                        .coerceIn(0L, durations.getValue(photoIndex) - 1L)
                                    val r = retrievers.getValue(photoIndex)
                                    val sampled = if (Build.VERSION.SDK_INT >= 27 &&
                                        scaledSupported[photoIndex] != false) {
                                        try {
                                            r.getScaledFrameAtTime(sampleUs,
                                                MediaMetadataRetriever.OPTION_CLOSEST,
                                                PREVIEW_FRAME_LIMIT, PREVIEW_FRAME_LIMIT)
                                        } catch (_: RuntimeException) {
                                            scaledSupported[photoIndex] = false
                                            r.getFrameAtTime(sampleUs, MediaMetadataRetriever.OPTION_CLOSEST)
                                        }
                                    } else {
                                        r.getFrameAtTime(sampleUs, MediaMetadataRetriever.OPTION_CLOSEST)
                                    }
                                    if (sampled != null) {
                                        frames[photoIndex] = sampled
                                        disposable.add(sampled)
                                    }
                                }
                                val composed = render(frames)
                                try {
                                    val alpha = crossfadeAlpha(timeUs)
                                    if (alpha > 0) {
                                        paint.alpha = alpha
                                        Canvas(composed).drawBitmap(still, 0f, 0f, paint)
                                    }
                                    onFrame(composed)
                                    drawn++
                                } finally {
                                    composed.recycle()
                                }
                            } finally {
                                disposable.forEach { if (!it.isRecycled) it.recycle() }
                            }
                        }
                        if (first) { onFirstFrame(); first = false }
                    }
                    if (!reported) {
                        Log.i("PB-Processing", "live preview rendered=$drawn frames in " +
                            "${SystemClock.elapsedRealtime() - loopStart}ms " +
                            "(target=${PREVIEW_FPS}fps, motion sources=${usedPhotoIndices.size})")
                        reported = true
                    }
                }
            } finally { still.recycle() }
        } finally { retrievers.values.forEach { try { it.release() } catch (_: Exception) { } } }
    }

    /** Smoothstep opacity: no visible blending before 2.6 s; fully still at 3.0 s. */
    private fun crossfadeAlpha(timeUs: Long): Int {
        val progress = ((timeUs - CROSSFADE_START_US).toFloat() / CROSSFADE_US)
            .coerceIn(0f, 1f)
        val smooth = progress * progress * (3f - 2f * progress)
        return (smooth * 255f).toInt().coerceIn(0, 255)
    }

    private class H264Writer(file: File, private val width: Int, private val height: Int, fps: Int) : Closeable {
        private val info = MediaCodec.BufferInfo()
        private val codec = createEncoder(width, height, fps)
        private lateinit var muxer: MediaMuxer
        private lateinit var input: Surface
        private lateinit var egl: EncoderSurface
        private var muxerStarted = false
        private var finished = false
        private var track = -1

        init {
            require(width % 2 == 0 && height % 2 == 0)
            try {
                val bitrate = when {
                    width.toLong() * height >= 3840L * 2160L -> 25_000_000
                    width.toLong() * height >= 1920L * 1080L -> 8_000_000
                    else -> 3_000_000
                }
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                input = codec.createInputSurface()
                muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                egl = EncoderSurface(input, width, height)
                codec.start()
            } catch (e: Throwable) {
                // A constructor/init failure never reaches close() at the call site.
                if (this::egl.isInitialized) runCatching { egl.close() }
                if (this::input.isInitialized) runCatching { input.release() }
                if (this::muxer.isInitialized) runCatching { muxer.release() }
                runCatching { codec.release() }
                throw e
            }
        }

        companion object {
            private fun createEncoder(width: Int, height: Int, fps: Int): MediaCodec {
                val candidates = android.media.MediaCodecList(
                    android.media.MediaCodecList.REGULAR_CODECS).codecInfos
                    .filter { it.isEncoder && it.supportedTypes.any { type ->
                        type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)
                    } }
                    .sortedByDescending {
                        if (Build.VERSION.SDK_INT >= 29) it.isHardwareAccelerated else false
                    }
                for (candidate in candidates) {
                    val caps = runCatching { candidate.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC) }
                        .getOrNull() ?: continue
                    if (MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface !in caps.colorFormats) continue
                    val supportsSize = runCatching {
                        caps.videoCapabilities.areSizeAndRateSupported(width, height, fps.toDouble())
                    }.getOrDefault(false)
                    if (!supportsSize) continue
                    return MediaCodec.createByCodecName(candidate.name)
                }
                throw IOException("No Android H.264 surface encoder advertises support for " +
                    "${width}x${height} at ${fps} FPS. Select a smaller Live Photo resolution in Settings.")
            }
        }

        fun addFrame(bitmap: Bitmap, timeUs: Long) {
            check(!finished)
            egl.draw(bitmap, timeUs * 1000L)
            drain(false)
        }

        fun finish() {
            if (finished) return
            codec.signalEndOfInputStream()
            drain(true)
            finished = true
        }

        private fun drain(end: Boolean) {
            var emptyTries = 0
            while (true) {
                val index = codec.dequeueOutputBuffer(info, if (end) 20_000L else 0L)
                when (index) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!end) return
                        if (++emptyTries > 500) throw IOException("Video encoder did not finish")
                    }
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerStarted) throw IOException("Video encoder format changed twice")
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    else -> if (index >= 0) {
                        val output = codec.getOutputBuffer(index) ?: throw IOException("Missing encoded video buffer")
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                        if (info.size > 0) {
                            if (!muxerStarted) throw IOException("Video container not initialized")
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            muxer.writeSampleData(track, output, info)
                        }
                        val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        codec.releaseOutputBuffer(index, false)
                        if (eos) return
                    }
                }
            }
        }

        override fun close() {
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            try { egl.close() } catch (_: Exception) {}
            try { input.release() } catch (_: Exception) {}
            try { if (muxerStarted) muxer.stop() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
        }
    }

    /** EGL input surface and a simple textured rectangle to upload composed Bitmaps. */
    private class EncoderSurface(surface: Surface, width: Int, height: Int) : Closeable {
        private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: android.opengl.EGLContext
        private val eglSurface: android.opengl.EGLSurface
        private val program: Int
        private val texture: Int
        private val vertices = ByteBuffer.allocateDirect(4 * 4 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                // x, y, u, v -- flip Android bitmap coordinates into OpenGL texture coordinates.
                put(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f,
                                 -1f,  1f, 0f, 0f, 1f,  1f, 1f, 0f))
                position(0)
            }
        private val posLocation: Int
        private val uvLocation: Int

        init {
            if (display == EGL14.EGL_NO_DISPLAY) throw IOException("EGL display missing")
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) throw IOException("EGL init failed")
            val configAttrs = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                0x3142 /* EGL_RECORDABLE_ANDROID */, 1, EGL14.EGL_NONE)
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttrs, 0, configs, 0, 1, count, 0) || count[0] == 0)
                throw IOException("No recordable EGL config")
            val config = configs[0] ?: throw IOException("EGL config unavailable")
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context))
                throw IOException("Cannot activate video encoder GL surface")
            GLES20.glViewport(0, 0, width, height)
            val v = compileShader(GLES20.GL_VERTEX_SHADER, """
                attribute vec2 position; attribute vec2 texCoord;
                varying vec2 uv;
                void main() { gl_Position = vec4(position, 0.0, 1.0); uv = texCoord; }
            """.trimIndent())
            val f = compileShader(GLES20.GL_FRAGMENT_SHADER, """
                precision mediump float; varying vec2 uv; uniform sampler2D image;
                void main() { gl_FragColor = texture2D(image, uv); }
            """.trimIndent())
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, v)
            GLES20.glAttachShader(program, f)
            GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(v); GLES20.glDeleteShader(f)
            posLocation = GLES20.glGetAttribLocation(program, "position")
            uvLocation = GLES20.glGetAttribLocation(program, "texCoord")
            texture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        private fun compileShader(type: Int, source: String): Int {
            val id = GLES20.glCreateShader(type)
            GLES20.glShaderSource(id, source)
            GLES20.glCompileShader(id)
            val status = IntArray(1)
            GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] != GLES20.GL_TRUE) throw IOException("Video shader error: ${GLES20.glGetShaderInfoLog(id)}")
            return id
        }

        fun draw(bitmap: Bitmap, timestampNs: Long) {
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "image"), 0)
            vertices.position(0)
            GLES20.glEnableVertexAttribArray(posLocation)
            GLES20.glVertexAttribPointer(posLocation, 2, GLES20.GL_FLOAT, false, 16, vertices)
            vertices.position(2)
            GLES20.glEnableVertexAttribArray(uvLocation)
            GLES20.glVertexAttribPointer(uvLocation, 2, GLES20.GL_FLOAT, false, 16, vertices)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(posLocation)
            GLES20.glDisableVertexAttribArray(uvLocation)
            if (GLES20.glGetError() != GLES20.GL_NO_ERROR) throw IOException("OpenGL video frame render failed")
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, timestampNs)
            if (!EGL14.eglSwapBuffers(display, eglSurface)) throw IOException("Could not submit video frame")
        }

        override fun close() {
            GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            GLES20.glDeleteProgram(program)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, eglSurface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }
}
