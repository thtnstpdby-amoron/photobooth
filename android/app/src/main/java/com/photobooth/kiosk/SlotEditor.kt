package com.photobooth.kiosk

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject

// Shows a frame image; dragging a finger draws a photo slot (stored as 0..1 fractions of the frame image).
class SlotEditor(ctx: Context, private val bmp: Bitmap, private val slots: JSONArray) : View(ctx) {
    private var sx = 0f; private var sy = 0f; private var cx = 0f; private var cy = 0f; private var drag = false
    private val line = Paint().apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 4f }
    private val fill = Paint().apply { color = Color.argb(60, 255, 0, 0) }
    private val label = Paint().apply { color = Color.RED; textSize = 44f; isFakeBoldText = true }
    private val pic = Paint(Paint.FILTER_BITMAP_FLAG)

    private fun box(): RectF {
        val sc = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height); val w = bmp.width * sc; val h = bmp.height * sc
        val l = (width - w) / 2; val t = (height - h) / 2; return RectF(l, t, l + w, t + h)
    }
    override fun onDraw(c: Canvas) {
        c.drawColor(Color.LTGRAY); val b = box(); c.drawBitmap(bmp, null, b, pic)
        for (i in 0 until slots.length()) {
            val s = slots.getJSONObject(i)
            val r = RectF(b.left + s.getDouble("x").toFloat() * b.width(), b.top + s.getDouble("y").toFloat() * b.height(), 0f, 0f)
            r.right = r.left + s.getDouble("w").toFloat() * b.width(); r.bottom = r.top + s.getDouble("h").toFloat() * b.height()
            c.drawRect(r, fill); c.drawRect(r, line); c.drawText("${i + 1}", r.left + 10, r.top + 46, label)
        }
        if (drag) c.drawRect(minOf(sx, cx), minOf(sy, cy), maxOf(sx, cx), maxOf(sy, cy), line)
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); sx = e.x; sy = e.y; cx = sx; cy = sy; drag = true }
            MotionEvent.ACTION_MOVE -> { cx = e.x; cy = e.y }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drag = false; val b = box()
                val x0 = ((minOf(sx, cx) - b.left) / b.width()).coerceIn(0f, 1f); val x1 = ((maxOf(sx, cx) - b.left) / b.width()).coerceIn(0f, 1f)
                val y0 = ((minOf(sy, cy) - b.top) / b.height()).coerceIn(0f, 1f); val y1 = ((maxOf(sy, cy) - b.top) / b.height()).coerceIn(0f, 1f)
                if (x1 - x0 > 0.03f && y1 - y0 > 0.03f) slots.put(JSONObject().put("x", x0.toDouble()).put("y", y0.toDouble()).put("w", (x1 - x0).toDouble()).put("h", (y1 - y0).toDouble()).put("r", 0))
            }
        }
        invalidate(); return true
    }
}
