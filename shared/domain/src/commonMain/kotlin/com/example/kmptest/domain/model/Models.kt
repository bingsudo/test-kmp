package com.example.kmptest.domain.model

/** One decoded frame in planar I420 (Y then U then V). The first width*height bytes are the luma plane. */
class YuvFrame(val width: Int, val height: Int, val data: ByteArray) {
    init {
        require(data.size >= width * height * 3 / 2) { "I420 frame needs ${width * height * 3 / 2} bytes, got ${data.size}" }
    }

    fun luma(x: Int, y: Int): Int = data[y * width + x].toInt() and 0xFF
}

enum class ProcessingMode { CLOCK, PLAIN }

/** Which implementation decodes and encodes the video. */
enum class BackendId { PLATFORM, OPENCV, FFMPEG, FFMPEG_CLI }

enum class ThemeSetting { SYSTEM, LIGHT, DARK, BLACK }

data class ProcessingOptions(
    val mode: ProcessingMode = ProcessingMode.PLAIN,
    val fps: Int = 20,
    val duplicateThreshold: Double = 0.08,
    val crf: Int = 20,
    val backend: BackendId = BackendId.PLATFORM,
) {
    init {
        require(fps in 1..120) { "fps" }
        require(duplicateThreshold >= 0.0) { "threshold" }
        require(crf in 0..51) { "crf" }
    }
}

data class VideoSource(val uri: String, val name: String)

/** [width] x [height] is the size as the video is *displayed* (already swapped for 90/270 degree rotation metadata). */
data class VideoInfo(val width: Int, val height: Int, val frameCount: Int, val durationMs: Long, val rotation: Int = 0)

/** Where the finished video goes; opaque to the domain, resolved by the data layer. */
data class OutputTarget(val uri: String, val displayName: String)

data class WriterConfig(val width: Int, val height: Int, val fps: Int, val crf: Int)

data class Box(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    val width get() = x1 - x0
    val height get() = y1 - y0
}

/** Cells holding the seconds digits of the burned-in clock. */
data class ClockRegion(val ones: Box, val tens: Box?)

enum class Stage { DETECT, ANALYZE, RENDER }

data class JobProgress(val stage: Stage, val fraction: Float) {
    /** Progress of the whole job: detect 5 %, analyse 45 %, render 50 %. */
    val overall: Float
        get() = when (stage) {
            Stage.DETECT -> 0.05f * fraction
            Stage.ANALYZE -> 0.05f + 0.45f * fraction
            Stage.RENDER -> 0.5f + 0.5f * fraction
        }
}

data class ProcessingResult(
    val sourceFrames: Int,
    val uniqueFrames: Int,
    val outputFrames: Int,
    val outputSeconds: Double,
    /** Seconds covered by the clock, null in plain mode. */
    val clockSpanSeconds: Int?,
)

enum class ClockError { NO_REGION, NO_CYCLE, TOO_SHORT, NO_DIGITS }

class ClockNotFoundException(val error: ClockError) : Exception(error.name)

class ProcessingException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class AppSettings(
    val language: String = LANGUAGE_AUTO,
    val theme: ThemeSetting = ThemeSetting.SYSTEM,
    val fontScale: Float = 1.0f,
    val options: ProcessingOptions = ProcessingOptions(),
    /** Persisted SAF tree uri chosen as output folder; null = Movies/KmpTest. */
    val outputFolderUri: String? = null,
    /** Appended to the source name to form the output name. */
    val outputSuffix: String = DEFAULT_SUFFIX,
) {
    companion object {
        const val LANGUAGE_AUTO = "auto"
        const val DEFAULT_SUFFIX = "_fixed"
    }
}

enum class ItemStatus { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

data class QueueItem(
    val source: VideoSource,
    val status: ItemStatus = ItemStatus.QUEUED,
    val progress: JobProgress? = null,
    val result: ProcessingResult? = null,
    val outputName: String? = null,
    val clockError: ClockError? = null,
    val errorMessage: String? = null,
)

data class QueueState(val items: List<QueueItem> = emptyList(), val running: Boolean = false) {
    /** 0..1 over the whole queue. */
    val overallFraction: Float
        get() = if (items.isEmpty()) 0f else items.sumOf { it.fraction.toDouble() }.toFloat() / items.size
}

private val QueueItem.fraction: Float
    get() = when (status) {
        ItemStatus.DONE, ItemStatus.FAILED, ItemStatus.CANCELLED -> 1f
        ItemStatus.QUEUED -> 0f
        ItemStatus.RUNNING -> progress?.overall ?: 0f
    }
