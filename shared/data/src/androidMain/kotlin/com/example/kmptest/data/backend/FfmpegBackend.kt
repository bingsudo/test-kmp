package com.example.kmptest.data.backend

import android.content.Context
import android.net.Uri
import com.example.kmptest.domain.model.BackendId
import com.example.kmptest.domain.model.ProcessingException
import com.example.kmptest.domain.model.VideoInfo
import com.example.kmptest.domain.model.VideoSource
import com.example.kmptest.domain.model.WriterConfig
import com.example.kmptest.domain.model.YuvFrame
import com.example.kmptest.domain.port.VideoBackend
import com.example.kmptest.domain.port.VideoReader
import com.example.kmptest.domain.port.VideoWriter
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/** FFmpeg (FFmpegKit): decode to / encode from raw I420 through named pipes. */
class FfmpegBackend(context: Context) : VideoBackend {
    override val id = BackendId.FFMPEG
    override val reader: VideoReader = FfmpegReader(context.applicationContext)
    override val writer: VideoWriter = FfmpegWriter(context.applicationContext)
}

/**
 * FFmpegKit ships native code for ARM only (arm64-v8a, armeabi-v7a). On anything else its static
 * initialiser fails; say so plainly instead of surfacing a native-loader error.
 */
private fun <T> ffmpegAvailable(block: () -> T): T = try {
    block()
} catch (e: LinkageError) {
    throw ProcessingException("The FFmpeg engine is not available on this device (it needs an ARM CPU). Choose another engine in Settings.", e)
} catch (e: IllegalStateException) {
    throw ProcessingException("The FFmpeg engine is not available on this device (it needs an ARM CPU). Choose another engine in Settings.", e)
}

private fun InputStream.readFully(buf: ByteArray): Boolean {
    var n = 0
    while (n < buf.size) {
        val r = read(buf, n, buf.size - n)
        if (r < 0) {
            if (n == 0) return false
            throw EOFException("truncated frame")
        }
        n += r
    }
    return true
}

private class FfmpegReader(private val context: Context) : VideoReader {
    override suspend fun probe(source: VideoSource): VideoInfo = MediaSupport.probe(context, source)

    override fun frames(source: VideoSource): Flow<YuvFrame> = flow {
        val info = MediaSupport.probe(context, source)
        if (info.width <= 0 || info.height <= 0) throw ProcessingException("Unknown video size for ${source.name}")
        val frameBytes = info.width * info.height * 3 / 2
        val pipe = ffmpegAvailable { FFmpegKitConfig.registerNewFFmpegPipe(context) } ?: throw ProcessingException("Cannot create FFmpeg pipe")
        val failed = CompletableDeferred<String?>()
        val session: FFmpegSession = FFmpegKit.executeWithArgumentsAsync(
            arrayOf(
                "-y", "-hide_banner", "-loglevel", "error", "-i", FFmpegKitConfig.getSafParameterForRead(context, Uri.parse(source.uri)),
                "-map", "0:v:0", "-an", "-sn", "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", "yuv420p", pipe,
            ),
        ) { s ->
            if (!ReturnCode.isSuccess(s.returnCode)) failed.complete(s.allLogsAsString ?: "ffmpeg failed")
            // an ffmpeg that died before opening the pipe would leave the reader blocked in open(): unblock it
            runCatching { FileOutputStream(pipe).close() }
        }
        try {
            FileInputStream(pipe).use { input ->
                val buf = ByteArray(frameBytes)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (!input.readFully(buf)) break
                    emit(YuvFrame(info.width, info.height, buf.copyOf()))
                }
            }
            if (failed.isCompleted && failed.await() != null) throw ProcessingException("FFmpeg: " + failed.await())
        } finally {
            FFmpegKit.cancel(session.sessionId)
            FFmpegKitConfig.closeFFmpegPipe(pipe)
        }
    }.flowOn(Dispatchers.IO).buffer(2)
}

private class FfmpegWriter(private val context: Context) : VideoWriter {
    override suspend fun open(localPath: String, config: WriterConfig): VideoWriter.Session = withContext(Dispatchers.IO) {
        val pipe = ffmpegAvailable { FFmpegKitConfig.registerNewFFmpegPipe(context) } ?: throw ProcessingException("Cannot create FFmpeg pipe")
        val done = CompletableDeferred<FFmpegSession>()
        val encoder = encoderArgs(config)
        val args = arrayOf(
            "-y", "-hide_banner", "-loglevel", "error",
            "-f", "rawvideo", "-pix_fmt", "yuv420p", "-s", "${config.width}x${config.height}", "-framerate", "${config.fps}",
            "-i", pipe, *encoder, "-pix_fmt", "yuv420p", "-movflags", "+faststart", localPath,
        )
        FFmpegKit.executeWithArgumentsAsync(args) { s ->
            done.complete(s)
            runCatching { FileInputStream(pipe).close() } // unblock a writer waiting on a dead ffmpeg
        }
        FfmpegSession(pipe, done, config)
    }

    /** Best H.264 encoder this FFmpegKit build has: libx264, then the device's MediaCodec, then MPEG-4. */
    private fun encoderArgs(config: WriterConfig): Array<String> {
        val list = cachedEncoders ?: FFmpegKit.execute("-hide_banner -encoders").output.orEmpty().also { cachedEncoders = it }
        return when {
            " libx264 " in list -> arrayOf("-c:v", "libx264", "-crf", "${config.crf}", "-preset", "veryfast")
            " h264_mediacodec " in list -> arrayOf(
                "-c:v", "h264_mediacodec", "-b:v", "${MediaSupport.bitrateFor(config.width, config.height, config.fps, config.crf)}",
            )
            else -> arrayOf("-c:v", "mpeg4", "-q:v", "${(config.crf / 4).coerceIn(2, 12)}")
        }
    }

    private companion object {
        @Volatile var cachedEncoders: String? = null
    }
}

private class FfmpegSession(
    private val pipe: String,
    private val done: CompletableDeferred<FFmpegSession>,
    private val config: WriterConfig,
) : VideoWriter.Session {
    private var out: FileOutputStream? = null
    private val frameBytes = config.width * config.height * 3 / 2

    override suspend fun write(frame: YuvFrame) = withContext(Dispatchers.IO) {
        val o = out ?: FileOutputStream(pipe).also { out = it }
        try {
            o.write(frame.data, 0, frameBytes)
        } catch (e: java.io.IOException) {
            if (done.isCompleted) throw ProcessingException("FFmpeg encoder stopped: " + done.await().allLogsAsString, e)
            throw e
        }
    }

    override suspend fun finish() = withContext(Dispatchers.IO) {
        runCatching { out?.close() }
        val session = done.await()
        FFmpegKitConfig.closeFFmpegPipe(pipe)
        if (!ReturnCode.isSuccess(session.returnCode)) throw ProcessingException("FFmpeg: " + session.allLogsAsString)
    }

    override fun abort() {
        runCatching { out?.close() }
        runCatching { FFmpegKit.cancel() }
        runCatching { FFmpegKitConfig.closeFFmpegPipe(pipe) }
    }
}
