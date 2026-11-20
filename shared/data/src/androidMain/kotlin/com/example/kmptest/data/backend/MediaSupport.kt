package com.example.kmptest.data.backend

import android.content.Context
import android.media.Image
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.example.kmptest.domain.model.ProcessingException
import com.example.kmptest.domain.model.VideoInfo
import com.example.kmptest.domain.model.VideoSource
import com.example.kmptest.domain.model.YuvFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.pow

/** Helpers shared by the decoding/encoding backends. */
internal object MediaSupport {
    /** Container info via the platform retriever (cheap, no decoding). */
    suspend fun probe(context: Context, source: VideoSource): VideoInfo = withContext(Dispatchers.IO) {
        val r = MediaMetadataRetriever()
        try {
            val pfd = context.contentResolver.openFileDescriptor(Uri.parse(source.uri), "r")
                ?: throw ProcessingException("Cannot open ${source.name}")
            pfd.use { r.setDataSource(it.fileDescriptor) }
            fun int(key: Int) = r.extractMetadata(key)?.toIntOrNull() ?: 0
            val rotation = ((int(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) % 360) + 360) % 360
            val sideways = rotation == 90 || rotation == 270
            val w = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val h = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            VideoInfo(
                width = if (sideways) h else w,
                height = if (sideways) w else h,
                rotation = rotation,
                frameCount = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT),
                durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
            )
        } catch (e: java.io.IOException) {
            throw ProcessingException("Cannot read ${source.name}: ${e.message}", e)
        } catch (e: RuntimeException) {
            throw ProcessingException("Cannot read ${source.name}: ${e.message}", e)
        } finally {
            r.release()
        }
    }

    /** x264-like quality knob (CRF, lower = better) to an average bitrate for hardware encoders. */
    fun bitrateFor(width: Int, height: Int, fps: Int, crf: Int): Int {
        val base = width.toDouble() * height * fps * 0.1
        return (base * 2.0.pow((23 - crf) / 6.0)).toInt().coerceIn(500_000, 60_000_000)
    }

    /** Copies a content uri into a cache file, for libraries that only open paths. */
    suspend fun copyToCache(context: Context, source: VideoSource): File = withContext(Dispatchers.IO) {
        val out = File.createTempFile("src", ".mp4", context.cacheDir)
        try {
            context.contentResolver.openInputStream(Uri.parse(source.uri))?.use { input ->
                out.outputStream().use { input.copyTo(it, 1 shl 20) }
            } ?: throw IOException("cannot open ${source.uri}")
            out
        } catch (t: Throwable) {
            out.delete()
            throw t
        }
    }

    /** Planar I420 copy of a decoder output image (honours crop rectangle, row and pixel strides). */
    fun toI420(image: Image): YuvFrame {
        val crop = image.cropRect
        val w = crop.width() and 1.inv()
        val h = crop.height() and 1.inv()
        val out = ByteArray(w * h * 3 / 2)
        val planes = image.planes
        copyPlane(planes[0], w, h, crop.left, crop.top, out, 0)
        copyPlane(planes[1], w / 2, h / 2, crop.left / 2, crop.top / 2, out, w * h)
        copyPlane(planes[2], w / 2, h / 2, crop.left / 2, crop.top / 2, out, w * h + (w / 2) * (h / 2))
        return YuvFrame(w, h, out)
    }

    private fun copyPlane(plane: Image.Plane, w: Int, h: Int, left: Int, top: Int, dst: ByteArray, dstOffset: Int) {
        val buf = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        var o = dstOffset
        if (pixelStride == 1) {
            for (r in 0 until h) {
                buf.position((top + r) * rowStride + left)
                buf.get(dst, o, w)
                o += w
            }
        } else {
            val row = ByteArray(max(rowStride, w * pixelStride))
            for (r in 0 until h) {
                val start = (top + r) * rowStride + left * pixelStride
                buf.position(start)
                buf.get(row, 0, minOf(buf.remaining(), (w - 1) * pixelStride + 1))
                for (c in 0 until w) dst[o++] = row[c * pixelStride]
            }
        }
    }

    /** Writes an I420 frame into an encoder input image (any plane layout). */
    fun fillImage(image: Image, frame: YuvFrame) {
        val w = frame.width
        val h = frame.height
        val planes = image.planes
        putPlane(planes[0], frame.data, 0, w, h)
        putPlane(planes[1], frame.data, w * h, w / 2, h / 2)
        putPlane(planes[2], frame.data, w * h + (w / 2) * (h / 2), w / 2, h / 2)
    }

    private fun putPlane(plane: Image.Plane, src: ByteArray, srcOffset: Int, w: Int, h: Int) {
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (pixelStride == 1) {
            for (r in 0 until h) {
                buf.position(r * rowStride)
                buf.put(src, srcOffset + r * w, w)
            }
        } else {
            for (r in 0 until h) {
                val base = r * rowStride
                for (c in 0 until w) buf.put(base + c * pixelStride, src[srcOffset + r * w + c])
            }
        }
    }
}
