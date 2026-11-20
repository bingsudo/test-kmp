package com.example.kmptest.domain.usecase

import com.example.kmptest.domain.algo.ClockDecoder
import com.example.kmptest.domain.algo.ClockDetector
import com.example.kmptest.domain.algo.GlyphClusterer
import com.example.kmptest.domain.algo.Scheduler
import com.example.kmptest.domain.algo.Signature
import com.example.kmptest.domain.model.AppSettings
import com.example.kmptest.domain.model.ClockError
import com.example.kmptest.domain.model.ClockNotFoundException
import com.example.kmptest.domain.model.ClockRegion
import com.example.kmptest.domain.model.JobProgress
import com.example.kmptest.domain.model.OutputTarget
import com.example.kmptest.domain.model.ProcessingException
import com.example.kmptest.domain.model.ProcessingMode
import com.example.kmptest.domain.model.ProcessingOptions
import com.example.kmptest.domain.model.ProcessingResult
import com.example.kmptest.domain.model.Stage
import com.example.kmptest.domain.model.VideoInfo
import com.example.kmptest.domain.model.VideoSource
import com.example.kmptest.domain.model.WriterConfig
import com.example.kmptest.domain.model.YuvFrame
import com.example.kmptest.domain.port.OutputStore
import com.example.kmptest.domain.port.VideoBackends
import com.example.kmptest.domain.port.VideoWriter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.takeWhile

/**
 * Removes duplicate frames from one laggy recording.
 *
 * Pass 1 (clock mode) finds the burned-in clock, pass 2 reads the clock and marks duplicate frames,
 * pass 3 re-reads the video and writes the scheduled frames at constant fps.
 */
class FixVideoUseCase(
    private val backends: VideoBackends,
    private val store: OutputStore,
) {
    class Output(val result: ProcessingResult, val target: OutputTarget)

    suspend operator fun invoke(
        source: VideoSource,
        options: ProcessingOptions,
        outputSuffix: String,
        onProgress: (JobProgress) -> Unit,
    ): Output {
        val backend = backends.get(options.backend)
        val reader = backend.reader
        val info = reader.probe(source)

        var region: ClockRegion? = null
        if (options.mode == ProcessingMode.CLOCK) region = detectClock(source, info, reader::frames, onProgress)

        // ---- pass 2: clock digits + duplicate flags
        val onesClusters = GlyphClusterer()
        val tensClusters = GlyphClusterer()
        val ones = ArrayList<Int>()
        val tens = ArrayList<Int>()
        val keep = ArrayList<Boolean>()
        var lastSig: FloatArray? = null
        var count = 0
        val total = maxOf(info.frameCount, 1)
        reader.frames(source).collect { frame ->
            currentCoroutineContext().ensureActive()
            var blank: IntRange? = null
            if (region != null) {
                ones.add(onesClusters.idOf(GlyphClusterer.cell(frame, region.ones)))
                tens.add(region.tens?.let { tensClusters.idOf(GlyphClusterer.cell(frame, it)) } ?: -1)
                blank = Signature.clockBand(region.ones, frame.height)
            }
            val sig = Signature.of(frame, blank)
            val prev = lastSig
            val duplicate = prev != null && Signature.meanAbsDiff(sig, prev) < options.duplicateThreshold
            if (!duplicate) lastSig = sig
            keep.add(!duplicate)
            count++
            if (count % 10 == 0) onProgress(JobProgress(Stage.ANALYZE, (count.toFloat() / total).coerceAtMost(1f)))
        }
        if (count == 0) throw ProcessingException("no frames decoded")
        val keepArray = keep.toBooleanArray()

        var clockSpan: Int? = null
        val schedule: IntArray = if (region != null) {
            val okOnes = onesClusters.reliable()
            val okTens = tensClusters.reliable()
            val onesIds = IntArray(count) { ones[it].takeIf { id -> id in okOnes } ?: -1 }
            val tensIds = if (region.tens != null) IntArray(count) { tens[it].takeIf { id -> id in okTens } ?: -1 } else null
            val clock = ClockDecoder.decode(onesIds, tensIds)
            val seconds = clock.seconds
            // the first frame of a new clock second is never a duplicate of the previous second's frame
            for (i in 1 until count) if (seconds[i] != seconds[i - 1]) keepArray[i] = true
            clockSpan = seconds.last() - seconds.first() + 1
            Scheduler.clockSchedule(seconds, keepArray, options.fps)
        } else {
            Scheduler.plainSchedule(keepArray)
        }

        // ---- pass 3: write
        val target = store.create(outputName(source.name, outputSuffix), source)
        val temp = store.newTempFile()
        var session: VideoWriter.Session? = null
        try {
            var written = 0
            var index = 0
            reader.frames(source).takeWhile { written < schedule.size }.collect { frame ->
                currentCoroutineContext().ensureActive()
                val s = session ?: backend.writer.open(
                    temp, WriterConfig(frame.width, frame.height, options.fps, options.crf)  // the output keeps the source frame size,
                ).also { session = it }
                while (written < schedule.size && schedule[written] == index) {
                    s.write(frame)
                    written++
                }
                index++
                if (index % 10 == 0) onProgress(JobProgress(Stage.RENDER, (written.toFloat() / schedule.size).coerceAtMost(1f)))
            }
            session?.finish() ?: throw ProcessingException("nothing written")
            session = null
            store.publish(temp, target)
        } catch (t: Throwable) {
            session?.abort()
            store.discard(target)
            throw t
        }
        onProgress(JobProgress(Stage.RENDER, 1f))
        return Output(
            ProcessingResult(count, keepArray.count { it }, schedule.size, schedule.size.toDouble() / options.fps, clockSpan),
            target,
        )
    }

    private suspend fun detectClock(
        source: VideoSource,
        info: VideoInfo,
        frames: (VideoSource) -> Flow<YuvFrame>,
        onProgress: (JobProgress) -> Unit,
    ): ClockRegion {
        val step = if (info.frameCount > 0) maxOf(info.frameCount / 150, 1) else 4
        var detector: ClockDetector? = null
        var i = 0
        frames(source).collect { frame ->
            currentCoroutineContext().ensureActive()
            if (i % step == 0) (detector ?: ClockDetector(frame.width, frame.height).also { detector = it }).add(frame)
            i++
            if (i % 10 == 0 && info.frameCount > 0) onProgress(JobProgress(Stage.DETECT, (i.toFloat() / info.frameCount).coerceAtMost(1f)))
        }
        val d = detector ?: throw ClockNotFoundException(ClockError.TOO_SHORT)
        return d.detect()
    }

    companion object {
        fun outputName(sourceName: String, suffix: String): String {
            val base = sourceName.substringBeforeLast('.', sourceName)
            // never produce the source's own name: an empty suffix falls back to the default
            return base + suffix.ifEmpty { AppSettings.DEFAULT_SUFFIX } + ".mp4"
        }
    }
}
