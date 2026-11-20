package com.example.kmptest

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.kmptest.TestClips.PICTURES
import com.example.kmptest.TestClips.REPEATS
import com.example.kmptest.data.backend.androidBackends
import com.example.kmptest.domain.model.BackendId
import com.example.kmptest.domain.model.ProcessingMode
import com.example.kmptest.domain.model.ProcessingOptions
import com.example.kmptest.domain.model.VideoSource
import com.example.kmptest.domain.usecase.FixVideoUseCase
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every engine on this device, on a clip encoded here: decode, remove the repeats, encode, read the result back. */
@RunWith(AndroidJUnit4::class)
class EnginesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val backends = androidBackends(context)

    @After
    fun cleanUp() {
        context.cacheDir.listFiles()?.filter { it.name.endsWith(".mp4") }?.forEach(File::delete)
    }

    private fun frameCount(uri: String): Int {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(Uri.parse(uri).path)
            return r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)!!.toInt()
        } finally {
            r.release()
        }
    }

    @Test
    fun enginesMatchTheDevice() {
        val arm = Build.SUPPORTED_ABIS.any { it.startsWith("arm") }
        val expected = listOf(BackendId.PLATFORM, BackendId.OPENCV) + if (arm) listOf(BackendId.FFMPEG) else emptyList()
        assertEquals(expected, backends.available)
    }

    @Test
    fun deviceEncoderWritesEveryFrame() {
        val clip = TestClips.laggy(context)
        assertEquals(PICTURES * REPEATS, frameCount(clip.uri))
    }

    private fun checkEngine(id: BackendId) = runBlocking {
        if (id !in backends.available) return@runBlocking
        val clip = TestClips.laggy(context)
        val backend = backends.get(id)
        assertEquals(PICTURES * REPEATS, backend.reader.frames(clip).count(), "$id decodes every frame exactly once")

        val t0 = System.currentTimeMillis()
        val out = FixVideoUseCase(backends, TestClips.CacheStore(context))(
            clip, ProcessingOptions(ProcessingMode.PLAIN, backend = id), "_$id",
        ) {}
        Log.i("EnginesTest", "$id: ${out.result.sourceFrames} -> ${out.result.uniqueFrames} in ${System.currentTimeMillis() - t0} ms")
        assertEquals(PICTURES * REPEATS, out.result.sourceFrames, "$id source frames")
        assertEquals(PICTURES, out.result.uniqueFrames, "$id unique frames")

        val result = VideoSource(out.target.uri, out.target.displayName)
        val info = backend.reader.probe(result)
        assertEquals(TestClips.W to TestClips.H, info.width to info.height, "$id output size")
        assertEquals(PICTURES, frameCount(out.target.uri), "$id output frames")
        assertTrue(File(Uri.parse(out.target.uri).path!!).length() > 0)
    }

    @Test
    fun mediaCodec() = checkEngine(BackendId.PLATFORM)

    @Test
    fun openCv() = checkEngine(BackendId.OPENCV)

    @Test
    fun ffmpeg() = checkEngine(BackendId.FFMPEG)
}
