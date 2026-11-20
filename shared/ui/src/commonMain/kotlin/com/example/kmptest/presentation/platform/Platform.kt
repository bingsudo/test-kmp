package com.example.kmptest.presentation.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.example.kmptest.domain.model.VideoSource

/** What the shared UI needs from the host system: file pickers, locale, system bars. Implemented by the Android and desktop apps. */
interface Platform {
    /** BCP 47 tag of the system language. */
    val systemLanguageTag: String

    /** Version shown in the settings. */
    val versionName: String

    /** Translation key of the text shown while no output folder is chosen. */
    val defaultOutputTextKey: String

    /** True when the system can show a folder in its file manager ("Open" button). */
    val canOpenFolder: Boolean

    suspend fun pickVideos(): List<VideoSource>

    /** A reference the data layer understands (a tree uri on Android, a directory path on the desktop); null when cancelled. */
    suspend fun pickOutputFolder(): String?

    /** A readable name for [folder]. */
    fun folderLabel(folder: String): String

    /** Called when Start is pressed; false cancels (e.g. nothing granted that is required). */
    suspend fun beforeStart(): Boolean

    fun openFolder(folder: String?, sources: List<VideoSource>)

    /** Light or dark system bar icons (Android only). */
    fun setSystemBarsDark(dark: Boolean)
}

val LocalPlatform = staticCompositionLocalOf<Platform> { error("No Platform provided") }

/** System back (Android back button / gesture). */
@Composable
expect fun BackHandler(enabled: Boolean, onBack: () -> Unit)
