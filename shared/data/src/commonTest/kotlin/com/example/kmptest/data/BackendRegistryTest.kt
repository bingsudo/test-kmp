package com.example.kmptest.data

import com.example.kmptest.data.backend.BackendRegistry
import com.example.kmptest.domain.model.BackendId
import com.example.kmptest.domain.port.VideoBackend
import com.example.kmptest.domain.port.VideoReader
import com.example.kmptest.domain.port.VideoWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class BackendRegistryTest {
    private class Dummy(override val id: BackendId) : VideoBackend {
        override val reader: VideoReader get() = error("unused")
        override val writer: VideoWriter get() = error("unused")
    }

    private val created = ArrayList<BackendId>()

    private fun candidate(id: BackendId, available: () -> Boolean) =
        BackendRegistry.Candidate(id, available) { created += id; Dummy(id) }

    @Test
    fun offersOnlyEnginesThatWork() {
        val registry = BackendRegistry(
            listOf(
                candidate(BackendId.FFMPEG_CLI) { false },
                candidate(BackendId.FFMPEG) { true },
                candidate(BackendId.OPENCV) { throw UnsatisfiedLinkError("no native library") },
                candidate(BackendId.PLATFORM) { true },
            ),
        )
        assertEquals(listOf(BackendId.FFMPEG, BackendId.PLATFORM), registry.available)
        assertEquals(emptyList(), created, "nothing is created before it is used")
    }

    @Test
    fun unavailableChoiceFallsBackToTheFirstEngine() {
        val registry = BackendRegistry(listOf(candidate(BackendId.FFMPEG) { true }, candidate(BackendId.OPENCV) { false }))
        assertEquals(BackendId.FFMPEG, registry.get(BackendId.OPENCV).id)
    }

    @Test
    fun eachEngineIsCreatedOnce() {
        val registry = BackendRegistry(listOf(candidate(BackendId.FFMPEG) { true }))
        assertSame(registry.get(BackendId.FFMPEG), registry.get(BackendId.FFMPEG))
        assertEquals(listOf(BackendId.FFMPEG), created)
    }
}
