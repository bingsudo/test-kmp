package com.example.kmptest.data.output

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.example.kmptest.domain.model.OutputTarget
import com.example.kmptest.domain.model.ProcessingException
import com.example.kmptest.domain.model.VideoSource
import com.example.kmptest.domain.port.OutputStore
import com.example.kmptest.domain.port.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Writers produce a file in the app cache; this publishes it to the folder the user chose
 * (a Storage Access Framework tree), or to Movies/KmpTest in the shared media collection.
 */
class AndroidOutputStore(
    private val context: Context,
    private val settings: SettingsRepository,
) : OutputStore {
    override suspend fun newTempFile(): String = withContext(Dispatchers.IO) {
        File.createTempFile("out", ".mp4", context.cacheDir).absolutePath
    }

    override suspend fun create(displayName: String, source: VideoSource): OutputTarget = withContext(Dispatchers.IO) {
        val tree = settings.settings.first().outputFolderUri
        if (tree != null) {
            val dir = DocumentFile.fromTreeUri(context, Uri.parse(tree))
            if (dir == null || !dir.canWrite()) throw ProcessingException("The output folder is not writable any more")
            val file = dir.createFile("video/mp4", displayName.removeSuffix(".mp4"))
                ?: throw ProcessingException("Cannot create $displayName in the output folder")
            OutputTarget(file.uri.toString(), file.name ?: displayName)
        } else {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/KmpTest")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw ProcessingException("Cannot create $displayName in Movies/KmpTest")
            OutputTarget(uri.toString(), displayName)
        }
    }

    override suspend fun publish(localPath: String, target: OutputTarget) {
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(target.uri)
            val file = File(localPath)
            try {
                context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    file.inputStream().use { it.copyTo(out, 1 shl 20) }
                } ?: throw ProcessingException("Cannot write ${target.displayName}")
                if (uri.authority == MediaStore.AUTHORITY) {
                    val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    context.contentResolver.update(uri, done, null, null)
                }
            } finally {
                file.delete()
            }
        }
    }

    override suspend fun discard(target: OutputTarget) {
        withContext(Dispatchers.IO) { runCatching { context.contentResolver.delete(Uri.parse(target.uri), null, null) } }
    }
}
