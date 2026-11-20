package com.example.kmptest.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.kmptest.domain.model.AppSettings
import com.example.kmptest.domain.model.BackendId
import com.example.kmptest.domain.model.ProcessingMode
import com.example.kmptest.domain.model.ProcessingOptions
import com.example.kmptest.domain.model.ThemeSetting
import com.example.kmptest.domain.port.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okio.Path.Companion.toPath

/** Settings in a DataStore file at [path] (the same preferences file format on every platform). */
class DataStoreSettingsRepository(path: String) : SettingsRepository {
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.createWithPath(produceFile = { path.toPath() })

    private object Keys {
        val language = stringPreferencesKey("language")
        val theme = stringPreferencesKey("theme")
        val fontScale = floatPreferencesKey("font_scale")
        val mode = stringPreferencesKey("mode")
        val fps = intPreferencesKey("fps")
        val threshold = doublePreferencesKey("threshold")
        val crf = intPreferencesKey("crf")
        val backend = stringPreferencesKey("backend")
        val outputFolder = stringPreferencesKey("output_folder")
        val suffix = stringPreferencesKey("output_suffix")
    }

    override val settings: Flow<AppSettings> = store.data.map(::read)

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { p ->
            val n = transform(read(p))
            p[Keys.language] = n.language
            p[Keys.theme] = n.theme.name
            p[Keys.fontScale] = n.fontScale
            p[Keys.mode] = n.options.mode.name
            p[Keys.fps] = n.options.fps
            p[Keys.threshold] = n.options.duplicateThreshold
            p[Keys.crf] = n.options.crf
            p[Keys.backend] = n.options.backend.name
            p[Keys.suffix] = n.outputSuffix
            val folder = n.outputFolderUri
            if (folder == null) p.remove(Keys.outputFolder) else p[Keys.outputFolder] = folder
        }
    }

    private fun read(p: Preferences): AppSettings {
        val d = ProcessingOptions()
        return AppSettings(
            language = p[Keys.language] ?: AppSettings.LANGUAGE_AUTO,
            theme = p[Keys.theme]?.let { runCatching { ThemeSetting.valueOf(it) }.getOrNull() } ?: ThemeSetting.SYSTEM,
            fontScale = p[Keys.fontScale] ?: 1f,
            options = ProcessingOptions(
                mode = p[Keys.mode]?.let { runCatching { ProcessingMode.valueOf(it) }.getOrNull() } ?: d.mode,
                fps = p[Keys.fps] ?: d.fps,
                duplicateThreshold = p[Keys.threshold] ?: d.duplicateThreshold,
                crf = p[Keys.crf] ?: d.crf,
                backend = p[Keys.backend]?.let { runCatching { BackendId.valueOf(it) }.getOrNull() } ?: d.backend,
            ),
            outputFolderUri = p[Keys.outputFolder],
            outputSuffix = p[Keys.suffix] ?: AppSettings.DEFAULT_SUFFIX,
        )
    }
}
