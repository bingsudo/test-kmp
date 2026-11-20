package com.example.kmptest.domain

import com.example.kmptest.domain.model.ItemStatus
import com.example.kmptest.domain.model.JobProgress
import com.example.kmptest.domain.model.ProcessingOptions
import com.example.kmptest.domain.model.QueueItem
import com.example.kmptest.domain.model.QueueState
import com.example.kmptest.domain.model.Stage
import com.example.kmptest.domain.model.VideoSource
import com.example.kmptest.domain.model.YuvFrame
import com.example.kmptest.domain.usecase.FixVideoUseCase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModelsTest {
    @Test
    fun optionsRejectOutOfRangeValues() {
        ProcessingOptions(fps = 1, crf = 0, duplicateThreshold = 0.0)
        ProcessingOptions(fps = 120, crf = 51)
        assertFailsWith<IllegalArgumentException> { ProcessingOptions(fps = 0) }
        assertFailsWith<IllegalArgumentException> { ProcessingOptions(fps = 121) }
        assertFailsWith<IllegalArgumentException> { ProcessingOptions(crf = 52) }
        assertFailsWith<IllegalArgumentException> { ProcessingOptions(duplicateThreshold = -0.1) }
    }

    @Test
    fun frameNeedsAFullI420Buffer() {
        YuvFrame(4, 2, ByteArray(12))
        assertFailsWith<IllegalArgumentException> { YuvFrame(4, 2, ByteArray(11)) }
    }

    @Test
    fun progressWeightsTheThreeStages() {
        assertEquals(0f, JobProgress(Stage.DETECT, 0f).overall)
        assertEquals(0.05f, JobProgress(Stage.DETECT, 1f).overall, 1e-6f)
        assertEquals(0.05f + 0.45f * 0.5f, JobProgress(Stage.ANALYZE, 0.5f).overall, 1e-6f)
        assertEquals(1f, JobProgress(Stage.RENDER, 1f).overall, 1e-6f)
    }

    @Test
    fun queueFractionCountsFinishedItemsAsWhole() {
        fun item(status: ItemStatus, progress: JobProgress? = null) = QueueItem(VideoSource("u$status", "n"), status, progress)
        assertEquals(0f, QueueState().overallFraction)
        val state = QueueState(
            listOf(
                item(ItemStatus.DONE),
                item(ItemStatus.FAILED),
                item(ItemStatus.QUEUED),
                item(ItemStatus.RUNNING, JobProgress(Stage.RENDER, 0f)),  // halfway overall
            ),
        )
        assertEquals((1f + 1f + 0f + 0.5f) / 4, state.overallFraction, 1e-6f)
    }

    @Test
    fun outputNameReplacesTheExtensionAndNeverReusesTheSourceName() {
        assertEquals("clip_fixed.mp4", FixVideoUseCase.outputName("clip.mov", "_fixed"))
        assertEquals("a.b_x.mp4", FixVideoUseCase.outputName("a.b.mp4", "_x"))
        assertEquals("noext_x.mp4", FixVideoUseCase.outputName("noext", "_x"))
        assertEquals("clip_fixed.mp4", FixVideoUseCase.outputName("clip.mp4", ""))
    }
}
