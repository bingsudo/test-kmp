package com.example.kmptest

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.kmptest.data.output.AndroidOutputStore
import com.example.kmptest.domain.model.AppSettings
import com.example.kmptest.domain.model.VideoSource
import com.example.kmptest.domain.port.ProcessingQueue
import com.example.kmptest.domain.port.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.get
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class OutputAndAppTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val compose = createEmptyComposeRule()

    @After
    fun cleanUp() {
        context.cacheDir.listFiles()?.filter { it.name.endsWith(".mp4") }?.forEach(File::delete)
    }

    private fun sizeInMediaStore(uri: Uri): Long? =
        context.contentResolver.query(uri, arrayOf(MediaStore.Video.Media.SIZE, MediaStore.Video.Media.RELATIVE_PATH), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getLong(0).also { assertEquals("Movies/KmpTest/", c.getString(1)) } else null }

    /** Without a chosen folder the result goes to Movies/KmpTest; a cancelled one leaves nothing behind. */
    @Test
    fun defaultOutputIsTheMoviesFolder() = runBlocking {
        val noFolder = object : SettingsRepository {
            override val settings: Flow<AppSettings> = flowOf(AppSettings(outputFolderUri = null))
            override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
        }
        val store = AndroidOutputStore(context, noFolder)
        val target = store.create("kmt_instrumented_test.mp4", VideoSource("unused", "unused"))
        val uri = Uri.parse(target.uri)
        assertEquals(MediaStore.AUTHORITY, uri.authority)
        try {
            val temp = File(store.newTempFile()).apply { writeBytes(ByteArray(1234) { it.toByte() }) }
            store.publish(temp.path, target)
            assertEquals(1234L, sizeInMediaStore(uri))
            assertEquals(false, temp.exists(), "the cache copy is removed")
        } finally {
            store.discard(target)
        }
        assertNull(sizeInMediaStore(uri))
    }

    /** A video shared to the app (Share / Open with, always a content:// uri) shows up in the list. */
    @Test
    fun sharedVideoAppearsInTheList() {
        val name = "kmt_shared_clip.mp4"
        val local = File(Uri.parse(TestClips.laggy(context, name).uri).path!!)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/KmpTest")
        }
        val shared = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
        val queue: ProcessingQueue = get(ProcessingQueue::class.java)
        try {
            resolver.openOutputStream(shared)!!.use { out -> local.inputStream().use { it.copyTo(out) } }
            val intent = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setDataAndType(shared, "video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Started and finished directly rather than with ActivityScenario: closing a scenario launches an activity of
            // the test package, which MIUI holds behind an "allow this app to open another app?" prompt for 45 s.
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val activity = instrumentation.startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            try {
                compose.waitUntil(10_000) { queue.state.value.items.any { it.source.uri == shared.toString() } }
                compose.onNodeWithText(name, substring = true).assertExists()
            } finally {
                activity.finish()
                instrumentation.waitForIdleSync()
            }
            assertEquals(name, queue.state.value.items.first { it.source.uri == shared.toString() }.source.name)
        } finally {
            queue.remove(VideoSource(shared.toString(), name))
            resolver.delete(shared, null, null)
        }
    }
}
