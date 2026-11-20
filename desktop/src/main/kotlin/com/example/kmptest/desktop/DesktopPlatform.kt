package com.example.kmptest.desktop

import com.example.kmptest.domain.model.VideoSource
import com.example.kmptest.presentation.platform.Platform
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.path
import java.awt.Desktop
import java.io.File
import java.util.Locale

/** Native (modern) file dialogs, the system locale, and the file manager. */
class DesktopPlatform : Platform {
    override val systemLanguageTag: String get() = Locale.getDefault().toLanguageTag()

    override val versionName: String = System.getProperty("jpackage.app-version") ?: "1.0"

    override val defaultOutputTextKey = "out_next_to_source"

    override val canOpenFolder = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)

    override suspend fun pickVideos(): List<VideoSource> {
        val picked = FileKit.openFilePicker(type = FileKitType.File(VIDEO_EXTENSIONS), mode = FileKitMode.Multiple()).orEmpty()
        return picked.flatMap { videosIn(File(it.path)) }.map { VideoSource(it.absolutePath, it.name) }
    }

    override suspend fun pickOutputFolder(): String? = FileKit.openDirectoryPicker()?.path

    override fun folderLabel(folder: String): String = folder

    override suspend fun beforeStart(): Boolean = true

    override fun openFolder(folder: String?, sources: List<VideoSource>) {
        val dir = folder?.takeIf { it.isNotBlank() }?.let(::File) ?: sources.firstOrNull()?.let { File(it.uri).parentFile }
        if (dir != null && dir.isDirectory) runCatching { Desktop.getDesktop().open(dir) }
    }

    override fun setSystemBarsDark(dark: Boolean) = Unit
}
