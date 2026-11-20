package com.example.kmptest.data

import com.example.kmptest.data.settings.DataStoreSettingsRepository
import com.example.kmptest.domain.model.AppSettings
import com.example.kmptest.domain.model.BackendId
import com.example.kmptest.domain.model.ProcessingMode
import com.example.kmptest.domain.model.ProcessingOptions
import com.example.kmptest.domain.model.ThemeSetting
import com.example.kmptest.domain.model.VideoSource
import com.example.kmptest.domain.port.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Settings file and output files on the desktop, in a temporary folder. */
class DesktopStorageTest {
    private val dir: File = Files.createTempDirectory("kmt-test").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun fixedSettings(outputFolder: String?) = object : SettingsRepository {
        override val settings: Flow<AppSettings> = flowOf(AppSettings(outputFolderUri = outputFolder))
        override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
    }

    @Test
    fun settingsStartAtTheDefaultsAndKeepEveryField() = runBlocking {
        val repo = DataStoreSettingsRepository(File(dir, "settings.preferences_pb").path)
        assertEquals(AppSettings(), repo.settings.first())
        val changed = AppSettings(
            language = "ja",
            theme = ThemeSetting.BLACK,
            fontScale = 1.25f,
            options = ProcessingOptions(ProcessingMode.CLOCK, fps = 25, duplicateThreshold = 0.5, crf = 28, backend = BackendId.FFMPEG_CLI),
            outputFolderUri = "/videos/out",
            outputSuffix = "_smooth",
        )
        repo.update { changed }
        assertEquals(changed, repo.settings.first())
        repo.update { it.copy(outputFolderUri = null) }
        assertEquals(changed.copy(outputFolderUri = null), repo.settings.first())
    }

    @Test
    fun outputGoesNextToTheSourceAndNeverOverwrites() = runBlocking {
        val source = File(dir, "cam.mp4").apply { writeText("source") }
        val store = DesktopOutputStore(fixedSettings(null))
        File(dir, "cam_fixed.mp4").writeText("earlier result")
        val first = store.create("cam_fixed.mp4", VideoSource(source.path, source.name))
        val second = store.create("cam_fixed.mp4", VideoSource(source.path, source.name))
        assertEquals("cam_fixed (2).mp4", first.displayName)
        assertEquals("cam_fixed (3).mp4", second.displayName)
        assertEquals(File(dir, "cam_fixed (2).mp4").absolutePath, first.uri)
        assertEquals("earlier result", File(dir, "cam_fixed.mp4").readText())
    }

    @Test
    fun chosenFolderIsCreatedAndPublishMovesTheFile() = runBlocking {
        val out = File(dir, "nested/out")
        val store = DesktopOutputStore(fixedSettings(out.path))
        val target = store.create("a_fixed.mp4", VideoSource(File(dir, "a.mp4").path, "a.mp4"))
        assertEquals(File(out, "a_fixed.mp4").absolutePath, target.uri)
        val temp = File(store.newTempFile()).apply { writeText("video") }
        store.publish(temp.path, target)
        assertFalse(temp.exists())
        assertEquals("video", File(target.uri).readText())
        store.discard(target)
        assertFalse(File(target.uri).exists())
        assertTrue(out.isDirectory)
    }
}
