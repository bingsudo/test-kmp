package com.example.kmptest.data

import com.example.kmptest.data.backend.FfmpegCliBackend
import com.example.kmptest.domain.model.*
import com.example.kmptest.domain.port.OutputStore
import com.example.kmptest.domain.usecase.FixVideoUseCase
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Optional: runs every engine over real camera clips, including rotated ones. Skipped unless KMPTEST_SAMPLES names a
 * folder with land.mp4 (1920x1080), rot90.mp4 (1920x1080 with 90-degree rotation metadata) and port.mp4 (1080x1920).
 */
class EngineSmokeTest {
    private val dir = System.getenv("KMPTEST_SAMPLES")?.let(::File)

    private class Store : OutputStore {
        override suspend fun newTempFile() = File.createTempFile("tmp", ".mp4").absolutePath
        override suspend fun create(displayName: String, source: VideoSource) = OutputTarget(File.createTempFile("res", ".mp4").absolutePath, displayName)
        override suspend fun publish(localPath: String, target: OutputTarget) { File(localPath).copyTo(File(target.uri), true) }
        override suspend fun discard(target: OutputTarget) {}
    }

    @Test
    fun allEnginesKeepSizeAndOrientation() = runTest {
        val dir = dir ?: return@runTest
        val backends = desktopBackends()
        println("available engines: ${backends.available}")
        val expected = mapOf("land" to (1920 to 1080), "rot90" to (1080 to 1920), "port" to (1080 to 1920))
        for (id in backends.available) for ((name, size) in expected) {
            val source = VideoSource(File(dir, "$name.mp4").absolutePath, "$name.mp4")
            val t0 = System.currentTimeMillis()
            val out = FixVideoUseCase(backends, Store())(source, ProcessingOptions(ProcessingMode.PLAIN, backend = id), "_fixed") {}
            val info = FfmpegCliBackend().reader.probe(VideoSource(out.target.uri, "o"))
            println("$id $name -> ${info.width}x${info.height} unique=${out.result.uniqueFrames} in ${System.currentTimeMillis() - t0} ms")
            assertEquals(size, info.width to info.height, "$id $name")
        }
    }
}
