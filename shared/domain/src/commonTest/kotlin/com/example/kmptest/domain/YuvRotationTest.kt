package com.example.kmptest.domain

import com.example.kmptest.domain.model.YuvFrame
import com.example.kmptest.domain.model.rotated
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame

class YuvRotationTest {
    /** 4x2 frame whose every byte (luma and chroma) is distinct. */
    private val frame = YuvFrame(4, 2, ByteArray(12) { it.toByte() })

    @Test
    fun zeroDegreesIsTheSameFrame() = assertSame(frame, frame.rotated(0))

    @Test
    fun ninetyDegreesTurnsClockwise() {
        val r = frame.rotated(90)
        assertEquals(2, r.width)
        assertEquals(4, r.height)
        // luma 0 1 2 3 / 4 5 6 7 turned clockwise: the left column read bottom-up becomes the top row
        assertContentEquals(byteArrayOf(4, 0, 5, 1, 6, 2, 7, 3), r.data.copyOf(8))
        // chroma planes are 2x1 each and turn the same way into 1x2
        assertContentEquals(byteArrayOf(8, 9, 10, 11), r.data.copyOfRange(8, 12))
    }

    @Test
    fun oneEightyDegreesReversesEveryPlane() {
        val r = frame.rotated(180)
        assertEquals(4, r.width)
        assertContentEquals(byteArrayOf(7, 6, 5, 4, 3, 2, 1, 0, 9, 8, 11, 10), r.data)
    }

    @Test
    fun opposingTurnsCancel() {
        val big = YuvFrame(6, 4, ByteArray(36) { (it * 7).toByte() })
        assertContentEquals(big.data, big.rotated(90).rotated(270).data)
        assertContentEquals(big.data, big.rotated(180).rotated(180).data)
        assertContentEquals(big.rotated(270).data, big.rotated(90).rotated(180).data)
    }
}
