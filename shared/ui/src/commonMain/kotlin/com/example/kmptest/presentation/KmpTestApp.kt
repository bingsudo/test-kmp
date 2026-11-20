package com.example.kmptest.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.kmptest.presentation.i18n.Languages
import com.example.kmptest.presentation.i18n.LocalStrings
import com.example.kmptest.presentation.i18n.Strings
import com.example.kmptest.presentation.main.MainScreen
import com.example.kmptest.presentation.platform.BackHandler
import com.example.kmptest.presentation.platform.LocalPlatform
import com.example.kmptest.presentation.platform.Platform
import com.example.kmptest.presentation.settings.SettingsScreen
import com.example.kmptest.presentation.settings.SettingsViewModel
import com.example.kmptest.presentation.theme.KmpTestTheme
import org.koin.compose.viewmodel.koinViewModel

private enum class Screen { MAIN, SETTINGS }

/** Root of the UI, shared by the Android app and the desktop app: language, theme from the settings, and the two screens. */
@Composable
fun KmpTestApp(platform: Platform, settingsViewModel: SettingsViewModel = koinViewModel()) {
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val code = Languages.resolve(settings.language, platform.systemLanguageTag)
    val strings by produceState<Strings?>(null, code) { value = Languages.load(code) }
    CompositionLocalProvider(LocalPlatform provides platform, LocalStrings provides (strings ?: Strings.Empty)) {
        KmpTestTheme(theme = settings.theme, fontScale = settings.fontScale) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                if (strings != null) {
                    var screen by remember { mutableStateOf(Screen.MAIN) }
                    BackHandler(enabled = screen == Screen.SETTINGS) { screen = Screen.MAIN }
                    // each screen centres its own content on a wide window, so backgrounds and bars span the whole window
                    Box(Modifier.fillMaxSize()) {
                        when (screen) {
                            Screen.MAIN -> MainScreen(onOpenSettings = { screen = Screen.SETTINGS })
                            Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.MAIN })
                        }
                    }
                }
            }
        }
    }
}
