package com.example.kmptest.domain

import com.example.kmptest.domain.algo.ClockDecoder
import com.example.kmptest.domain.model.ClockError
import com.example.kmptest.domain.model.ClockNotFoundException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The decoder only sees glyph ids, numbered in order of first appearance and unrelated to the digit they show.
 * These tests give it such ids for known clock times and check that it recovers the elapsed seconds.
 */
class ClockDecoderTest {
    private val onesGlyph = intArrayOf(7, 3, 9, 0, 5, 1, 8, 2, 6, 4)  // digit -> id
    private val tensGlyph = intArrayOf(4, 2, 5, 0, 3, 1)

    /** Clock times (seconds of the minute, may pass 59), [perSecond] frames each. */
    private fun clip(times: List<Int>, perSecond: Int = 3): List<Int> = times.flatMap { t -> List(perSecond) { t } }

    private fun ones(t: List<Int>) = IntArray(t.size) { onesGlyph[t[it] % 10] }
    private fun tens(t: List<Int>) = IntArray(t.size) { tensGlyph[t[it] % 60 / 10] }
    private fun elapsed(t: List<Int>) = IntArray(t.size) { t[it] - t[0] }

    @Test
    fun fullMinuteWithWrapAround() {
        val t = clip((35..110).toList())  // 00:35 .. 01:50, all six tens glyphs appear
        val d = ClockDecoder.decode(ones(t), tens(t))
        assertTrue(d.usedTens)
        assertContentEquals(elapsed(t), d.seconds)
    }

    @Test
    fun longFeedGapIsBridgedByTheTensDigit() {
        val times = (0..95).filter { it !in 20..44 }  // 25 s without any frame
        val t = clip(times)
        assertContentEquals(elapsed(t), ClockDecoder.decode(ones(t), tens(t)).seconds)
    }

    @Test
    fun shortClipLearnsALinearTensOrder() {
        val t = clip((5..33).toList())  // tens 0..3 only, never wraps
        val d = ClockDecoder.decode(ones(t), tens(t))
        assertTrue(d.usedTens)
        assertContentEquals(elapsed(t), d.seconds)
    }

    @Test
    fun onesDigitAloneHandlesShortGaps() {
        val t = clip((0..40).filter { it !in 12..16 })  // 5 s gap
        val d = ClockDecoder.decode(ones(t), null)
        assertFalse(d.usedTens)
        assertContentEquals(elapsed(t), d.seconds)
    }

    @Test
    fun unreadableFramesKeepThePreviousSecond() {
        val t = clip((0..30).toList())
        // frames 42 and 45 are the first frames of seconds 14 and 15
        val o = ones(t).also { it[42] = -1 }
        val tn = tens(t).also { it[45] = -1 }
        val seconds = ClockDecoder.decode(o, tn).seconds
        val expected = elapsed(t).also { it[42] = it[41]; it[45] = it[44] }
        assertContentEquals(expected, seconds)
        assertEquals(t.last() - t.first(), seconds.last())
    }

    @Test
    fun frozenClockHasNoCycle() {
        val e = assertFailsWith<ClockNotFoundException> { ClockDecoder.decode(IntArray(50) { 3 }, null) }
        assertEquals(ClockError.NO_CYCLE, e.error)
    }

    @Test
    fun learnCycleFindsTheSuccessorOrder() {
        val ids = IntArray(40) { intArrayOf(2, 0, 1)[it / 2 % 3] }
        val cycle = ClockDecoder.learnCycle(ids, 3, null)!!
        // any rotation of 2 -> 0 -> 1 is right; the decoder starts it at the smallest id
        assertEquals(listOf(0, 1, 2), cycle)
        assertEquals(null, ClockDecoder.learnCycle(ids, 4, null))
    }
}
