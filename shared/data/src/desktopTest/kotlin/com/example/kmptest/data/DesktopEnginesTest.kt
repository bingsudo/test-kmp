package com.example.kmptest.data

import com.example.kmptest.data.backend.JavaCvFfmpegBackend
import com.example.kmptest.domain.model.BackendId
import com.example.kmptest.domain.model.OutputTarget
import com.example.kmptest.domain.model.ProcessingMode
import com.example.kmptest.domain.model.ProcessingOptions
import com.example.kmptest.domain.model.VideoSource
import com.example.kmptest.domain.model.WriterConfig
import com.example.kmptest.domain.model.YuvFrame
import com.example.kmptest.domain.port.OutputStore
import com.example.kmptest.domain.usecase.FixVideoUseCase
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real encode/decode with every engine available on this computer: a clip of 12 different pictures, each shown
 * three times (like a stalled camera feed), must come out as exactly those 12 pictures at the same size.
 */
class DesktopEnginesTest {
    private val dir: File = Files.createTempDirectory("kmt-engines").toFile()
    private val w = 320
    private val h = 240
    private val pictures = 12
    private val repeats = 3

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** Picture [n]: a mid-grey background with a bright block that moves a long way between pictures. */
    private fun picture(n: Int): YuvFrame {
        val data = ByteArray(w * h * 3 / 2) { if (it < w * h) 90 else 128.toByte() }
        val x0 = n * 24
        for (y in 60 until 180) for (x in x0 until x0 + 40) data[y * w + x] = 220.toByte()
        return YuvFrame(w, h, data)
    }

    private fun laggyClip(): File {
        val file = File(dir, "laggy.mp4")
        runBlocking {
            val session = JavaCvFfmpegBackend().writer.open(file.path, WriterConfig(w, h, fps = 15, crf = 18))
            for (n in 0 until pictures) repeat(repeats) { session.write(picture(n)) }
            session.finish()
        }
        return file
    }

    private inner class Store : OutputStore {
        override suspend fun newTempFile() = File.createTempFile("tmp", ".mp4", dir).path
        override suspend fun create(displayName: String, source: VideoSource) = OutputTarget(File(dir, displayName).path, displayName)
        override suspend fun publish(localPath: String, target: OutputTarget) {
            File(localPath).renameTo(File(target.uri))
        }
        override suspend fun discard(target: OutputTarget) {
            File(target.uri).delete()
        }
    }

    @Test
    fun everyEngineRemovesTheRepeatedFrames() = runBlocking {
        val backends = desktopBackends()
        println("engines available: ${backends.available}")
        assertTrue(BackendId.FFMPEG in backends.available, "the bundled FFmpeg must always load")
        val clip = laggyClip()
        val source = VideoSource(clip.path, clip.name)
        for (id in backends.available) {
            val backend = backends.get(id)
            assertEquals(pictures * repeats, backend.reader.frames(source).count(), "$id decodes every frame")
            val out = FixVideoUseCase(backends, Store())(source, ProcessingOptions(ProcessingMode.PLAIN, backend = id), "_$id") {}
            assertEquals(pictures * repeats, out.result.sourceFrames, "$id source frames")
            assertEquals(pictures, out.result.uniqueFrames, "$id unique frames")
            val info = backend.reader.probe(VideoSource(out.target.uri, "out"))
            assertEquals(w to h, info.width to info.height, "$id output size")
            assertEquals(pictures, backend.reader.frames(VideoSource(out.target.uri, "out")).count(), "$id output frames")
        }
    }
}
