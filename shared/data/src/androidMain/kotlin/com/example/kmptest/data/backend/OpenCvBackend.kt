package com.example.kmptest.data.backend

import android.content.Context
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
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.videoio.VideoCapture
import org.opencv.videoio.VideoWriter as CvVideoWriter
import org.opencv.videoio.Videoio

/** OpenCV's VideoCapture / VideoWriter (its Android MediaNDK backend), as in the desktop tool. */
class OpenCvBackend(context: Context) : VideoBackend {
    override val id = BackendId.OPENCV
    override val reader: VideoReader = OpenCvReader(context.applicationContext)
    override val writer: VideoWriter = OpenCvWriter()

    companion object {
        @Volatile private var loaded = false

        fun ensureLoaded() {
            if (loaded) return
            synchronized(this) {
                if (!loaded) {
                    if (!OpenCVLoader.initLocal()) throw ProcessingException("OpenCV native library failed to load")
                    loaded = true
                }
            }
        }
    }
}

private class OpenCvReader(private val context: Context) : VideoReader {
    override suspend fun probe(source: VideoSource): VideoInfo = MediaSupport.probe(context, source)

    override fun frames(source: VideoSource): Flow<YuvFrame> = flow {
        OpenCvBackend.ensureLoaded()
        val local = MediaSupport.copyToCache(context, source)
        val cap = VideoCapture()
        val bgr = Mat()
        val yuv = Mat()
        try {
            val opened = cap.open(local.absolutePath) || cap.open(local.absolutePath, Videoio.CAP_ANDROID)
            if (!opened || !cap.isOpened) throw ProcessingException("OpenCV cannot open ${source.name}")
            val expected = MediaSupport.probe(context, source)  // upright size; the capture may hand over unrotated frames
            // The MediaNDK capture can hand out one stale frame after the last real one. Frames are emitted one
            // behind, so that the last one can be dropped when the count is exactly one above the file's own.
            var held: YuvFrame? = null
            var count = 0
            while (cap.read(bgr) && !bgr.empty()) {
                currentCoroutineContext().ensureActive()
                Imgproc.cvtColor(bgr, yuv, Imgproc.COLOR_BGR2YUV_I420)
                val bytes = ByteArray((yuv.total() * yuv.channels()).toInt())
                yuv.get(0, 0, bytes)
                val frame = YuvFrame(bgr.cols(), bgr.rows(), bytes)
                // this backend does not apply the rotation metadata (and some builds do): rotate only when still sideways
                val upright = frame.width == expected.width && frame.height == expected.height
                held?.let { emit(it) }
                held = if (upright) frame else frame.rotated(expected.rotation)
                count++
            }
            val surplus = expected.frameCount > 0 && count == expected.frameCount + 1
            if (!surplus) held?.let { emit(it) }
        } finally {
            cap.release()
            bgr.release()
            yuv.release()
            local.delete()
        }
    }.flowOn(Dispatchers.Default).buffer(2)
}

private class OpenCvWriter : VideoWriter {
    override suspend fun open(localPath: String, config: WriterConfig): VideoWriter.Session = withContext(Dispatchers.Default) {
        OpenCvBackend.ensureLoaded()
        val size = Size(config.width.toDouble(), config.height.toDouble())
        val attempts = listOf(
            Videoio.CAP_ANDROID to CvVideoWriter.fourcc('H', '2', '6', '4'),
            Videoio.CAP_ANDROID to CvVideoWriter.fourcc('a', 'v', 'c', '1'),
            Videoio.CAP_ANY to CvVideoWriter.fourcc('a', 'v', 'c', '1'),
            Videoio.CAP_ANY to CvVideoWriter.fourcc('m', 'p', '4', 'v'),
        )
        for ((api, fourcc) in attempts) {
            val w = CvVideoWriter()
            if (w.open(localPath, api, fourcc, config.fps.toDouble(), size) && w.isOpened) return@withContext CvSession(w, config)
            w.release()
        }
        throw ProcessingException("OpenCV cannot create an MP4 writer on this device")
    }
}

private class CvSession(private val writer: CvVideoWriter, private val config: WriterConfig) : VideoWriter.Session {
    private val yuv = Mat(config.height * 3 / 2, config.width, CvType.CV_8UC1)
    private val bgr = Mat()

    override suspend fun write(frame: YuvFrame) = withContext(Dispatchers.Default) {
        yuv.put(0, 0, frame.data)
        Imgproc.cvtColor(yuv, bgr, Imgproc.COLOR_YUV2BGR_I420)
        writer.write(bgr)
        Unit
    }

    override suspend fun finish() = withContext(Dispatchers.Default) { release() }

    override fun abort() = release()

    private fun release() {
        writer.release()
        yuv.release()
        bgr.release()
    }
}
