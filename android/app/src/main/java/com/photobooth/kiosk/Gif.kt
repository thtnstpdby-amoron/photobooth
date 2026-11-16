package com.photobooth.kiosk

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

// Small GIF89a writer (median-cut palette + LZW), used for the slideshow GIF. No library needed.
object Gif {
    fun encode(frames: List<Bitmap>, delayMs: Int): ByteArray {
        val w = frames[0].width; val h = frames[0].height
        val px = frames.map { b -> IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) } }
        val pal = palette(px)
        val lut = IntArray(32768) { -1 }
        fun nearest(c: Int): Int {
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            val key = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
            val cached = lut[key]; if (cached >= 0) return cached
            var best = 0; var bd = Int.MAX_VALUE
            for (i in pal.indices) {
                val p = pal[i]; val dr = r - ((p shr 16) and 255); val dg = g - ((p shr 8) and 255); val db = b - (p and 255)
                val d = dr * dr + dg * dg + db * db
                if (d < bd) { bd = d; best = i }
            }
            lut[key] = best; return best
        }
        val o = ByteArrayOutputStream()
        o.write("GIF89a".toByteArray()); le16(o, w); le16(o, h); o.write(0xF7); o.write(0); o.write(0)
        for (i in 0 until 256) { val c = if (i < pal.size) pal[i] else 0; o.write((c shr 16) and 255); o.write((c shr 8) and 255); o.write(c and 255) }
        o.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B)); o.write("NETSCAPE2.0".toByteArray()); o.write(byteArrayOf(3, 1, 0, 0, 0))
        val delay = (delayMs / 10).coerceAtLeast(2)
        for (f in px) {
            o.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0x04, (delay and 255).toByte(), (delay shr 8).toByte(), 0, 0))
            o.write(0x2C); le16(o, 0); le16(o, 0); le16(o, w); le16(o, h); o.write(0)
            val idx = ByteArray(w * h) { nearest(f[it]).toByte() }
            lzw(idx, o)
        }
        o.write(0x3B)
        return o.toByteArray()
    }
    private fun le16(o: ByteArrayOutputStream, v: Int) { o.write(v and 255); o.write((v shr 8) and 255) }

    private fun palette(px: List<IntArray>): IntArray {
        val total = px.sumOf { it.size }; val step = maxOf(1, total / 30000)
        val sample = ArrayList<Int>()
        for (f in px) { var i = 0; while (i < f.size) { sample.add(f[i] and 0xFFFFFF); i += step } }
        val boxes = ArrayList<List<Int>>(); boxes.add(sample)
        fun range(l: List<Int>, sh: Int): Int { var mn = 255; var mx = 0; for (c in l) { val v = (c shr sh) and 255; if (v < mn) mn = v; if (v > mx) mx = v }; return mx - mn }
        while (boxes.size < 256) {
            var bi = -1; var bs = 0; var bsh = 16
            for ((i, bx) in boxes.withIndex()) {
                if (bx.size < 2) continue
                for (sh in intArrayOf(16, 8, 0)) { val r = range(bx, sh); if (r > bs) { bs = r; bi = i; bsh = sh } }
            }
            if (bi < 0) break
            val sorted = boxes[bi].sortedBy { (it shr bsh) and 255 }; val mid = sorted.size / 2
            boxes[bi] = sorted.subList(0, mid); boxes.add(sorted.subList(mid, sorted.size))
        }
        return IntArray(boxes.size) { i ->
            val bx = boxes[i]; var r = 0L; var g = 0L; var b = 0L
            for (c in bx) { r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255 }
            val n = maxOf(1, bx.size); ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
        }
    }

    // GIF flavour of LZW with variable code size (9..12 bits), clear code when the table is full.
    private fun lzw(pix: ByteArray, o: ByteArrayOutputStream) {
        val data = ByteArrayOutputStream(); val clear = 256; val eoi = 257
        var codeSize = 9; var maxcode = 511; var free = 258; var clearFlag = false; var acc = 0; var bits = 0
        val dict = HashMap<Int, Int>()
        fun out(code: Int) {
            acc = acc or (code shl bits); bits += codeSize
            while (bits >= 8) { data.write(acc and 255); acc = acc ushr 8; bits -= 8 }
            if (free > maxcode || clearFlag) {
                if (clearFlag) { codeSize = 9; maxcode = 511; clearFlag = false }
                else { codeSize++; maxcode = if (codeSize == 12) 4096 else (1 shl codeSize) - 1 }
            }
        }
        out(clear)
        var ent = pix[0].toInt() and 255
        for (i in 1 until pix.size) {
            val c = pix[i].toInt() and 255; val key = (c shl 12) or ent
            val f = dict[key]
            if (f != null) { ent = f; continue }
            out(ent); ent = c
            if (free < 4096) { dict[key] = free; free++ } else { dict.clear(); free = 258; clearFlag = true; out(clear) }
        }
        out(ent); out(eoi)
        if (bits > 0) data.write(acc and 255)
        val arr = data.toByteArray(); o.write(8)
        var off = 0
        while (off < arr.size) { val n = minOf(255, arr.size - off); o.write(n); o.write(arr, off, n); off += n }
        o.write(0)
    }
}
