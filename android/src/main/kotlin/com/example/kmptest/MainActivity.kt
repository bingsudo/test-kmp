package com.example.kmptest

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.core.view.WindowCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.kmptest.domain.model.AppSettings
import com.example.kmptest.domain.model.VideoSource
import com.example.kmptest.domain.port.ProcessingQueue
import com.example.kmptest.domain.port.SettingsRepository
import com.example.kmptest.presentation.KmpTestApp
import com.example.kmptest.presentation.platform.Platform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import java.util.Locale

/** AppCompatActivity so that the per-app language (also offered in Android's own settings) works on every API level. */
class MainActivity : AppCompatActivity(), Platform {
    private val settings: SettingsRepository by inject()
    private val queue: ProcessingQueue by inject()

    private var videosResult: CompletableDeferred<List<Uri>>? = null
    private var folderResult: CompletableDeferred<Uri?>? = null
    private var notificationResult: CompletableDeferred<Unit>? = null

    private val videoLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { runCatching { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        videosResult?.complete(uris)
    }
    private val folderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        folderResult?.complete(uri)
    }
    // the foreground-service notification needs this on Android 13+; processing works without it
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationResult?.complete(Unit)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settings.settings.map { it.language }.distinctUntilChanged().collect(::applyLanguage)
            }
        }
        setContent { KmpTestApp(platform = this) }
        if (savedInstanceState == null) handleShared(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShared(intent)
    }

    // ------------------------------------------------------------------ Platform
    override val systemLanguageTag: String get() = Locale.getDefault().toLanguageTag()

    override val versionName: String
        get() = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()

    override val defaultOutputTextKey = "out_folder_default"

    override val canOpenFolder = false

    override suspend fun pickVideos(): List<VideoSource> {
        val result = CompletableDeferred<List<Uri>>().also { videosResult = it }
        videoLauncher.launch(arrayOf("video/*"))
        return result.await().map { VideoSource(it.toString(), displayName(it)) }
    }

    override suspend fun pickOutputFolder(): String? {
        val result = CompletableDeferred<Uri?>().also { folderResult = it }
        folderLauncher.launch(null)
        return result.await()?.toString()
    }

    override fun folderLabel(folder: String): String =
        runCatching { DocumentFile.fromTreeUri(this, Uri.parse(folder))?.name }.getOrNull()
            ?: Uri.parse(folder).lastPathSegment.orEmpty()

    override suspend fun beforeStart(): Boolean {
        val needs = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needs) {
            val result = CompletableDeferred<Unit>().also { notificationResult = it }
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            result.await()
        }
        return true
    }

    override fun openFolder(folder: String?, sources: List<VideoSource>) = Unit

    override fun setSystemBarsDark(dark: Boolean) {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    // ------------------------------------------------------------------ helpers
    /** Videos shared from the gallery or a file manager (Share / Open with) go straight into the list. */
    @Suppress("DEPRECATION")
    private fun handleShared(intent: Intent) {
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_STREAM),
            )
            Intent.ACTION_SEND_MULTIPLE ->
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (uris.isNotEmpty()) queue.add(uris.map { VideoSource(it.toString(), displayName(it)) })
    }

    /** The file name behind a content uri, as the picker shows it. */
    private fun displayName(uri: Uri): String {
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "video"
    }

    private var languageSeen = false

    /**
     * An explicit choice in Settings is applied as the app locale. "Automatic" only clears it when the user
     * switches back to it in this session; at startup it must leave alone a language picked in Android's own
     * per-app language settings.
     */
    private fun applyLanguage(tag: String) {
        val first = !languageSeen
        languageSeen = true
        if (tag == AppSettings.LANGUAGE_AUTO && first) return
        val wanted = if (tag == AppSettings.LANGUAGE_AUTO) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
        if (AppCompatDelegate.getApplicationLocales().toLanguageTags() != wanted.toLanguageTags()) {
            AppCompatDelegate.setApplicationLocales(wanted)
        }
    }
}
