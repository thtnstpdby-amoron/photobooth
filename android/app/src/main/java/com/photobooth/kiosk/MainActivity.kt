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
    private val idle = Runnable { if (guest && ev != null && cur != "busy" && cur != "snap") startScreen() }
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
        cur = "start"; job?.cancel(); pvJob?.cancel(); guest = true; closeCam(); shots.clear(); sel = mutableListOf(); picked.clear(); chosen.clear(); gifEnabled = false; previewMode = PreviewMode.PHOTO; gifFrame = null; mirrorEnabled = false; liveEnabled = false; selectedGrade = "original"; gradeStrength = 0.85f; resultJob?.cancel(); resultSlides = emptyList(); framedThumbs = emptyList(); discardMotionClips()
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
        val maxFrames = 1 // Client requirement: exactly one main frame per guest session
        if (fr.length() == 0) { toast("This event has no frames yet"); startScreen(); return }

        val blue = Theme.SIDEBAR_BTN
        val sm = small()

        // ==========================================
        // 1. LEFT COLUMN: Title and Grid of Frames
        // ==========================================
        val leftSide = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        
        val title = txt("Choose one frame, then press START", if (sm) 16f else 24f).apply {
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
                    cornerRadius = u(16f).toFloat()
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
                            cornerRadius = u(10f).toFloat()
                        }
                    },
                    FrameLayout.LayoutParams(-1, -2).apply {
                        setMargins(u(16f), u(16f), u(16f), u(16f))
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
                            cornerRadius = u(16f).toFloat()
                        }
                    }
                    addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
                }
            }
        }

        // Drop the items into your existing grid() helper function inside a scroll view
        val gridScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(grid(frameItems, if (sm) 5 else 6)) // 3 columns for small screens, 4 for big
        }
        leftSide.addView(gridScroll, lp(-1, 0, 1f))

        // Action Buttons
        val startBtn = btn("START") {
            if (picked.isEmpty()) toast("Pick at least one frame") else begin()
        }.apply { alpha = if (picked.isEmpty()) 0.45f else 1f
                    typeface = Theme.serifBold }
        val backBtn = btn("Back") { startScreen() }.apply { typeface = Theme.serifBold }

        // ==========================================
        // 3. MASTER CONTAINER
        // ==========================================
        val mainContent = FrameLayout(this).apply {
            setPadding(u(32f), u(32f), u(24f), u(24f))

            // LEFT SIDE: Occupies the available screen area
            addView(
                leftSide,
                FrameLayout.LayoutParams(-1, -1)
            )

            // RIGHT SIDE: Button container, positioned bottom-right
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setPadding(u(16f), u(16f), 0, 0)

                    addView(startBtn, lp(-2, -2).apply { marginEnd = u(12f) })
                    addView(backBtn, lp(-2, -2))
                },
                FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END)
            )
        }

        show(mainContent)
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
        gifEnabled = false; previewMode = PreviewMode.PHOTO; gifFrame = null; mirrorEnabled = false; liveEnabled = false; selectedGrade = "original"; gradeStrength = 0.85f
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
                if (captured.width > 1600)
                    Bitmap.createScaledBitmap(captured, 1600, 1600 * captured.height / captured.width, true)
                else captured
            } else suspendCancellableCoroutine<Bitmap> { c ->
                capture!!.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(im: ImageProxy) {
                        shutterAt = android.os.SystemClock.elapsedRealtimeNanos()
                        try {
                            val matrix = Matrix().apply { postRotate(im.imageInfo.rotationDegrees.toFloat()) }
                            val raw = im.toBitmap(); im.close()
                            var result = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                            if (result.width > 1600) result = Bitmap.createScaledBitmap(result, 1600, 1600 * result.height / result.width, true)
                            c.resume(result)
                        } catch (e: Exception) { im.close(); c.resumeWithException(e) }
                    }
                    override fun onError(e: ImageCaptureException) { c.resumeWithException(e) }
                })
            }
            if (recording) delay(1000)
            val clip = if (recording) finishMotionRecording(shutterAt) else null
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
    // It uses the exact same 720px / 12 FPS encode as the final downloadable Live Photo,
    // so Finish can reuse the file instead of encoding the motion twice.
    private fun livePreviewSignature(f: JSONObject, picks: List<Int>): String = listOf(
        f.toString(),
        picks.joinToString(","),
        selectedGrade,
        gradeStrength.toString(),
        mirrorEnabled.toString(),
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
        val fw = frameBmp(f, 720)
        val evenHeight = ((720 * fw.height / fw.width) / 2 * 2).coerceAtLeast(2)
        val target = File(cacheDir, "pb-review-live-${System.nanoTime()}.mp4")
        cachedLiveKey = signature
        cachedLiveFile = target
        val task = scope.async(Dispatchers.Default) {
            val ctx = currentCoroutineContext()
            val started = android.os.SystemClock.elapsedRealtime()
            android.util.Log.i("PB-Processing", "Review Live Photo encoding started: ${picks.size} selected photos")
            try {
                FramedLiveVideo.encode(
                    destination = target,
                    height = evenHeight,
                    rawPhotos = photos,
                    usedPhotoIndices = picks.toSet(),
                    clips = clips
                ) { currentFrames ->
                    // Stop composing once a retake, grade or photo-selection change
                    // invalidates this preview; do not waste time on stale work.
                    ctx.ensureActive()
                    val image = compose(f, picks, 720, sources = currentFrames,
                        gradeId = gradeId, gradeLevel = gradeLevel, mirrored = mirrored)
                    if (image.height == evenHeight) image else {
                        val resized = Bitmap.createScaledBitmap(image, 720, evenHeight, true)
                        image.recycle()
                        resized
                    }
                }
                ctx.ensureActive()
                android.util.Log.i("PB-Processing", "review live encode=" +
                    "${android.os.SystemClock.elapsedRealtime() - started}ms")
                target
            } catch (e: Throwable) {
                target.delete()
                throw e
            }
        }
        cachedLiveTask = task
        return task
    }

    // Guest review: Main Photo, optional Live Photo, optional GIF.
    private fun review() {
        cur = "review"; pvJob?.cancel(); stopReviewLivePlayback()
        val f = frame ?: return
        val n = uniquePhotoCount(f)
        val sm = small()
        val pv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val pvBox = FrameLayout(this).apply {
            background = round(Theme.BRAND_BG, 12f)
            setPadding(u(8f), u(16f), u(8f), u(32f))
            addView(pv, FrameLayout.LayoutParams(-1, -1))
        }
        val editingGif = gifEnabled && previewMode == PreviewMode.GIF
        val viewingLive = liveEnabled && previewMode == PreviewMode.LIVE
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (gifEnabled || liveEnabled) {
                val tabs = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(btn("MAIN PHOTO") { previewMode = PreviewMode.PHOTO; review() }.apply {
                        typeface = Theme.serifBold; alpha = if (previewMode == PreviewMode.PHOTO) 1f else .55f
                    }, lp(0, -2, 1f))
                    if (liveEnabled) addView(btn("LIVE PHOTO") { previewMode = PreviewMode.LIVE; review() }.apply {
                        typeface = Theme.serifBold; alpha = if (viewingLive) 1f else .55f
                    }, lp(0, -2, 1f))
                    if (gifEnabled) addView(btn("SLIDESHOW GIF") { previewMode = PreviewMode.GIF; review() }.apply {
                        typeface = Theme.serifBold; alpha = if (editingGif) 1f else .55f
                    }, lp(0, -2, 1f))
                }
                addView(tabs, lp(-1, -2))
            }
            addView(pvBox, lp(-1, 0, 1f))
            addView(txt(when {
                editingGif -> "GIF frame: ${gifFrame?.optString("name") ?: "not selected"}"
                viewingLive -> "Live Photo: synchronized motion + still transition"
                else -> "Main photo (${sel.size}/$n selected)"
            }, if (sm) 12f else 16f).apply { typeface = Theme.serifBold }, lp(-1, -2))
        }

        when {
            editingGif -> {
                val gf = gifFrame
                if (gf == null) {
                    pvBox.addView(txt("Select a single-slot GIF frame on the right", 17f),
                        FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
                } else if (shots.isNotEmpty()) {
                    val gifLoading = txt("Preparing slideshow preview...", 16f)
                    pvBox.addView(gifLoading, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
                    pvJob = scope.launch {
                        val frames = withContext(Dispatchers.Default) {
                            shots.indices.map { compose(gf, listOf(it), 480, true) }
                        }
                        try {
                            gifLoading.visibility = View.GONE
                            var i = 0
                            while (isActive && cur == "review" && gifEnabled && previewMode == PreviewMode.GIF) {
                                pv.setImageBitmap(frames[i % frames.size])
                                i++; delay(700)
                            }
                        } finally {
                            pv.setImageDrawable(null)
                            frames.forEach { if (!it.isRecycled) it.recycle() }
                        }
                    }
                }
            }
            viewingLive -> {
                if (sel.size != n) {
                    pvBox.addView(txt("Choose all $n photos for the main frame to preview Live Photo", 16f),
                        FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
                } else {
                    // VideoView is a SurfaceView: it MUST be VISIBLE so its surface is
                    // created. Waiting for onPrepared() before making it visible can
                    // prevent onPrepared() from ever being called.
                    val video = VideoView(this).apply { visibility = View.VISIBLE }
                    pvBox.addView(video, FrameLayout.LayoutParams(-1, -1))

                    // Keep a framed still and status text on top until the video is
                    // encoded AND Android's video player has actually prepared it.
                    val cover = FrameLayout(this).apply {
                        background = round(Theme.BRAND_BG, 12f)
                        addView(ImageView(this@MainActivity).apply {
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            setImageBitmap(compose(f, sel.toList(), 480, true))
                        }, FrameLayout.LayoutParams(-1, -1))
                    }
                    val loading = txt("Preparing Live Photo video...", 16f).apply {
                        background = round(Theme.BRAND_BG, 12f)
                        setPadding(dp(16), dp(10), dp(16), dp(10))
                    }
                    cover.addView(loading, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
                    pvBox.addView(cover, FrameLayout.LayoutParams(-1, -1))
                    val picks = sel.toList()
                    val signature = livePreviewSignature(f, picks)
                    val prepared = prepareReviewLive(f, picks)
                    // prepareReviewLive() can stop a previous player when inputs change.
                    reviewLivePlayer = video
                    pvJob = scope.launch {
                        val started = android.os.SystemClock.elapsedRealtime()
                        val status = launch {
                            var elapsedSeconds = 0
                            while (isActive) {
                                delay(5000)
                                elapsedSeconds += 5
                                if (reviewLivePlayer === video && cover.visibility == View.VISIBLE) {
                                    loading.text = "Encoding Live Photo... ${elapsedSeconds}s"
                                }
                            }
                        }
                        try {
                            // Fail visibly rather than leaving guests in an endless wait.
                            val file = withTimeoutOrNull(60_000L) { prepared.await() }
                            if (file == null) {
                                if (reviewLivePlayer === video && previewMode == PreviewMode.LIVE) {
                                    loading.text = "Live Photo preview took too long. You can return to Main Photo."
                                    android.util.Log.e("PB-Processing", "Review Live Photo encode exceeded 60s")
                                    cachedLiveTask?.cancel()
                                }
                                return@launch
                            }
                            if (!isActive || cur != "review" || !liveEnabled ||
                                previewMode != PreviewMode.LIVE ||
                                reviewLivePlayer !== video || cachedLiveKey != signature) return@launch
                            if (!file.isFile || file.length() == 0L) throw IOException("Encoded preview file is empty")
                            android.util.Log.i("PB-Processing", "Review MP4 ready in " +
                                "${android.os.SystemClock.elapsedRealtime() - started}ms, " +
                                "bytes=${file.length()}")
                            loading.text = "Opening Live Photo player..."
                            // If the MP4 exists but VideoView never prepares, make that
                            // a visible, separately logged failure instead of blank UI.
                            val playerWatchdog = scope.launch {
                                delay(15_000L)
                                if (cur == "review" && previewMode == PreviewMode.LIVE &&
                                    reviewLivePlayer === video && cover.visibility == View.VISIBLE) {
                                    loading.text = "MP4 prepared, but video player has not started"
                                    android.util.Log.e("PB-Processing", "Review player preparation exceeded 15s")
                                }
                            }
                            video.setOnErrorListener { _, what, extra ->
                                playerWatchdog.cancel()
                                android.util.Log.e("PB-Processing", "Review player error what=$what extra=$extra")
                                if (reviewLivePlayer === video) {
                                    loading.text = "Live Photo playback failed (code $what)"
                                    cover.visibility = View.VISIBLE
                                }
                                true
                            }
                            video.setOnPreparedListener { mp ->
                                playerWatchdog.cancel()
                                if (cur == "review" && liveEnabled &&
                                    previewMode == PreviewMode.LIVE && reviewLivePlayer === video) {
                                    android.util.Log.i("PB-Processing", "Review MP4 player prepared")
                                    mp.isLooping = true
                                    cover.visibility = View.GONE
                                    video.start()
                                }
                            }
                            video.setVideoURI(Uri.fromFile(file))
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            android.util.Log.e("PB-Processing", "Review Live Photo preparation failed", e)
                            if (cur == "review" && previewMode == PreviewMode.LIVE &&
                                reviewLivePlayer === video) {
                                loading.text = "Live Photo unavailable: ${e.message ?: "encoding error"}"
                                cover.visibility = View.VISIBLE
                            }
                        } finally {
                            status.cancel()
                        }
                    }
                }
            }
            else -> pv.setImageBitmap(compose(f, sel, 640, true))
        }

        val cols = 3
        val tw = ((resources.displayMetrics.widthPixels * .60f) / cols).toInt() - dp(20)
        val th = tw * 3 / 4
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        grid.addView(txt("Photo color grade (all outputs)", 17f).apply {
            typeface = Theme.serifBold; gravity = Gravity.START
        }, lp(-1, -2))
        val gradeBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        PhotoGrade.presets.forEach { preset ->
            gradeBar.addView(btn(preset.title) {
                selectedGrade = preset.id
                review()
            }.apply {
                textSize = if (small()) 10f else 12f
                alpha = if (preset.id == selectedGrade) 1f else 0.55f
                typeface = Theme.serifBold
            }, lp(0, -2, 1f).apply { setMargins(dp(2), 0, dp(2), dp(4)) })
        }
        grid.addView(gradeBar, lp(-1, -2))
        val gradeSlider = SeekBar(this).apply {
            max = 100; progress = (gradeStrength * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) { }
                override fun onStartTrackingTouch(s: SeekBar?) { }
                override fun onStopTrackingTouch(s: SeekBar?) {
                    gradeStrength = (s?.progress ?: 85) / 100f
                    review()
                }
            })
        }
        grid.addView(txt("Color strength", 13f), lp(-1, -2))
        grid.addView(gradeSlider, lp(-1, -2))
        val hasSingleSlotGifFrame = ev!!.getJSONArray("frames").let { frames ->
            (0 until frames.length()).any { index ->
                (frames.optJSONObject(index)?.optJSONArray("slots")?.length() ?: 0) == 1
            }
        }
        if (sessionShots > 1 && hasSingleSlotGifFrame) grid.addView(pill(if (gifEnabled) "Slideshow GIF: ON" else "Slideshow GIF: OFF",
            if (sm) 16f else 20f, Icons.PLAY, true, gifEnabled) {
                gifEnabled = !gifEnabled
                if (!gifEnabled && previewMode == PreviewMode.GIF) previewMode = PreviewMode.PHOTO
                review()
            }, lp(-2, -2).apply { setMargins(dp(6), dp(2), dp(6), dp(8)) })
        grid.addView(pill(if (mirrorEnabled) "Mirror photo: ON" else "Mirror photo: OFF", 17f,
            Icons.IMAGE, true, mirrorEnabled) { mirrorEnabled = !mirrorEnabled; review() },
            lp(-2, -2).apply { setMargins(dp(6), dp(2), dp(6), dp(8)) })
        grid.addView(pill(if (liveEnabled) "Live Photo: ON" else "Live Photo: OFF", 17f,
            Icons.CAMERA, true, liveEnabled) {
                if (!liveEnabled && (0 until shots.size).any { motionClips.getOrNull(it)?.file?.exists() != true })
                    toast("Live Photo unavailable: one or more camera recordings failed")
                else {
                    liveEnabled = !liveEnabled
                    if (!liveEnabled) clearReviewLiveCache()
                    previewMode = if (liveEnabled) PreviewMode.LIVE else PreviewMode.PHOTO
                    review()
                }
            }, lp(-2, -2).apply { setMargins(dp(6), dp(2), dp(6), dp(8)) })

        if (editingGif) {
            val available = ev!!.getJSONArray("frames")
            val frames = (0 until available.length()).map { available.getJSONObject(it) }
                .filter { (it.optJSONArray("slots")?.length() ?: 0) == 1 }
            if (frames.isEmpty()) grid.addView(txt("No single-slot frames available", 17f))
            else {
                val items = frames.map { gf ->
                    val selected = gifFrame?.optString("id") == gf.optString("id")
                    LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        background = round(if (selected) Theme.SIDEBAR_BTN else Theme.BRAND_BG, 12f)
                        setPadding(u(8f), u(8f), u(8f), u(8f))
                        isClickable = true
                        setOnClickListener { gifFrame = gf; review() }
                        addView(ImageView(this@MainActivity).apply {
                            setImageBitmap(thumb(Net.imgFile("f_" + gf.optString("id")), 400))
                            scaleType = ImageView.ScaleType.FIT_CENTER
                        }, lp(-1, u(160f)))
                        addView(txt(gf.optString("name", "Frame"), 14f), lp(-1, -2))
                        if (selected) addView(txt("Selected", 12f), lp(-1, -2))
                    } as View
                }
                grid.addView(this.grid(items, 2), lp(-1, -2))
            }
        } else {
            shots.indices.chunked(cols).forEach { chunk ->
                val row = LinearLayout(this)
                chunk.forEach { i ->
                    val on = i in sel
                    val tile = FrameLayout(this@MainActivity).apply {
                        background = round(if (on) Theme.SIDEBAR_BTN else Theme.BRAND_BG, 16f)
                        setPadding(u(12f), u(12f), u(12f), u(12f))
                        addView(ImageView(this@MainActivity).apply {
                            setImageBitmap(shots[i]); scaleType = ImageView.ScaleType.CENTER_CROP
                            background = round(Theme.BRAND_BG, 10f); clipToOutline = true
                        }, FrameLayout.LayoutParams(-1, -1))
                        setOnClickListener {
                            if (i in sel) sel.remove(i)
                            else {
                                sel.add(i)
                                while (sel.size > n) sel.removeAt(sel.lastIndex - 1)
                            }
                            review()
                        }
                    }
                    val img = FrameLayout(this@MainActivity).apply {
                        addView(tile, FrameLayout.LayoutParams(-1, -1))
                        if (on) addView(txt("${sel.indexOf(i) + 1}", 12f).apply {
                            typeface = Theme.serifBold; setTextColor(Theme.TEXT_DARK)
                            background = round(Theme.SIDEBAR_BTN, 10f)
                            setPadding(dp(8), dp(2), dp(8), dp(2))
                        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
                    }
                    val cell = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                        addView(img, lp(-1, th))
                        addView(btn("Retake") {
                            pvJob?.cancel(); clearReviewLiveCache()
                            job = scope.launch {
                                try { shots[i] = snap(i) }
                                catch (e: CancellationException) { throw e }
                                catch (_: Exception) { }
                                review()
                            }
                        }.apply { typeface = Theme.serifBold }, lp(-2, -2).apply { topMargin = dp(6) })
                    }
                    row.addView(cell, lp(0, -2, 1f).apply { setMargins(dp(6), dp(4), dp(6), dp(8)) })
                }
                repeat(cols - chunk.size) { row.addView(View(this), lp(0, 1, 1f)) }
                grid.addView(row, lp(-1, -2))
            }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        content.addView(left, lp(0, -1, 1f).apply { setMargins(dp(4), dp(4), dp(8), dp(4)) })
        content.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(grid) }, lp(0, -1, 1.6f))
        val next = btn("FINISH") {
            when {
                sel.size < n -> { toast("Choose $n photos for your main frame"); previewMode = PreviewMode.PHOTO; review() }
                gifEnabled && gifFrame == null -> { toast("Select a GIF frame first"); previewMode = PreviewMode.GIF; review() }
                else -> {
                    pvJob?.cancel(); stopReviewLivePlayback()
                    chosen.clear(); chosen.add(f to sel.toList())
                    cur = "busy"; show(View(this).apply { setBackgroundColor(Theme.BRAND_BG) })
                    showBusy("Preparing your photos..."); finishSession()
                }
            }
        }.apply { alpha = if (sel.size < n || (gifEnabled && gifFrame == null)) .45f else 1f; typeface = Theme.serifBold }
        val backBtn = btn("BACK") { job?.cancel(); pvJob?.cancel(); closeCam(); frameScreen() }
            .apply { typeface = Theme.serifBold }
        val mainContent = FrameLayout(this).apply {
            addView(page("Arrange photos for your frame", null, content), FrameLayout.LayoutParams(-1, -1))
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER
                addView(next, lp(-2, -2).apply { marginEnd = u(12f) })
                addView(backBtn, lp(-2, -2))
            }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, u(24f), u(24f))
            })
        }
        show(mainContent)
    }
    // ---- compositing happens here on the tablet; the server never touches the image
    private fun finishSession() {
        // CameraX unbindAll() must run on the main/UI thread.
        closeCam()
        bg {
        val id = Net.rid(); val q = File(Net.dir, "q"); val tmp = File(q, ".$id").apply { mkdirs() }
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
            val out = compose(f, picks, 2000)
            thumbs.add(Bitmap.createScaledBitmap(out, 360, 360 * out.height / out.width, true))
            File(tmp, if (idx == 0) "photo.jpg" else "photo${idx + 1}.jpg").outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            if (idx == 0) { first = out; File(tmp, "thumb.jpg").outputStream().use { Bitmap.createScaledBitmap(out, 300, 300 * out.height / out.width, true).compress(Bitmap.CompressFormat.JPEG, 70, it) } }
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
            } catch (e: Exception) { toast("GIF generation failed: ${e.message}"); null }
        } else null
        mark("gif-and-raw")
        if (liveEnabled) {
            try {
                val (mainFrame, picks) = chosen.first()
                val destination = File(tmp, "live.mp4")
                val signature = livePreviewSignature(mainFrame, picks)
                val existingTask = if (signature == cachedLiveKey) cachedLiveTask else null
                // Review starts encoding as soon as Live Photo is selected. If the
                // guest finishes early, wait here (already on a worker thread).
                val reusable = if (existingTask != null) {
                    try { runBlocking { existingTask.await() } } catch (_: Exception) { null }
                } else null
                if (reusable?.isFile == true && reusable.length() > 0L) {
                    reusable.copyTo(destination, overwrite = true)
                    android.util.Log.i("PB-Processing", "reused review Live Photo MP4")
                } else {
                    // Fallback for a preview error or finishing without visiting Live.
                    val fw = frameBmp(mainFrame, 720)
                    val evenHeight = ((720 * fw.height / fw.width) / 2 * 2).coerceAtLeast(2)
                    FramedLiveVideo.encode(
                        destination = destination,
                        height = evenHeight,
                        rawPhotos = shots.toList(),
                        usedPhotoIndices = picks.toSet(),
                        clips = motionClips.toList()
                    ) { currentFrames ->
                        val image = compose(mainFrame, picks, 720, sources = currentFrames)
                        if (image.height == evenHeight) image else {
                            val resized = Bitmap.createScaledBitmap(image, 720, evenHeight, true)
                            image.recycle()
                            resized
                        }
                    }
                }
            } catch (e: Exception) {
                File(tmp, "live.mp4").delete()
                toast("Live Photo export failed; normal photo is saved: ${e.message}")
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
                        mirrored: Boolean = mirrorEnabled): Bitmap {
        val fb = frameBmp(f, W)

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
    private fun result(id: String, photo: Bitmap) {
        cur = "result"; resultJob?.cancel(); resId = id; resPhoto = photo
        val hh = resources.displayMetrics.heightPixels; val sm = small()
        val ph = (hh * (if (sm) .50 else .60)).toInt()
        val qs = minOf(dp(220), (hh * (if (sm) .35 else .40)).toInt())
        val slides = resultSlides.ifEmpty { listOf(photo) }
        var index = 0
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val counter = txt("", 15f)
        fun change(step: Int) {
            index = (index + step + slides.size) % slides.size
            image.setImageBitmap(slides[index])
            counter.text = if (slides.size == 1) "Your photo" else "Result ${index + 1} / ${slides.size}"
        }
        change(0)
        resultVideo?.stopPlayback(); resultVideo = null
        val liveFile = File(File(Net.dir, "q"), "$id/live.mp4")
        val liveAvailable = liveFile.isFile && liveFile.length() > 0L
        var playingLive = false
        val video = VideoView(this).apply {
            setOnCompletionListener { it.start() }
            setOnErrorListener { _, _, _ ->
                playingLive = false
                visibility = View.GONE; image.visibility = View.VISIBLE
                true
            }
        }
        val mediaBox = FrameLayout(this).apply {
            addView(image, FrameLayout.LayoutParams(-1, -1))
            addView(video, FrameLayout.LayoutParams(-1, -1))
            video.visibility = View.GONE
        }
        fun showLive(enabled: Boolean) {
            playingLive = enabled && liveAvailable
            image.visibility = if (playingLive) View.GONE else View.VISIBLE
            video.visibility = if (playingLive) View.VISIBLE else View.GONE
            if (playingLive) {
                try {
                    resultVideo = video
                    video.setVideoURI(Uri.fromFile(liveFile))
                    video.setOnPreparedListener { mp -> mp.isLooping = true; mp.start() }
                } catch (_: Exception) {
                    playingLive = false; image.visibility = View.VISIBLE; video.visibility = View.GONE
                }
            } else {
                video.stopPlayback(); resultVideo = null
            }
        }
        var touchX = 0f
        image.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { touchX = e.x; true }
                MotionEvent.ACTION_UP -> {
                    val dx = e.x - touchX
                    if (kotlin.math.abs(dx) > dp(35) && slides.size > 1) change(if (dx < 0) 1 else -1)
                    true
                }
                else -> true
            }
        }
        val previewPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(mediaBox, lp(-1, ph))
            if (liveAvailable) addView(btn("STILL / LIVE PHOTO") {
                showLive(!playingLive)
            }.apply { typeface = Theme.serifBold }, lp(-2, -2))
            addView(counter, lp(-1, -2))
        }
        val qrPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        if (Net.pub() != "") {
            qrPanel.addView(ImageView(this).apply { setImageBitmap(qr("${Net.pub()}/d/$id")) }, lp(qs, qs))
            qrPanel.addView(txt("Scan to download photos, GIF and Live Photo", if (sm) 13f else 18f), lp(-1, -2))
        } else qrPanel.addView(txt("Set the public address in the Windows admin app", 18f))
        val mid = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(8), 0) }
        mid.addView(previewPanel, lp(0, -1, 1.2f).apply { setMargins(dp(8), 0, dp(8), 0) })
        mid.addView(qrPanel, lp(0, -1, 1f).apply { setMargins(dp(8), 0, dp(8), 0) })
        val mainContent = FrameLayout(this).apply {
            addView(page("Scan to download your photos", "Link: ${Net.viaText()}", mid), FrameLayout.LayoutParams(-1, -1))
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER
                addView(btn("PRINT") { resultJob?.cancel(); resultVideo?.stopPlayback(); resultVideo = null; printScreen(id) }.apply { typeface = Theme.serifBold },
                    lp(-2, -2).apply { marginEnd = u(12f) })
                addView(btn("FINISH") { resultJob?.cancel(); resultVideo?.stopPlayback(); resultVideo = null; startScreen() }.apply { typeface = Theme.serifBold }, lp(-2, -2))
            }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, u(24f), u(24f)) })
        }
        show(mainContent)
        if (liveAvailable) showLive(true)
        if (slides.size > 1) resultJob = scope.launch {
            while (isActive && cur == "result") {
                delay(2600)
                if (cur == "result" && !playingLive) change(1)
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
        nav("Camera", Icons.CAMERA, "camera") { cameraSettings() }
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
        if (section != "camera") { cameraTuning.onControlFailure = null; closeCam() }
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
            "event", "gallery", "camera", "conn" -> eventsScreen()
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
                video.setOnPreparedListener { mp -> mp.isLooping = true; video.start() }
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
                if (uvc != null) {
                    controls.removeAllViews()
                    controls.addView(txt("USB webcam: Camera2 ISO/WB controls do not apply. UVC tuning depends on device-specific controls.",16f))
                } else rebuildControls()
            }
        }

        val selected = modes.firstOrNull { it.first == Net.camMode() } ?: modes.first()
        val dd = dropdown(selected.second, modes) { key -> Net.setCam(key); startPreview() }
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
        c.addView(head, lp(-1, -2).apply { bottomMargin = u(12f) })
        c.addView(horizontal, lp(-1, 0, 1f))
        showShell("camera", bodyPage(header("Camera", null), c, false))
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
