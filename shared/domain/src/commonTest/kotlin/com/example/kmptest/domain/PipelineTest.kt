package com.example.kmptest.domain

import com.example.kmptest.domain.model.BackendId
import com.example.kmptest.domain.model.OutputTarget
import com.example.kmptest.domain.model.ProcessingMode
import com.example.kmptest.domain.model.ProcessingOptions
import com.example.kmptest.domain.model.VideoInfo
import com.example.kmptest.domain.model.VideoSource
import com.example.kmptest.domain.model.WriterConfig
import com.example.kmptest.domain.model.YuvFrame
import com.example.kmptest.domain.port.OutputStore
import com.example.kmptest.domain.port.VideoBackend
import com.example.kmptest.domain.port.VideoBackends
import com.example.kmptest.domain.port.VideoReader
import com.example.kmptest.domain.port.VideoWriter
import com.example.kmptest.domain.usecase.FixVideoUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.random.Random

/** Synthetic lag recording: a burned-in clock, a moving block, duplicated frames and a gap in the feed. */
class PipelineTest {
    private val w = 960
    private val h = 540
    private val glyphs = listOf(
        "01110100011001110101110011000101110", "00100011000010000100001000010001110",
        "01110100010000100010001000100011111", "11110000010000101110000010000111110",
        "00010001100101010010111110001000010", "11111100001111000001000011000101110",
        "00110010001000011110100011000101110", "11111000010001000100010000100001000",
        "01110100011000101110100011000101110", "01110100011000101111000010001001100",
    )

    private fun frame(clockSecond: Int, motion: Int, startSecondOfDay: Int): YuvFrame {
        val data = ByteArray(w * h * 3 / 2)
        val rnd = Random(motion)
        for (y in 0 until h) for (x in 0 until w) data[y * w + x] = (110 + rnd.nextInt(3)).toByte()
        // moving bright block (scene content)
        val bx = 40 + (motion * 7) % 800
        for (y in 200 until 300) for (x in bx until bx + 80) data[y * w + x] = 220.toByte()
        // clock bar: HH:MM:SS, 18x30 cells, pitch 24
        for (y in 3 until 40) for (x in 5 until 175) data[y * w + x] = 10
        val t = startSecondOfDay + clockSecond
        val digits = listOf(t / 3600 / 10, t / 3600 % 10, t / 60 % 60 / 10, t / 60 % 10, t % 60 / 10, t % 10)
        digits.forEachIndexed { k, d ->
            val x0 = 10 + k * 20 + (if (k >= 2) 6 else 0) + (if (k >= 4) 6 else 0)
            val g = glyphs[d]
            for (gy in 0 until 7) for (gx in 0 until 5) if (g[gy * 5 + gx] == '1') {
                for (y in 0 until 3) for (x in 0 until 3) data[(9 + gy * 3 + y) * w + x0 + gx * 3 + x] = 235.toByte()
            }
        }
        return YuvFrame(w, h, data)
    }

    /** seconds -> list of frames; unique frames per second given by [counts], each shown twice. */
    private fun recording(start: Int, counts: Map<Int, Int>, last: Int): List<YuvFrame> {
        val frames = ArrayList<YuvFrame>()
        var motion = 0
        for (s in 0..last) {
            val n = counts[s] ?: 0
            repeat(n) {
                val f = frame(s, motion++, start)
                frames.add(f)
                frames.add(f) // duplicate frame (same pixels)
            }
        }
        return frames
    }

    private class FakeBackend(private val frames: List<YuvFrame>, private val failAfter: Int = Int.MAX_VALUE) : VideoBackend {
        val written = ArrayList<YuvFrame>()
        var aborted = false
        override val id = BackendId.PLATFORM
        override val reader = object : VideoReader {
            override suspend fun probe(source: VideoSource) = VideoInfo(frames[0].width, frames[0].height, frames.size, 0)
            override fun frames(source: VideoSource): Flow<YuvFrame> = frames.asFlow()
        }
        override val writer = object : VideoWriter {
            override suspend fun open(localPath: String, config: WriterConfig) = object : VideoWriter.Session {
                override suspend fun write(frame: YuvFrame) {
                    if (written.size == failAfter) throw IllegalStateException("disk full")
                    written.add(frame)
                }
                override suspend fun finish() {}
                override fun abort() { aborted = true }
            }
        }
    }

