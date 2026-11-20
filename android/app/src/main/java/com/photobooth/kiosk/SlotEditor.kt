package com.photobooth.kiosk

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Edits normalized rectangular slots. Tap to select; drag to move; drag the lower-right
 * handle to resize. In add mode, draw a new rectangle. Rotation and ordering are exposed
 * through the parent dialog's buttons. Changes only affect the supplied draft JSONArray.
 */
class SlotEditor(ctx: Context, private val bmp: Bitmap, private val slots: JSONArray) : View(ctx) {
    var selectedIndex: Int = -1
        private set
    var addMode: Boolean = false
    var onChanged: (() -> Unit)? = null
    private var gesture = ""
    private var downX = 0f; private var downY = 0f
    private var lastX = 0f; private var lastY = 0f
    private var drawX = 0f; private var drawY = 0f
    private val framePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 3f }
    private val activeOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 5f }
    private val tint = Paint().apply { color = Color.argb(46, 255, 0, 0) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 29f; isFakeBoldText = true; setShadowLayer(2f, 1f, 1f, Color.BLACK) }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW }

    private fun imageBox(): RectF {
        if (width == 0 || height == 0) return RectF(0f, 0f, 1f, 1f)
        val scale = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val w = bmp.width * scale; val h = bmp.height * scale
        return RectF((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
    }
    private fun rect(s: JSONObject, b: RectF): RectF {
        val x = b.left + s.optDouble("x", 0.0).toFloat() * b.width()
        val y = b.top + s.optDouble("y", 0.0).toFloat() * b.height()
        return RectF(x, y, x + s.optDouble("w", .2).toFloat() * b.width(), y + s.optDouble("h", .2).toFloat() * b.height())
    }
    private fun changed() { invalidate(); onChanged?.invoke() }
    private fun current(): JSONObject? = if (selectedIndex in 0 until slots.length()) slots.optJSONObject(selectedIndex) else null
    fun selectedSource(): Int = current()?.optInt("source", -1)?.takeIf { it >= 0 } ?: selectedIndex
    fun rotateSelected(delta: Float) {
        current()?.let { s -> s.put("r", ((s.optDouble("r", 0.0) + delta + 360.0) % 360.0)); changed() }
    }
    fun setSource(delta: Int) {
        current()?.let { s -> s.put("source", (selectedSource() + delta).coerceIn(0, maxOf(0, slots.length() - 1))); changed() }
    }
    fun removeSelected() {
        if (selectedIndex in 0 until slots.length()) {
            slots.remove(selectedIndex)
            selectedIndex = if (slots.length() > 0) (selectedIndex - 1).coerceAtLeast(0) else -1
            changed()
        }
    }
    fun swapOrder(delta: Int) {
        val target = selectedIndex + delta
        if (selectedIndex !in 0 until slots.length() || target !in 0 until slots.length()) return
        val a = slots.getJSONObject(selectedIndex); val b = slots.getJSONObject(target)
        slots.put(selectedIndex, b); slots.put(target, a); selectedIndex = target; changed()
    }
    fun applyTwoColumnRepeat() {
        if (slots.length() < 2 || slots.length() % 2 != 0) return
        val ordered = (0 until slots.length()).sortedWith(compareBy<Int> { slots.getJSONObject(it).optDouble("y") }.thenBy { slots.getJSONObject(it).optDouble("x") })
        ordered.forEachIndexed { order, index -> slots.getJSONObject(index).put("source", order / 2) }
        changed()
    }
    fun setUniqueMapping() {
        for (i in 0 until slots.length()) slots.getJSONObject(i).put("source", i)
        changed()
    }
    override fun onDraw(c: Canvas) {
        c.drawColor(Color.LTGRAY)
        val b = imageBox(); c.drawBitmap(bmp, null, b, framePaint)
        for (i in 0 until slots.length()) {
            val s = slots.getJSONObject(i); val r = rect(s, b)
            c.save(); c.rotate(s.optDouble("r", 0.0).toFloat(), r.centerX(), r.centerY())
            c.drawRect(r, tint); c.drawRect(r, if (i == selectedIndex) activeOutline else outline)
            c.drawText("${i + 1} / P${s.optInt("source", -1).takeIf { it >= 0 }?.plus(1) ?: (i + 1)}", r.left + 5, r.top + 30, labelPaint)
            if (i == selectedIndex) c.drawCircle(r.right, r.bottom, 14f, handlePaint)
            c.restore()
        }
        if (gesture == "add") {
            val p = RectF(minOf(downX, drawX), minOf(downY, drawY), maxOf(downX, drawX), maxOf(downY, drawY))
            c.drawRect(p, activeOutline)
        }
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val b = imageBox()
        fun posX(x: Float) = ((x - b.left) / b.width()).coerceIn(0f, 1f)
        fun posY(y: Float) = ((y - b.top) / b.height()).coerceIn(0f, 1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y; drawX = e.x; drawY = e.y
                if (addMode) { gesture = "add" }
                else {
                    val hit = (slots.length() - 1 downTo 0).firstOrNull { i ->
                        val s = slots.getJSONObject(i); val r = rect(s, b)
                        val rad = Math.toRadians(-s.optDouble("r", 0.0))
                        val dx = e.x - r.centerX(); val dy = e.y - r.centerY()
                        val xx = r.centerX() + dx * kotlin.math.cos(rad) - dy * kotlin.math.sin(rad)
                        val yy = r.centerY() + dx * kotlin.math.sin(rad) + dy * kotlin.math.cos(rad)
                        r.contains(xx.toFloat(), yy.toFloat())
                    }
                    selectedIndex = hit ?: -1
                    if (hit != null) {
                        val r = rect(slots.getJSONObject(hit), b)
                        gesture = if (abs(e.x - r.right) < 34f && abs(e.y - r.bottom) < 34f) "resize" else "move"
                    } else gesture = "none"
                }
                changed()
            }
            MotionEvent.ACTION_MOVE -> {
                val s = current()
                if (s != null && (gesture == "move" || gesture == "resize")) {
                    val dx = (e.x - lastX) / b.width(); val dy = (e.y - lastY) / b.height()
                    if (gesture == "move") {
                        val w = s.optDouble("w"); val h = s.optDouble("h")
                        s.put("x", (s.optDouble("x") + dx).coerceIn(0.0, 1.0 - w))
                        s.put("y", (s.optDouble("y") + dy).coerceIn(0.0, 1.0 - h))
                    } else {
                        s.put("w", (s.optDouble("w") + dx).coerceIn(.03, 1.0 - s.optDouble("x")))
                        s.put("h", (s.optDouble("h") + dy).coerceIn(.03, 1.0 - s.optDouble("y")))
                    }
                    changed()
                }
                drawX = e.x; drawY = e.y; lastX = e.x; lastY = e.y; invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (gesture == "add") {
                    val x0 = minOf(posX(downX), posX(e.x)); val x1 = maxOf(posX(downX), posX(e.x))
                    val y0 = minOf(posY(downY), posY(e.y)); val y1 = maxOf(posY(downY), posY(e.y))
                    if (x1 - x0 > .03f && y1 - y0 > .03f) {
                        slots.put(JSONObject().put("x", x0.toDouble()).put("y", y0.toDouble()).put("w", (x1 - x0).toDouble()).put("h", (y1 - y0).toDouble()).put("r", 0).put("source", slots.length()))
                        selectedIndex = slots.length() - 1; addMode = false; changed()
                    }
                }
                gesture = ""; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { gesture = ""; invalidate() }
        }
        return true
    }
}
