package com.example.kmptest.data.backend

import com.example.kmptest.domain.model.BackendId
import com.example.kmptest.domain.model.ProcessingException
import com.example.kmptest.domain.model.VideoInfo
import com.example.kmptest.domain.model.VideoSource
import com.example.kmptest.domain.model.WriterConfig
import com.example.kmptest.domain.model.rotated
import com.example.kmptest.domain.model.YuvFrame
import com.example.kmptest.domain.port.VideoBackend
import com.example.kmptest.domain.port.VideoReader
import com.example.kmptest.domain.port.VideoWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Frame
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

/** JavaCV's in-process FFmpeg (libavcodec through JNI). */
class JavaCvFfmpegBackend : VideoBackend {
    override val id = BackendId.FFMPEG
    override val reader: VideoReader = JcvReader()
    override val writer: VideoWriter = JcvWriter()

    companion object {
        /** True when the native libraries load on this machine. */
        fun isAvailable(): Boolean = runCatching { avutil.av_version_info().string; true }.getOrDefault(false)
    }
}

private class JcvReader : VideoReader {
    private fun open(source: VideoSource): FFmpegFrameGrabber =
        FFmpegFrameGrabber(source.uri).apply {
            pixelFormat = avutil.AV_PIX_FMT_BGR24
            try {
                start()
            } catch (e: Exception) {
                throw ProcessingException("Cannot read ${source.name}: ${e.message}", e)
            }
        }

    /** The grabber reports the coded size; a 90 / 270 degree display matrix means the upright size is swapped. */
    private fun sideways(g: FFmpegFrameGrabber): Boolean = abs(g.displayRotation.toInt()) % 180 == 90

    override suspend fun probe(source: VideoSource): VideoInfo = withContext(Dispatchers.IO) {
        open(source).use { g ->
            val sw = sideways(g)
            VideoInfo(
                if (sw) g.imageHeight else g.imageWidth, if (sw) g.imageWidth else g.imageHeight,
                g.lengthInVideoFrames, g.lengthInTime / 1000,
            )
        }
    }

    override fun frames(source: VideoSource): Flow<YuvFrame> = flow {
        val g = open(source)
        try {
            var bgr = ByteArray(0)
            // JavaCV hands over the coded (unrotated) frames; the display matrix says how to turn them upright
            val clockwise = ((-g.displayRotation.roundToInt() % 360) + 360) % 360
            while (true) {
                currentCoroutineContext().ensureActive()
                val f = g.grabImage() ?: break
                val buffer = f.image?.firstOrNull() as? ByteBuffer ?: continue
                val stride = f.imageStride
                if (bgr.size != stride * f.imageHeight) bgr = ByteArray(stride * f.imageHeight)
                buffer.rewind()
                buffer.get(bgr, 0, bgr.size)
                emit(Yuv.fromBgr(bgr, f.imageWidth, f.imageHeight, stride).rotated(clockwise))
            }
        } finally {
            runCatching { g.stop() }
            runCatching { g.release() }
        }
    }.flowOn(Dispatchers.Default).buffer(2)

    private inline fun <T> FFmpegFrameGrabber.use(block: (FFmpegFrameGrabber) -> T): T =
        try {
            block(this)
        } finally {
            runCatching { stop() }
            runCatching { release() }
        }
}

private class JcvWriter : VideoWriter {
    override suspend fun open(localPath: String, config: WriterConfig): VideoWriter.Session = withContext(Dispatchers.Default) {
        val rec = FFmpegFrameRecorder(localPath, config.width, config.height, 0).apply {
            format = "mp4"
            pixelFormat = avutil.AV_PIX_FMT_YUV420P
            frameRate = config.fps.toDouble()
            videoQuality = 0.0
            setVideoOption("crf", config.crf.toString())
            setVideoOption("preset", "medium")
            setOption("movflags", "+faststart")
        }
        val names = listOf("libx264", "libopenh264", "h264_mf", "h264_videotoolbox", "mpeg4")
        var last: Exception? = null
        for (name in names) {
            try {
                rec.setVideoCodecName(name)
                if (name != "libx264") rec.videoBitrate = 8_000_000
                rec.start()
                return@withContext JcvSession(rec, config)
            } catch (e: Exception) {
                last = e
                runCatching { rec.release() }
            }
        }
        throw ProcessingException("JavaCV found no H.264 encoder: ${last?.message}", last)
    }
}

private class JcvSession(private val rec: FFmpegFrameRecorder, config: WriterConfig) : VideoWriter.Session {
    private val frame = Frame(config.width, config.height, Frame.DEPTH_UBYTE, 3)
    private val bgr = ByteArray(config.width * config.height * 3)

    override suspend fun write(frame: YuvFrame) = withContext(Dispatchers.Default) {
        Yuv.toBgr(frame, bgr)
        val buffer = this@JcvSession.frame.image[0] as ByteBuffer
        buffer.clear()
        val stride = this@JcvSession.frame.imageStride
        if (stride == frame.width * 3) {
            buffer.put(bgr)
        } else {
            for (y in 0 until frame.height) {
                buffer.position(y * stride)
                buffer.put(bgr, y * frame.width * 3, frame.width * 3)
            }
        }
        buffer.rewind()
        rec.record(this@JcvSession.frame)
    }

    override suspend fun finish() = withContext(Dispatchers.Default) {
        rec.stop()
        rec.release()
    }

    override fun abort() {
        runCatching { rec.stop() }
        runCatching { rec.release() }
    }
}
