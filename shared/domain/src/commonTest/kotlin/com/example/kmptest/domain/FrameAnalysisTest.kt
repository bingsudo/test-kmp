package com.example.kmptest.domain

import com.example.kmptest.domain.algo.GlyphClusterer
import com.example.kmptest.domain.algo.Scheduler
import com.example.kmptest.domain.algo.Signature
import com.example.kmptest.domain.model.Box
import com.example.kmptest.domain.model.YuvFrame
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FrameAnalysisTest {
    private fun flat(w: Int, h: Int, luma: Int, paint: (x: Int, y: Int) -> Int? = { _, _ -> null }): YuvFrame {
        val data = ByteArray(w * h * 3 / 2) { 128.toByte() }
        for (y in 0 until h) for (x in 0 until w) data[y * w + x] = (paint(x, y) ?: luma).toByte()
        return YuvFrame(w, h, data)
    }

    @Test
    fun identicalFramesHaveNoDifference() {
        val f = flat(640, 360, 90) { x, y -> if (x in 100..200 && y in 50..90) 200 else null }
        assertEquals(0.0, Signature.meanAbsDiff(Signature.of(f, null), Signature.of(f, null)))
    }

    @Test
    fun differenceIsOnTheFullRangeScale() {
        // a uniform step of 21.9 luma levels in limited range is 25.5 levels in full range
        val a = Signature.of(flat(640, 360, 100), null)
        val b = Signature.of(flat(640, 360, 121), null)
        assertEquals(21.0 * 255 / 219, Signature.meanAbsDiff(a, b), 1e-4)
    }

    @Test
    fun frameSmallerThanTheSignatureStillWorks() {
        val a = Signature.of(flat(64, 32, 50), null)
        val b = Signature.of(flat(64, 32, 60), null)
        assertEquals(Signature.W * Signature.H, a.size)
        assertEquals(10.0 * 255 / 219, Signature.meanAbsDiff(a, b), 1e-4)
    }

    @Test
    fun blankedRowsDoNotCountAsChange() {
        val a = flat(640, 360, 80)
        val clockTicked = flat(640, 360, 80) { _, y -> if (y in 10..29) 235 else null }
        val band = Signature.clockBand(Box(0, 10, 40, 30), 360)
        assertEquals(0.0, Signature.meanAbsDiff(Signature.of(a, band), Signature.of(clockTicked, band)))
        assertNotEquals(0.0, Signature.meanAbsDiff(Signature.of(a, null), Signature.of(clockTicked, null)))
    }

    @Test
    fun clockBandPadsByHalfADigitAndStaysInsideTheFrame() {
        assertEquals(0 until 40, Signature.clockBand(Box(0, 10, 10, 30), 100))
        assertEquals(86 until 100, Signature.clockBand(Box(0, 90, 10, 98), 100))
    }

    @Test
    fun plainScheduleKeepsOnlyKeptFrames() {
        assertContentEquals(intArrayOf(0, 2, 3), Scheduler.plainSchedule(booleanArrayOf(true, false, true, true, false)))
    }

    @Test
    fun clockScheduleSpreadsEachSecondAndHoldsOverGaps() {
        // second 0 has four frames, second 1 two, second 2 none (feed gap), second 3 one
        val seconds = intArrayOf(0, 0, 0, 0, 1, 1, 3)
        val keep = BooleanArray(seconds.size) { true }
        assertContentEquals(
            intArrayOf(0, 1, 2, 3, 4, 4, 5, 5, 5, 5, 5, 5, 6, 6, 6, 6),
            Scheduler.clockSchedule(seconds, keep, fps = 4),
        )
    }

    @Test
    fun clockScheduleSkipsDuplicates() {
        val seconds = intArrayOf(5, 5, 5, 5, 6, 6)
        val keep = booleanArrayOf(true, false, true, false, true, false)
        assertContentEquals(intArrayOf(0, 0, 2, 2, 4, 4, 4, 4), Scheduler.clockSchedule(seconds, keep, fps = 4))
    }

    @Test
    fun glyphClustererGroupsSimilarCells() {
        val g = GlyphClusterer()
        val zero = FloatArray(10) { 0f }
        val nearZero = FloatArray(10) { 10f }
        val bright = FloatArray(10) { 200f }
        assertEquals(0, g.idOf(zero))
        assertEquals(0, g.idOf(nearZero))
        assertEquals(1, g.idOf(bright))
        assertEquals(0, g.idOf(zero))
        assertEquals(setOf(0), g.reliable(min = 3))
        assertEquals(setOf(0, 1), g.reliable(min = 1))
    }

    @Test
    fun glyphCellAveragesTheBox() {
        val f = flat(100, 60, 0) { x, _ -> if (x < 20) 140 else null }
        val cell = GlyphClusterer.cell(f, Box(10, 0, 30, 18))  // left half bright, right half dark
        assertEquals(GlyphClusterer.CELL_W * GlyphClusterer.CELL_H, cell.size)
        assertEquals(140f, cell[0])
        assertEquals(0f, cell[GlyphClusterer.CELL_W - 1])
    }
}