    private class FakeStore : OutputStore {
        var published = false
        var discarded = false
        override suspend fun newTempFile() = "tmp"
        override suspend fun create(displayName: String, source: VideoSource) = OutputTarget("mem://$displayName", displayName)
        override suspend fun publish(localPath: String, target: OutputTarget) { published = true }
        override suspend fun discard(target: OutputTarget) { discarded = true }
    }

    private suspend fun run(frames: List<YuvFrame>, options: ProcessingOptions, backend: FakeBackend = FakeBackend(frames)): Triple<FakeBackend, FakeStore, FixVideoUseCase.Output> {
        val store = FakeStore()
        val backends = object : VideoBackends {
            override val available = listOf(BackendId.PLATFORM)
            override fun get(id: BackendId) = backend
        }
        val out = FixVideoUseCase(backends, store)(VideoSource("x", "lag.mp4"), options, "_fixed") {}
        return Triple(backend, store, out)
    }

    @Test
    fun clockModeKeepsRealTimeAndDropsDuplicates() = runTest {
        // 08:59:50 .. 09:00:19, seconds 12..21 have no frames at all (feed gap), tens digit and minute roll over
        val counts = (0..29).associateWith { s -> if (s in 12..21) 0 else 4 + s % 5 }
        val frames = recording(8 * 3600 + 59 * 60 + 50, counts, 29)
        val (backend, store, out) = run(frames, ProcessingOptions(ProcessingMode.CLOCK, fps = 20))
        assertEquals(frames.size, out.result.sourceFrames)
        assertEquals(counts.values.sum(), out.result.uniqueFrames)
        assertEquals(30, out.result.clockSpanSeconds)
        assertEquals(30 * 20, out.result.outputFrames)
        assertEquals(30 * 20, backend.written.size)
        assertTrue(store.published)
    }

    @Test
    fun plainModeJustDropsDuplicates() = runTest {
        val counts = (0..9).associateWith { 3 }
        val frames = recording(100, counts, 9)
        val (backend, _, out) = run(frames, ProcessingOptions(ProcessingMode.PLAIN, fps = 10))
        assertEquals(30, out.result.uniqueFrames)
        assertEquals(30, backend.written.size)
        assertEquals(null, out.result.clockSpanSeconds)
    }

    @Test
    fun clockModeWithoutClockFailsClearly() = runTest {
        val rnd = Random(1)
        val frames = (0 until 60).map { i ->
            val data = ByteArray(w * h * 3 / 2) { (100 + rnd.nextInt(4)).toByte() }
            for (y in 200 until 300) for (x in (i * 5) until (i * 5 + 80)) data[y * w + x] = 220.toByte()
            YuvFrame(w, h, data)
        }
        assertFailsWith<com.example.kmptest.domain.model.ClockNotFoundException> { run(frames, ProcessingOptions(ProcessingMode.CLOCK)) }
    }

    @Test
    fun writerFailureDiscardsTheOutput() = runTest {
        val frames = recording(100, (0..4).associateWith { 3 }, 4)
        val backend = FakeBackend(frames, failAfter = 5)
        val store = FakeStore()
        val backends = object : VideoBackends {
            override val available = listOf(BackendId.PLATFORM)
            override fun get(id: BackendId) = backend
        }
        val e = assertFailsWith<IllegalStateException> {
            FixVideoUseCase(backends, store)(VideoSource("x", "lag.mp4"), ProcessingOptions(ProcessingMode.PLAIN), "_fixed") {}
        }
        assertEquals("disk full", e.message)
        assertTrue(backend.aborted)
        assertTrue(store.discarded)
        assertTrue(!store.published)
    }

    @Test
    fun emptyVideoFails() = runTest {
        val backend = object : VideoBackend {
            override val id = BackendId.PLATFORM
            override val reader = object : VideoReader {
                override suspend fun probe(source: VideoSource) = VideoInfo(64, 32, 0, 0)
                override fun frames(source: VideoSource): Flow<YuvFrame> = emptyList<YuvFrame>().asFlow()
            }
            override val writer = FakeBackend(emptyList()).writer
        }
        val backends = object : VideoBackends {
            override val available = listOf(BackendId.PLATFORM)
            override fun get(id: BackendId) = backend
        }
        assertFailsWith<com.example.kmptest.domain.model.ProcessingException> {
            FixVideoUseCase(backends, FakeStore())(VideoSource("x", "empty.mp4"), ProcessingOptions(ProcessingMode.PLAIN), "_fixed") {}
        }
    }
}
