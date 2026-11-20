package com.example.kmptest.data

import com.example.kmptest.data.backend.BackendRegistry
import com.example.kmptest.data.backend.FfmpegCliBackend
import com.example.kmptest.data.backend.JavaCvFfmpegBackend
import com.example.kmptest.domain.model.BackendId
import com.example.kmptest.domain.model.OutputTarget
import com.example.kmptest.domain.model.ProcessingException
import com.example.kmptest.domain.model.VideoSource
import com.example.kmptest.domain.port.OutputStore
import com.example.kmptest.domain.port.SettingsRepository
import com.example.kmptest.domain.port.VideoBackends
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Engines that load on this computer; the settings screen offers exactly these. */
fun desktopBackends(): VideoBackends = BackendRegistry(
    listOf(
        BackendRegistry.Candidate(BackendId.FFMPEG_CLI, { FfmpegCliBackend.executable != null }, { FfmpegCliBackend() }),
        BackendRegistry.Candidate(BackendId.FFMPEG, { JavaCvFfmpegBackend.isAvailable() }, { JavaCvFfmpegBackend() }),
    ),
)

/**
 * Output goes to the chosen folder, or next to each source file. A file that already exists is never overwritten:
 * the name gets " (2)", " (3)", ... instead.
 */
class DesktopOutputStore(private val settings: SettingsRepository) : OutputStore {
    override suspend fun newTempFile(): String = withContext(Dispatchers.IO) {
        File.createTempFile("kmptest", ".mp4").absolutePath
    }

    override suspend fun create(displayName: String, source: VideoSource): OutputTarget = withContext<OutputTarget>(Dispatchers.IO) {
        val chosen = settings.settings.first().outputFolderUri
        val dir = if (chosen.isNullOrBlank()) File(source.uri).absoluteFile.parentFile else File(chosen)
        if (!dir.isDirectory && !dir.mkdirs()) throw ProcessingException("Cannot use the output folder: ${dir.path}")
        val stem = displayName.removeSuffix(".mp4")
        var n = 1
        while (true) {
            val name = if (n == 1) "$stem.mp4" else "$stem ($n).mp4"
            val file = File(dir, name)
            // createNewFile is atomic: it also reserves the name, so a parallel job cannot take it
            val created = try {
                file.createNewFile()
            } catch (e: java.io.IOException) {
                throw ProcessingException("Cannot create ${file.path}: ${e.message}", e)
            }
            if (created) return@withContext OutputTarget(file.absolutePath, name)
            n++
        }
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException()
    }

    override suspend fun publish(localPath: String, target: OutputTarget) {
        withContext(Dispatchers.IO) {
            Files.move(File(localPath).toPath(), File(target.uri).toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override suspend fun discard(target: OutputTarget) {
        withContext(Dispatchers.IO) { runCatching { File(target.uri).delete() } }
    }
}
