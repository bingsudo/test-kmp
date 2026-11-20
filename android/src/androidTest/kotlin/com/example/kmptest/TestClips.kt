package com.example.kmptest

import android.content.Context
import android.net.Uri
import com.example.kmptest.data.backend.PlatformBackend
import com.example.kmptest.domain.model.OutputTarget
import com.example.kmptest.domain.model.VideoSource
import com.example.kmptest.domain.model.WriterConfig
import com.example.kmptest.domain.model.YuvFrame
import com.example.kmptest.domain.port.OutputStore
import kotlinx.coroutines.runBlocking
import java.io.File

/** A laggy clip made on the device: [PICTURES] different pictures, each repeated [REPEATS] times, like a stalled feed. */
object TestClips {
    const val W = 320
    const val H = 240
    const val PICTURES = 12
    const val REPEATS = 3

    /** Mid-grey with a bright block that jumps far between pictures. */
    fun picture(n: Int): YuvFrame {
        val data = ByteArray(W * H * 3 / 2) { if (it < W * H) 90 else 128.toByte() }
        val x0 = n * 24
        for (y in 60 until 180) for (x in x0 until x0 + 40) data[y * W + x] = 220.toByte()
        return YuvFrame(W, H, data)
    }

    /** Encoded with the device's own H.264 encoder into the app cache; read through a file:// uri. */
    fun laggy(context: Context, name: String = "kmt_test_clip.mp4"): VideoSource {
        val file = File(context.cacheDir, name)
        runBlocking {
            val session = PlatformBackend(context).writer.open(file.path, WriterConfig(W, H, fps = 15, crf = 18))
            for (n in 0 until PICTURES) repeat(REPEATS) { session.write(picture(n)) }
            session.finish()
        }
        return VideoSource(Uri.fromFile(file).toString(), name)
    }

    /** Output files in the app cache, so tests leave nothing in the shared Movies folder. */
    class CacheStore(private val context: Context) : OutputStore {
        override suspend fun newTempFile(): String = File.createTempFile("tmp", ".mp4", context.cacheDir).path
        override suspend fun create(displayName: String, source: VideoSource): OutputTarget {
            val file = File(context.cacheDir, displayName).apply { delete() }
            return OutputTarget(Uri.fromFile(file).toString(), displayName)
        }
        override suspend fun publish(localPath: String, target: OutputTarget) {
            File(localPath).copyTo(File(Uri.parse(target.uri).path!!), overwrite = true)
            File(localPath).delete()
        }
        override suspend fun discard(target: OutputTarget) {
            File(Uri.parse(target.uri).path!!).delete()
        }
    }
}
