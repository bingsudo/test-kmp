package com.example.kmptest.domain.algo

import com.example.kmptest.domain.model.Box
import com.example.kmptest.domain.model.ClockError
import com.example.kmptest.domain.model.ClockNotFoundException
import com.example.kmptest.domain.model.ClockRegion
import com.example.kmptest.domain.model.YuvFrame
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Finds the burned-in clock without OCR or font assumptions. Digit strokes flip between a dark
 * text box and bright text, so over many frames their pixels have a large spread, are dark most of
 * the time and bright now and then. Statistics are accumulated online, so no frames are stored.
 */
class ClockDetector(private val width: Int, private val height: Int) {
    private val band = (height * 0.22).toInt()
    private val size = 2 * band * width
    private val sum = DoubleArray(size)
    private val sumSq = DoubleArray(size)
    private val dark = IntArray(size)
    private val bright = IntArray(size)
    var samples = 0
        private set

    fun add(frame: YuvFrame) {
        require(frame.width == width && frame.height == height) { "frame size changed" }
        val d = frame.data
        for (r in 0 until band) {
            accumulate(d, r * width, r * width)
            accumulate(d, (height - band + r) * width, (band + r) * width)
        }
        samples++
    }

    private fun accumulate(d: ByteArray, src: Int, dst: Int) {
        for (x in 0 until width) {
            val v = d[src + x].toInt() and 0xFF
            sum[dst + x] += v.toDouble()
            sumSq[dst + x] += (v * v).toDouble()
            if (v < 50) dark[dst + x]++
            if (v > 180) bright[dst + x]++
        }
    }

    fun detect(): ClockRegion {
        if (samples < 10 || band < 4) throw ClockNotFoundException(ClockError.TOO_SHORT)
        val n = samples.toDouble()
        val mask = ByteArray(size)
        for (i in 0 until size) {
            val mean = sum[i] / n
            val std = sqrt(max(sumSq[i] / n - mean * mean, 0.0))
            if (std > 22 && dark[i] / n >= 0.5 && bright[i] / n >= 0.02) mask[i] = 1
        }
        val k = max(3, width / 200) or 1
        var best: Candidate? = null
        for (b in 0..1) {
            val offset = b * band * width
            val bandMask = mask.copyOfRange(offset, offset + band * width)
            val closed = Morphology.close(bandMask, width, band, k * 2 + 1)
            val yBase = if (b == 0) 0 else height - band
            for (c in Morphology.components(closed, width, band)) {
                if (c.h > height * 0.08 || c.w > width * 0.2 || c.h < 6) continue
                var count = 0
                for (y in c.y until c.y + c.h) for (x in c.x until c.x + c.w) count += bandMask[y * width + x]
                if (best == null || count > best.count) best = Candidate(count, c.x, yBase + c.y, c.w, c.h, bandMask, c.y)
            }
        }
        val cand = best ?: throw ClockNotFoundException(ClockError.NO_REGION)
        return split(cand)
    }

    private class Candidate(
        val count: Int, val x: Int, val y: Int, val w: Int, val h: Int,
        val bandMask: ByteArray, val bandY: Int,
    )

    private fun split(c: Candidate): ClockRegion {
        val cols = BooleanArray(c.w)
        var minRow = Int.MAX_VALUE
        var maxRow = -1
        for (yy in 0 until c.h) for (xx in 0 until c.w) {
            if (c.bandMask[(c.bandY + yy) * width + c.x + xx].toInt() != 0) {
                cols[xx] = true
                minRow = min(minRow, yy)
                maxRow = max(maxRow, yy)
            }
        }
        val runs = ArrayList<IntArray>()
        var start = -1
        for (i in 0..c.w) {
            val v = i < c.w && cols[i]
            if (v && start < 0) start = i
            if (!v && start >= 0) {
                runs.add(intArrayOf(start, i)); start = -1
            }
        }
        val merged = ArrayList<IntArray>()
        for (r in runs) {
            if (merged.isNotEmpty() && r[0] - merged.last()[1] <= 2) merged.last()[1] = r[1] else merged.add(r)
        }
        val digits = merged.filter { it[1] - it[0] >= 3 }
        if (digits.isEmpty() || maxRow < 0) throw ClockNotFoundException(ClockError.NO_DIGITS)
        val y0 = c.y + max(minRow - 2, 0)
        val y1 = c.y + min(maxRow + 3, c.h)
        fun box(r: IntArray) = Box(max(c.x + r[0] - 2, 0), y0, min(c.x + r[1] + 2, width), y1)
        val last = digits.last()
        val ones = box(last)
        val tens = if (digits.size >= 2) {
            val prev = digits[digits.size - 2]
            if (last[0] - prev[1] < 2.5 * (last[1] - last[0])) box(prev) else null
        } else null
        return ClockRegion(ones, tens)
    }
}

/** Binary-image helpers (rectangular structuring elements via prefix sums). */
internal object Morphology {
    class Component(val x: Int, val y: Int, val w: Int, val h: Int)

    fun close(src: ByteArray, w: Int, h: Int, ksize: Int): ByteArray = erode(dilate(src, w, h, ksize), w, h, ksize)

    private fun dilate(src: ByteArray, w: Int, h: Int, k: Int) = window(src, w, h, k, needAll = false)
    private fun erode(src: ByteArray, w: Int, h: Int, k: Int) = window(src, w, h, k, needAll = true)

    /** needAll=false: any pixel set in the window; true: every in-image pixel of the window set. */
    private fun window(src: ByteArray, w: Int, h: Int, k: Int, needAll: Boolean): ByteArray {
        val r = k / 2
        val tmp = ByteArray(w * h)
        val prefix = IntArray(max(w, h) + 1)
        for (y in 0 until h) {
            for (x in 0 until w) prefix[x + 1] = prefix[x] + src[y * w + x]
            for (x in 0 until w) {
                val a = max(x - r, 0)
                val b = min(x + r + 1, w)
                val c = prefix[b] - prefix[a]
                tmp[y * w + x] = if (if (needAll) c == b - a else c > 0) 1 else 0
            }
        }
        val out = ByteArray(w * h)
        for (x in 0 until w) {
            for (y in 0 until h) prefix[y + 1] = prefix[y] + tmp[y * w + x]
            for (y in 0 until h) {
                val a = max(y - r, 0)
                val b = min(y + r + 1, h)
                val c = prefix[b] - prefix[a]
                out[y * w + x] = if (if (needAll) c == b - a else c > 0) 1 else 0
            }
        }
        return out
    }

    /** 8-connected components of the set pixels. */
    fun components(m: ByteArray, w: Int, h: Int): List<Component> {
        val seen = BooleanArray(w * h)
        val out = ArrayList<Component>()
        val stack = IntArray(w * h)
        for (start in 0 until w * h) {
            if (m[start].toInt() == 0 || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var x0 = w
            var y0 = h
            var x1 = -1
            var y1 = -1
            while (sp > 0) {
                val p = stack[--sp]
                val px = p % w
                val py = p / w
                x0 = min(x0, px); x1 = max(x1, px); y0 = min(y0, py); y1 = max(y1, py)
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = px + dx
                    val ny = py + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val q = ny * w + nx
                    if (m[q].toInt() != 0 && !seen[q]) {
                        seen[q] = true
                        stack[sp++] = q
                    }
                }
            }
            out.add(Component(x0, y0, x1 - x0 + 1, y1 - y0 + 1))
        }
        return out
    }
}
