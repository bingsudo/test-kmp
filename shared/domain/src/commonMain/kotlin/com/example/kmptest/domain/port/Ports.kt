package com.example.kmptest.domain.port

import com.example.kmptest.domain.model.AppSettings
import com.example.kmptest.domain.model.BackendId
import com.example.kmptest.domain.model.OutputTarget
import com.example.kmptest.domain.model.ProcessingOptions
import com.example.kmptest.domain.model.QueueState
import com.example.kmptest.domain.model.VideoInfo
import com.example.kmptest.domain.model.VideoSource
import com.example.kmptest.domain.model.WriterConfig
import com.example.kmptest.domain.model.YuvFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Decodes a video into frames, in presentation order, every frame exactly once. */
interface VideoReader {
    suspend fun probe(source: VideoSource): VideoInfo

    /** Cold flow: each collection decodes the video from the start. Must honour cancellation. */
    fun frames(source: VideoSource): Flow<YuvFrame>
}

/** Writes frames into a local file as constant-fps H.264 MP4. */
interface VideoWriter {
    suspend fun open(localPath: String, config: WriterConfig): Session

    interface Session {
        suspend fun write(frame: YuvFrame)
        suspend fun finish()

        /** Release everything without producing a valid file (cancel / error). */
        fun abort()
    }
}

/** One interchangeable way of decoding/encoding. */
interface VideoBackend {
    val id: BackendId
    val reader: VideoReader
    val writer: VideoWriter
}

interface VideoBackends {
    /** The engines that work on this device / computer, in display order. */
    val available: List<BackendId>

    fun get(id: BackendId): VideoBackend
}

/** Where finished videos go. Writers produce a local file; the store publishes it. */
interface OutputStore {
    /** A scratch file path for a writer. */
    suspend fun newTempFile(): String

    /**
     * Creates the destination and returns it: the chosen output folder, or the platform default
     * (Movies/KmpTest on Android, next to [source] on the desktop). Never overwrites an existing file.
     */
    suspend fun create(displayName: String, source: VideoSource): OutputTarget

    suspend fun publish(localPath: String, target: OutputTarget)
    suspend fun discard(target: OutputTarget)
}

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

/** The list of videos to fix and the background job that works through it (outlives the screen). */
interface ProcessingQueue {
    val state: StateFlow<QueueState>

    /** Adds videos to the list (already-listed ones are ignored). Nothing runs yet. */
    fun add(sources: List<VideoSource>)

    /** Runs every item that is not done yet, one after another. */
    fun start(options: ProcessingOptions, outputSuffix: String)
    fun cancel()
    fun remove(source: VideoSource)
    fun clear()

    /** Removes the finished items; waiting and failed ones stay. */
    fun clearDone()
}
