package com.example.kmptest.domain.algo

import com.example.kmptest.domain.model.Box
import com.example.kmptest.domain.model.YuvFrame
import kotlin.math.abs

/** Small luma thumbnail used to compare frames; the clock's row band can be blanked out. */
object Signature {
    const val W = 480
    const val H = 240

    fun of(frame: YuvFrame, blankRows: IntRange?): FloatArray {
        val out = FloatArray(W * H)
        val fw = frame.width
        val fh = frame.height
        val d = frame.data
        for (j in 0 until H) {
            val ys = j * fh / H
            val ye = maxOf((j + 1) * fh / H, ys + 1)
            for (i in 0 until W) {
                val xs = i * fw / W
                val xe = maxOf((i + 1) * fw / W, xs + 1)
                var s = 0
                var c = 0
                for (y in ys until ye) {
                    c += xe - xs
                    if (blankRows != null && y in blankRows) continue
                    val row = y * fw
                    for (x in xs until xe) s += d[row + x].toInt() and 0xFF
                }
                out[j * W + i] = s.toFloat() / c
            }
        }
        return out
    }

    /**
     * Mean absolute luma difference on the same scale as OpenCV's grayscale of the decoded picture:
     * that conversion stretches the limited video range (16..235) to 0..255, so raw luma differences
     * are multiplied by 255/219. The default duplicate threshold was tuned on that scale.
     */
    fun meanAbsDiff(a: FloatArray, b: FloatArray): Double {
        var s = 0.0
        for (i in a.indices) s += abs(a[i] - b[i])
        return s / a.size * LIMITED_RANGE_GAIN
    }

    private const val LIMITED_RANGE_GAIN = 255.0 / 219.0

    /** Rows around the clock that must not count as change. */
    fun clockBand(ones: Box, height: Int): IntRange {
        val pad = (ones.y1 - ones.y0) / 2
        return maxOf(ones.y0 - pad, 0) until minOf(ones.y1 + pad, height)
    }
}

/** Source frame -> output frame schedules. */
object Scheduler {
    /**
     * Source frame index for every output frame. Kept frames of each clock second are spread evenly
     * across that second; seconds without frames hold the last frame. [seconds] must not decrease.
     */
    fun clockSchedule(seconds: IntArray, keep: BooleanArray, fps: Int): IntArray {
        val t0 = seconds.first()
        val t1 = seconds.last()
        val bySecond = LinkedHashMap<Int, MutableList<Int>>()
        for (i in seconds.indices) if (keep[i]) bySecond.getOrPut(seconds[i]) { ArrayList() }.add(i)
        class Slot(val time: Double, val frame: Int)
        val slots = ArrayList<Slot>()
        for ((s, idx) in bySecond) idx.forEachIndexed { j, fi -> slots.add(Slot((s - t0) + j.toDouble() / idx.size, fi)) }
        slots.sortWith(compareBy({ it.time }, { it.frame }))
        val nOut = (t1 - t0 + 1) * fps
        val out = IntArray(nOut)
        var cur = slots.first().frame
        var p = 0
        for (o in 0 until nOut) {
            val t = o.toDouble() / fps
            while (p < slots.size && slots[p].time <= t + 1e-9) {
                cur = slots[p].frame
                p++
            }
            out[o] = cur
        }
        return out
    }

    fun plainSchedule(keep: BooleanArray): IntArray = keep.indices.filter { keep[it] }.toIntArray()
}
