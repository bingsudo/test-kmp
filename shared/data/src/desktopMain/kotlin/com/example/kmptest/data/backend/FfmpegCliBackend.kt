package com.example.kmptest.data.backend

import com.example.kmptest.domain.model.BackendId
import com.example.kmptest.domain.model.ProcessingException
import com.example.kmptest.domain.model.VideoInfo
import com.example.kmptest.domain.model.VideoSource
import com.example.kmptest.domain.model.WriterConfig
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
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/** The `ffmpeg` command-line program (the one on the PATH, else the copy bundled with JavaCV) through raw-video pipes. */
class FfmpegCliBackend : VideoBackend {
    override val id = BackendId.FFMPEG_CLI
    override val reader: VideoReader = CliReader()
    override val writer: VideoWriter = CliWriter()

    companion object {
        /** Path of a working ffmpeg, or null. Cached. */
        val executable: String? by lazy { locate() }

        private fun works(exe: String): Boolean = runCatching {
            val p = ProcessBuilder(exe, "-hide_banner", "-version").redirectErrorStream(true).start()
            p.inputStream.readBytes()
            p.waitFor() == 0
        }.getOrDefault(false)

        private fun locate(): String? {
            if (works("ffmpeg")) return "ffmpeg"
            return runCatching {
                org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.ffmpeg::class.java)
            }.getOrNull()?.takeIf { works(it) }
        }

        /** For diagnostics: what happens when ffmpeg is looked for (system program first, then the JavaCV copy). */
        fun whyUnavailable(): String {
            fun attempt(exe: String): String = runCatching {
                val p = ProcessBuilder(exe, "-hide_banner", "-version").redirectErrorStream(true).start()
                val out = p.inputStream.readBytes().decodeToString().lineSequence().take(3).joinToString(" | ")
                "exit ${p.waitFor()}: $out"
            }.getOrElse { "${it::class.simpleName}: ${it.message}" }
            val bundled = runCatching { org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.ffmpeg::class.java) }
            return "system ffmpeg -> ${attempt("ffmpeg")}; bundled -> " +
                bundled.fold({ "$it -> ${attempt(it)}" }, { "${it::class.simpleName}: ${it.message}" })
        }

        private val encoders: Set<String> by lazy {
            val exe = executable ?: return@lazy emptySet()
            runCatching {
                val p = ProcessBuilder(exe, "-hide_banner", "-encoders").redirectErrorStream(true).start()
                val text = p.inputStream.readBytes().decodeToString()
                p.waitFor()
                text.lineSequence().mapNotNull { it.trim().split(Regex("\\s+")).getOrNull(1) }.toSet()
            }.getOrDefault(emptySet())
        }

        /** Best available H.264 encoder (software x264 first), with the quality arguments it understands. */
        fun encoderArgs(crf: Int): List<String> = when {
            "libx264" in encoders -> listOf("-c:v", "libx264", "-crf", "$crf", "-preset", "medium")
            "libopenh264" in encoders -> listOf("-c:v", "libopenh264", "-b:v", "8M")
            "h264_videotoolbox" in encoders -> listOf("-c:v", "h264_videotoolbox", "-q:v", "${(100 - crf * 2).coerceIn(10, 100)}")
            "h264_mf" in encoders -> listOf("-c:v", "h264_mf", "-b:v", "8M")
            else -> listOf("-c:v", "mpeg4", "-q:v", "${(crf / 3).coerceIn(1, 31)}")
        }

        val supportsFpsMode: Boolean by lazy {
            val exe = executable ?: return@lazy true
            runCatching {
                val p = ProcessBuilder(exe, "-hide_banner", "-version").redirectErrorStream(true).start()
                val first = p.inputStream.readBytes().decodeToString().lineSequence().first()
                p.waitFor()
                val major = Regex("version\\D*(\\d+)").find(first)?.groupValues?.get(1)?.toIntOrNull()
                major == null || major >= 6
            }.getOrDefault(true)
        }
    }
}

private fun requireExe(): String =
    FfmpegCliBackend.executable ?: throw ProcessingException("ffmpeg was not found. Install it or choose another engine in Settings.")

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

/** Collects stderr in the background so a chatty process never blocks on a full pipe. */
private class StderrTail(stream: InputStream) {
    private val text = StringBuilder()
    private val thread = Thread {
        runCatching { stream.bufferedReader().forEachLine { synchronized(text) { if (text.length < 8000) text.appendLine(it) } } }
    }.apply { isDaemon = true; start() }

