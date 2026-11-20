package com.example.kmptest.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.example.kmptest.data.DesktopOutputStore
import com.example.kmptest.data.backend.FfmpegCliBackend
import com.example.kmptest.data.desktopBackends
import com.example.kmptest.data.queue.WorkKeeper
import com.example.kmptest.data.settings.DataStoreSettingsRepository
import com.example.kmptest.domain.model.BackendId
import com.example.kmptest.domain.model.ProcessingMode
import com.example.kmptest.domain.model.ProcessingOptions
import com.example.kmptest.domain.model.VideoSource
import com.example.kmptest.domain.port.OutputStore
import com.example.kmptest.domain.port.ProcessingQueue
import com.example.kmptest.domain.port.SettingsRepository
import com.example.kmptest.domain.port.VideoBackends
import com.example.kmptest.domain.usecase.FixVideoUseCase
import com.example.kmptest.presentation.KmpTestApp
import com.example.kmptest.presentation.di.sharedModule
import kotlinx.coroutines.runBlocking
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.dsl.module
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import javax.swing.UIManager
import kotlin.system.exitProcess

private val desktopModule = module {
    single<SettingsRepository> { DataStoreSettingsRepository(File(appDataDir(), "settings.preferences_pb").also { it.parentFile.mkdirs() }.path) }
    single<OutputStore> { DesktopOutputStore(get()) }
    single<VideoBackends> { desktopBackends() }
    single<WorkKeeper> { object : WorkKeeper { override fun workStarted() = Unit } }
}

/** Per-user settings folder: %APPDATA% / ~/Library/Application Support / ~/.config. */
private fun appDataDir(): File {
    val home = System.getProperty("user.home")
    val os = System.getProperty("os.name").lowercase()
    val base = when {
        "win" in os -> System.getenv("APPDATA")?.let(::File) ?: File(home)
        "mac" in os -> File(home, "Library/Application Support")
        else -> System.getenv("XDG_CONFIG_HOME")?.let(::File) ?: File(home, ".config")
    }
    return File(base, "KmpTest")
}

fun main(args: Array<String>) {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }  // native file dialogs
    val koin = startKoin { modules(sharedModule, desktopModule) }.koin
    if (args.firstOrNull() == "--cli") {
        val ok = runCli(koin, args.drop(1))
        exitProcess(if (ok) 0 else 1)
    }
    val queue = koin.get<ProcessingQueue>()
    val platform = DesktopPlatform()
    // videos or folders given on the command line ("Open with") start in the list
    queue.add(args.map(::File).flatMap { videosIn(it) }.map { VideoSource(it.absolutePath, it.name) })

    application {
        val icon = BitmapPainter(useResource("icon.png", ::loadImageBitmap))
        Window(
            onCloseRequest = ::exitApplication,
            title = "Kmp Test",
            icon = icon,
            state = rememberWindowState(size = DpSize(900.dp, 860.dp)),
        ) {
            // files or folders dropped anywhere on the window go into the list
            DisposableEffect(window) {
                val target = DropTarget(window, object : DropTargetAdapter() {
                    override fun drop(event: DropTargetDropEvent) {
                        event.acceptDrop(DnDConstants.ACTION_COPY)
                        val files = runCatching {
                            @Suppress("UNCHECKED_CAST")
                            event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                        }.getOrDefault(emptyList())
                        queue.add(files.flatMap { videosIn(it) }.map { VideoSource(it.absolutePath, it.name) })
                        event.dropComplete(true)
                    }
                })
                onDispose { target.removeNotify() }
            }
            KmpTestApp(platform)
        }
    }
}

/** A video file itself, or every video inside a folder (recursively). */
internal fun videosIn(file: File): List<File> = when {
    file.isDirectory -> file.walkTopDown().filter { it.isFile && isVideo(it) }.sortedBy { it.path }.toList()
    file.isFile -> listOf(file)
    else -> emptyList()
}

internal val VIDEO_EXTENSIONS = setOf("mp4", "mov", "avi", "mkv", "m4v", "ts", "wmv", "flv", "webm", "mpg", "mpeg")

internal fun isVideo(file: File) = file.extension.lowercase() in VIDEO_EXTENSIONS

/**
 * Headless run for testing and scripts:
 * `--cli [--engine=FFMPEG_CLI|FFMPEG] [--mode=plain|clock] [--report=file] files-or-folders...`
 * Writes `<name>_<engine>.mp4` next to each source and reports one line per file (to stdout and, if given, to the
 * report file, which is how a GUI-subsystem launcher such as the Windows exe can be checked). True when every run worked.
 */
private fun runCli(koin: Koin, args: List<String>): Boolean {
    val backends = koin.get<VideoBackends>()
    val engine = args.firstOrNull { it.startsWith("--engine=") }?.substringAfter('=')?.let { BackendId.valueOf(it) }
    val mode = if (args.any { it == "--mode=clock" }) ProcessingMode.CLOCK else ProcessingMode.PLAIN
    val report = args.firstOrNull { it.startsWith("--report=") }?.substringAfter('=')?.let(::File)
    val lines = ArrayList<String>()
    fun say(line: String) { println(line); lines += line; report?.writeText(lines.joinToString("\n") + "\n") }
    say("engines available: ${backends.available}")
    if (BackendId.FFMPEG_CLI !in backends.available) say("note: FFMPEG_CLI unavailable: " + FfmpegCliBackend.whyUnavailable())
    val files = args.filterNot { it.startsWith("--") }.map(::File).flatMap { videosIn(it) }
    if (files.isEmpty()) say("FAILED no videos given")
    var ok = files.isNotEmpty()
    val fix = koin.get<FixVideoUseCase>()
    runBlocking {
        for (id in if (engine != null) listOf(engine) else backends.available) for (f in files) {
            val t0 = System.currentTimeMillis()
            val source = VideoSource(f.absolutePath, f.name)
            runCatching {
                val out = fix(source, ProcessingOptions(mode, backend = id), "_" + id.name.lowercase()) {}
                val info = backends.get(id).reader.probe(VideoSource(out.target.uri, "out"))
                val r = out.result
                say("$id ${f.name}: ${r.sourceFrames} frames -> ${r.uniqueFrames} unique, ${info.width}x${info.height}, ${System.currentTimeMillis() - t0} ms")
            }.onFailure { ok = false; say("FAILED $id ${f.name}: ${it::class.simpleName}: ${it.message}") }
        }
    }
    return ok
}
