package com.photobooth.kiosk

import android.Manifest
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.provider.OpenableColumns
import android.graphics.*
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.camera.camera2.interop.Camera2CameraInfo
import android.os.Build
import android.os.Bundle
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
import android.text.InputType
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.FallbackStrategy
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MainActivity : ComponentActivity() {
    // Guest UI dimensions. Change these values to retune all four guest stages and frame selection.
    private val guestCornerRadius = 24f
    private val guestPanelPadding = 24f
    // Preview containers are deliberately pure white: change only this value to test alternatives.
    private val guestPreviewColor = Color.WHITE
    private var livePreparationFailed = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var root: FrameLayout
    private var ev: JSONObject? = null
    private var frame: JSONObject? = null
    private val shots = mutableListOf<Bitmap>()
    private var sel = mutableListOf<Int>()
    private val picked = mutableSetOf<Int>()
    private var queue = listOf<JSONObject>()
    private val chosen = mutableListOf<Pair<JSONObject, List<Int>>>()
    private var preview: PreviewView? = null
    private var capture: ImageCapture? = null
    private var prov: ProcessCameraProvider? = null
    private val cameraTuning by lazy { CameraTuning(this) }
    private val outputResolution by lazy { OutputResolutionSettings(this) }
    // Last camera opened in this process. Never treat a previous webcam as a new camera.
    private var cameraMaximum: OutputResolutionSettings.Size? = null
    private var cameraMaximumContext = ""
    private var guest = false
    private var job: Job? = null
    private var camLabel = ""
    private var cur = ""
    private var lastBack = 0L
    private var statusTv: TextView? = null
    private var sideStatus: TextView? = null
    private var k = 1f
    private var evFilter = 0
    private var detailTv: TextView? = null
    private var sessBack: () -> Unit = { }
    private val thumbCache = HashMap<String, Bitmap>()
    private val galleryStatusViews = HashMap<String, TextView>()
    private val io = java.util.concurrent.Executors.newFixedThreadPool(3)
    private var pickMode = ""
    private var pickEid = ""
    private var curEid = ""
    private var editSave: (() -> Unit)? = null
    private var slotDone: (() -> Unit)? = null
    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) onPicked(uri) }
    private val idle = Runnable { if (guest && ev != null && cur != "busy" && cur != "snap" && cur != "live-prep" && cur != "result") startScreen() }
    private val perm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val scanPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) scanScreen() else toast("The camera is needed to scan the QR code. You can still type the code.") }
    private val scanDone = java.util.concurrent.atomic.AtomicBoolean(false)
    private val themePick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) onPicked(uri) }
    private var busyView: View? = null
    private var lastShown = ""
    private var gifEnabled = false
    private var gifFrame: JSONObject? = null
    private enum class PreviewMode { PHOTO, LIVE, GIF }
    private var previewMode = PreviewMode.PHOTO
    private var selectedGrade = "original"
    private var gradeStrength = 0.85f
    private var uvc: UvcCameraBridge? = null
    private var resultVideo: VideoView? = null
    private var activePreview: View? = null
    private var pvJob: Job? = null
    private var reviewLivePlayer: VideoView? = null
    @Volatile private var cachedLiveKey: String? = null
    @Volatile private var cachedLiveFile: File? = null
    @Volatile private var cachedLiveTask: Deferred<File>? = null
    private var resultJob: Job? = null
    private var mirrorEnabled = false
    private var liveEnabled = false
    private var sessionShots = 4
    private var motionCapture: VideoCapture<Recorder>? = null
    private var motionRecording: Recording? = null
    private var motionResult: CompletableDeferred<File?>? = null
    private var motionStarted: CompletableDeferred<Long?>? = null
    private var motionStartNanos = 0L
    private var motionOutputFile: File? = null
    // Raw motion is strictly temporary and never queued to the host.
    private val motionClips = mutableListOf<MotionClip?>()
    private var resultSlides: List<Bitmap> = emptyList()
    // event editor draft: nothing is stored until Save is pressed
    private var draft: JSONObject? = null
    private var draftBase = ""
    private var draftEid = ""
    private var draftNew = false
    private val staged = mutableListOf<Triple<String, String, String>>()
    private val frameCache = HashMap<String, Bitmap>()
    private val gradeThumbnailCache = HashMap<String, Bitmap>()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> try { File(filesDir, "crash.txt").writeText(android.util.Log.getStackTraceString(e)) } catch (_: Exception) { }; prev?.uncaughtException(t, e) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); hideBars()
        Net.init(this); Theme.load(this); root = FrameLayout(this); setContentView(root)
        // Remove temporary recordings left by an interrupted or crashed session.
        cacheDir.listFiles()?.filter {
            it.name.startsWith("pb-live-") || it.name.startsWith("pb-review-live-")
        }?.forEach { it.delete() }
        k = minOf(resources.displayMetrics.widthPixels / 1280f, resources.displayMetrics.heightPixels / 800f); root.setBackgroundColor(Theme.CREAM)
        Thread { while (true) { uploadTick(); Thread.sleep(5000) } }.apply { isDaemon = true; start() }
        Thread { Net.monitor() }.apply { isDaemon = true; start() }
        try {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) { Net.lan = ""; quiet { Net.probeLan(); Net.ping(); refreshStatus() } }
                override fun onLost(n: Network) { Net.lan = ""; Net.via = "offline"; refreshStatus() }
            })
        } catch (_: Exception) { }
        onBackPressedDispatcher.addCallback(this) { goBack() }
        val cr = File(filesDir, "crash.txt")
        if (cr.exists()) {
            val t = cr.readText(); cr.delete()
            cur = "crash"; show(ScrollView(this).apply { addView(col(txt("The app crashed last time. Send this text to the developer:", 20f), TextView(this@MainActivity).apply { text = t; textSize = 12f; setTextIsSelectable(true) }, btn("Continue") { if (Net.token() == "") pairScreen() else eventsScreen() })) })
        } else if (Net.token() == "") pairScreen() else { eventsScreen(); bg { Net.probeLan(); Net.sync(); runOnUiThread { eventsScreen() } } }
    }
    private var tick = 0
    // No status bar (clock, battery, notifications): immersive mode; a swipe from the edge shows the bars for a moment.
    private fun hideBars() {
        try {
            WindowInsetsControllerCompat(window, window.decorView).apply { hide(WindowInsetsCompat.Type.systemBars()); systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
            if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        } catch (_: Exception) { }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideBars() }
    private fun quiet(f: () -> Unit) { Thread { try { if (Net.token() != "") f() } catch (_: Exception) { } }.start() }
    // Every 5 s: send queued photos. Every minute: sync. The connection itself is handled by Net.monitor() in its own thread.
    private fun uploadTick() {
        try { Net.flush() } catch (_: Exception) { }
        if (tick++ % 12 == 0) {
            try { Net.cleanupLocalGallery() } catch (_: Exception) { }
            if (Net.token() != "") try { Net.sync() } catch (_: Exception) { }
        }
        refreshStatus()
    }
    override fun dispatchTouchEvent(e: MotionEvent): Boolean { root.removeCallbacks(idle); root.postDelayed(idle, 60000); return super.dispatchTouchEvent(e) }

    // ---- small UI helpers
    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun toast(t: String) = runOnUiThread { Toast.makeText(this, t, Toast.LENGTH_LONG).show() }
    private fun bg(f: () -> Unit) { Thread { try { f() } catch (e: Exception) { hideBusy(); toast(e.message ?: "error") } }.start() }
    // Waiting screen: a spinner over the current page, so the app never looks frozen. busy() runs the work in a thread and hides it after.
    private fun showBusy(msg: String) = runOnUiThread {
        hideBusyNow()
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(Color.argb(240, 255, 252, 207)); isClickable = true; isFocusable = true
            addView(LoadingSpinner(this@MainActivity), lp(u(76f), u(76f)))
            addView(tv(msg, 26f, Theme.MAROON, Theme.serifBold), lp(-2, -2).apply { topMargin = u(20f) })
        }
        busyView = v; v.alpha = 0f; root.addView(v, FrameLayout.LayoutParams(-1, -1)); v.animate().alpha(1f).setDuration(160).start()
    }
    private fun hideBusyNow() { busyView?.let { root.removeView(it) }; busyView = null }
    private fun hideBusy() = runOnUiThread { val v = busyView; if (v != null) { busyView = null; v.animate().alpha(0f).setDuration(180).withEndAction { root.removeView(v) }.start() } }
    private fun busy(msg: String, f: () -> Unit) { showBusy(msg); Thread { try { f() } catch (e: Exception) { toast(e.message ?: "error") } finally { hideBusy() } }.start() }
    // Every tappable view shrinks a little while pressed and springs back.
    private fun attachPress(v: View) {
        if (v.isClickable && v.hasOnClickListeners() && v !is EditText) {
            v.setOnTouchListener { view, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> if (view.width < root.width * 0.6f) view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(OvershootInterpolator(2.4f)).start()
                }
                false
            }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) attachPress(v.getChildAt(i))
    }
    private fun show(v: View) {
        if (cur != "result") { resultVideo?.stopPlayback(); resultVideo = null }
        statusTv = null; detailTv = null
        val bv = busyView; if (bv != null) root.removeView(bv)
        root.removeAllViews(); root.addView(v, FrameLayout.LayoutParams(-1, -1)); attachPress(v)
        // a new screen fades and slides in; refreshing the same screen (a tap on a frame, a sync) swaps instantly
        if (cur != lastShown) { lastShown = cur; v.alpha = 0f; v.translationY = u(20f).toFloat(); v.animate().alpha(1f).translationY(0f).setDuration(280).setInterpolator(DecelerateInterpolator()).start() }
        if (bv != null) root.addView(bv)
    }
    private fun col(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24))
        v.forEach { addView(it, LinearLayout.LayoutParams(if (it is EditText) dp(480) else -2, -2).apply { setMargins(0, dp(8), 0, dp(8)) }) }
    }

    private fun small() = resources.displayMetrics.heightPixels < dp(520)
    private fun scroll(v: View) = ScrollView(this).apply { isFillViewport = true; addView(v) }
    // A screen with a title, scrolling content, and a button bar that sits BELOW the content (never on top of it).
    private fun page(title: String, sub: String?, content: View, vararg bar: View): View {
        val sm = small()
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        l.addView(txt(title, if (sm) 18f else 26f).apply { typeface = Theme.serifBold }, LinearLayout.LayoutParams(-1, -2))
        if (sub != null) l.addView(txt(sub, 14f), LinearLayout.LayoutParams(-1, -2))
        l.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        if (bar.isNotEmpty()) l.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            val bw = minOf(if (sm) 170 else 280, (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt() / bar.size - 16)
            bar.forEach { addView(it, LinearLayout.LayoutParams(dp(bw), -2).apply { setMargins(dp(6), dp(4), dp(6), 0) }) }
        }, LinearLayout.LayoutParams(-1, -2))
        return l
    }
    private fun statusText() = "Connection: ${Net.viaText()}" +
        (if (Net.lan != "") "\n${Net.lan}" else "") +
        (if (Net.serverVer in 1..5) "\nServer is outdated: rebuild/restart v6 to preserve repeat mappings" else "") +
        (if (Net.syncWarning != "") "\n${Net.syncWarning}" else "")
    private fun refreshStatus() { runOnUiThread {
        statusTv?.text = shortStatus(); detailTv?.text = statusText()
        if (cur == "event" || cur == "egallery" || cur == "gsession")
            for ((sid, view) in galleryStatusViews) view.text = Net.localSessionStatus(sid)
    } }
    private fun exitTap(msg: String, act: () -> Unit) { val t = System.currentTimeMillis(); if (t - lastBack < 2000) act() else { lastBack = t; toast(msg) } }
    // ---- screens
    // ---- admin shell: a sidebar with the sections, the content of the chosen section on the right
    // ---- gallery: the photos live on the laptop, so these screens read them over the current link
    private fun decode(b: ByteArray, max: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(b, 0, b.size, o)
        var sm = 1; while (o.outWidth / (sm * 2) >= max && o.outHeight / (sm * 2) >= max) sm *= 2
        return BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = sm })
    }
    private fun loadInto(iv: ImageView, path: String, max: Int, cache: Boolean = false) {
        if (cache) synchronized(thumbCache) { thumbCache[path] }?.let { iv.setImageBitmap(it); return }
        io.execute {
            try {
                val (c, b) = Net.req("GET", path)
                if (c == 200) { val bmp = decode(b, max); if (bmp != null) { if (cache) synchronized(thumbCache) { thumbCache[path] = bmp }; runOnUiThread { iv.alpha = 0f; iv.setImageBitmap(bmp); iv.animate().alpha(1f).setDuration(220).start() } } }
            } catch (_: Exception) { }
        }
    }
    // Events section: every event is listed. Tap one to see its details, its photo sessions, and Start / Edit / Delete.
    private fun startScreen() {
        clearReviewLiveCache()
        gradeThumbnailCache.clear()
        cur = "start"; job?.cancel(); pvJob?.cancel(); guest = true; closeCam(); shots.clear(); sel = mutableListOf(); picked.clear(); chosen.clear(); gifEnabled = false; previewMode = PreviewMode.PHOTO; gifFrame = null; mirrorEnabled = false; liveEnabled = false; selectedGrade = "original"; gradeStrength = 0.85f; livePreparationFailed = false; resultJob?.cancel(); resultSlides = emptyList(); framedThumbs = emptyList(); discardMotionClips()
        val l = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        val th = ev!!.optJSONObject("theme"); val img = th?.optString("image") ?: ""; val video = th?.optString("kind") == "video"
        val f = Net.imgFile("t_$img")
        if (img != "" && f.exists()) {
            if (video) l.addView(CoverVideoView(this, f.path), FrameLayout.LayoutParams(-1, -1))
            else l.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 2 }))
            }, FrameLayout.LayoutParams(-1, -1))
        }
        l.addView(View(this).apply { isClickable = true; contentDescription = "Touch anywhere to start"; setOnClickListener { frameScreen() } }, FrameLayout.LayoutParams(-1, -1))
        l.addView(View(this).apply { setOnLongClickListener { eventScreen(curEid); true } }, FrameLayout.LayoutParams(dp(100), dp(100), Gravity.TOP or Gravity.START))
        show(l)
    }
    private fun frameScreen() {
        if (cur == "review" || cur == "snap") {
            clearReviewLiveCache()
            discardMotionClips()
        }
        cur = "frames"
        val fr = ev!!.getJSONArray("frames")
        val maxFrames = 1
        if (fr.length() == 0) { toast("This event has no frames yet"); startScreen(); return }

        val blue = Theme.SIDEBAR_BTN
        val sm = small()

        // ==========================================
        // 1. LEFT COLUMN: Title and Grid of Frames
        // ==========================================
        val leftSide = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        
        val title = txt("Pilih Satu Frame dan Mulai Sesi Foto", if (sm) 16f else 24f).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            typeface = Theme.serifBold
        }
        leftSide.addView(title, lp(-1, -2).apply { bottomMargin = u(24f) })

        // Build the individual frame items
        val frameItems = (0 until fr.length()).map { i ->
            val f = fr.getJSONObject(i)
            val on = i in picked
            val iv = FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    setColor(if (on) blue else Theme.BRAND_BG)
                    cornerRadius = u(guestCornerRadius).toFloat()
                }

                addView(
                    ImageView(this@MainActivity).apply {
                        setImageBitmap(
                            BitmapFactory.decodeFile(
                                Net.imgFile("f_" + f.getString("id")).path
                            )
                        )

                        adjustViewBounds = true
                        clipToOutline = true

                        background = GradientDrawable().apply {
                            cornerRadius = u(guestCornerRadius).toFloat()
                        }
                    },
                    FrameLayout.LayoutParams(-1, -2).apply {
                        setMargins(u(18f), u(18f), u(18f), u(18f))
                    }
                )

                setOnClickListener {
                    if (i in picked){
                        picked.remove(i)
                    } else if (maxFrames == 1 ){
                        picked.clear()
                        picked.add(i)
                    } else if (picked.size < maxFrames ) {
                        picked.add(i)
                    } else {
                        toast("Max frames is reached : $maxFrames")
                    }
                    frameScreen()
                }
            }
            FrameLayout(this).apply {
                addView(iv, FrameLayout.LayoutParams(-1, -2))
                if (on) {
                    val badge = txt("\u2713", 16f).apply {
                        setTextColor(Color.WHITE)
                        setPadding(u(10f), u(4f), u(10f), u(4f))
                        background = GradientDrawable().apply {
                            setColor(blue)
                            cornerRadius = u(guestCornerRadius).toFloat()
                        }
                    }
                    addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
                }
            }
        }

        val gridScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(grid(frameItems, if (sm) 5 else 6))
        }
        leftSide.addView(gridScroll, lp(-1, 0, 1f))

        // Action Buttons
        val startBtn = btn("START") {
                if (picked.isEmpty()) toast("Pilih Satu Frame Sebelum Memulai!") else begin()
            }.apply { alpha = if (picked.isEmpty()) 0.45f else 1f
                    typeface = Theme.serifBold
                    background = round(Theme.SURFACE_WHITE, 64f)
                    }
        val backBtn = btn("Back") { startScreen() }.apply { typeface = Theme.serifBold }

        val selectedFrame = picked.firstOrNull()?.let {
            fr.getJSONObject(it)
        }

        val previewBox = FrameLayout(this).apply {
            background = round(guestPreviewColor, guestCornerRadius)
            setPadding(u(guestPanelPadding), u(guestPanelPadding), u(guestPanelPadding), u(guestPanelPadding))

            if (selectedFrame != null) {
                addView(
                    ImageView(this@MainActivity).apply {
                        setImageBitmap(
                            thumb(
                                Net.imgFile("f_" + selectedFrame.getString("id")),
                                900
                            )
                        )
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    },
                    FrameLayout.LayoutParams(-1, -1)
                )
            } else {
                addView(
                    txt("Pilih Frame", 18f).apply { typeface = Theme.serifBold; alpha = 0.45f},
                    FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
                )
            }
        }
        
        val rightSide = LinearLayout(this).apply {
            background = round(Theme.BRAND_BG, guestCornerRadius)
            setPadding(u(guestPanelPadding), u(guestPanelPadding), u(guestPanelPadding), u(guestPanelPadding))
            orientation = LinearLayout.VERTICAL

            // Large preview takes the available height.
            addView(previewBox, lp(-1, 0, 1f))

            // Buttons sit below the preview.
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END

                    addView(
                        startBtn,
                        lp(-2, -2)
                    )
                },
                lp(-1, -2).apply {
                    topMargin = u(16f)
                }
            )
        }

        // ==========================================
        // 3. MASTER CONTAINER
        // ==========================================
        val mainContent = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL

            setPadding(
                u(guestPanelPadding),
                u(guestPanelPadding),
                u(guestPanelPadding),
                u(guestPanelPadding)
            )

            // Left: frame list, approximately 60% width.
            addView(
                leftSide,
                lp(0, -1, 2f).apply {
                    marginEnd = u(20f)
                }
            )

            // Right: larger selected frame and buttons.
            addView(
                rightSide,
                lp(0, -1, 1f)
            )
        }

        show(mainContent)
    }

    private fun cameraXHardwareMaximum(cameraId: String): OutputResolutionSettings.Size? = try {
        val manager = getSystemService(CameraManager::class.java)
        val sizes = manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.JPEG)
        sizes?.maxByOrNull { it.width.toLong() * it.height }
            ?.let { OutputResolutionSettings.Size(it.width, it.height) }
    } catch (e: Exception) {
        android.util.Log.w("PB-Camera", "Could not read Camera2 JPEG capabilities", e)
        null
    }

    private fun openedUsbHardwareMaximum(bridge: UvcCameraBridge): OutputResolutionSettings.Size? {
        val advertised = bridge.availableResolutions().mapNotNull { (key, _) ->
            val parts = key.split(',') // UVCAndroid reports type,width,height,fps
            if (parts.size != 4) return@mapNotNull null
            val w = parts[1].toIntOrNull() ?: return@mapNotNull null
            val h = parts[2].toIntOrNull() ?: return@mapNotNull null
            if (w > 0 && h > 0) OutputResolutionSettings.Size(w, h) else null
        }
        return advertised.maxByOrNull { it.pixels }
    }

    private fun discoverSystemCameraMaximum(): OutputResolutionSettings.Size? {
        // This is only a preliminary listing before CameraX opens; the actual bound
        // Camera2 ID replaces it as soon as the preview opens.
        if (UvcCameraBridge.hasUsbCamera(this) && Net.camMode() in listOf("auto", "external")) return null
        return try {
            val manager = getSystemService(CameraManager::class.java)
            val requestedFacing = when (Net.camMode()) {
                "front" -> CameraCharacteristics.LENS_FACING_FRONT
                "back" -> CameraCharacteristics.LENS_FACING_BACK
                "external" -> CameraCharacteristics.LENS_FACING_EXTERNAL
                else -> null
            }
            val available = manager.cameraIdList.toList().filter { id ->
                requestedFacing == null || manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == requestedFacing
            }
            val ordered = if (requestedFacing == null) available.sortedBy { id ->
                val facing = manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING)
                when (facing) {
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> 0
                    CameraCharacteristics.LENS_FACING_FRONT -> 1
                    else -> 2
                }
            } else available
            ordered.firstNotNullOfOrNull { cameraXHardwareMaximum(it) }
        } catch (e: Exception) {
            android.util.Log.w("PB-Camera", "Cannot enumerate camera output sizes", e)
            null
        }
    }

    private fun hardwareContextKey(): String {
        // Revalidate when USB devices attach/detach or the selected camera mode changes.
        val usbKeys = try {
            getSystemService(UsbManager::class.java).deviceList.values.filter { device ->
                device.deviceClass == UsbConstants.USB_CLASS_VIDEO ||
                    (0 until device.interfaceCount).any {
                        device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO
                    }
            }.map { "${it.vendorId}:${it.productId}:${it.deviceId}" }.sorted().joinToString(";")
        } catch (_: Exception) { "unknown" }
        return "${Net.camMode()}|$usbKeys"
    }

    private fun currentCameraMaximum(): OutputResolutionSettings.Size? =
        if (cameraMaximumContext == hardwareContextKey() && cameraMaximum != null) cameraMaximum
        else discoverSystemCameraMaximum()

    private fun frameSourceDimensions(f: JSONObject): OutputResolutionSettings.Size {
        val path = Net.imgFile("f_" + f.getString("id")).path
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Frame image missing: sync first")
        return OutputResolutionSettings.Size(bounds.outWidth, bounds.outHeight)
    }

    private fun liveVideoDimensions(f: JSONObject): OutputResolutionSettings.Size {
        val source = frameSourceDimensions(f)
        val tier = outputResolution.effectiveTier(outputResolution.liveHeight, currentCameraMaximum())
        return outputResolution.fittedSize(source.width, source.height, tier, currentCameraMaximum())
    }

    // ---- camera (external UVC webcam is used if Android exposes it as an external camera, else the built-in one)
    private fun startCam(fail: () -> Unit = { startScreen() }, overlay: Boolean = true, done: () -> Unit) {
        if (overlay) showBusy("Starting the camera...")
        motionCapture = null
        cameraTuning.detach()
        uvc?.close(); uvc = null
        val prefersUvc = Net.camMode() in listOf("auto", "external") && UvcCameraBridge.hasUsbCamera(this)
        if (prefersUvc) {
            val bridge = UvcCameraBridge(this)
            uvc = bridge
            activePreview = bridge.view
            // AUSBC opens its UVC stream after TextureView receives a SurfaceTexture.
            // Without attaching this view now, the OPENED callback can never arrive:
            // snap() only attaches the view AFTER startCam's done callback.
            root.addView(bridge.view, 0, FrameLayout.LayoutParams(-1, -1))
            val started = android.os.SystemClock.elapsedRealtime()
            var completed = false
            lateinit var timeout: Runnable
            fun complete(success: Boolean, message: String?) {
                if (completed) return
                completed = true
                root.removeCallbacks(timeout)
                if (success) {
                    hideBusy()
                    cameraMaximum = openedUsbHardwareMaximum(bridge)
                    cameraMaximumContext = hardwareContextKey()
                    android.util.Log.i("PB-Camera", "USB advertised maximum=$cameraMaximum")
                    camLabel = "USB UVC webcam"
                    android.util.Log.i("PB-Camera", "UVC ready in ${android.os.SystemClock.elapsedRealtime()-started}ms")
                    done()
                } else {
                    bridge.close()
                    (bridge.view.parent as? ViewGroup)?.removeView(bridge.view)
                    uvc = null
                    if (Net.camMode() == "external") {
                        hideBusy(); toast(message ?: "USB camera unavailable"); fail()
                    } else {
                        // Auto mode: a UVC failure must not break the internal CameraX path.
                        startCamCameraX(fail, overlay, done)
                    }
                }
            }
            timeout = Runnable { complete(false, "USB camera did not open within 12 seconds") }
            root.postDelayed(timeout, 12_000L)
            bridge.open { success, message ->
                // AUSBC may invoke callbacks on its worker thread; UI work must be on Main.
                runOnUiThread { complete(success, message) }
            }
            return
        }
        startCamCameraX(fail, overlay, done)
    }

    private fun startCamCameraX(fail: () -> Unit, overlay: Boolean, done: () -> Unit) {
        val pv = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }.also { preview = it; activePreview = it }; val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            if (overlay) hideBusy()
            try {
                val p = f.get(); prov = p
                val ext = CameraSelector.Builder().addCameraFilter { l -> l.filter { it.lensFacing == CameraSelector.LENS_FACING_EXTERNAL } }.build()
                val order = when (Net.camMode()) {
                    "external" -> listOf(ext)
                    "front" -> listOf(CameraSelector.DEFAULT_FRONT_CAMERA)
                    "back" -> listOf(CameraSelector.DEFAULT_BACK_CAMERA)
                    else -> listOf(ext, CameraSelector.DEFAULT_FRONT_CAMERA, CameraSelector.DEFAULT_BACK_CAMERA)
                }
                var err: Exception? = null; var ok = false
                for (cs in order) {
                    try {
                        p.unbindAll()
                        val pr = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                        val ic = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                        val recorder = Recorder.Builder().setQualitySelector(
                            QualitySelector.from(Quality.SD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
                        ).build()
                        val video = VideoCapture.withOutput(recorder)
                        val cam = try {
                            p.bindToLifecycle(this, cs, pr, ic, video).also { motionCapture = video }
                        } catch (_: Exception) {
                            p.unbindAll()
                            motionCapture = null
                            p.bindToLifecycle(this, cs, pr, ic)
                        }
                        capture = ic; ok = true
                        cameraMaximum = try {
                            cameraXHardwareMaximum(Camera2CameraInfo.from(cam.cameraInfo).cameraId)
                        } catch (e: Exception) {
                            android.util.Log.w("PB-Camera", "Cannot identify bound Camera2 camera", e)
                            null
                        }
                        cameraMaximumContext = hardwareContextKey()
                        android.util.Log.i("PB-Camera", "CameraX advertised JPEG maximum=$cameraMaximum")
                        try { cameraTuning.attach(cam) } catch (_: Exception) { cameraTuning.detach() }
                        camLabel = when (cam.cameraInfo.lensFacing) { CameraSelector.LENS_FACING_EXTERNAL -> "External webcam"; CameraSelector.LENS_FACING_FRONT -> "Front camera"; CameraSelector.LENS_FACING_BACK -> "Rear camera"; else -> "Camera" }
                        break
                    } catch (e: Exception) { err = e }
                }
                if (!ok) throw err ?: IOException("no camera found")
                done()
            } catch (e: Exception) { toast("Camera unavailable: ${e.message}"); fail() }
        }, ContextCompat.getMainExecutor(this))
    }
    // ---- event management (works offline; Net.sync pushes the changes when the laptop is reachable)
    private fun syncText() = if (Net.dirtyCount() == 0) "All changes are synced" else "${Net.dirtyCount()} change(s) waiting to sync"
    private fun confirm(msg: String, yes: () -> Unit) { android.app.AlertDialog.Builder(this).setMessage(msg).setPositiveButton("Yes") { _, _ -> yes() }.setNegativeButton("No", null).show() }
    private fun thumb(f: File, max: Int): Bitmap? {
        if (!f.exists()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(f.path, o)
        var sm = 1; while (o.outWidth / (sm * 2) >= max && o.outHeight / (sm * 2) >= max) sm *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sm })
    }
    private fun isPlaceholderGreen(c: Int): Boolean {
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        val a = Color.alpha(c)

        if (a < 20) return false

        return g > 140 &&
            g > r + 40 &&
            g > b + 40 &&
            r < 180 &&
            b < 180
    }

    private fun removeGreenPlaceholder(src: Bitmap): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        out.setHasAlpha(true)

        for (y in 0 until out.height) {
            for (x in 0 until out.width) {
                val px = out.getPixel(x, y)
                if (isPlaceholderGreen(px)) {
                    out.setPixel(x, y, Color.TRANSPARENT)
                }
            }
        }

        return out
    }

    private fun detectGreenSlots(src: Bitmap): JSONArray {
        val scale = minOf(1f, 1000f / maxOf(src.width, src.height))
        val w = maxOf(1, (src.width * scale).toInt())
        val h = maxOf(1, (src.height * scale).toInt())

        val sample = if (w == src.width && h == src.height) src
                    else Bitmap.createScaledBitmap(src, w, h, false)

        val pixels = IntArray(w * h)
        sample.getPixels(pixels, 0, w, 0, 0, w, h)

        val visited = BooleanArray(pixels.size)
        val queue = IntArray(pixels.size)
        val regions = mutableListOf<Rect>()

        for (start in pixels.indices) {
            if (visited[start]) continue
            visited[start] = true
            if (!isPlaceholderGreen(pixels[start])) continue

            var head = 0
            var tail = 0
            queue[tail++] = start

            var minX = w
            var minY = h
            var maxX = 0
            var maxY = 0
            var count = 0

            while (head < tail) {
                val p = queue[head++]
                val x = p % w
                val y = p / w

                count++
                minX = minOf(minX, x)
                minY = minOf(minY, y)
                maxX = maxOf(maxX, x)
                maxY = maxOf(maxY, y)

                fun visit(n: Int) {
                    if (!visited[n]) {
                        visited[n] = true
                        if (isPlaceholderGreen(pixels[n])) queue[tail++] = n
                    }
                }

                if (x > 0) visit(p - 1)
                if (x < w - 1) visit(p + 1)
                if (y > 0) visit(p - w)
                if (y < h - 1) visit(p + w)
            }

            val rw = maxX - minX + 1
            val rh = maxY - minY + 1

            if (rw >= w * 0.05f &&
                rh >= h * 0.05f &&
                count >= rw * rh * 0.75f) {
                regions.add(Rect(minX, minY, maxX + 1, maxY + 1))
            }
        }

        if (sample !== src) sample.recycle()

        val slots = JSONArray()

        regions.sortedWith(compareBy<Rect> { it.top }.thenBy { it.left })
            .forEach { r ->
                slots.put(
                    JSONObject()
                        .put("x", r.left.toDouble() / w)
                        .put("y", r.top.toDouble() / h)
                        .put("w", r.width().toDouble() / w)
                        .put("h", r.height().toDouble() / h)
                        .put("r", 0)
                )
            }

        return slots
    }

    private fun pick(mode: String, eid: String) {
        pickMode = mode; pickEid = eid
        if (mode == "theme") themePick.launch(arrayOf("image/*", "video/mp4")) else picker.launch("image/*")
    }
    private fun onPicked(uri: Uri) {
        val mode = pickMode; val eid = pickEid
        busy("Saving the file...") {
            val d = draft ?: throw IOException("Open the event again")
            var name = "Frame"; var size = -1L
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); val sx = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst()) { if (ix >= 0) name = c.getString(ix) ?: name; if (sx >= 0) size = c.getLong(sx) }
            }
            val mime = contentResolver.getType(uri) ?: ""
            val video = mode == "theme" && (mime.startsWith("video/") || name.endsWith(".mp4", true))
            if (video && size > 40L * 1024 * 1024) throw IOException("That video is too large (max 40 MB). Use a shorter or smaller one.")
            val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            if (video) {
                if (bytes.size < 12 || String(bytes, 4, 4) != "ftyp") throw IOException("Only MP4 videos are supported")
                val id = Net.rid(); Net.imgFile("t_$id").writeBytes(bytes)
                d.getJSONObject("theme").put("image", id).put("kind", "video"); staged.add(Triple("t", id, ""))
            } else {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                if (o.outWidth <= 0) throw IOException(if (mode == "theme") "That file is not an image or an MP4 video" else "That file is not an image")
                val id = Net.rid()
                if (mode == "frame") {
                    val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: throw IOException("That file is not an image")


                    val detectedSlots = detectGreenSlots(src)
                    val cleaned = removeGreenPlaceholder(src)

                    Net.imgFile("f_$id").outputStream().use {
                        cleaned.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }

                    d.getJSONArray("frames").put(
                        JSONObject()
                            .put("id", id)
                            .put("name", name)
                            .put("slots", detectedSlots)
                    )
                    staged.add(Triple("f", id, name))
                } else {
                    Net.imgFile("t_$id").writeBytes(bytes)
                    d.getJSONObject("theme").put("image", id).remove("kind"); staged.add(Triple("t", id, ""))
                }
            }
            runOnUiThread {
                editScreen(eid)
                if (mode == "frame") {
                    val count = draft?.optJSONArray("frames")?.length() ?: 0
                    if (count > 0) frameEditorDialog(eid, count - 1)
                }
            }
        }
    }
    private fun closeCam() {
        uvc?.close(); uvc = null; activePreview = null
        cameraTuning.detach()
        try { motionRecording?.close() } catch (_: Exception) { }
        motionRecording = null; motionCapture = null
        try { prov?.unbindAll() } catch (_: Exception) { }
    }
    // Every captured image corresponds to a distinct photo source. A single-source
    // frame needs exactly one still image and is ineligible for slideshow GIF.
    private fun uniquePhotoCount(f: JSONObject): Int {
        val a = f.optJSONArray("slots") ?: return 1
        if (a.length() == 0) return 1
        return (0 until a.length()).maxOf { i ->
            a.getJSONObject(i).optInt("source", -1).let { if (it >= 0) it else i }
        } + 1
    }
    private fun begin() {
        clearReviewLiveCache()
        gifEnabled = false; previewMode = PreviewMode.PHOTO; gifFrame = null; mirrorEnabled = false; liveEnabled = false; selectedGrade = "original"; gradeStrength = 0.85f; livePreparationFailed = false
        discardMotionClips()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            perm.launch(Manifest.permission.CAMERA)
            toast("Allow the camera, then pick the frame again")
            return
        }
        queue = picked.sorted().take(1).map { ev!!.getJSONArray("frames").getJSONObject(it) }
        if (queue.isEmpty()) { toast("Choose a frame first"); return }
        frame = queue[0]
        sessionShots = uniquePhotoCount(frame!!)
        if (sessionShots > 9) { toast("This frame needs more than 9 photos; adjust the slot sources"); return }
        chosen.clear(); sel.clear()
        startCam {
            job = scope.launch {
                try {
                    shots.clear()
                    for (i in 0 until sessionShots) shots.add(snap(i))
                    review()
                } catch (e: CancellationException) { throw e }
                  catch (e: Exception) { toast("Camera error: ${e.message}"); startScreen() }
            }
        }
    }

    private fun discardMotionClips() {
        // UVC recordings are stopped by closing the camera; temporary clip files remain
        // under pb-live-* and are removed on session end or next app startup.
        try { motionRecording?.stop() } catch (_: Exception) {}
        try { motionRecording?.close() } catch (_: Exception) {}
        motionRecording = null
        motionResult = null
        motionStarted = null
        motionOutputFile?.delete()
        motionOutputFile = null
        motionClips.forEach { it?.file?.delete() }
        motionClips.clear()
    }

    // Starting is asynchronous: wait for CameraX Start before advancing the countdown.
    // This minimizes the gap between "2" being displayed and actual recorded frames.
    private suspend fun startMotionRecording(): Boolean {
        uvc?.let { return it.startVideo() }
        val cap = motionCapture ?: return false
        val out = File(cacheDir, "pb-live-${Net.rid()}.mp4")
        val started = CompletableDeferred<Long?>()
        val finalized = CompletableDeferred<File?>()
        motionStarted = started
        motionResult = finalized
        motionOutputFile = out
        return try {
            motionRecording = cap.output.prepareRecording(this, FileOutputOptions.Builder(out).build())
                .start(ContextCompat.getMainExecutor(this)) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            val at = android.os.SystemClock.elapsedRealtimeNanos()
                            if (!started.isCompleted) started.complete(at)
                        }
                        is VideoRecordEvent.Finalize -> {
                            if (!started.isCompleted) started.complete(null)
                            val file = if (!event.hasError() && out.length() > 0L) out else null
                            if (file == null) out.delete()
                            // The caller owns successful recordings; late finalizations are discarded.
                            if (!finalized.isCompleted) finalized.complete(file) else out.delete()
                        }
                    }
                }
            val startAt = withTimeoutOrNull(2500L) { started.await() }
            if (startAt == null) {
                if (!finalized.isCompleted) finalized.complete(null)
                motionRecording?.stop()
                motionRecording = null
                out.delete()
                false
            } else {
                motionStartNanos = startAt
                true
            }
        } catch (_: Exception) {
            if (!finalized.isCompleted) finalized.complete(null)
            try { motionRecording?.stop() } catch (_: Exception) {}
            motionRecording = null
            out.delete()
            false
        }
    }

    private suspend fun finishMotionRecording(shutterAtNanos: Long): MotionClip? {
        uvc?.let { return it.stopVideo(shutterAtNanos) }
        try { motionRecording?.stop() } catch (_: Exception) {}
        motionRecording = null
        val finalized = motionResult
        val saved = if (finalized != null) withTimeoutOrNull(6000L) { finalized.await() } else null
        // Timeout: any late completed recording is deleted by the finalize callback below.
        if (saved == null && finalized != null && !finalized.isCompleted) {
            finalized.complete(null)
        }
        motionResult = null
        motionStarted = null
        motionOutputFile = null
        return if (saved?.isFile == true && saved.length() > 0L) {
            MotionClip(saved, ((shutterAtNanos - motionStartNanos) / 1000L).coerceAtLeast(0L))
        } else {
            saved?.delete()
            null
        }
    }

    // For each photograph: start motion near the countdown's 2-second mark,
    // capture the still at zero, then record for one extra second.
    private suspend fun snap(i: Int): Bitmap {
        cur = "snap"
        val total = sessionShots
        val lay = FrameLayout(this)
        val pv = activePreview ?: throw IOException("No active camera preview")
        (pv.parent as? ViewGroup)?.removeView(pv)
        lay.addView(pv, FrameLayout.LayoutParams(-1, -1))
        val hint = txt("Photo ${i + 1} / $total - tap the screen", 22f).apply {
            setTextColor(Color.WHITE); setShadowLayer(12f, 0f, 0f, Color.BLACK)
            alpha = 0.4f; typeface = Theme.serifBold
        }
        val cd = TextView(this).apply {
            textSize = 100f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setShadowLayer(12f, 0f, 0f, Color.BLACK); alpha = 0.4f; typeface = Theme.serifBold
        }
        val tap = View(this)
        lay.addView(cd, FrameLayout.LayoutParams(-1, -1))
        lay.addView(hint, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dp(24) })
        lay.addView(tap, FrameLayout.LayoutParams(-1, -1))
        show(lay)
        suspendCancellableCoroutine<Unit> { c ->
            tap.setOnClickListener { tap.setOnClickListener(null); tap.isClickable = false; c.resume(Unit) }
        }
        hint.text = ""
        val countdown = ev!!.optInt("countdown", 3)
        var recording = false
        var recordingAttempted = false
        try {
            for (seconds in countdown downTo 1) {
                cd.text = "$seconds"
                if (!recordingAttempted && seconds <= 2) {
                    recordingAttempted = true
                    recording = startMotionRecording()
                }
                delay(1000)
            }
            // A countdown of zero offers no pre-roll; still capture must remain functional.
            if (countdown <= 0) recording = startMotionRecording()
            cd.text = ""
            var shutterAt = 0L
            val photo = if (uvc != null) {
                val captured = uvc!!.takePhoto() ?: throw IOException("USB camera still capture failed")
                shutterAt = android.os.SystemClock.elapsedRealtimeNanos()
                android.util.Log.i("PB-Camera", "UVC still source=${captured.width}x${captured.height}")
                captured
            } else suspendCancellableCoroutine<Bitmap> { c ->
                capture!!.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(im: ImageProxy) {
                        shutterAt = android.os.SystemClock.elapsedRealtimeNanos()
                        try {
                            val matrix = Matrix().apply { postRotate(im.imageInfo.rotationDegrees.toFloat()) }
                            val raw = im.toBitmap(); im.close()
                            val result = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                            android.util.Log.i("PB-Camera", "CameraX still source=${result.width}x${result.height}")
                            c.resume(result)
                        } catch (e: Exception) { im.close(); c.resumeWithException(e) }
                    }
                    override fun onError(e: ImageCaptureException) { c.resumeWithException(e) }
                })
            }
            if (recording) delay(1000)
            val clip = if (recording) finishMotionRecording(shutterAt) else null
            android.util.Log.i("PB-Processing", "Photo ${i+1} motion: " +
                "startSucceeded=$recording clip=${clip?.file?.name ?: "MISSING"} " +
                "bytes=${clip?.file?.length() ?: 0L} shutterOffsetUs=${clip?.shutterUs ?: -1}")
            // Only a successful retake replaces its previous motion clip.
            while (motionClips.size <= i) motionClips.add(null)
            motionClips[i]?.file?.delete()
            motionClips[i] = clip
            return photo
        } catch (e: Exception) {
            if (recording) finishMotionRecording(android.os.SystemClock.elapsedRealtimeNanos())?.file?.delete()
            throw e
        }
    }

    // The review preview is encoded once, then played by Android's normal video decoder.
    // It uses the same admin-selected / 12 FPS encode as the final downloadable Live Photo,
    // so Finish can reuse the file instead of encoding the motion twice.
    private fun livePreviewSignature(f: JSONObject, picks: List<Int>): String = listOf(
        f.toString(),
        picks.joinToString(","),
        selectedGrade,
        gradeStrength.toString(),
        mirrorEnabled.toString(),
        outputResolution.liveHeight.toString(),
        shots.joinToString(",") { System.identityHashCode(it).toString() },
        motionClips.joinToString(",") { clip ->
            clip?.let { "${it.file.absolutePath}:${it.file.length()}:${it.file.lastModified()}:${it.shutterUs}" }
                ?: "missing"
        }
    ).joinToString("|")

    private fun stopReviewLivePlayback() {
        try { reviewLivePlayer?.stopPlayback() } catch (_: Exception) { }
        reviewLivePlayer = null
    }

    private fun clearReviewLiveCache() {
        stopReviewLivePlayback()
        cachedLiveKey = null
        cachedLiveTask?.cancel()
        cachedLiveTask = null
        cachedLiveFile?.delete()
        cachedLiveFile = null
    }

    // Call on UI thread. Rendering/encoding is always dispatched off the UI thread.
    private fun prepareReviewLive(f: JSONObject, picks: List<Int>): Deferred<File> {
        val signature = livePreviewSignature(f, picks)
        val previous = cachedLiveTask
        if (signature == cachedLiveKey && previous != null && !previous.isCancelled) return previous
        clearReviewLiveCache()

        val photos = shots.toList()
        val clips = motionClips.toList()
        val gradeId = selectedGrade
        val gradeLevel = gradeStrength
        val mirrored = mirrorEnabled
        val videoSize = liveVideoDimensions(f)
        val videoWidth = videoSize.width
        val evenHeight = videoSize.height
        val target = File(cacheDir, "pb-review-live-${System.nanoTime()}.mp4")
        cachedLiveKey = signature
        cachedLiveFile = target
        val task = scope.async(Dispatchers.Default) {
            val ctx = currentCoroutineContext()
            val started = android.os.SystemClock.elapsedRealtime()
            android.util.Log.i("PB-Processing", "Review Live Photo encoding started: ${picks.size} selected photos")
            try {
                val frameOverlay = frameBmp(f, videoWidth)
                try {
                FramedLiveVideo.encode(
                    destination = target,
                    width = videoWidth,
                    height = evenHeight,
                    rawPhotos = photos,
                    usedPhotoIndices = picks.toSet(),
                    clips = clips
                ) { currentFrames ->
                    // Stop composing once a retake, grade or photo-selection change
                    // invalidates this preview; do not waste time on stale work.
                    ctx.ensureActive()
                    val image = compose(f, picks, videoWidth, sources = currentFrames,
                        gradeId = gradeId, gradeLevel = gradeLevel, mirrored = mirrored,
                        frameOverlay = frameOverlay)
                    if (image.height == evenHeight) image else {
                        val resized = Bitmap.createScaledBitmap(image, videoWidth, evenHeight, true)
                        image.recycle()
                        resized
                    }
                }
                } finally { frameOverlay.recycle() }
                ctx.ensureActive()
                android.util.Log.i("PB-Processing", "review live encode=" +
                    "${android.os.SystemClock.elapsedRealtime() - started}ms")
                target
            } catch (e: Throwable) {
                android.util.Log.e("PB-Processing", "Review Live Photo encode FAILED; " +
                    "clipFiles=${clips.map { it?.file?.name ?: "MISSING" }}", e)
                target.delete()
                throw e
            }
        }
        cachedLiveTask = task
        return task
    }

    // VideoView measures its own SurfaceView from the media aspect ratio. A generic
    // MATCH_PARENT layout can leave portrait/square video aligned toward the left.
    // Explicitly fit the media rectangle and center that rectangle in its pvBox.
    private fun centerVideoInPreview(video: VideoView, box: FrameLayout, videoW: Int, videoH: Int) {
        if (videoW <= 0 || videoH <= 0) return
        fun place() {
            val maxW = box.width - box.paddingLeft - box.paddingRight
            val maxH = box.height - box.paddingTop - box.paddingBottom
            if (maxW <= 0 || maxH <= 0) return
            val scale = minOf(maxW.toFloat() / videoW, maxH.toFloat() / videoH)
            val fittedW = maxOf(1, (videoW * scale).toInt())
            val fittedH = maxOf(1, (videoH * scale).toInt())
            val current = video.layoutParams as? FrameLayout.LayoutParams
            if (current == null || current.width != fittedW || current.height != fittedH || current.gravity != Gravity.CENTER) {
                video.layoutParams = FrameLayout.LayoutParams(fittedW, fittedH, Gravity.CENTER)
            }
        }
        box.post { place() }
        // Refit if the preview area changes size, such as after a display rotation.
        if (video.tag != "centered-video-layout") {
            video.tag = "centered-video-layout"
            box.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) place()
            }
        }
    }

    // ---- Guest workflow: the preview owns the larger left panel on every step.
    private fun guestPane(title: String, left: View, controls: View, back: () -> Unit,
                          nextText: String, next: () -> Unit, nextEnabled: Boolean = true) {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(u(guestPanelPadding), u(guestPanelPadding), u(guestPanelPadding), u(guestPanelPadding))
            addView(txt(title, if (small()) 20f else 26f).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                typeface = Theme.serifBold
            }, lp(-1, -2).apply { bottomMargin = u(14f) })
        }
        val columns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        columns.addView(left, lp(0, -1, 1.85f).apply { rightMargin = u(guestPanelPadding) })
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(Theme.BRAND_BG, guestCornerRadius)
            setPadding(u(guestPanelPadding), u(guestPanelPadding), u(guestPanelPadding), u(guestPanelPadding))
        }
        right.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = false
            addView(controls)
        }, lp(-1, 0, 1f))
        val actions = LinearLayout(this).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(btn("BACK", back).apply { typeface = Theme.serifBold },
                lp(0, -2, 1f).apply { rightMargin = u(8f) })
            addView(btn(nextText, next).apply {
                typeface = Theme.serifBold
                isEnabled = nextEnabled
                alpha = if (nextEnabled) 1f else .45f
                background = round(Theme.SIDEBAR_BTN, 40f)
            }, lp(0, -2, 1f))
        }
        right.addView(actions, lp(-1, -2).apply { topMargin = u(20f) })
        columns.addView(right, lp(0, -1, 1f))
        page.addView(columns, lp(-1, 0, 1f))
        show(page)
    }

    private fun framedPreview(f: JSONObject, picks: List<Int>, size: Int = 880): FrameLayout =
        FrameLayout(this).apply {
            background = round(guestPreviewColor, guestCornerRadius)
            setPadding(u(guestPanelPadding), u(guestPanelPadding), u(guestPanelPadding), u(guestPanelPadding))
            addView(ImageView(this@MainActivity).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(compose(f, picks, size, true))
            }, FrameLayout.LayoutParams(-1, -1))
        }

    // Step 1: arrange selected photos (one scrollable column, not a thumbnail grid).
    private fun review() {
        cur = "review"
        pvJob?.cancel(); stopReviewLivePlayback()
        val f = frame ?: return
        val n = uniquePhotoCount(f)
        val tiles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tiles.addView(txt("Select $n photo${if (n == 1) "" else "s"} in slot order", 17f).apply {
            gravity = Gravity.START; typeface = Theme.serifBold
        }, lp(-1, -2).apply { bottomMargin = u(12f) })
        shots.forEachIndexed { i, shot ->
            val selected = i in sel
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = round(Color.WHITE, guestCornerRadius,
                    if (selected) Theme.SIDEBAR_BTN else 0)
                setPadding(u(20f), u(20f), u(20f), u(20f))
            }
            val picture = ImageView(this).apply {
                setImageBitmap(shot)
                // Use all available column width while retaining the photograph's aspect ratio.
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
                clipToOutline = true
                background = round(guestPreviewColor, guestCornerRadius)
                contentDescription = "Select captured photograph ${i + 1}"
                setOnClickListener {
                    if (selected) sel.remove(i)
                    else if (sel.size < n) sel.add(i)
                    else { sel.removeAt(sel.lastIndex); sel.add(i) }
                    clearReviewLiveCache(); review()
                }
            }
            val pictureHolder = FrameLayout(this).apply {
                addView(picture, FrameLayout.LayoutParams(-1, -2))
                if (selected) addView(FrameLayout(this@MainActivity).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL; setColor(Theme.SIDEBAR_BTN)
                    }
                    addView(icon(Icons.CHECK, 17f, Theme.TEXT_DARK),
                        FrameLayout.LayoutParams(u(17f), u(17f), Gravity.CENTER))
                }, FrameLayout.LayoutParams(u(32f), u(32f), Gravity.TOP or Gravity.END).apply {
                    setMargins(0, u(8f), u(8f), 0)
                })
            }
            tile.addView(pictureHolder, lp(-1, -2))
            tile.addView(btn("Retake") {
                pvJob?.cancel(); clearReviewLiveCache()
                gradeThumbnailCache.clear()
                fun captureAgain() {
                    job = scope.launch {
                        try { shots[i] = snap(i) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { toast("Retake failed: ${e.message}") }
                        review()
                    }
                }
                if (activePreview == null) startCam(fail = {
                    toast("Camera could not reopen for retake"); review()
                }, done = { captureAgain() })
                else captureAgain()
            }.apply {
                textSize = 12f
                minHeight = 0; minimumHeight = 0
                minWidth = 0; minimumWidth = 0
                background = round(Theme.BRAND_BG, guestCornerRadius)
                setPadding(u(18f), u(6f), u(18f), u(6f))
            }, lp(-2, -2).apply { topMargin = u(12f) })
            tiles.addView(tile, lp(-1, -2).apply { bottomMargin = u(18f) })
        }
        guestPane("1 / 4   Arrange Your Photos", framedPreview(f, sel.toList()), tiles,
            back = { job?.cancel(); pvJob?.cancel(); closeCam(); frameScreen() },
            nextText = "NEXT", next = {
                if (sel.size == n) filterScreen() else toast("Select all $n photographs")
            }, nextEnabled = sel.size == n)
    }

    // Step 2: filter presets, previewed on the same session portrait.
    private fun filterScreen() {
        cur = "filter"
        pvJob?.cancel(); stopReviewLivePlayback()
        val f = frame ?: return
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        controls.addView(txt("Filter intensity", 18f).apply {
            gravity = Gravity.START; typeface = Theme.serifBold
        }, lp(-1, -2))
        controls.addView(SeekBar(this).apply {
            max = 100; progress = (gradeStrength * 100f).toInt().coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    val next = (bar?.progress ?: 85) / 100f
                    if (next != gradeStrength) { gradeStrength = next; gradeThumbnailCache.clear(); clearReviewLiveCache(); filterScreen() }
                }
            })
        }, lp(-1, -2).apply { bottomMargin = u(14f) })
        controls.addView(txt("Choose a look", 17f).apply {
            gravity = Gravity.START; typeface = Theme.serifBold
        }, lp(-1, -2))
        val source = shots.getOrNull(sel.firstOrNull() ?: 0)
        val presetItems = PhotoGrade.presets.map { preset ->
            val active = preset.id == selectedGrade
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = round(if (active) Theme.SIDEBAR_BTN else Theme.SURFACE_WHITE, guestCornerRadius)
                setPadding(u(20f), u(20f), u(20f), u(20f))
                isClickable = true
                contentDescription = "Filter ${preset.title}"
                setOnClickListener {
                    if (selectedGrade != preset.id) {
                        selectedGrade = preset.id; clearReviewLiveCache(); filterScreen()
                    }
                }
                addView(ImageView(this@MainActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    clipToOutline = true
                    background = round(guestPreviewColor, guestCornerRadius)
                    val bmp = source
                    if (bmp != null) {
                        val key = "${System.identityHashCode(bmp)}:${preset.id}:${gradeStrength}"
                        val preview = gradeThumbnailCache.getOrPut(key) {
                            val scale = minOf(1f, 160f / maxOf(bmp.width, bmp.height))
                            val w = maxOf(1, (bmp.width * scale).toInt())
                            val h = maxOf(1, (bmp.height * scale).toInt())
                            val thumb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                                colorFilter = PhotoGrade.colorFilter(preset.id, gradeStrength)
                            }
                            Canvas(thumb).drawBitmap(bmp, null, Rect(0, 0, w, h), paint)
                            thumb
                        }
                        setImageBitmap(preview)
                    }
                }, lp(-1, u(116f)))
                addView(txt(preset.title, 14f).apply { typeface = Theme.serifBold },
                    lp(-1, -2).apply { topMargin = u(5f) })
            } as View
        }
        controls.addView(grid(presetItems, 2), lp(-1, -2))
        // A separate mirror control, not a small full-width text button underneath the presets.
        val mirrorSwitch = Switch(this).apply {
            text = ""
            contentDescription = "Mirror photograph"
            isChecked = mirrorEnabled
            setOnCheckedChangeListener { _, checked ->
                if (mirrorEnabled != checked) {
                    mirrorEnabled = checked
                    clearReviewLiveCache()
                    filterScreen()
                }
            }
        }
        val mirrorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(Theme.SURFACE_WHITE, guestCornerRadius)
            setPadding(u(20f), u(20f), u(20f), u(20f))
            addView(txt("⇄", 30f), lp(u(42f), -2).apply { rightMargin = u(10f) })
            addView(txt("Mirror photo", 17f).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                typeface = Theme.serifBold
            }, lp(0, -2, 1f))
            addView(mirrorSwitch, lp(-2, -2))
            isClickable = true
            setOnClickListener { mirrorSwitch.isChecked = !mirrorSwitch.isChecked }
        }
        controls.addView(mirrorRow, lp(-1, -2).apply { topMargin = u(22f) })
        guestPane("2 / 4   Choose Your Filter", framedPreview(f, sel.toList()), controls,
            back = { review() }, nextText = "NEXT", next = {
                // The confirmed frame arrangement + grade are now stable.
                prepareLiveBeforeOptions()
            })
    }

    // Dedicated full-screen preparation stage after filter confirmation.
    // The optional-output tabs never need to display an encoder spinner inside pvBox.
    private fun prepareLiveBeforeOptions() {
        val f = frame ?: return
        pvJob?.cancel(); stopReviewLivePlayback()
        cur = "live-prep"
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Theme.CREAM)
            setPadding(u(guestPanelPadding), u(guestPanelPadding), u(guestPanelPadding), u(guestPanelPadding))
            addView(LoadingSpinner(this@MainActivity), lp(u(88f), u(88f)))
            addView(txt("Preparing your Live Photo", 26f).apply {
                typeface = Theme.serifBold
            }, lp(-2, -2).apply { topMargin = u(28f) })
            addView(txt("Combining your recordings and applying the selected filter...", 16f),
                lp(-2, -2).apply { topMargin = u(12f) })
        }
        show(screen)
        val clipsAvailable = motionClips.size >= shots.size && shots.indices.all {
            motionClips.getOrNull(it)?.file?.isFile == true
        }
        if (!clipsAvailable) {
            livePreparationFailed = true
            liveEnabled = false
            optionalOutputsIntro() // Live Photo will be disabled; normal JPEG/GIF still work.
            return
        }
        livePreparationFailed = false
        pvJob = scope.launch {
            try {
                val file = withTimeout(180_000L) { prepareReviewLive(f, sel.toList()).await() }
                if (cur != "live-prep") return@launch
                if (!file.isFile || file.length() == 0L) throw IOException("Live Photo file is empty")
                pvJob = null // Don't cancel the current preparation coroutine on intro navigation.
                optionalOutputsIntro()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (cur != "live-prep") return@launch
                android.util.Log.e("PB-Processing", "Live Photo preparation failed", e)
                livePreparationFailed = true
                liveEnabled = false
                clearReviewLiveCache()
                // Allow recovery without blocking normal still-photo delivery.
                val errorPage = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(u(guestPanelPadding), u(guestPanelPadding), u(guestPanelPadding), u(guestPanelPadding))
                    addView(txt("Live Photo could not be prepared", 24f).apply {
                        typeface = Theme.serifBold
                    }, lp(-1, -2))
                    addView(txt(e.message ?: "Video encoding failed", 16f),
                        lp(-1, -2).apply { topMargin = u(16f) })
                    addView(btn("RETRY") { prepareLiveBeforeOptions() },
                        lp(-1, -2).apply { topMargin = u(24f) })
                    addView(btn("CONTINUE WITHOUT LIVE PHOTO") {
                        liveEnabled = false
                        optionalOutputsIntro()
                    }, lp(-1, -2).apply { topMargin = u(14f) })
                }
                show(errorPage)
            }
        }
    }

    private fun gifFramesAvailable(): List<JSONObject> {
        val frames = ev?.optJSONArray("frames") ?: return emptyList()
        if (sessionShots < 2) return emptyList()
        return (0 until frames.length()).mapNotNull { frames.optJSONObject(it) }
            .filter { (it.optJSONArray("slots")?.length() ?: 0) == 1 }
    }

    // Step 3a: two big cards; choosing a card only changes the visible tab, never its switch.
    private fun optionalOutputsIntro() {
        cur = "optional"
        previewMode = PreviewMode.PHOTO
        pvJob?.cancel(); stopReviewLivePlayback()
        val entry = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(u(guestPanelPadding), u(guestPanelPadding), u(guestPanelPadding), u(guestPanelPadding))
        }
        fun featureCard(name: String, desc: String, ico: String, mode: PreviewMode, enabled: Boolean) =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = round(Theme.SURFACE_WHITE, guestCornerRadius)
                elevation = u(5f).toFloat()
                setPadding(u(guestPanelPadding), u(guestPanelPadding), u(guestPanelPadding), u(guestPanelPadding))
                addView(icon(ico, 64f, Theme.TEXT_DARK), lp(u(64f), u(64f)))
                addView(txt(name, 23f).apply { typeface = Theme.serifBold },
                    lp(-1, -2).apply { topMargin = u(14f) })
                addView(txt(desc, 14f), lp(-1, -2).apply { topMargin = u(8f) })
                if (enabled) addView(txt("Included", 13f).apply { typeface = Theme.serifBold },
                    lp(-1, -2).apply { topMargin = u(12f) })
                isClickable = true
                setOnClickListener { optionalOutputsDetail(mode, animate = true) }
            }
        entry.addView(featureCard("Live Photo", "Framed motion video", Icons.PLAY,
            PreviewMode.LIVE, liveEnabled), lp(-1, 0, 1f).apply { bottomMargin = u(20f) })
        val gifAvailable = gifFramesAvailable().isNotEmpty()
        entry.addView(featureCard("Slideshow GIF", if (gifAvailable) "Animated photo sequence" else "Not available for this session",
            Icons.IMAGE, PreviewMode.GIF, gifEnabled), lp(-1, 0, 1f))
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(txt("Optional extras", 22f).apply { typeface = Theme.serifBold; gravity = Gravity.START })
            addView(txt("Choose a card to preview. Each extra remains OFF until you enable it.", 17f).apply {
                gravity = Gravity.START
            }, lp(-1, -2).apply { topMargin = u(14f) })
            addView(txt("You can also continue with your still photo only.", 15f).apply {
                gravity = Gravity.START
            }, lp(-1, -2).apply { topMargin = u(16f) })
        }
        guestPane("3 / 4   Optional Outputs", entry, info,
            back = { filterScreen() }, nextText = "CONTINUE", next = { confirmOptionalOutputs() })
    }

    // Step 3b: always keep two tab buttons under pvBox; tabs never alter switches.
    private fun optionalOutputsDetail(mode: PreviewMode, animate: Boolean = false) {
        cur = "optional"
        previewMode = mode
        pvJob?.cancel(); stopReviewLivePlayback()
        val f = frame ?: return
        val previewColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pvBox = FrameLayout(this).apply {
            background = round(guestPreviewColor, guestCornerRadius)
            setPadding(u(guestPanelPadding), u(guestPanelPadding), u(guestPanelPadding), u(guestPanelPadding))
        }
        previewColumn.addView(pvBox, lp(-1, 0, 1f))
        val tabs = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            fun tab(ico: String, selected: Boolean, tabMode: PreviewMode, description: String) {
                addView(circleBtn(ico, 48f) { optionalOutputsDetail(tabMode) }.apply {
                    alpha = if (selected) 1f else .55f
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (selected) Theme.SIDEBAR_BTN else Theme.SURFACE_WHITE)
                    }
                    contentDescription = description
                }, lp(u(48f), u(48f)).apply { setMargins(u(10f), 0, u(10f), 0) })
            }
            tab(Icons.PLAY, mode == PreviewMode.LIVE, PreviewMode.LIVE, "Live Photo tab")
            tab(Icons.IMAGE, mode == PreviewMode.GIF, PreviewMode.GIF, "Slideshow GIF tab")
        }
        previewColumn.addView(tabs, lp(-1, -2).apply { topMargin = u(16f) })
        if (animate) {
            previewColumn.translationX = u(130f).toFloat()
            previewColumn.alpha = 0f
            previewColumn.animate().translationX(0f).alpha(1f).setDuration(280).start()
        }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (mode == PreviewMode.LIVE) {
            controls.addView(txt("Live Photo", 22f).apply {
                gravity = Gravity.START; typeface = Theme.serifBold
            }, lp(-1, -2))
            val available = !livePreparationFailed && motionClips.size >= shots.size && shots.indices.all {
                motionClips.getOrNull(it)?.file?.isFile == true
            }
            controls.addView(Switch(this).apply {
                text = "Include Live Photo"
                textSize = 16f
                isChecked = liveEnabled
                isEnabled = available
                setOnCheckedChangeListener { _, checked ->
                    liveEnabled = checked
                    optionalOutputsDetail(PreviewMode.LIVE)
                }
            }, lp(-1, -2).apply { topMargin = u(18f) })
            controls.addView(txt(if (available) "The video is previewed on the left, even when the switch is OFF."
                else "Unavailable: a camera motion recording is missing.", 15f).apply {
                    gravity = Gravity.START
            }, lp(-1, -2).apply { topMargin = u(16f) })
            if (!available) {
                pvBox.addView(txt("Live Photo unavailable: one or more clips are missing", 17f),
                    FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
            } else {
                val still = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageBitmap(compose(f, sel.toList(), 640))
                }
                pvBox.addView(still, FrameLayout.LayoutParams(-1, -1))
                val video = VideoView(this).apply { visibility = View.VISIBLE }
                pvBox.addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
                val cover = FrameLayout(this).apply {
                    background = round(guestPreviewColor, guestCornerRadius)
                    addView(ImageView(this@MainActivity).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setImageBitmap(compose(f, sel.toList(), 540))
                    }, FrameLayout.LayoutParams(-1, -1))
                }
                val msg = txt("", 16f).apply {
                    visibility = View.GONE // The video was encoded on the previous full-screen loading stage.
                    background = round(Color.WHITE, guestCornerRadius)
                    setPadding(u(18f), u(12f), u(18f), u(12f))
                }
                cover.addView(msg, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
                pvBox.addView(cover, FrameLayout.LayoutParams(-1, -1))
                val task = prepareReviewLive(f, sel.toList())
                reviewLivePlayer = video
                pvJob = scope.launch {
                    try {
                        val completed = withTimeoutOrNull(90_000L) { task.await() }
                        if (completed == null || !completed.isFile) throw IOException("Live Photo preview timed out")
                        if (cur != "optional" || previewMode != PreviewMode.LIVE || reviewLivePlayer !== video) return@launch
                        video.setOnErrorListener { _, _, _ ->
                            if (reviewLivePlayer === video) {
                                cover.visibility = View.VISIBLE
                                msg.visibility = View.VISIBLE
                                msg.text = "Unable to play Live Photo"
                            }
                            true
                        }
                        video.setOnPreparedListener { player ->
                            if (cur == "optional" && previewMode == PreviewMode.LIVE && reviewLivePlayer === video) {
                                player.isLooping = true
                                centerVideoInPreview(video, pvBox, player.videoWidth, player.videoHeight)
                                cover.visibility = View.GONE
                                video.start()
                            }
                        }
                        video.setVideoURI(Uri.fromFile(completed))
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { if (cur == "optional" && previewMode == PreviewMode.LIVE) {
                        msg.visibility = View.VISIBLE
                        msg.text = "Preview unavailable: ${e.message}"
                    } }
                }
            }
        } else {
            val candidates = gifFramesAvailable()
            controls.addView(txt("Slideshow GIF", 22f).apply {
                gravity = Gravity.START; typeface = Theme.serifBold
            }, lp(-1, -2))
            controls.addView(Switch(this).apply {
                text = "Include GIF"
                textSize = 16f
                isChecked = gifEnabled
                isEnabled = candidates.isNotEmpty()
                setOnCheckedChangeListener { _, checked ->
                    gifEnabled = checked
                    optionalOutputsDetail(PreviewMode.GIF)
                }
            }, lp(-1, -2).apply { topMargin = u(18f) })
            if (candidates.isEmpty()) {
                controls.addView(txt("GIF requires at least two captured photos and one single-slot frame.", 15f),
                    lp(-1, -2).apply { topMargin = u(16f) })
                pvBox.addView(txt("Slideshow GIF unavailable", 17f),
                    FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
            } else if (!gifEnabled) {
                controls.addView(txt("Turn on GIF to choose a frame.", 15f),
                    lp(-1, -2).apply { topMargin = u(16f) })
                pvBox.addView(ImageView(this).apply {
                    setImageBitmap(compose(f, sel.toList(), 540))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }, FrameLayout.LayoutParams(-1, -1))
            } else {
                controls.addView(txt("Choose one single-slot frame", 16f).apply {
                    gravity = Gravity.START
                }, lp(-1, -2).apply { topMargin = u(14f) })
                val items = candidates.map { gf ->
                    val on = gifFrame?.optString("id") == gf.optString("id")
                    LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        background = round(if (on) Theme.SIDEBAR_BTN else Theme.SURFACE_WHITE, guestCornerRadius)
                        setPadding(u(20f), u(20f), u(20f), u(20f))
                        isClickable = true
                        setOnClickListener { gifFrame = gf; optionalOutputsDetail(PreviewMode.GIF) }
                        addView(ImageView(this@MainActivity).apply {
                            setImageBitmap(thumb(Net.imgFile("f_${gf.optString("id")}"), 240))
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            background = round(guestPreviewColor, guestCornerRadius)
                            clipToOutline = true
                        }, lp(-1, u(115f)))
                        addView(txt(gf.optString("name", "Frame"), 13f), lp(-1, -2))
                    } as View
                }
                controls.addView(grid(items, 2), lp(-1, -2))
                val gf = gifFrame
                if (gf == null) {
                    pvBox.addView(txt("Select a GIF frame on the right", 17f),
                        FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
                } else {
                    val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                    pvBox.addView(image, FrameLayout.LayoutParams(-1, -1))
                    pvJob = scope.launch {
                        val frames = withContext(Dispatchers.Default) {
                            shots.indices.map { compose(gf, listOf(it), 520) }
                        }
                        try {
                            var i = 0
                            while (isActive && cur == "optional" && previewMode == PreviewMode.GIF) {
                                image.setImageBitmap(frames[i % frames.size])
                                i++; delay(700)
                            }
                        } finally {
                            image.setImageDrawable(null)
                            frames.forEach { if (!it.isRecycled) it.recycle() }
                        }
                    }
                }
            }
        }
        guestPane("3 / 4   Optional Outputs", previewColumn, controls,
            back = { optionalOutputsIntro() }, nextText = "NEXT", next = { confirmOptionalOutputs() },
            nextEnabled = !(gifEnabled && gifFrame == null))
    }

    private fun confirmOptionalOutputs() {
        if (livePreparationFailed && liveEnabled) {
            liveEnabled = false
            toast("Live Photo was not successfully prepared. Retry from the Filter screen.")
            optionalOutputsDetail(PreviewMode.LIVE)
            return
        }
        if (gifEnabled && gifFrame == null) {
            toast("Turn off GIF or choose a GIF frame")
            optionalOutputsDetail(PreviewMode.GIF)
            return
        }
        if (liveEnabled && (motionClips.size < shots.size || shots.indices.any {
                motionClips.getOrNull(it)?.file?.isFile != true
            })) {
            toast("Live Photo cannot be included because a recording is missing")
            optionalOutputsDetail(PreviewMode.LIVE)
            return
        }
        val f = frame ?: return
        chosen.clear(); chosen.add(f to sel.toList())
        pvJob?.cancel(); stopReviewLivePlayback()
        cur = "busy"
        show(View(this).apply { setBackgroundColor(Theme.BRAND_BG) })
        showBusy("Saving your selected photos...")
        finishSession()
    }
    // Export within the admin-selected resolution envelope and the advertised
    // maximum JPEG/UVC camera input size. Frame aspect ratio is retained.
    private fun stillExportWidth(f: JSONObject): Int {
        val frameSize = frameSourceDimensions(f)
        val tier = outputResolution.effectiveTier(outputResolution.jpegHeight, currentCameraMaximum())
        return outputResolution.fittedSize(frameSize.width, frameSize.height, tier,
            currentCameraMaximum()).width
    }
    // ---- compositing happens here on the tablet; the server never touches the image
    private fun finishSession() {
        // CameraX unbindAll() must run on the main/UI thread.
        closeCam()
        bg {
        val id = Net.rid(); val q = File(Net.dir, "q"); val tmp = File(q, ".$id").apply { mkdirs() }
        try {
        val totalStart = android.os.SystemClock.elapsedRealtime()
        var phaseStart = totalStart
        val phases = LinkedHashMap<String, Long>()
        fun mark(name: String) {
            val now = android.os.SystemClock.elapsedRealtime()
            phases[name] = now - phaseStart; phaseStart = now
        }
        var first: Bitmap? = null
        val thumbs = ArrayList<Bitmap>()
        chosen.forEachIndexed { idx, (f, picks) ->
            val out = compose(f, picks, stillExportWidth(f))
            try {
                // Save the full-resolution composite; only display/QR thumbnails are scaled.
                val stillFile = File(tmp, if (idx == 0) "photo.jpg" else "photo${idx + 1}.jpg")
                stillFile.outputStream().use {
                    if (!out.compress(Bitmap.CompressFormat.JPEG, 95, it))
                        throw IOException("Could not encode full-resolution JPEG")
                }
                android.util.Log.i("PB-Processing", "Still JPEG ${out.width}x${out.height} bytes=${stillFile.length()}")
                thumbs.add(Bitmap.createScaledBitmap(out, 360,
                    (360L * out.height / out.width).toInt().coerceAtLeast(1), true))
                if (idx == 0) {
                    // Do not retain a full-size output bitmap solely for the result screen.
                    val previewWidth = minOf(1200, out.width)
                    first = Bitmap.createScaledBitmap(out, previewWidth,
                        (previewWidth.toLong() * out.height / out.width).toInt().coerceAtLeast(1), true)
                    val thumb = Bitmap.createScaledBitmap(out, 300,
                        (300L * out.height / out.width).toInt().coerceAtLeast(1), true)
                    try {
                        File(tmp, "thumb.jpg").outputStream().use {
                            thumb.compress(Bitmap.CompressFormat.JPEG, 70, it)
                        }
                    } finally {
                        if (thumb !== out && thumb !== first) thumb.recycle()
                    }
                }
            } finally {
                if (out !== first && thumbs.none { it === out }) out.recycle()
            }
        }
        mark("jpeg-compose")
        shots.forEachIndexed { i, b -> File(tmp, "raw${i + 1}.jpg").outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
        val gifPreview = if (gifEnabled && gifFrame != null && shots.isNotEmpty()) {
            try {
                val gf = gifFrame!!
                val still = compose(gf, listOf(0), 800)
                if (shots.size > 1) {
                    File(tmp, "gif.gif").writeBytes(Gif.encode(shots.indices.map { compose(gf, listOf(it), 800) }, 700))
                }
                still
            } catch (e: Exception) { throw IOException("GIF generation failed: ${e.message}", e) }
        } else null
        mark("gif-and-raw")
        if (liveEnabled) {
            try {
                val (mainFrame, picks) = chosen.first()
                val destination = File(tmp, "live.mp4")
                val signature = livePreviewSignature(mainFrame, picks)
                val existingTask = if (signature == cachedLiveKey) cachedLiveTask else null
                // Encoding begins after filter confirmation. If the guest finishes
                // before it completes, reuse the in-flight task (on a worker thread).
                val reusable = if (existingTask != null) {
                    try { runBlocking { existingTask.await() } } catch (_: Exception) { null }
                } else null
                if (reusable?.isFile == true && reusable.length() > 0L) {
                    reusable.copyTo(destination, overwrite = true)
                    android.util.Log.i("PB-Processing", "reused review Live Photo MP4")
                } else {
                    // Fallback for a preview error or finishing without visiting Live.
                    val videoSize = liveVideoDimensions(mainFrame)
                    val videoWidth = videoSize.width
                    val evenHeight = videoSize.height
                    val frameOverlay = frameBmp(mainFrame, videoWidth)
                    try {
                    FramedLiveVideo.encode(
                        destination = destination,
                        width = videoWidth,
                        height = evenHeight,
                        rawPhotos = shots.toList(),
                        usedPhotoIndices = picks.toSet(),
                        clips = motionClips.toList()
                    ) { currentFrames ->
                        val image = compose(mainFrame, picks, videoWidth, sources = currentFrames,
                            frameOverlay = frameOverlay)
                        if (image.height == evenHeight) image else {
                            val resized = Bitmap.createScaledBitmap(image, videoWidth, evenHeight, true)
                            image.recycle()
                            resized
                        }
                    }
                    } finally { frameOverlay.recycle() }
                }
            } catch (e: Exception) {
                File(tmp, "live.mp4").delete()
                throw IOException("Live Photo export failed: ${e.message}", e)
            }
        }
        mark("live-video")
        // Never upload the raw temporary motion recordings, even when Live Photo is ON.
        discardMotionClips()
        File(tmp, "meta.json").writeText(JSONObject()
            .put("eventId", ev!!.getString("id"))
            .put("frameId", chosen[0].first.getString("id"))
            .put("rawCount", shots.size).put("photoCount", chosen.size)
            .put("created", Net.now()).toString())
        if (!tmp.renameTo(File(q, id))) throw IOException("Could not preserve the session locally")
        mark("local-save")
        try {
            val line = "${java.util.Date()} id=$id camera=$camLabel shots=${shots.size} " +
                "live=$liveEnabled gif=$gifEnabled " +
                phases.entries.joinToString(" ") { "${it.key}=${it.value}ms" } +
                " total=${android.os.SystemClock.elapsedRealtime()-totalStart}ms"
            val log = File(filesDir, "photobooth-processing-times.log")
            val previous = if (log.isFile) log.readLines().takeLast(49) else emptyList()
            log.writeText((previous + line).joinToString("\n", postfix = "\n"))
            android.util.Log.i("PB-Processing", line)
        } catch (_: Exception) { }
        framedThumbs = thumbs
        resultSlides = thumbs + listOfNotNull(gifPreview)
        val shown = first!!
        hideBusy()
        runOnUiThread { clearReviewLiveCache(); result(id, shown) }
        } catch (e: Exception) {
            // Do not present a QR for a session missing a selected output.
            // Return to optional-output controls so the guest can retry or deselect it.
            tmp.deleteRecursively()
            hideBusy()
            toast("Could not finalize selected outputs: ${e.message}")
            runOnUiThread { if (guest) optionalOutputsIntro() }
        }
        }
    }
    // Compositing happens on the tablet: cover-crop each chosen shot into its slot, then draw the frame on top.
    private fun frameBmp(f: JSONObject, w: Int): Bitmap {
        val key = f.getString("id") + "@" + w
        synchronized(frameCache) { frameCache[key] }?.let { return it }
        val path = Net.imgFile("f_" + f.getString("id")).path
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(path, o)
        var sm = 1; while (o.outWidth / (sm * 2) >= w) sm *= 2
        val b = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sm }) ?: throw IOException("Frame image missing: sync first")
        if (w <= 700) synchronized(frameCache) { frameCache[key] = b }
        return b
    }
    // cover-crop each chosen shot into its slot, then draw the frame on top. preview = true also draws numbered grey boxes for empty slots.
    private fun compose(f: JSONObject, picks: List<Int>, W: Int = 1200, preview: Boolean = false,
                        sources: List<Bitmap> = shots,
                        gradeId: String = selectedGrade, gradeLevel: Float = gradeStrength,
                        mirrored: Boolean = mirrorEnabled,
                        frameOverlay: Bitmap? = null): Bitmap {
        val fb = frameOverlay ?: frameBmp(f, W)

        val H = W * fb.height / fb.width; val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); val cv = Canvas(out); cv.drawColor(Color.WHITE)
        val pt = Paint(Paint.FILTER_BITMAP_FLAG)
        val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = PhotoGrade.colorFilter(gradeId, gradeLevel)
        }
        val sl = f.optJSONArray("slots"); val n = maxOf(1, sl?.length() ?: 0)
        for (k in 0 until n) {
            val s = if (sl != null && sl.length() > 0) sl.getJSONObject(k) else JSONObject("""{"x":0.1,"y":0.1,"w":0.8,"h":0.8,"r":0}""")
            val w = (s.getDouble("w") * W).toFloat(); val h = (s.getDouble("h") * H).toFloat()
            cv.save(); cv.translate((s.getDouble("x") * W).toFloat() + w / 2, (s.getDouble("y") * H).toFloat() + h / 2); cv.rotate(s.optDouble("r", 0.0).toFloat())
            cv.clipRect(-w / 2, -h / 2, w / 2, h / 2)
            val source = s.optInt("source", -1).let { if (it >= 0) it else k }
            if (source < picks.size && picks[source] in shots.indices) {
                val p = sources[picks[source]]; val sc = maxOf(w / p.width, h / p.height); val sw = (w / sc).toInt().coerceAtLeast(1); val sh = (h / sc).toInt().coerceAtLeast(1)
                val l = (p.width - sw) / 2; val t = (p.height - sh) / 2
                cv.save()
                if (mirrored) cv.scale(-1f, 1f)
                cv.drawBitmap(p, Rect(l, t, l + sw, t + sh), RectF(-w / 2, -h / 2, w / 2, h / 2), imagePaint)
                cv.restore()
            } else if (preview) {
                cv.drawColor(Color.rgb(222, 216, 196))
                val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(128, 100, 100); textSize = minOf(w, h) * 0.4f; textAlign = Paint.Align.CENTER }
                cv.drawText("${k + 1}", 0f, tp.textSize / 3f, tp)
            }
            cv.restore()
        }
        cv.drawBitmap(fb, null, Rect(0, 0, W, H), pt)
        if (frameOverlay == null && W > 700) fb.recycle() // Caller owns its reusable overlay
        return out
    }
    // ---- guest printing: pick framed photos and copies of each. The event sets the total allowed per photo session.
    private var framedThumbs: List<Bitmap> = listOf()
    private var resId = ""
    private var resPhoto: Bitmap? = null
    private fun printScreen(id: String) {
        bg {
            val (c, b) = Net.req("GET", "/api/v1/sessions/$id/print-info")
            if (c != 200) { toast("Your photos are still being saved. Try again in a moment."); return@bg }
            val j = JSONObject(String(b))
            runOnUiThread { printUi(id, j.getInt("max"), j.getInt("used")) }
        }
    }
    private fun printUi(id: String, max: Int, used: Int) {
        cur = "print"
        val left = max - used; val sm = small()
        val counts = IntArray(framedThumbs.size); val tvs = ArrayList<TextView>(); val total = txt("", 20f)
        fun refresh() { total.text = "Copies chosen: ${counts.sum()} of $left"; tvs.forEachIndexed { i, t -> t.text = "${counts[i]}" } }
        val rows = framedThumbs.mapIndexed { i, bmp ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(ImageView(this).apply { setImageBitmap(bmp) }, LinearLayout.LayoutParams(dp(if (sm) 80 else 120), dp(if (sm) 80 else 120)))
            row.addView(txt("Photo ${i + 1}", 18f), LinearLayout.LayoutParams(dp(90), -2))
            row.addView(btn("-") { if (counts[i] > 0) counts[i]--; refresh() }.apply { textSize = 18f; typeface = Theme.serifBold }, LinearLayout.LayoutParams(dp(38), dp(38)))
            val tv = txt("0", 24f); tvs.add(tv); row.addView(tv, LinearLayout.LayoutParams(dp(48), -2))
            row.addView(btn("+") { if (counts.sum() < left) counts[i]++ else toast("The limit is $max copies per photo session"); refresh() }.apply { textSize = 18f; typeface = Theme.serifBold }, LinearLayout.LayoutParams(dp(38), dp(38)))
            row as View
        }
        refresh()
        val send = btn("PRINT") {
            if (counts.sum() == 0) { toast("Choose at least one copy"); return@btn }
            val items = JSONArray()
            counts.forEachIndexed { i, n -> if (n > 0) items.put(JSONObject().put("file", if (i == 0) "photo.jpg" else "photo${i + 1}.jpg").put("copies", n)) }
            val body = JSONObject().put("items", items).toString().toByteArray()
            busy("Sending to the printer...") {
                val (code, rb) = Net.req("POST", "/api/v1/sessions/$id/print", body)
                if (code == 200) { toast("Sent to print: ${counts.sum()} copies"); runOnUiThread { resPhoto?.let { result(resId, it) } } }
                else if (code == 409) { val r = try { JSONObject(String(rb)).optInt("remaining", 0) } catch (_: Exception) { 0 }; toast("Only $r copies left for this photo session") }
                else toast("Could not send to print (code $code)")
            }
        }.apply {
            typeface = Theme.serifBold
        }
        val mainContent = FrameLayout(this).apply {
            addView(
                page("Choose what to print", if (left > 0) "Up to $left copies in total (limit $max per photo session)" else "The print limit for this photo session is used up",
                    scroll(col(total, *rows.toTypedArray()))),
                FrameLayout.LayoutParams(-1, -1)
            )

            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER

                    addView(send, lp(-2, -2).apply {
                        marginEnd = u(12f)
                    })

                    addView(
                        btn("BACK") { resPhoto?.let { result(resId, it) } }.apply { typeface = Theme.serifBold }
                    )
                },
                FrameLayout.LayoutParams(
                    -2, -2,
                    Gravity.BOTTOM or Gravity.END
                ).apply {
                    setMargins(u(0f), u(0f), u(24f), u(24f))
                }
            )
        }
        show(mainContent)
    }
    private fun qr(t: String): Bitmap {
        val m = QRCodeWriter().encode(t, BarcodeFormat.QR_CODE, 512, 512); val b = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
        for (x in 0 until 512) for (y in 0 until 512) b.setPixel(x, y, if (m[x, y]) Color.BLACK else Color.WHITE)
        return b
    }
    // Step 4: manual swipe results on the left; QR and print status on the right.
    // QR remains hidden until the upload receipt, host file list and public link are verified.
    private fun result(id: String, photo: Bitmap) {
        cur = "result"
        resultJob?.cancel()
        resultVideo?.stopPlayback(); resultVideo = null
        resId = id; resPhoto = photo
        val dir = File(File(Net.dir, "q"), id)
        val options = mutableListOf("photo")
        if (File(dir, "live.mp4").isFile) options.add("live")
        if (File(dir, "gif.gif").isFile) options.add("gif")
        var current = 0
        val mediaBox = FrameLayout(this).apply {
            background = round(guestPreviewColor, guestCornerRadius)
            elevation = u(4f).toFloat()
        }
        val picture = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val movie = VideoView(this).apply { visibility = View.GONE }
        mediaBox.addView(picture, FrameLayout.LayoutParams(-1, -1))
        mediaBox.addView(movie, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        val dots = LinearLayout(this).apply { gravity = Gravity.CENTER }
        var animatedGif: android.graphics.drawable.AnimatedImageDrawable? = null
        fun renderSlide() {
            try { movie.stopPlayback() } catch (_: Exception) {}
            resultVideo = null
            animatedGif?.stop(); animatedGif = null
            movie.visibility = View.GONE
            picture.visibility = View.VISIBLE
            picture.setImageDrawable(null)
            when (options[current]) {
                "live" -> {
                    movie.visibility = View.VISIBLE
                    picture.visibility = View.GONE
                    movie.setOnErrorListener { _, _, _ ->
                        movie.visibility = View.GONE
                        picture.visibility = View.VISIBLE
                        picture.setImageBitmap(photo)
                        true
                    }
                    movie.setOnPreparedListener { mp ->
                        if (cur == "result" && options[current] == "live") {
                            mp.isLooping = true
                            centerVideoInPreview(movie, mediaBox, mp.videoWidth, mp.videoHeight)
                            movie.start()
                        }
                    }
                    resultVideo = movie
                    movie.setVideoURI(Uri.fromFile(File(dir, "live.mp4")))
                }
                "gif" -> {
                    val gif = File(dir, "gif.gif")
                    if (Build.VERSION.SDK_INT >= 28) {
                        try {
                            val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(gif))
                            picture.setImageDrawable(drawable)
                            if (drawable is android.graphics.drawable.AnimatedImageDrawable) {
                                animatedGif = drawable; drawable.start()
                            }
                        } catch (_: Exception) {
                            picture.setImageBitmap(resultSlides.lastOrNull() ?: photo)
                        }
                    } else picture.setImageBitmap(resultSlides.lastOrNull() ?: photo)
                }
                else -> picture.setImageBitmap(photo)
            }
            dots.removeAllViews()
            options.forEachIndexed { i, kind ->
                dots.addView(View(this).apply {
                    background = round(if (i == current) Theme.SIDEBAR_BTN else Theme.TEXT_DARK, 8f)
                    alpha = if (i == current) 1f else .35f
                    contentDescription = "$kind result ${i + 1} of ${options.size}"
                }, lp(u(if (i == current) 24f else 9f), u(7f)).apply {
                    setMargins(u(5f), 0, u(5f), 0)
                })
            }
        }
        // Transparent swipe surface: animation never moves automatically.
        var downX = 0f
        mediaBox.addView(View(this).apply {
            isClickable = true
            contentDescription = "Swipe left or right to view session outputs"
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = event.x; true }
                    MotionEvent.ACTION_UP -> {
                        val distance = event.x - downX
                        if (options.size > 1 && kotlin.math.abs(distance) > dp(36)) {
                            current = (current + if (distance < 0) 1 else options.size - 1) % options.size
                            renderSlide()
                        }
                        true
                    }
                    else -> true
                }
            }
        }, FrameLayout.LayoutParams(-1, -1))
        val previewColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(mediaBox, lp(-1, 0, 1f))
            addView(dots, lp(-1, -2).apply { topMargin = u(15f) })
        }
        renderSlide()

        var hostReady = false
        var publicReady = false
        var uploadStatus = "Waiting to upload..."
        var printMax = 0
        var printUsed = 0
        var copies = 1
        var tab = "qr"
        var sendingPrint = false
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        controls.addView(tabBar, lp(-1, -2))
        controls.addView(body, lp(-1, -2).apply { topMargin = u(24f) })
        fun renderControls() {
            tabBar.removeAllViews()
            fun addTab(title: String, ico: String, selected: Boolean, kind: String) {
                val t = LinearLayout(this).apply {
                    gravity = Gravity.CENTER
                    background = round(if (selected) Theme.SIDEBAR_BTN else Theme.SURFACE_WHITE, guestCornerRadius)
                    setPadding(u(14f), u(16f), u(14f), u(16f))
                    isClickable = true
                    setOnClickListener { tab = kind; renderControls() }
                    addView(icon(ico, 21f, Theme.TEXT_DARK), lp(u(21f), u(21f)).apply { rightMargin = u(6f) })
                    addView(txt(title, 15f).apply { typeface = Theme.serifBold })
                }
                tabBar.addView(t, lp(0, -2, 1f).apply { rightMargin = u(5f) })
            }
            addTab("QR Code", Icons.LINK, tab == "qr", "qr")
            addTab("Print", Icons.PRINT, tab == "print", "print")
            body.removeAllViews()
            if (tab == "qr") {
                if (publicReady && Net.pub().isNotBlank()) {
                    body.addView(ImageView(this).apply {
                        setImageBitmap(qr("${Net.pub().trimEnd('/')}/d/$id"))
                        contentDescription = "Download QR code"
                    }, lp(u(205f), u(205f)))
                    body.addView(txt("Scan to download your results", 17f),
                        lp(-1, -2).apply { topMargin = u(12f) })
                } else {
                    body.addView(LoadingSpinner(this), lp(u(60f), u(60f)))
                    body.addView(txt(if (hostReady && Net.pub().isBlank())
                        "Public download address not configured"
                        else if (hostReady) "Public download link unreachable; checking again..."
                        else if (Net.via == "offline") "Host unavailable. Files are saved on the tablet and will retry."
                        else uploadStatus, 17f), lp(-1, -2).apply { topMargin = u(18f) })
                    body.addView(txt("The QR code appears only when all files are available.", 14f),
                        lp(-1, -2).apply { topMargin = u(12f) })
                }
            } else {
                if (!hostReady || printMax == 0) {
                    body.addView(LoadingSpinner(this), lp(u(60f), u(60f)))
                    body.addView(txt(if (hostReady) "Checking print availability..." else if (Net.via == "offline")
                        "Host offline. Printing will be available after upload." else uploadStatus, 17f),
                        lp(-1, -2).apply { topMargin = u(18f) })
                } else {
                    val remaining = (printMax - printUsed).coerceAtLeast(0)
                    copies = copies.coerceIn(if (remaining > 0) 1 else 0, remaining)
                    body.addView(txt("Print copies", 20f).apply { typeface = Theme.serifBold })
                    body.addView(txt("$remaining of $printMax remaining", 14f),
                        lp(-1, -2).apply { topMargin = u(7f) })
                    val step = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                    val number = txt("$copies", 26f).apply { typeface = Theme.serifBold }
                    step.addView(circleBtn(Icons.CHEVRON_LEFT, 44f) {
                        if (copies > 1) { copies--; number.text = "$copies" }
                    }, lp(u(44f), u(44f)))
                    step.addView(number, lp(u(78f), -2))
                    step.addView(circleBtn(Icons.CHEVRON_RIGHT, 44f) {
                        if (copies < remaining) { copies++; number.text = "$copies" }
                    }, lp(u(44f), u(44f)))
                    body.addView(step, lp(-2, -2).apply { topMargin = u(18f) })
                    body.addView(btn(if (sendingPrint) "SENDING..." else "PRINT") {
                        if (sendingPrint || copies < 1 || !hostReady) return@btn
                        sendingPrint = true
                        val amount = copies
                        renderControls()
                        bg {
                            val payload = JSONObject().put("items", JSONArray().put(
                                JSONObject().put("file", "photo.jpg").put("copies", amount)
                            )).toString().toByteArray()
                            val (code, bytes) = Net.req("POST", "/api/v1/sessions/$id/print", payload)
                            runOnUiThread {
                                sendingPrint = false
                                if (code == 200) {
                                    printUsed += amount
                                    toast("Sent $amount cop${if (amount == 1) "y" else "ies"} to print queue")
                                } else if (code == 409) {
                                    try {
                                        val remainingNow = JSONObject(String(bytes)).optInt("remaining", 0)
                                        printUsed = (printMax - remainingNow).coerceAtLeast(0)
                                    } catch (_: Exception) {}
                                    toast("Print limit reached or changed")
                                } else toast("Printer request rejected ($code)")
                                if (cur == "result") renderControls()
                            }
                        }
                    }.apply {
                        typeface = Theme.serifBold
                        val printerIcon = IconDrawable(Icons.PRINT, Theme.TEXT_DARK).apply {
                            setBounds(0, 0, u(20f), u(20f))
                        }
                        setCompoundDrawablesRelative(printerIcon, null, null, null)
                        compoundDrawablePadding = u(8f)
                        isEnabled = remaining > 0 && !sendingPrint
                        alpha = if (isEnabled) 1f else .45f
                    }, lp(-1, -2).apply { topMargin = u(20f) })
                }
            }
        }
        renderControls()
        guestPane("4 / 4   Your Results", previewColumn, controls,
            back = { startScreen() }, nextText = "FINISH", next = {
                if (!hostReady) {
                    confirm("Your session is saved on the tablet but still uploading. Leave this screen? Uploads will continue in the background.") {
                        startScreen()
                    }
                } else startScreen()
            })
        resultJob = scope.launch {
            while (isActive && cur == "result") {
                try {
                    val status = withContext(Dispatchers.IO) {
                        val st = Net.localSessionStatus(id)
                        val h = if (Net.sessionUploaded(id)) Net.verifyHostSession(id) else false
                        val p = h && Net.pub().isNotBlank() && Net.verifyPublicSession(id)
                        val print = if (h) try {
                            val (code, bytes) = Net.req("GET", "/api/v1/sessions/$id/print-info")
                            if (code == 200) JSONObject(String(bytes)) else null
                        } catch (_: Exception) { null } else null
                        Triple(st, h, Pair(p, print))
                    }
                    if (cur != "result") break
                    val wasDifferent = uploadStatus != status.first || hostReady != status.second ||
                        publicReady != status.third.first
                    uploadStatus = status.first
                    hostReady = status.second
                    publicReady = status.third.first
                    val print = status.third.second
                    val changedPrint = print != null && (printMax != print.optInt("max") || printUsed != print.optInt("used"))
                    if (print != null) {
                        printMax = print.optInt("max", 0)
                        printUsed = print.optInt("used", 0)
                    }
                    if (wasDifferent || changedPrint) renderControls()
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    if (cur == "result") {
                        uploadStatus = Net.localSessionStatus(id)
                        renderControls()
                    }
                }
                delay(3500)
            }
        }
    }

    // ===================================================================================================
    // Design kit + admin screens, built from the Figma design (1280 x 800 canvas).
    // u(x) turns a design pixel into screen pixels. Fonts: Cormorant Garamond (serif), Lexend (sans).
    // ===================================================================================================
    private fun u(x: Float) = (x * k).toInt()
    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
    private fun tv(text: String, size: Float, color: Int, face: Typeface = Theme.serif) = TextView(this).apply {
        this.text = text; setTextSize(TypedValue.COMPLEX_UNIT_PX, size * k); setTextColor(color); typeface = face; includeFontPadding = false
    }
    private fun round(color: Int, r: Float, stroke: Int = 0) = GradientDrawable().apply { setColor(color); cornerRadius = r * k; if (stroke != 0) setStroke(maxOf(1, u(1.5f)), stroke) }
    private fun icon(path: String, size: Float, color: Int) = ImageView(this).apply { setImageDrawable(IconDrawable(path, color)); layoutParams = ViewGroup.LayoutParams(u(size), u(size)) }
    private fun txt(t: String, s: Float = 22f) = TextView(this).apply { text = t; textSize = s; gravity = Gravity.CENTER; setTextColor(Theme.TEXT_DARK); typeface = Theme.serif }
    private fun btn(t: String, f: () -> Unit) = Button(this).apply {
        text = t; textSize = if (small()) 14f else 18f; setOnClickListener { f() }; isAllCaps = false; typeface = Theme.serif; setTextColor(Theme.TEXT_DARK)
        background = round(Theme.BRAND_BG, 64f); stateListAnimator = null; setPadding(dp(12), dp(6), dp(12), dp(4))
    }
    // cream pill with maroon text (dark = maroon pill with cream text), optional icon
    private fun pill(text: String, size: Float = 20f, ico: String? = null, bold: Boolean = false, dark: Boolean = false, iconRight: Boolean = false, onClick: () -> Unit): LinearLayout {
        val fg = Theme.TEXT_DARK
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER; background = round(Theme.SIDEBAR_BTN, 100f); setPadding(u(16f), u(7f), u(16f), u(7f)); isClickable = true; setOnClickListener { onClick() }
            val ic = if (ico != null) icon(ico, size, fg) else null
            if (ic != null && !iconRight) addView(ic, lp(u(size * 1.1f), u(size * 1.1f)).apply { rightMargin = u(8f) })
            addView(tv(text, size, fg, if (bold) Theme.serifBold else Theme.serif))
            if (ic != null && iconRight) addView(ic, lp(u(size * 1.1f), u(size * 1.1f)).apply { leftMargin = u(8f) })
        }
    }
    private fun circleBtn(ico: String, d: Float = 34f, onClick: () -> Unit): View = FrameLayout(this).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Theme.SURFACE_WHITE) }; isClickable = true; setOnClickListener { onClick() }
        addView(icon(ico, d * 0.5f, Theme.TEXT_DARK), FrameLayout.LayoutParams(u(d * 0.5f), u(d * 0.5f), Gravity.CENTER))
    }
    private fun card(r: Float = 16f, pad: Float = 10f) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = round(Theme.BRAND_BG, r); elevation = 5f * k; setPadding(u(pad), u(pad), u(pad), u(pad)) }
    private fun ratioImg(ratio: Float, r: Float = 10f) = RatioImageView(this, ratio, r * k).apply { setBackgroundColor(Theme.PREVIEW) }
    private fun field(hintText: String, numeric: Boolean = false) = EditText(this).apply {
        hint = hintText; setSingleLine(); setTextSize(TypedValue.COMPLEX_UNIT_PX, 22f * k); setTextColor(Theme.TEXT_DARK); setHintTextColor(Theme.TEXT_DARK); typeface = Theme.serif
        background = round(Theme.SURFACE_WHITE, 100f); setPadding(u(20f), u(8f), u(20f), u(8f)); if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
    }
    private fun dropdown(current: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit): View {
        val label = tv(current, 20f, Theme.TEXT_DARK)
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; background = round(Theme.SURFACE_WHITE, 100f); setPadding(u(22f), u(9f), u(14f), u(9f)); isClickable = true
            addView(label); addView(icon(Icons.EXPAND_MORE, 22f, Theme.TEXT_DARK), lp(u(22f), u(22f)).apply { leftMargin = u(10f) })
            setOnClickListener { v -> PopupMenu(this@MainActivity, v).apply { options.forEachIndexed { i, o -> menu.add(0, i, i, o.second) }; setOnMenuItemClickListener { label.text = options[it.itemId].second; onSelect(options[it.itemId].first); true } }.show() }
        }
    }
    private fun stepper(label: String, ico: String, start: Int, lo: Int, hi: Int, onChange: (Int) -> Unit): View {
        var v = start
        val valueTv = tv("$v", 22f, Theme.TEXT_DARK, Theme.sans).apply { gravity = Gravity.CENTER }
        fun arrow(path: String, delta: Int) = FrameLayout(this).apply {
            isClickable = true
            setOnClickListener { val nv = (v + delta).coerceIn(lo, hi); if (nv != v) { v = nv; valueTv.text = "$v"; onChange(v) } }
            addView(icon(path, 16f, Theme.TEXT_DARK), FrameLayout.LayoutParams(u(16f), u(16f), Gravity.CENTER))
        }
        val chip = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; background = round(Theme.SURFACE_WHITE, 100f); setPadding(u(4f), u(2f), u(4f), u(2f)) }
        chip.addView(arrow(Icons.CHEVRON_LEFT, -1), lp(u(28f), u(28f))); chip.addView(valueTv, lp(u(44f), -2)); chip.addView(arrow(Icons.CHEVRON_RIGHT, 1), lp(u(28f), u(28f)))
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; background = round(0, 100f, Theme.TEXT_DARK); setPadding(u(14f), u(7f), u(8f), u(7f))
            addView(icon(ico, 22f, Theme.TEXT_DARK), lp(u(22f), u(22f)).apply { rightMargin = u(8f) })
            addView(tv(label, 22f, Theme.TEXT_DARK), lp(0, -2, 1f)); addView(chip, lp(-2, -2))
        }
    }
    private fun grid(items: List<View>, cols: Int): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        val animate = cur != lastShown
        items.chunked(cols).forEach { chunk ->
            val row = LinearLayout(this).apply { clipChildren = false }
            for (i in 0 until cols) row.addView(if (i < chunk.size) chunk[i] else View(this), lp(0, -1, 1f).apply { setMargins(u(7f), u(7f), u(7f), u(7f)) })
            box.addView(row, lp(-1, -2))
        }
        if (animate) items.forEachIndexed { i, v -> v.alpha = 0f; v.translationY = u(16f).toFloat(); v.animate().alpha(1f).translationY(0f).setStartDelay(minOf(i, 10) * 45L).setDuration(280).setInterpolator(DecelerateInterpolator()).start() }
        return box
    }
    private fun cols() = if (resources.displayMetrics.widthPixels / k > 1500f) 4 else 3
    private fun header(title: String, back: (() -> Unit)?, right: View? = null): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        val left = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL; if (back != null) { isClickable = true; setOnClickListener { back() } } }
        if (back != null) left.addView(icon(Icons.CHEVRON_LEFT, 28f, Theme.TEXT_DARK), lp(u(28f), u(28f)))
        left.addView(tv(title, 26f, Theme.TEXT_DARK, Theme.sans))
        addView(left, lp(0, -2, 1f))
        if (right != null) addView(right, lp(-2, -2))
    }
    private fun bodyPage(head: View, content: View, scrolling: Boolean = true): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(u(24f), u(22f), u(24f), u(12f))
        addView(head, lp(-1, -2).apply { bottomMargin = u(14f); leftMargin = u(10f); rightMargin = u(8f) })
        if (scrolling) addView(ScrollView(this@MainActivity).apply { isVerticalScrollBarEnabled = false; addView(content) }, lp(-1, 0, 1f)) else addView(content, lp(-1, 0, 1f))
    }
    private fun vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
    private fun addTo(box: LinearLayout, v: View) = box.addView(v, lp(-1, -2).apply { setMargins(u(7f), 0, u(7f), u(12f)) })

    // ---- shell: maroon sidebar + cream content panel
    private fun shortStatus() = "Local Connection :  ${if (Net.lan != "") "Connected" else "Not connected"}\nHost Connection :  ${if (Net.via == "local" || Net.via == "internet") "Connected" else "Not connected"}"
    private fun sidebar(section: String): View {
        val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(u(32f), u(32f), u(32f), u(24f)) }
        val lg = Theme.logo
        if (lg != null) side.addView(ImageView(this).apply { setImageBitmap(lg); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER }, lp(-1, -2).apply { bottomMargin = u(40f) })
        fun nav(label: String, ico: String, key: String, go: () -> Unit) {
            val on = key == section; val fg = Theme.TEXT_DARK
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(u(24f), u(8f), u(24f), u(8f)); if (on) background = round(Theme.SIDEBAR_BTN, 100f); isClickable = true; setOnClickListener { guard(go) } }
            row.addView(icon(ico, 28f, fg), lp(u(28f), u(28f)).apply { rightMargin = u(10f) })
            row.addView(tv(label, 24f, fg))
            side.addView(row, lp(-1, -2).apply { topMargin = u(11f) })
        }
        nav("Events", Icons.CALENDAR, "events") { eventsScreen() }
        nav("Gallery", Icons.IMAGE, "gallery") { galleryScreen() }
        nav("Settings", Icons.CAMERA, "settings") { outputSettingsScreen() }
        side.addView(View(this), lp(-1, 0, 1f))
        nav("Connection", Icons.LINK, "connection") { connectionScreen() }
        val dirty = Net.dirtyCount()
        side.addView(pill(if (dirty > 0) "Sync With host ($dirty)" else "Sync With host", 22f, Icons.SYNC) { syncNow() }, lp(-1, u(41f)).apply { topMargin = u(11f) })
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = round(0, 18f, Theme.TEXT_DARK); setPadding(u(12f), u(12f), u(12f), u(12f)) }
        val st = tv(shortStatus(), 14f, Theme.TEXT_DARK, Theme.sans).apply { setLineSpacing(u(10f).toFloat(), 1f) }
        box.addView(st); sideStatus = st
        side.addView(box, lp(-1, -2).apply { topMargin = u(16f) })
        return side
    }
    private fun showShell(section: String, body: View) {
        if (cur != "camera") { cameraTuning.onControlFailure = null; closeCam() }
        val row = LinearLayout(this).apply { setBackgroundColor(Theme.BRAND_BG) }
        row.addView(sidebar(section), lp(u(307f), -1))
        val panel = FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(Theme.SURFACE_WHITE) }; clipToOutline = true
        }
        panel.addView(body, FrameLayout.LayoutParams(-1, -1))
        row.addView(panel, lp(0, -1, 1f))

        if (cur != lastShown) {
            panel.alpha = 0f
            panel.translationY = u(20f).toFloat()
            panel.animate().alpha(1f).translationY(0f).setDuration(280).setInterpolator(DecelerateInterpolator()).start()

            lastShown = cur 
        }

        show(row); statusTv = sideStatus
    }
    private fun syncNow() {
        busy("Syncing with the host...") {
            Net.probeLan(); Net.ping(); Net.sync(); toast("Synced")
            runOnUiThread { when (cur) { "events" -> eventsScreen(); "event" -> eventScreen(curEid); "gallery" -> galleryScreen(); "egallery" -> eventGalleryScreen(curEid); else -> refreshStatus() } }
        }
    }
    private fun goBack() {
        when (cur) {
            "frames", "result" -> startScreen()
            "snap", "review" -> { job?.cancel(); pvJob?.cancel(); closeCam(); frameScreen() }
            "filter" -> review()
            "optional" -> filterScreen()
            "event", "gallery", "camera", "settings", "conn" -> eventsScreen()
            "egallery" -> galleryScreen()
            "gsession" -> sessBack()
            "edit" -> leaveEditor { if (draftNew) eventsScreen() else eventScreen(curEid) }
            "slots" -> editScreen(curEid)
            "print" -> resPhoto?.let { result(resId, it) } ?: startScreen()
            "start" -> exitTap("Press back again to leave this event") { eventScreen(curEid) }
            "scan" -> { closeCam(); pairScreen() }
            "events", "pair", "crash" -> exitTap("Press back again to exit the app") { finish() }
        }
    }

    // ---- data helpers
    private val filterNames = listOf("ALL Events", "Today", "This week", "This month")
    private fun evDate(e: JSONObject): Long { val c = e.optLong("created"); return if (c > 0) c else e.optLong("updated") }
    private fun passes(e: JSONObject): Boolean { val age = Net.now() - evDate(e); return when (evFilter) { 1 -> age < 86400000L; 2 -> age < 7 * 86400000L; 3 -> age < 30 * 86400000L; else -> true } }
    private fun fmtDate(ms: Long) = java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.ENGLISH).format(java.util.Date(ms))
    private fun fmtTime(ms: Long) = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.ENGLISH).format(java.util.Date(ms))
    private fun themeBitmap(e: JSONObject, max: Int): Bitmap? {
        val t = e.optJSONObject("theme"); val id = t?.optString("image") ?: ""; if (id == "") return null
        val f = Net.imgFile("t_$id")
        return if (t?.optString("kind") == "video") videoPoster(f) else thumb(f, max)
    }
    private fun videoPoster(f: File): Bitmap? {
        if (!f.exists()) return null
        synchronized(thumbCache) { thumbCache[f.path] }?.let { return it }
        return try {
            val r = android.media.MediaMetadataRetriever(); r.setDataSource(f.path); val b = r.getFrameAtTime(0L); r.release()
            if (b != null) synchronized(thumbCache) { thumbCache[f.path] = b }; b
        } catch (_: Exception) { null }
    }
    private fun filterView(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; isClickable = true
        addView(icon(Icons.FILTER, 22f, Theme.TEXT_DARK), lp(u(22f), u(22f)).apply { rightMargin = u(10f) })
        addView(tv(filterNames[evFilter], 20f, Theme.TEXT_DARK, Theme.sans))
        setOnClickListener { v -> PopupMenu(this@MainActivity, v).apply { filterNames.forEachIndexed { i, n -> menu.add(0, i, i, n) }; setOnMenuItemClickListener { evFilter = it.itemId; eventsScreen(); true } }.show() }
    }

    // ---- Events (list)
    private fun eventCard(e: JSONObject, editable: Boolean): View {
        val id = e.getString("id"); val c = card()
        c.addView(ratioImg(9f / 16f).apply { themeBitmap(e, 700)?.let { setImageBitmap(it) } }, lp(-1, -2))
        val row = LinearLayout(this).apply { gravity = Gravity.BOTTOM }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(tv(if (editable) e.optString("name") else "${e.optString("name")} Galleries", 24f, Theme.TEXT_DARK, Theme.serifBold))
        texts.addView(tv(fmtDate(evDate(e)), 20f, Theme.TEXT_DARK), lp(-2, -2).apply { topMargin = u(4f) })
        row.addView(texts, lp(0, -2, 1f))
        if (editable) {
            row.addView(circleBtn(Icons.EDIT) { openEditor(id) }, lp(u(34f), u(34f)).apply { rightMargin = u(6f) })
            row.addView(circleBtn(Icons.DELETE) { confirm("Delete \"${e.optString("name")}\" and its frames?") { Net.deleteEvent(id); quiet { Net.sync() }; eventsScreen() } }, lp(u(34f), u(34f)))
        }
        c.addView(row, lp(-1, -2).apply { topMargin = u(8f); leftMargin = u(6f); rightMargin = u(4f); bottomMargin = u(2f) })
        c.isClickable = true; c.setOnClickListener { if (editable) eventScreen(id) else eventGalleryScreen(id) }
        return c
    }
    private fun newEventCard(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = round(Theme.BRAND_BG, 16f); elevation = 5f * k; minimumHeight = u(240f); isClickable = true
        addView(icon(Icons.ADD, 90f, Theme.TEXT_DARK), lp(u(90f), u(90f)))
        addView(tv("New Event", 20f, Theme.TEXT_DARK, Theme.sans), lp(-2, -2).apply { topMargin = u(6f) })
        setOnClickListener { openEditor(null) }
    }
    private fun eventsScreen() {
        cur = "events"; guest = false; editSave = null; slotDone = null
        val items = Net.events().filter { passes(it) }.map { eventCard(it, true) } + newEventCard()
        showShell("events", bodyPage(header("Event List", null, filterView()), grid(items, cols())))
    }

    // ---- Event information page
    private fun eventScreen(id: String) {
        cur = "event"; curEid = id; editSave = null; slotDone = null; guest = false
        val e = Net.event(id)
        if (e == null || e.optBoolean("deleted")) { eventsScreen(); return }
        val frames = e.getJSONArray("frames").length()
        val hc = card(16f, 12f).apply { orientation = LinearLayout.HORIZONTAL }
        hc.addView(ratioImg(9f / 16f).apply { themeBitmap(e, 500)?.let { setImageBitmap(it) } }, lp(u(222f), -2))
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(u(18f), u(2f), 0, 0) }
        mid.addView(tv(e.optString("name"), 24f, Theme.TEXT_DARK, Theme.serifBold))
        mid.addView(tv(fmtDate(evDate(e)), 20f, Theme.TEXT_DARK), lp(-2, -2).apply { topMargin = u(4f) })
        val btns = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        btns.addView(pill("Start Event Session", 14f, Icons.PLAY, true, iconRight = true) { if (frames == 0) toast("Add a frame first: Edit > Add Frame") else { ev = e; startScreen() } }.apply { alpha = if (frames == 0) 0.55f else 1f }, lp(-2, u(34f)).apply { rightMargin = u(8f) })
        btns.addView(circleBtn(Icons.EDIT) { openEditor(id) }, lp(u(34f), u(34f)).apply { rightMargin = u(8f) })
        btns.addView(circleBtn(Icons.DELETE) { confirm("Delete \"${e.optString("name")}\" and its frames?") { Net.deleteEvent(id); quiet { Net.sync() }; eventsScreen() } }, lp(u(34f), u(34f)))
        mid.addView(btns, lp(-2, -2).apply { topMargin = u(18f) })
        hc.addView(mid, lp(0, -2, 1f))
        fun stat(ico: String, text: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(icon(ico, 24f, Theme.TEXT_DARK), lp(u(24f), u(24f))); addView(tv(text, 18f, Theme.TEXT_DARK), lp(-2, -2).apply { topMargin = u(2f) })
        }
        val stats = LinearLayout(this)
        stats.addView(stat(Icons.CLOCK, "${e.optInt("countdown", 3)}s"), lp(u(60f), -2)); stats.addView(stat(Icons.CAMERA, "${e.optInt("shots", 4)}x"), lp(u(60f), -2)); stats.addView(stat(Icons.CLOUD, "${e.optInt("retention", 10)}d"), lp(u(60f), -2))
        hc.addView(stats, lp(-2, -2))
        val content = vbox()
        addTo(content, hc)
        val bar = card(14f, 12f); bar.addView(tv("Event Gallery", 26f, Theme.TEXT_DARK, Theme.serifBold), lp(-2, -2).apply { setMargins(u(12f), u(4f), 0, u(4f)) })
        addTo(content, bar)
        content.addView(sessionsGrid(id, "events") { eventScreen(id) })
        showShell("events", bodyPage(header("Event List", { eventsScreen() }), content))
    }

    // ---- Sessions of an event (used by the event page and by the gallery)
    // Local archive is shown immediately; host-only sessions are merged when reachable.
    private fun combineSessions(eventId: String, host: JSONArray?): List<JSONObject> {
        val rows = LinkedHashMap<String, JSONObject>()
        for (local in Net.localSessions(eventId)) rows[local.getString("id")] = local
        if (host != null) for (i in 0 until host.length()) {
            val remote = host.optJSONObject(i) ?: continue
            val sid = remote.optString("id")
            if (sid.isEmpty()) continue
            val local = rows[sid]
            if (local == null) rows[sid] = JSONObject(remote.toString())
            else {
                // A partially uploaded session may have more files locally or on the host.
                val union = LinkedHashSet<String>()
                for (item in listOf(local.optJSONArray("files"), remote.optJSONArray("files")))
                    if (item != null) for (j in 0 until item.length()) union.add(item.getString(j))
                local.put("files", JSONArray(union.toList()))
                local.put("remote", true)
            }
        }
        return rows.values.sortedByDescending { it.optLong("created", 0L) }
    }

    private fun sessionsGrid(eid: String, nav: String, back: () -> Unit): View {
        val box = FrameLayout(this)
        fun render(host: JSONArray? = null, unavailable: Boolean = false) {
            val rows = combineSessions(eid, host)
            box.removeAllViews()
            galleryStatusViews.clear()
            if (rows.isNotEmpty()) box.addView(grid(sessionCards(rows, nav, back), cols()))
            else box.addView(tv(if (unavailable) "No sessions on this tablet yet. Connect to the host for older sessions."
                else "No photo sessions yet.", 18f, Theme.TEXT_DARK).apply { setPadding(u(14f), u(8f), 0, 0) })
        }
        render()  // Never wait on the network before showing saved results.
        bg {
            try {
                val (c, bytes) = Net.req("GET", "/api/v1/events/$eid/sessions")
                if (c != 200) throw IOException("Host returned $c")
                val remote = JSONArray(String(bytes))
                runOnUiThread {
                    if ((cur == "event" || cur == "egallery") && curEid == eid) render(remote)
                }
                // Cache all available host photos for future offline gallery access,
                // within tablet free-space and local-retention limits.
                Net.cacheHostSessions(eid, remote)
                runOnUiThread {
                    if ((cur == "event" || cur == "egallery") && curEid == eid) render(remote)
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if ((cur == "event" || cur == "egallery") && curEid == eid) render(unavailable = true)
                }
            }
        }
        return box
    }
    private fun sessionCards(rows: List<JSONObject>, nav: String, back: () -> Unit): List<View> = rows.mapIndexed { i, s ->
        val sid = s.getString("id")
        val n = rows.size - i
        val c = card()
        val cover = ratioImg(9f / 16f)
        val local = Net.localSessionFile(sid, "thumb.jpg") ?: Net.localSessionFile(sid, "photo.jpg")
        if (local != null) thumb(local, 500)?.let { cover.setImageBitmap(it) }
        else loadInto(cover, "/f/$sid/thumb.jpg", 500, true)
        c.addView(cover, lp(-1, -2))
        c.addView(tv("Session $n", 24f, Theme.TEXT_DARK, Theme.serifBold), lp(-1, -2).apply { setMargins(u(6f), u(8f), 0, 0) })
        c.addView(tv(fmtTime(s.optLong("created", 0L)), 17f, Theme.TEXT_DARK), lp(-1, -2).apply { setMargins(u(6f), u(2f), 0, u(4f)) })
        val status = tv(Net.localSessionStatus(sid), 16f, Theme.TEXT_DARK, Theme.sans)
        galleryStatusViews[sid] = status
        c.addView(status, lp(-1, -2).apply { setMargins(u(6f), u(2f), 0, u(4f)) })
        c.isClickable = true; c.setOnClickListener { sessionScreen(s, "Session $n", nav, back) }
        c as View
    }
    private fun loadSessionImage(iv: ImageView, sid: String, name: String, max: Int) {
        val local = Net.localSessionFile(sid, name)
        if (local != null) thumb(local, max)?.let { iv.setImageBitmap(it) }
        else loadInto(iv, "/api/v1/sessions/$sid/files/$name", max)
    }
    private fun viewSessionMedia(sid: String, name: String) {
        fun openLocal(file: File) {
            val d = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            if (name.endsWith(".mp4")) {
                val video = VideoView(this)
                val container = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
                container.addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
                val close = btn("CLOSE") { d.dismiss() }
                container.addView(close, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
                d.setContentView(container)
                d.setOnDismissListener { video.stopPlayback() }
                d.show()
                video.setVideoPath(file.path)
                video.setOnPreparedListener { mp ->
                    centerVideoInPreview(video, container, mp.videoWidth, mp.videoHeight)
                    mp.isLooping = true
                    video.start()
                }
                video.setOnErrorListener { _, _, _ -> toast("Unable to play this video"); true }
            } else {
                val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.BLACK); setOnClickListener { d.dismiss() } }
                if (name.endsWith(".gif") && Build.VERSION.SDK_INT >= 28) {
                    try {
                        val source = android.graphics.ImageDecoder.createSource(file)
                        val drawable = android.graphics.ImageDecoder.decodeDrawable(source)
                        iv.setImageDrawable(drawable)
                        (drawable as? android.graphics.drawable.Animatable)?.start()
                    } catch (_: Exception) { thumb(file, 1600)?.let { iv.setImageBitmap(it) } }
                } else thumb(file, 2000)?.let { iv.setImageBitmap(it) }
                d.setContentView(iv); d.show()
            }
        }
        val local = Net.localSessionFile(sid, name)
        if (local != null) { openLocal(local); return }
        showBusy("Downloading session file...")
        bg {
            val downloaded = Net.downloadSessionFile(sid, name)
            runOnUiThread {
                hideBusy()
                if (downloaded != null) openLocal(downloaded)
                else toast("This file is not stored locally and the host could not be reached")
            }
        }
    }
    private fun showQr(sid: String) {
        if (Net.pub() == "") toast("The public address is not set")
        else android.app.AlertDialog.Builder(this).setView(ImageView(this).apply { setImageBitmap(qr("${Net.pub()}/d/$sid")) }).setPositiveButton("Close", null).show()
    }
    private fun sessionScreen(s: JSONObject, title: String, nav: String, back: () -> Unit) {
        cur = "gsession"; sessBack = back
        val sid = s.getString("id")
        val names = LinkedHashSet<String>()
        s.optJSONArray("files")?.let { arr -> for (i in 0 until arr.length()) names.add(arr.getString(i)) }
        for (name in Net.localSessionFiles(sid)) names.add(name)
        val framed = names.filter { Regex("photo(?:[2-5])?\\.jpg").matches(it) }.sorted()
        val raws = names.filter { Regex("raw[1-9]\\.jpg").matches(it) }.sorted()
        val extras = names.filter { it == "gif.gif" || it == "live.mp4" }.sorted()
        val hc = card(16f, 14f).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(tv(title, 26f, Theme.TEXT_DARK, Theme.serifBold))
        t.addView(tv(fmtTime(s.optLong("created", 0L)), 20f, Theme.TEXT_DARK), lp(-2, -2).apply { topMargin = u(4f) })
        t.addView(tv(Net.localSessionStatus(sid), 17f, Theme.TEXT_DARK, Theme.sans), lp(-2, -2))
        hc.addView(t, lp(0, -2, 1f).apply { leftMargin = u(6f) })
        hc.addView(pill("QR code", 18f, Icons.LINK) { showQr(sid) }, lp(-2, -2).apply { rightMargin = u(8f) })
        if (Net.hasPendingUpload(sid)) hc.addView(pill("Cancel upload", 16f, Icons.DELETE) {
            confirm("Cancel this upload and delete its local photos? Any files already sent may remain on the host.") {
                bg { Net.removeLocalSession(sid); runOnUiThread { back() } }
            }
        }, lp(-2, -2).apply { rightMargin = u(8f) })
        hc.addView(pill("Delete from host", 16f, Icons.DELETE) {
            confirm("Delete this session from the host? This cannot be undone.") {
                bg {
                    try {
                        val (code, _) = Net.req("DELETE", "/api/v1/sessions/$sid")
                        if (code == 200) { Net.removeLocalSession(sid); runOnUiThread { back() } }
                        else toast("Host refused deletion ($code)")
                    } catch (_: Exception) { toast("Cannot delete from host while offline") }
                }
            }
        }, lp(-2, -2))
        val cards = (framed + raws + extras).map { name ->
            val item = card()
            if (name.endsWith(".jpg")) {
                val iv = ratioImg(0.62f)
                loadSessionImage(iv, sid, name, 900)
                item.addView(iv, lp(-1, -2))
            } else item.addView(tv(if (name == "live.mp4") "LIVE PHOTO" else "SLIDESHOW GIF", 22f, Theme.TEXT_DARK, Theme.serifBold), lp(-1, u(100f)))
            item.addView(tv(name, 16f, Theme.TEXT_DARK), lp(-1, -2))
            item.isClickable = true
            item.setOnClickListener { viewSessionMedia(sid, name) }
            item as View
        }
        val content = vbox(); addTo(content, hc)
        if (cards.isEmpty()) addTo(content, tv("No local files available. Connect to host to access older photos.", 18f, Theme.TEXT_DARK))
        else content.addView(grid(cards, cols()))
        showShell(nav, bodyPage(header("Gallery", back), content))
    }

    // ---- Gallery
    private fun galleryScreen() {
        cur = "gallery"
        val cards = Net.events().map { eventCard(it, false) }
        showShell("gallery", bodyPage(header("Gallery", null), grid(if (cards.isEmpty()) listOf(tv("No events yet.", 18f, Theme.TEXT_DARK)) else cards, cols())))
    }
    private fun eventGalleryScreen(id: String) {
        cur = "egallery"; curEid = id
        val e = Net.event(id) ?: return galleryScreen()
        val hc = card(16f, 12f).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        hc.addView(ratioImg(9f / 16f).apply { themeBitmap(e, 400)?.let { setImageBitmap(it) } }, lp(u(166f), -2))
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(tv("${e.optString("name")} Galleries", 24f, Theme.TEXT_DARK, Theme.serifBold)); t.addView(tv(fmtDate(evDate(e)), 20f, Theme.TEXT_DARK), lp(-2, -2).apply { topMargin = u(4f) })
        hc.addView(t, lp(0, -2, 1f).apply { leftMargin = u(18f) })
        val content = vbox(); addTo(content, hc); content.addView(sessionsGrid(id, "gallery") { eventGalleryScreen(id) })
        showShell("gallery", bodyPage(header("Session", { galleryScreen() }), content))
    }

    // ---- Event edit
    // ---- event editor: works on a COPY of the event. Nothing is stored until Save is pressed; leaving asks before discarding.
    private fun isDirty() = draft != null && draft.toString() != draftBase
    private fun openEditor(id: String?) {
        staged.clear()
        if (id == null) { draft = Net.newEventDraft(); draftNew = true }
        else { val e = Net.event(id) ?: return eventsScreen(); draft = JSONObject(e.toString()); draftNew = false }
        draftEid = draft!!.getString("id"); draftBase = draft.toString(); editScreen(draftEid)
    }
    private fun discardDraft() { for ((t, id, _) in staged) Net.imgFile((if (t == "f") "f_" else "t_") + id).delete(); staged.clear(); draft = null; draftEid = ""; draftNew = false }
    private fun leaveEditor(then: () -> Unit) { if (!isDirty()) { discardDraft(); then() } else confirm("Leave without saving? Your changes will be lost.") { discardDraft(); then() } }
    private fun guard(go: () -> Unit) { if (cur == "edit" || cur == "slots") leaveEditor(go) else go() }
    private fun saveDraft() {
        val e = draft ?: return
        e.put("name", e.optString("name").trim().ifEmpty { "Event" }); val eid = e.getString("id")
        val used = HashSet<String>(); val ti = e.optJSONObject("theme")?.optString("image") ?: ""; if (ti != "") used.add(ti)
        val fr = e.getJSONArray("frames"); for (i in 0 until fr.length()) used.add(fr.getJSONObject(i).getString("id"))
        for ((t, id, name) in staged) { if (id in used) Net.queueImage(t, eid, id, name) else Net.imgFile((if (t == "f") "f_" else "t_") + id).delete() }
        staged.clear(); Net.saveEvent(e); draftBase = e.toString(); draftNew = false
        toast("Saved"); quiet { Net.sync() }
    }
    private fun editScreen(id: String) {
        cur = "edit"; curEid = id; slotDone = null
        val e = draft ?: return eventsScreen()
        val nameEt = field("Event name").apply { setText(e.optString("name")); doAfterTextChanged { t -> e.put("name", t.toString()) } }
        val leave = { leaveEditor { if (draftNew) eventsScreen() else eventScreen(id) } }
        val isVideo = e.optJSONObject("theme")?.optString("kind") == "video"
        val ss = card(16f, 12f)
        ss.addView(tv("Start Screen :", 24f, Theme.TEXT_DARK, Theme.serifBold), lp(-2, -2).apply { setMargins(u(6f), u(2f), 0, u(10f)) })
        ss.addView(ratioImg(9f / 16f).apply { themeBitmap(e, 900)?.let { setImageBitmap(it) } }, lp(-1, -2))
        if (isVideo) ss.addView(tv("Video - plays in a loop", 16f, Theme.TEXT_DARK, Theme.sans), lp(-2, -2).apply { topMargin = u(8f); leftMargin = u(6f) })
        val br = LinearLayout(this)
        br.addView(pill("Choose file", 16f, Icons.ATTACH) { pick("theme", id) }, lp(0, -2, 1f).apply { rightMargin = u(5f) })
        br.addView(pill("Delete", 16f, Icons.DELETE) { e.getJSONObject("theme").remove("image"); e.getJSONObject("theme").remove("kind"); editScreen(id) }, lp(0, -2, 1f))
        ss.addView(br, lp(-1, -2).apply { topMargin = u(10f) })
        val st = card(16f, 12f)
        st.addView(tv("Event name :", 24f, Theme.TEXT_DARK, Theme.serifBold), lp(-2, -2).apply { setMargins(u(6f), u(2f), 0, u(8f)) })
        st.addView(nameEt, lp(-1, -2).apply { setMargins(u(4f), 0, u(4f), u(14f)) })
        listOf(
            stepper("Countdown (s) :", Icons.CLOCK, e.optInt("countdown", 3), 0, 30) { e.put("countdown", it) },
            // Guest capture count now derives from frame slot photo sources, not event.shots.
            stepper("Keep files for (days) :", Icons.CLOUD, e.optInt("retention", 10), 1, 365) { e.put("retention", it) },
            stepper("Max print copies per session :", Icons.PRINT, e.optInt("copies", 1), 1, 20) { e.put("copies", it) },
            // Existing maxFrames is retained in stored events for compatibility. Guest mode selects one main frame.
        ).forEach { st.addView(it, lp(-1, -2).apply { setMargins(u(4f), u(4f), u(4f), u(6f)) }) }
        val two = LinearLayout(this).apply { clipChildren = false }
        two.addView(ss, lp(0, -1, 1f).apply { rightMargin = u(7f) }); two.addView(st, lp(0, -1, 1.1f))
        val af = card(14f, 10f).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        af.addView(tv("Frames", 26f, Theme.TEXT_DARK, Theme.serifBold), lp(0, -2, 1f).apply { leftMargin = u(10f) })
        af.addView(pill("Choose file", 16f, Icons.ATTACH) { pick("frame", id) }, lp(-2, -2))
        val fr = e.getJSONArray("frames")
        val frameCards = (0 until fr.length()).map { i ->
            val f = fr.getJSONObject(i); val c = card(14f, 8f)
            c.addView(ratioImg(.62f, 8f).apply {
                setBackgroundColor(Theme.SURFACE_WHITE)
                scaleType = ImageView.ScaleType.FIT_CENTER
                thumb(Net.imgFile("f_" + f.getString("id")), 600)?.let { setImageBitmap(it) }
            }, lp(-1, -2))
            c.addView(tv("${f.optString("name", "Frame")} · ${uniquePhotoCount(f)} unique photos", 14f,
                Theme.TEXT_DARK, Theme.sans), lp(-1, -2))
            c.isClickable = true
            c.setOnClickListener { frameEditorDialog(id, i) }
            c as View
        }
        val content = vbox()
        addTo(content, two); addTo(content, af); content.addView(grid(frameCards, 3))
        showShell("events", bodyPage(header("Event", leave, pill("Save", 20f, Icons.CHECK, true, true) { saveDraft() }), content))
    }
    // Frame editor in an AlertDialog. The copy is committed only on Save.
    private fun frameEditorDialog(id: String, i: Int) {
        val e = draft ?: return
        val frames = e.getJSONArray("frames")
        if (i !in 0 until frames.length()) return
        val f = frames.getJSONObject(i)
        val bmp = thumb(Net.imgFile("f_" + f.optString("id")), 1600)
            ?: run { toast("Frame image missing: sync first"); return }
        val edited = JSONArray((f.optJSONArray("slots") ?: JSONArray()).toString())
        val editor = SlotEditor(this, bmp, edited)
        val dialogBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(8))
        }
        val stats = txt("", 16f)
        val help = txt("Tap a slot to select. Drag to move; drag the yellow handle to resize.", 15f)
        fun refresh() {
            val selected = editor.selectedIndex
            stats.text = "Slots: ${edited.length()} · Unique photos: ${slotsUniqueCount(edited)}" +
                if (selected >= 0) " · Selected slot ${selected + 1} uses photo ${editor.selectedSource() + 1}" else ""
        }
        editor.onChanged = { refresh() }
        dialogBody.addView(help, lp(-1, -2))
        dialogBody.addView(editor, lp(-1, dp(360)))
        dialogBody.addView(stats, lp(-1, -2))
        fun actions(vararg pair: Pair<String, () -> Unit>) {
            val bar = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
            pair.forEach { (label, action) ->
                bar.addView(btn(label) { action(); refresh() }.apply { textSize = 12f },
                    lp(0, -2, 1f).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) })
            }
            dialogBody.addView(bar, lp(-1, -2))
        }
        actions("Add slot" to {
            editor.addMode = true
            help.text = "Drag across an empty area to draw a new slot"
        }, "Delete slot" to { editor.removeSelected() },
            "Rotate -5°" to { editor.rotateSelected(-5f) },
            "Rotate +5°" to { editor.rotateSelected(5f) })
        actions("Order ↑" to { editor.swapOrder(-1) }, "Order ↓" to { editor.swapOrder(1) },
            "Photo -" to { editor.setSource(-1) }, "Photo +" to { editor.setSource(1) })
        actions("Repeat two columns" to { editor.applyTwoColumnRepeat() },
            "All unique" to { editor.setUniqueMapping() })
        refresh()
        val scroller = ScrollView(this).apply { addView(dialogBody) }
        android.app.AlertDialog.Builder(this)
            .setTitle("Edit frame: ${f.optString("name", "Frame")}")
            .setView(scroller)
            .setPositiveButton("Save slots") { _, _ ->
                f.put("slots", edited)
                editScreen(id)
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Delete frame") { _, _ ->
                confirm("Delete this frame?") { frames.remove(i); editScreen(id) }
            }
            .show()
    }

    private fun slotsUniqueCount(slots: JSONArray): Int {
        if (slots.length() == 0) return 1
        return (0 until slots.length()).maxOf { i ->
            slots.getJSONObject(i).optInt("source", -1).let { if (it >= 0) it else i }
        } + 1
    }

    private fun slotScreen(id: String, i: Int) = frameEditorDialog(id, i)

    // ---- Administrator Settings / Output Resolution
    private fun settingsTabs(cameraPage: Boolean): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(btn("OUTPUT RESOLUTION") { outputSettingsScreen() }.apply {
            typeface = Theme.serifBold
            alpha = if (cameraPage) 0.7f else 1f
        }, lp(-2, -2).apply { rightMargin = u(12f) })
        addView(btn("CAMERA") { cameraSettings() }.apply {
            typeface = Theme.serifBold
            alpha = if (cameraPage) 1f else 0.7f
        }, lp(-2, -2))
    }

    private fun outputSettingsScreen() {
        cur = "settings"
        val cap = currentCameraMaximum()
        val tiers = outputResolution.tiersSupportedByCamera(cap)
        val card = card(16f, 16f)
        addTo(card, settingsTabs(false))
        addTo(card, tv("Output resolution", 28f, Theme.TEXT_DARK, Theme.serifBold))
        addTo(card, tv(
            if (cap == null) "Camera capability unavailable. Open the Camera tab with a connected camera first. " +
                "Resolution choices are disabled until the camera reports its supported sizes."
            else "Camera advertised maximum: ${cap.width} x ${cap.height}. " +
                "Resolution choices depend on camera capture capabilities, not H.264 encoder capacity.",
            16f, Theme.TEXT_DARK, Theme.sans))

        fun selector(label: String, selected: Int, update: (Int) -> Unit) {
            val choices = tiers.map { tier ->
                val spec = outputResolution.envelope(tier)
                tier.toString() to (when (tier) { 720 -> "HD"; 1080 -> "Full HD"; else -> "4K UHD" } +
                    " (${spec.width} x ${spec.height} for 16:9)")
            }
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            row.addView(tv(label, 22f, Theme.TEXT_DARK, Theme.serifBold), lp(-1, -2))
            if (choices.isNotEmpty()) {
                val effective = outputResolution.effectiveTier(selected, cap)
                row.addView(dropdown(choices.first { it.first == effective.toString() }.second, choices) { value ->
                    update(value.toInt())
                    outputSettingsScreen()
                }, lp(-1, -2))
                if (effective != selected) row.addView(tv(
                    "Saved preference ${selected}p exceeds this camera's reported maximum; effective output is ${effective}p.",
                    15f, Theme.TEXT_DARK, Theme.sans))
            } else row.addView(tv(
                if (cap == null) "Connect/open the camera to enable this setting"
                else "This camera reports less than HD source resolution; output will be capped to the camera's native maximum.",
                16f, Theme.TEXT_DARK, Theme.sans))
            addTo(card, row)
        }

        selector("Framed still JPEG", outputResolution.jpegHeight) { outputResolution.jpegHeight = it }
        selector("Framed Live Photo MP4", outputResolution.liveHeight) { outputResolution.liveHeight = it }
        addTo(card, tv("The chosen tier is a maximum output envelope, not a forced aspect ratio. " +
            "For example, Full HD is 1920 x 1080 for landscape 16:9, 1080 x 1920 for portrait 9:16, " +
            "and 1080 x 1080 for square frames. Live Photo can be set as high as 4K when " +
            "the camera advertises enough resolution. The device's video encoder can still reject 4K; " +
            "such failures are reported explicitly during export.", 16f, Theme.TEXT_DARK, Theme.sans))
        addTo(card, tv("Note: a lower active USB stream resolution cannot gain genuine detail " +
            "simply by selecting a larger output file. Full-resolution capture must also be configured in Camera.",
            15f, Theme.TEXT_DARK, Theme.sans))
        showShell("settings", bodyPage(header("Settings", null), card, true))
    }

    // ---- Camera settings: live preview and capability-checked, persisted camera controls.
    // Profiles are stored per physical Camera2 ID and re-applied when the guest camera binds.
    private fun cameraSettings() {
        cur = "camera"
        val modes = listOf("auto" to "Automatic", "external" to "External Camera", "front" to "Front Camera", "back" to "Rear Camera")
        val box = FrameLayout(this).apply {
            background = round(Theme.PREVIEW, 14f)
            clipToOutline = true
        }
        val hint = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(icon(Icons.CAMERA, 34f, Theme.TEXT_DARK), lp(u(34f), u(34f)))
            addView(tv("Camera Preview", 24f, Theme.TEXT_DARK, Theme.sans), lp(-2, -2))
        }
        box.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        val which = tv("", 16f, Theme.TEXT_DARK, Theme.sans)
        cameraTuning.onControlFailure = { failure ->
            if (cur == "camera") which.text = failure
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(u(8f), u(4f), u(14f), u(16f))
        }

        // Each slider has an actual camera-control callback; no image brightness filters.
        fun sliderRow(
            title: String, min: Int, max: Int, current: Int,
            describe: (Int) -> String, changed: (Int) -> Unit
        ): View {
            val group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val label = tv("$title: ${describe(current)}", 18f, Theme.TEXT_DARK, Theme.sans)
            group.addView(label, lp(-1, -2))
            val seek = SeekBar(this).apply {
                this.max = (max - min).coerceAtLeast(0)
                progress = (current - min).coerceIn(0, this.max)
                isEnabled = max > min
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        val value = min + progress
                        label.text = "$title: ${describe(value)}"
                        changed(value)
                    }
                    override fun onStartTrackingTouch(bar: SeekBar?) { }
                    override fun onStopTrackingTouch(bar: SeekBar?) { }
                })
            }
            group.addView(seek, lp(-1, -2))
            return group
        }

        fun addControl(view: View) {
            controls.addView(view, lp(-1, -2).apply { bottomMargin = u(10f) })
        }
        fun addNote(message: String) = addControl(tv(message, 15f, Theme.TEXT_DARK, Theme.sans))
        fun settingRow(name: String, choices: List<Pair<String, String>>, selected: String, change: (String) -> Unit): View {
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.VERTICAL
            }
            row.addView(tv(name, 18f, Theme.TEXT_DARK, Theme.sans), lp(-1, -2))
            row.addView(dropdown(choices.firstOrNull { it.first == selected }?.second ?: choices.first().second, choices, change), lp(-1, -2))
            return row
        }

        fun rebuildControls() {
            controls.removeAllViews()
            val caps = cameraTuning.capabilities
            if (caps == null) {
                addNote("Open a camera to see its available controls. Unsupported options are not shown.")
                return
            }
            val values = cameraTuning.values
            addControl(tv("Camera controls", 24f, Theme.TEXT_DARK, Theme.serifBold))
            addNote("Saved separately for each physical camera. Changes affect preview, JPEG capture, and video where supported.")

            // Exposure compensation affects automatic exposure. It is not manual ISO.
            if (caps.manualExposure) {
                addControl(settingRow(
                    "Exposure mode", listOf("auto" to "Auto exposure", "manual" to "Manual ISO + shutter"),
                    if (values.manualExposure) "manual" else "auto"
                ) { key ->
                    cameraTuning.setManualExposure(key == "manual")
                    rebuildControls()
                })
            } else addNote("Exposure mode: Auto (manual sensor controls not supported by this camera)")

            if (!values.manualExposure && caps.exposureCompensation) {
                addControl(sliderRow(
                    "Exposure compensation", caps.exposureMin, caps.exposureMax, values.exposureIndex,
                    { i -> "%+.2f EV".format(i * caps.exposureStepEv) }
                ) { cameraTuning.setExposureIndex(it) })
            } else if (!values.manualExposure) {
                addNote("Exposure compensation: unavailable")
            }

            if (values.manualExposure && caps.manualExposure) {
                addControl(sliderRow(
                    "ISO", 0, 100, (((values.iso - caps.isoMin).toDouble() / (caps.isoMax - caps.isoMin).coerceAtLeast(1)) * 100).toInt(),
                    { progress -> "${caps.isoMin + ((caps.isoMax - caps.isoMin) * (progress / 100.0)).toInt()}" }
                ) { progress ->
                    cameraTuning.setIso(caps.isoMin + ((caps.isoMax - caps.isoMin) * (progress / 100.0)).toInt())
                })
                // Common shutter steps, limited to the device-advertised sensor range.
                val shutterSteps = listOf(
                    1_000_000L, 2_000_000L, 4_000_000L, 8_000_000L,
                    10_000_000L, 16_666_667L, 20_000_000L, 33_333_333L,
                    40_000_000L, 50_000_000L, 66_666_667L, 100_000_000L,
                    125_000_000L, 250_000_000L, 500_000_000L
                ).plus(caps.shutterMinNs).plus(caps.shutterMaxNs)
                    .filter { it in caps.shutterMinNs..caps.shutterMaxNs }.distinct().sorted()
                if (shutterSteps.isNotEmpty()) {
                    val selected = shutterSteps.indices.minByOrNull { kotlin.math.abs(shutterSteps[it] - values.shutterNs) } ?: 0
                    addControl(sliderRow("Shutter speed", 0, shutterSteps.lastIndex, selected,
                        { i -> CameraTuning.shutterLabel(shutterSteps[i]) }
                    ) { cameraTuning.setShutterNs(shutterSteps[it]) })
                    addNote("Long exposures may lower Live Photo frame rate. Adjust and test on the actual camera.")
                }
            }

            if (caps.whiteBalanceModes.isNotEmpty()) {
                addControl(settingRow(
                    "White balance", caps.whiteBalanceModes.map { "$it" to CameraTuning.whiteBalanceLabel(it) },
                    "${values.whiteBalanceMode}"
                ) { cameraTuning.setWhiteBalance(it.toInt()) })
            } else addNote("White balance: not exposed by this camera")

            if (caps.antiBandingModes.isNotEmpty()) {
                addControl(settingRow(
                    "Anti-flicker", caps.antiBandingModes.map { "$it" to CameraTuning.antiBandingLabel(it) },
                    "${values.antiBandingMode}"
                ) { cameraTuning.setAntiBanding(it.toInt()) })
                addNote("Indonesia's mains frequency is 50 Hz; use 50 Hz if artificial lighting flickers.")
            }

            if (caps.zoom) {
                addControl(sliderRow(
                    "Zoom", 0, 100,
                    (((values.zoomRatio - caps.zoomMin) / (caps.zoomMax - caps.zoomMin)) * 100f).toInt().coerceIn(0, 100),
                    { p -> "%.2fx".format(caps.zoomMin + (caps.zoomMax - caps.zoomMin) * p / 100f) }
                ) { p -> cameraTuning.setZoom(caps.zoomMin + (caps.zoomMax - caps.zoomMin) * p / 100f) })
            } else addNote("Zoom: fixed on this camera")

            if (caps.manualFocus) {
                addControl(settingRow("Focus mode", listOf("auto" to "Autofocus", "manual" to "Manual focus"),
                    if (values.manualFocus) "manual" else "auto"
                ) { key -> cameraTuning.setManualFocus(key == "manual"); rebuildControls() })
                if (values.manualFocus) {
                    addControl(sliderRow(
                        "Focus distance", 0, 100,
                        (values.focusDiopters / caps.maxFocusDiopters * 100f).toInt().coerceIn(0, 100),
                        { p -> if (p == 0) "Infinity" else "%.2f diopters".format(caps.maxFocusDiopters * p / 100f) }
                    ) { p -> cameraTuning.setFocus(caps.maxFocusDiopters * p / 100f) })
                }
            } else addNote("Focus: camera-controlled / fixed-focus")

            addControl(btn("RESET THIS CAMERA TO AUTO") {
                cameraTuning.reset()
                rebuildControls()
            }.apply { typeface = Theme.serifBold })
        }

        var restartUvcPreview: () -> Unit = {}
        fun rebuildUvcControls() {
            controls.removeAllViews()
            val bridge = uvc ?: return
            addControl(tv("USB / UVC camera controls", 24f, Theme.TEXT_DARK, Theme.serifBold))
            addNote("Hardware controls are only shown if this webcam reports support. " +
                "UVC Gain/Exposure use webcam units, not Camera2 ISO and EV.")

            val resolutions = bridge.availableResolutions()
            addControl(settingRow("USB stream resolution (reopen camera to apply)",
                resolutions, bridge.selectedResolutionKey()) { selectedKey ->
                bridge.setSelectedResolutionKey(selectedKey)
                restartUvcPreview()
            })
            addNote("Active USB preview: ${bridge.activeResolutionLabel()}. " +
                "Resolution changes require reopening the USB camera. " +
                "Try 720p first; only advertised modes can be selected.")
            addControl(settingRow("Live Photo MP4 encoder frame rate (not camera stream FPS)",
                listOf("15" to "15 FPS", "20" to "20 FPS", "25" to "25 FPS (default)", "30" to "30 FPS"),
                bridge.videoFps().toString()) {
                bridge.setVideoFps(it.toInt())
            })
            addControl(settingRow("Live Photo MP4 encoder bitrate",
                listOf("3" to "3 Mbps", "5" to "5 Mbps (default)", "8" to "8 Mbps", "12" to "12 Mbps"),
                bridge.videoBitrateMbps().toString()) {
                bridge.setVideoBitrateMbps(it.toInt())
            })
            addNote("Live Photo uses silent H.264 MP4 with a 1-second keyframe interval. " +
                "Higher recording settings can fail on slower Android devices.")

            val toggleOptions = bridge.availableToggles()
            for (option in toggleOptions) {
                addControl(settingRow(option.label,
                    listOf("true" to "On / automatic", "false" to "Off / manual"),
                    option.checked.toString()) { value ->
                    if (!bridge.setToggleValue(option.id, value == "true")) {
                        which.text = "USB control ${option.label} was rejected"
                    }
                    rebuildUvcControls()
                })
            }
            val settings = bridge.availableControls()
            for (setting in settings) {
                addControl(sliderRow(setting.label, setting.min, setting.max,
                    setting.value, { it.toString() }) { value ->
                    if (!bridge.setControlValue(setting.id, value)) {
                        which.text = "USB control ${setting.label} was rejected"
                    }
                })
            }
            if (settings.isEmpty() && toggleOptions.isEmpty()) {
                addNote("This camera exposes no recognized adjustable UVC controls via the current SDK. " +
                    "Resolution selection and recording diagnostics are still available.")
            } else {
                addControl(btn("RESET USB CAMERA CONTROLS") {
                    bridge.resetControls()
                    rebuildUvcControls()
                }.apply { typeface = Theme.serifBold })
            }
        }

        fun startPreview() {
            closeCam()
            controls.removeAllViews()
            addNote("Connecting to camera...")
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                perm.launch(Manifest.permission.CAMERA)
                which.text = "Allow the camera, then open this page again"
                return
            }
            startCam({
                which.text = "No camera could be opened"
                rebuildControls()
            }, false) {
                val pv = activePreview ?: return@startCam
                (pv.parent as? ViewGroup)?.removeView(pv)
                box.removeAllViews()
                box.addView(pv, FrameLayout.LayoutParams(-1, -1))
                val liveSupport = if (uvc != null) "UVC video capture (test on device)"
                    else if (motionCapture != null) "Live Photo ready" else "Stills only"
                which.text = "Using: $camLabel ($liveSupport)"
                if (uvc != null) rebuildUvcControls() else rebuildControls()
            }
        }

        restartUvcPreview = { startPreview() }
        val selected = modes.firstOrNull { it.first == Net.camMode() } ?: modes.first()
        val dd = dropdown(selected.second, modes) { key ->
            Net.setCam(key)
            cameraMaximum = null
            cameraMaximumContext = ""
            startPreview()
        }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(tv("Camera", 30f, Theme.TEXT_DARK, Theme.serifBold), lp(-2, -2).apply { rightMargin = u(12f) })
        head.addView(dd, lp(-2, -2))
        head.addView(which, lp(-2, -2).apply { leftMargin = u(16f) })

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            addView(controls, FrameLayout.LayoutParams(-1, -2))
        }
        val horizontal = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(box, lp(0, -1, 1.25f).apply { rightMargin = u(12f) })
            addView(scroll, lp(0, -1, 1f))
        }
        val c = card(14f, 12f)
        c.addView(settingsTabs(true), lp(-1, -2).apply { bottomMargin = u(10f) })
        c.addView(head, lp(-1, -2).apply { bottomMargin = u(12f) })
        c.addView(horizontal, lp(-1, 0, 1f))
        showShell("settings", bodyPage(header("Settings / Camera", null), c, false))
        startPreview()
    }

    // ---- Connection
    private fun connectionScreen() {
        cur = "conn"
        val modes = listOf("auto" to "Automatic", "internet" to "Internet only", "local" to "Local network only")
        val desc = mapOf("auto" to "Local network when available, otherwise internet", "internet" to "Always use the internet address", "local" to "Only use the host on the local network")
        val descTv = tv("Description : ${desc[Net.linkMode()]}", 22f, Theme.TEXT_DARK)
        val statusLine = tv(statusText(), 16f, Theme.TEXT_DARK, Theme.sans)
        val logTv = tv(Net.probeLog.ifEmpty { "No search yet." }, 16f, Theme.TEXT_DARK, Theme.sans)
        val c1 = card(16f, 20f)
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(tv("Connection mode :", 30f, Theme.TEXT_DARK, Theme.serifBold), lp(0, -2, 1f))
        head.addView(dropdown(modes.first { it.first == Net.linkMode() }.second, modes) { m -> Net.setLinkMode(m); descTv.text = "Description : ${desc[m]}" }, lp(-2, -2))
        c1.addView(head, lp(-1, -2)); c1.addView(descTv, lp(-1, -2).apply { topMargin = u(12f) }); c1.addView(statusLine, lp(-1, -2).apply { topMargin = u(8f) })
        c1.addView(pill("Find Host on Local", 22f) { logTv.text = "Searching..."; bg { Net.probeLan(); runOnUiThread { logTv.text = Net.probeLog; statusLine.text = statusText(); refreshStatus() } } }, lp(-2, -2).apply { topMargin = u(22f) })
        val c2 = card(16f, 16f)
        val addr = field("Host address, for example 192.168.1.20")
        c2.addView(tv("Connect to a host address", 24f, Theme.TEXT_DARK, Theme.serifBold), lp(-2, -2).apply { bottomMargin = u(10f) })
        c2.addView(addr, lp(-1, -2))
        c2.addView(pill("Connect", 18f) { bg { Net.tryManual(addr.text.toString()); runOnUiThread { logTv.text = Net.probeLog; statusLine.text = statusText(); refreshStatus() } } }, lp(-2, -2).apply { topMargin = u(10f) })
        c2.addView(tv("Search log", 20f, Theme.TEXT_DARK, Theme.serifBold), lp(-2, -2).apply { topMargin = u(16f); bottomMargin = u(4f) }); c2.addView(logTv)
        val content = vbox()
        addTo(content, c1)
        content.addView(pill("Unpair device with current host", 20f, dark = true) { confirm("Unpair this tablet from the host? Photos that were not uploaded yet stay on the tablet.") { Net.unpair(); pairScreen() } }, lp(-2, -2).apply { setMargins(u(7f), 0, 0, u(12f)) })
        addTo(content, c2)
        showShell("connection", bodyPage(header("Connection", null), content)); detailTv = statusLine
    }

    // ---- Pairing (no sidebar)
    private fun pairScreen() {
        cur = "pair"
        val a = field("https://photos.example.com   or   192.168.1.20").apply { setText(Net.pub()) }
        val code = field("6-digit pairing code", true)
        val box = card(20f, 22f).apply { gravity = Gravity.CENTER_HORIZONTAL }
        box.addView(tv("Pair with the host", 32f, Theme.TEXT_DARK, Theme.serifBold), lp(-2, -2).apply { bottomMargin = u(6f) })
        box.addView(tv("On the laptop open Devices > New Device, then scan the QR code.", 18f, Theme.TEXT_DARK), lp(-2, -2))
        box.addView(pill("Scan QR code", 22f, Icons.CAMERA, bold = true) { scanScreen() }, lp(-2, -2).apply { topMargin = u(14f) })
        box.addView(tv("Or pair by hand. On the same Wi-Fi as the laptop? Press Find. Otherwise type the public address.", 18f, Theme.WHITE80), lp(-2, -2).apply { topMargin = u(18f) })
        box.addView(a, lp(-1, -2).apply { topMargin = u(16f) })
        box.addView(pill("Find host on this Wi-Fi", 18f) { busy("Looking for the host...") { val f = Net.discover(); runOnUiThread { if (f.isEmpty()) toast("No host answered. Check the Wi-Fi or type the address.") else { a.setText(f[0]); toast("Found ${f[0]}") } } } }, lp(-2, -2).apply { topMargin = u(10f) })
        box.addView(code, lp(-1, -2).apply { topMargin = u(16f) })
        box.addView(pill("Pair", 22f, bold = true) { busy("Pairing with the host...") { Net.pair(a.text.toString(), code.text.toString().trim()); Net.probeLan(); Net.sync(); runOnUiThread { eventsScreen() } } }, lp(-2, -2).apply { topMargin = u(16f) })
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(Theme.CREAM); setPadding(u(40f), u(20f), u(40f), u(20f)) }
        val lg = Theme.logo
        if (lg != null) wrap.addView(ImageView(this).apply { setImageBitmap(lg); adjustViewBounds = true }, lp(u(420f), -2).apply { bottomMargin = u(20f) })
        wrap.addView(box, lp(u(640f), -2))
        show(ScrollView(this).apply { isFillViewport = true; addView(wrap) })
    }

    // ---- QR scanner for the first pairing (CameraX preview + ZXing decoding of the luminance plane)
    private fun scanScreen() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { scanPerm.launch(Manifest.permission.CAMERA); return }
        cur = "scan"; scanDone.set(false); closeCam()
        val pv = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        val msg = tv("Point the camera at the QR code on the laptop", 22f, Theme.WHITE80)
        val box = FrameLayout(this).apply { background = round(Theme.PREVIEW, 14f); clipToOutline = true; addView(pv, FrameLayout.LayoutParams(-1, -1)) }
        val c = card(16f, 16f).apply { gravity = Gravity.CENTER_HORIZONTAL }
        c.addView(tv("Scan the pairing QR code", 32f, Color.WHITE, Theme.serifBold), lp(-2, -2).apply { bottomMargin = u(10f) })
        c.addView(box, lp(u(560f), u(360f)))
        c.addView(msg, lp(-2, -2).apply { topMargin = u(12f) })
        c.addView(pill("Type the code instead", 20f) { closeCam(); pairScreen() }, lp(-2, -2).apply { topMargin = u(14f) })
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(Theme.CREAM); setPadding(u(40f), u(20f), u(40f), u(20f)) }
        wrap.addView(c, lp(u(640f), -2))
        show(ScrollView(this).apply { isFillViewport = true; addView(wrap) })
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            try {
                val p = f.get(); prov = p; p.unbindAll()
                val pr = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                val ana = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                val reader = com.google.zxing.qrcode.QRCodeReader()
                val hints = mapOf(com.google.zxing.DecodeHintType.TRY_HARDER to true, com.google.zxing.DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
                ana.setAnalyzer(io) { img ->
                    try {
                        if (scanDone.get()) return@setAnalyzer
                        val pl = img.planes[0]; val buf = pl.buffer; val data = ByteArray(buf.remaining()); buf.get(data)
                        val rows = pl.rowStride; val h = img.height; val w = img.width
                        val src = com.google.zxing.PlanarYUVLuminanceSource(data, rows, h, 0, 0, w, h, false)
                        var text: String? = null
                        for (s in listOf<com.google.zxing.LuminanceSource>(src, src.invert())) {
                            try { text = reader.decode(com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(s)), hints).text; break }
                            catch (_: com.google.zxing.ReaderException) { reader.reset() }
                        }
                        val found = text
                        if (found != null && scanDone.compareAndSet(false, true)) runOnUiThread { onScanned(found, msg) }
                    } catch (_: Exception) { } finally { img.close() }
                }
                val order = listOf(CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.DEFAULT_FRONT_CAMERA,
                    CameraSelector.Builder().addCameraFilter { l -> l.filter { it.lensFacing == CameraSelector.LENS_FACING_EXTERNAL } }.build())
                var err: Exception? = null; var ok = false
                for (cs in order) { try { p.unbindAll(); p.bindToLifecycle(this, cs, pr, ana); ok = true; break } catch (e: Exception) { err = e } }
                if (!ok) throw err ?: IOException("no camera found")
            } catch (e: Exception) { toast("Camera unavailable: ${e.message}"); pairScreen() }
        }, ContextCompat.getMainExecutor(this))
    }
    private fun onScanned(text: String, msg: TextView) {
        if (!Net.isPairQr(text)) { msg.text = "That is not a Photobooth pairing code. Try the QR code from Devices > New Device."; scanDone.set(false); return }
        msg.text = "Pairing..."; closeCam(); showBusy("Pairing with the host...")
        Thread {
            try { Net.pairQr(text); Net.probeLan(); Net.sync(); runOnUiThread { eventsScreen() } }
            catch (e: Exception) { toast(e.message ?: "Pairing failed"); runOnUiThread { scanScreen() } }
            finally { hideBusy() }
        }.start()
    }
}
