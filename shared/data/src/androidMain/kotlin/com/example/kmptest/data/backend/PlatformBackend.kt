package com.example.kmptest.data.backend

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
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

/** Android's own MediaCodec / MediaMuxer: no extra native libraries. */
class PlatformBackend(context: Context) : VideoBackend {
    override val id = BackendId.PLATFORM
    override val reader: VideoReader = PlatformReader(context.applicationContext)
    override val writer: VideoWriter = PlatformWriter()
}

private const val TIMEOUT_US = 10_000L

private class PlatformReader(private val context: Context) : VideoReader {
    override suspend fun probe(source: VideoSource): VideoInfo = MediaSupport.probe(context, source)

    override fun frames(source: VideoSource): Flow<YuvFrame> = flow {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            context.contentResolver.openFileDescriptor(Uri.parse(source.uri), "r")
                ?.use { extractor.setDataSource(it.fileDescriptor) }
                ?: throw ProcessingException("Cannot open ${source.name}")
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw ProcessingException("No video track in ${source.name}")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val rotation = MediaSupport.probe(context, source).rotation  // decoders output the frames unrotated
            val c = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(format, null, null, 0)
            c.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val ii = c.dequeueInputBuffer(TIMEOUT_US)
                    if (ii >= 0) {
                        val size = extractor.readSampleData(c.getInputBuffer(ii)!!, 0)
                        if (size < 0) {
                            c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(ii, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, TIMEOUT_US)
                if (oi >= 0) {
                    var frame: YuvFrame? = null
                    if (info.size > 0) c.getOutputImage(oi)?.use { frame = MediaSupport.toI420(it).rotated(rotation) }
                    c.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    frame?.let { emit(it) }
                }
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }.flowOn(Dispatchers.Default).buffer(2)
}

private class PlatformWriter : VideoWriter {
    override suspend fun open(localPath: String, config: WriterConfig): VideoWriter.Session =
        withContext(Dispatchers.Default) { PlatformSession(localPath, config) }
}

private class PlatformSession(path: String, private val config: WriterConfig) : VideoWriter.Session {
    private val codec: MediaCodec
    private val muxer: MediaMuxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxerStarted = false
    private var frames = 0L
    private var released = false

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, MediaSupport.bitrateFor(config.width, config.height, config.fps, config.crf))
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (t: Throwable) {
            codec.release()
            muxer.release()
            throw ProcessingException("The device's H.264 encoder rejected ${config.width}x${config.height}", t)
        }
    }

    override suspend fun write(frame: YuvFrame) = withContext(Dispatchers.Default) {
        require(frame.width == config.width && frame.height == config.height) { "frame size changed" }
        while (true) {
            currentCoroutineContext().ensureActive()
            val ii = codec.dequeueInputBuffer(TIMEOUT_US)
            if (ii >= 0) {
                MediaSupport.fillImage(codec.getInputImage(ii)!!, frame)
                codec.queueInputBuffer(ii, 0, frame.width * frame.height * 3 / 2, ptsUs(frames++), 0)
                drain(endOfStream = false)
                break
            }
            drain(endOfStream = false)
        }
    }

    override suspend fun finish() = withContext(Dispatchers.Default) {
        while (true) {
            val ii = codec.dequeueInputBuffer(TIMEOUT_US)
            if (ii >= 0) {
                codec.queueInputBuffer(ii, 0, 0, ptsUs(frames), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                break
            }
            drain(false)
        }
        drain(endOfStream = true)
        if (muxerStarted) muxer.stop()
        release()
    }

    override fun abort() = release()

    private fun ptsUs(n: Long) = n * 1_000_000L / config.fps

    /** Moves encoded data to the muxer; with [endOfStream] blocks until the encoder signals the end. */
    private fun drain(endOfStream: Boolean) {
        var idle = 0
        while (true) {
            val oi = codec.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0)
            when {
                oi == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    if (++idle > 500) throw ProcessingException("Encoder did not finish")
                }
                oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                oi >= 0 -> {
                    idle = 0
                    val buf = codec.getOutputBuffer(oi)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxerStarted) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun release() {
        if (released) return
        released = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { muxer.release() }
    }
}
