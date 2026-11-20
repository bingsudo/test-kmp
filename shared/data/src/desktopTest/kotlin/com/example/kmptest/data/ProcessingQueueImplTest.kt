package com.example.kmptest.data

import com.example.kmptest.data.queue.ProcessingQueueImpl
import com.example.kmptest.data.queue.WorkKeeper
import com.example.kmptest.domain.model.BackendId
import com.example.kmptest.domain.model.ClockError
import com.example.kmptest.domain.model.ClockNotFoundException
import com.example.kmptest.domain.model.ItemStatus
import com.example.kmptest.domain.model.OutputTarget
import com.example.kmptest.domain.model.ProcessingOptions
import com.example.kmptest.domain.model.QueueState
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The queue runs on a real background dispatcher, so these tests wait (with a real timeout) for its state.
 * Sources: "ok*" decode three different frames, "broken" throws, "noclock" reports a missing clock, "slow" never ends.
 */
class ProcessingQueueImplTest {
    private class Backend : VideoBackend {
        override val id = BackendId.FFMPEG
        override val reader = object : VideoReader {
            override suspend fun probe(source: VideoSource) = VideoInfo(64, 32, 3, 0)
            override fun frames(source: VideoSource): Flow<YuvFrame> = flow {
                when (source.uri) {
                    "broken" -> throw IllegalStateException("corrupt file")
                    "noclock" -> throw ClockNotFoundException(ClockError.NO_REGION)
                    "slow" -> awaitCancellation()
                }
                repeat(3) { i -> emit(YuvFrame(64, 32, ByteArray(64 * 32 * 3 / 2) { (i * 80).toByte() })) }
            }
        }
        override val writer = object : VideoWriter {
            override suspend fun open(localPath: String, config: WriterConfig) = object : VideoWriter.Session {
                override suspend fun write(frame: YuvFrame) {}
                override suspend fun finish() {}
                override fun abort() {}
            }
        }
    }

    private val store = object : OutputStore {
        override suspend fun newTempFile() = "tmp"
        override suspend fun create(displayName: String, source: VideoSource) = OutputTarget("mem://$displayName", displayName)
        override suspend fun publish(localPath: String, target: OutputTarget) {}
        override suspend fun discard(target: OutputTarget) {}
    }

    private var keeperCalls = 0
    private val queue = ProcessingQueueImpl(
        FixVideoUseCase(
            object : VideoBackends {
                override val available = listOf(BackendId.FFMPEG)
                override fun get(id: BackendId): VideoBackend = Backend()
            },
            store,
        ),
        object : WorkKeeper { override fun workStarted() { keeperCalls++ } },
    )

    private fun src(uri: String) = VideoSource(uri, "$uri.mp4")
    private fun statuses() = queue.state.value.items.map { it.source.uri to it.status }
    private fun awaitState(test: (QueueState) -> Boolean) = runBlocking { withTimeout(10_000) { queue.state.first(test) } }
    private fun runAll() {
        queue.start(ProcessingOptions(backend = BackendId.FFMPEG), "_fixed")
        awaitState { !it.running }
    }

    @Test
    fun addIgnoresVideosAlreadyListed() {
        queue.add(listOf(src("ok1"), src("ok2")))
        queue.add(listOf(src("ok2"), src("ok3")))
        assertEquals(listOf("ok1", "ok2", "ok3"), queue.state.value.items.map { it.source.uri })
        assertTrue(queue.state.value.items.all { it.status == ItemStatus.QUEUED })
    }

    @Test
    fun runsEveryItemAndRecordsFailures() {
        queue.add(listOf(src("ok1"), src("broken"), src("noclock"), src("ok2")))
        runAll()
        assertEquals(
            listOf("ok1" to ItemStatus.DONE, "broken" to ItemStatus.FAILED, "noclock" to ItemStatus.FAILED, "ok2" to ItemStatus.DONE),
            statuses(),
        )
        val items = queue.state.value.items
        assertEquals("ok1_fixed.mp4", items[0].outputName)
        assertEquals(3, items[0].result?.uniqueFrames)
        assertEquals("corrupt file", items[1].errorMessage)
        assertEquals(ClockError.NO_REGION, items[2].clockError)
        assertEquals(1f, queue.state.value.overallFraction)
        assertEquals(1, keeperCalls)
    }

    @Test
    fun startAgainRetriesOnlyWhatIsNotDone() {
        queue.add(listOf(src("ok1"), src("broken")))
        runAll()
        val firstResult = queue.state.value.items[0].result
        runAll()
        assertEquals(listOf("ok1" to ItemStatus.DONE, "broken" to ItemStatus.FAILED), statuses())
        assertTrue(firstResult === queue.state.value.items[0].result, "the done item was not run again")
    }

    @Test
    fun cancelStopsTheRunningItemAndLeavesTheRestWaiting() {
        queue.add(listOf(src("ok1"), src("slow"), src("ok2")))
        queue.start(ProcessingOptions(backend = BackendId.FFMPEG), "_fixed")
        awaitState { s -> s.items.any { it.source.uri == "slow" && it.status == ItemStatus.RUNNING } }
        queue.cancel()
        awaitState { !it.running }
        assertEquals(listOf("ok1" to ItemStatus.DONE, "slow" to ItemStatus.CANCELLED, "ok2" to ItemStatus.QUEUED), statuses())
    }

    @Test
    fun listCannotChangeWhileRunning() {
        queue.add(listOf(src("slow"), src("ok1")))
        queue.start(ProcessingOptions(backend = BackendId.FFMPEG), "_fixed")
        awaitState { it.running }
        queue.remove(src("ok1"))
        queue.clear()
        queue.clearDone()
        assertEquals(2, queue.state.value.items.size)
        queue.cancel()
        awaitState { !it.running }
        queue.remove(src("ok1"))
        assertEquals(listOf("slow"), queue.state.value.items.map { it.source.uri })
        queue.clear()
        assertEquals(emptyList(), queue.state.value.items)
    }

    @Test
    fun clearDoneKeepsFailedAndWaitingItems() {
        queue.add(listOf(src("ok1"), src("broken")))
        runAll()
        queue.add(listOf(src("ok2")))
        queue.clearDone()
        assertEquals(listOf("broken" to ItemStatus.FAILED, "ok2" to ItemStatus.QUEUED), statuses())
    }
}