    fun get(): String = synchronized(text) { text.toString().trim() }
}

private class CliReader : VideoReader {
    override suspend fun probe(source: VideoSource): VideoInfo = withContext(Dispatchers.IO) {
        val exe = requireExe()
        val p = ProcessBuilder(exe, "-hide_banner", "-i", source.uri).redirectErrorStream(true).start()
        val text = p.inputStream.readBytes().decodeToString()
        p.waitFor()
        val line = text.lineSequence().firstOrNull { "Video:" in it }
            ?: throw ProcessingException("Cannot read ${source.name}: ${text.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()}")
        val size = Regex("(\\d{2,5})x(\\d{2,5})").findAll(line.substringAfter("Video:")).firstOrNull()
            ?: throw ProcessingException("Unknown video size for ${source.name}")
        var w = size.groupValues[1].toInt()
        var h = size.groupValues[2].toInt()
        // ffmpeg turns frames upright by itself: a 90 / 270 degree display matrix swaps the output size
        val rotation = Regex("rotation of (-?[\\d.]+) degrees").find(text)?.groupValues?.get(1)?.toDoubleOrNull()?.roundToInt() ?: 0
        if (abs(rotation) % 180 == 90) w = h.also { h = w }
        val fps = Regex("([\\d.]+) fps").find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: 25.0
        val seconds = Regex("Duration: (\\d+):(\\d+):([\\d.]+)").find(text)?.let {
            it.groupValues[1].toInt() * 3600 + it.groupValues[2].toInt() * 60 + it.groupValues[3].toDouble()
        } ?: 0.0
        VideoInfo(w, h, (seconds * fps).roundToInt(), (seconds * 1000).toLong())
    }

    override fun frames(source: VideoSource): Flow<YuvFrame> = flow {
        val info = probe(source)
        val exe = requireExe()
        val frameBytes = info.width * info.height * 3 / 2
        val sync = if (FfmpegCliBackend.supportsFpsMode) listOf("-fps_mode", "passthrough") else listOf("-vsync", "0")
        val process = ProcessBuilder(
            listOf(exe, "-v", "error", "-i", source.uri, "-map", "0:v:0", "-an", "-sn") + sync +
                listOf("-f", "rawvideo", "-pix_fmt", "yuv420p", "-"),
        ).start()
        val err = StderrTail(process.errorStream)
        try {
            val input = process.inputStream.buffered(1 shl 20)
            val buf = ByteArray(frameBytes)
            var any = false
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!input.readFully(buf)) break
                any = true
                emit(YuvFrame(info.width, info.height, buf.copyOf()))
            }
            if (!any) throw ProcessingException("ffmpeg could not decode ${source.name}: ${err.get()}")
        } finally {
            process.destroy()
        }
    }.flowOn(Dispatchers.IO).buffer(2)
}

private class CliWriter : VideoWriter {
    override suspend fun open(localPath: String, config: WriterConfig): VideoWriter.Session = withContext(Dispatchers.IO) {
        val exe = requireExe()
        val process = ProcessBuilder(
            listOf(
                exe, "-y", "-v", "error", "-f", "rawvideo", "-pix_fmt", "yuv420p", "-s", "${config.width}x${config.height}",
                "-r", "${config.fps}", "-i", "-",
            ) + FfmpegCliBackend.encoderArgs(config.crf) + listOf("-pix_fmt", "yuv420p", "-movflags", "+faststart", localPath),
        ).start()
        CliSession(process, StderrTail(process.errorStream))
    }
}

private class CliSession(private val process: Process, private val err: StderrTail) : VideoWriter.Session {
    private val stdin: OutputStream = process.outputStream.buffered(1 shl 20)

    override suspend fun write(frame: YuvFrame) = withContext(Dispatchers.IO) {
        try {
            stdin.write(frame.data, 0, frame.width * frame.height * 3 / 2)
        } catch (e: java.io.IOException) {
            throw ProcessingException("ffmpeg stopped while writing: ${err.get()}", e)
        }
    }

    override suspend fun finish() = withContext(Dispatchers.IO) {
        runCatching { stdin.close() }
        if (process.waitFor() != 0) throw ProcessingException("ffmpeg failed: ${err.get()}")
    }

    override fun abort() {
        runCatching { stdin.close() }
        process.destroyForcibly()
    }
}
