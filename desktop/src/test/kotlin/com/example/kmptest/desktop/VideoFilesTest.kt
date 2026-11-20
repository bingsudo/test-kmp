package com.example.kmptest.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What goes into the list when files or folders are dropped on the window or given on the command line. */
class VideoFilesTest {
    private val dir: File = Files.createTempDirectory("kmt-files").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun touch(path: String) = File(dir, path).apply { parentFile.mkdirs(); writeText("") }

    @Test
    fun folderGivesItsVideosRecursivelyInPathOrder() {
        touch("b.MP4")
        touch("a.mov")
        touch("notes.txt")
        touch("sub/c.mkv")
        touch("sub/deeper/d.ts")
        touch("sub/cover.jpg")
        val names = videosIn(dir).map { it.relativeTo(dir).invariantSeparatorsPath }
        assertEquals(listOf("a.mov", "b.MP4", "sub/c.mkv", "sub/deeper/d.ts"), names)
    }

    @Test
    fun aFileIsTakenAsGivenAndMissingPathsAreIgnored() {
        val any = touch("clip.unknown")
        assertEquals(listOf(any), videosIn(any))
        assertEquals(emptyList(), videosIn(File(dir, "missing.mp4")))
    }

    @Test
    fun videoExtensionsIgnoreCase() {
        assertTrue(isVideo(File("x.Mp4")))
        assertTrue(isVideo(File("x.webm")))
        assertTrue(!isVideo(File("x.mp3")))
        assertTrue(!isVideo(File("mp4")))
    }
}
