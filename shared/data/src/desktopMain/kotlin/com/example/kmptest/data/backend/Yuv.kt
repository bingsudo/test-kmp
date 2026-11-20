package com.example.kmptest.data.backend

import com.example.kmptest.domain.model.YuvFrame

/** BGR <-> planar I420 (BT.601, limited range: the same maths as OpenCV's COLOR_BGR2YUV_I420). */
internal object Yuv {
    /** [bgr] holds [height] rows of [stride] bytes, each starting with [width] BGR pixels. */
    fun fromBgr(bgr: ByteArray, width: Int, height: Int, stride: Int): YuvFrame {
        val w = width and 1.inv()
        val h = height and 1.inv()
        val out = ByteArray(w * h * 3 / 2)
        val uOff = w * h
        val vOff = uOff + (w / 2) * (h / 2)
        for (y in 0 until h) {
            val row = y * stride
            for (x in 0 until w) {
                val i = row + x * 3
                val b = bgr[i].toInt() and 0xFF
                val g = bgr[i + 1].toInt() and 0xFF
                val r = bgr[i + 2].toInt() and 0xFF
                out[y * w + x] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
                if (y % 2 == 0 && x % 2 == 0) {
                    val c = (y / 2) * (w / 2) + x / 2
                    out[uOff + c] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                    out[vOff + c] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                }
            }
        }
        return YuvFrame(w, h, out)
    }

    /** Tightly packed BGR (3 bytes per pixel, no row padding). */
    fun toBgr(frame: YuvFrame, out: ByteArray = ByteArray(frame.width * frame.height * 3)): ByteArray {
        val w = frame.width
        val h = frame.height
        val d = frame.data
        val uOff = w * h
        val vOff = uOff + (w / 2) * (h / 2)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = (y / 2) * (w / 2) + x / 2
                val yy = ((d[y * w + x].toInt() and 0xFF) - 16).coerceAtLeast(0) * 298
                val u = (d[uOff + c].toInt() and 0xFF) - 128
                val v = (d[vOff + c].toInt() and 0xFF) - 128
                val o = (y * w + x) * 3
                out[o] = ((yy + 516 * u + 128) shr 8).coerceIn(0, 255).toByte()
                out[o + 1] = ((yy - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255).toByte()
                out[o + 2] = ((yy + 409 * v + 128) shr 8).coerceIn(0, 255).toByte()
            }
        }
        return out
    }
}
