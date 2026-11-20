package com.example.kmptest.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.kmptest.domain.model.AppSettings
import com.example.kmptest.domain.model.BackendId
import com.example.kmptest.domain.model.ThemeSetting
import com.example.kmptest.presentation.i18n.Languages
import com.example.kmptest.presentation.i18n.fill
import com.example.kmptest.presentation.i18n.str
import com.example.kmptest.presentation.platform.LocalPlatform
import org.koin.compose.viewmodel.koinViewModel
import com.example.kmptest.presentation.theme.GlassCard
import com.example.kmptest.presentation.theme.SectionTitle
import com.example.kmptest.presentation.theme.centeredContent

@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = koinViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        Box(
            Modifier.fillMaxWidth().background(
                Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), Color.Transparent)),
            ),
        ) {
            Row(Modifier.centeredContent().statusBarsPadding().padding(start = 4.dp, end = 16.dp, top = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = str("back"), tint = MaterialTheme.colorScheme.primary)
                }
                Text(str("settings_title"), style = MaterialTheme.typography.titleLarge)
            }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        Column(
            Modifier.centeredContent().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            GlassCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    LanguagePicker(s, vm)
                    ThemePicker(s.theme, vm::setTheme)
                    FontScale(s.fontScale, vm::setFontScale)
                }
            }
            GlassCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    EnginePicker(s.options.backend, vm.availableEngines, vm::setBackend)
                }
            }
            val version = LocalPlatform.current.versionName
            GlassCard {
                Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(str("app_title"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("${str("version")} $version", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Dropdown(label: String, selectedText: String, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = selectedText,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            shape = MaterialTheme.shapes.medium,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((value, text) in options) {
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onSelect(value) })
            }
        }
    }
}

@Composable
private fun LanguagePicker(s: AppSettings, vm: SettingsViewModel) {
    val names by produceState(emptyMap<String, String>()) { value = Languages.nativeNames() }
    val systemCode = Languages.resolve(Languages.AUTO, LocalPlatform.current.systemLanguageTag)
    val auto = str("lang_auto").fill("language" to (names[systemCode] ?: systemCode))
    val options = listOf(AppSettings.LANGUAGE_AUTO to auto) + Languages.codes.map { it to (names[it] ?: it) }
    val selected = options.firstOrNull { it.first == s.language }?.second ?: auto
    Dropdown(str("language"), selected, options, vm::setLanguage)
}

@Composable
private fun ThemePicker(theme: ThemeSetting, onSelect: (ThemeSetting) -> Unit) {
    val options = listOf(
        ThemeSetting.SYSTEM to str("theme_system"),
        ThemeSetting.LIGHT to str("theme_light"),
        ThemeSetting.DARK to str("theme_dark"),
        ThemeSetting.BLACK to str("theme_black"),
    )
    Dropdown(str("theme"), options.first { it.first == theme }.second, options, onSelect)
}

@Composable
private fun FontScale(scale: Float, onChange: (Float) -> Unit) {
    var live by remember(scale) { mutableStateOf(scale) }
    Column {
        Text("${str("font_size")}: ${Math.round(live * 100)}%", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = live,
            onValueChange = { live = it },
            onValueChangeFinished = { onChange((Math.round(live * 20) / 20f)) },
            valueRange = 0.8f..1.6f,
        )
    }
}

@Composable
private fun EnginePicker(current: BackendId, available: List<BackendId>, onSelect: (BackendId) -> Unit) {
    SectionTitle(str("engine"))
    for (id in available) {
        val text = when (id) {
            BackendId.PLATFORM -> "engine_platform"
            BackendId.OPENCV -> "engine_opencv"
            BackendId.FFMPEG -> "engine_ffmpeg"
            BackendId.FFMPEG_CLI -> "engine_ffmpeg_cli"
        }
        GlassCard(selected = current == id, onClick = { onSelect(id) }) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = current == id, onClick = null, modifier = Modifier.padding(8.dp))
                Text(str(text), modifier = Modifier.padding(end = 8.dp))
            }
        }
    }
    Text(str("engine_hint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
