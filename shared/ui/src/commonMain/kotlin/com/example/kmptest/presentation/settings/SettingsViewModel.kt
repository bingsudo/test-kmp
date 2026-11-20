package com.example.kmptest.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.kmptest.domain.model.AppSettings
import com.example.kmptest.domain.model.BackendId
import com.example.kmptest.domain.model.ThemeSetting
import com.example.kmptest.domain.port.SettingsRepository
import com.example.kmptest.domain.port.VideoBackends
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val repo: SettingsRepository, backends: VideoBackends) : ViewModel() {
    /** The engines that work here; the picker shows exactly these. */
    val availableEngines: List<BackendId> = backends.available

    /** A saved engine that is not available on this device falls back to the first one that is. */
    val settings: StateFlow<AppSettings> = repo.settings
        .map { s ->
            if (s.options.backend in availableEngines || availableEngines.isEmpty()) s
            else s.copy(options = s.options.copy(backend = availableEngines.first()))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setLanguage(tag: String) = update { it.copy(language = tag) }

    fun setTheme(theme: ThemeSetting) = update { it.copy(theme = theme) }

    fun setFontScale(scale: Float) = update { it.copy(fontScale = scale) }

    fun setBackend(backend: BackendId) = update { it.copy(options = it.options.copy(backend = backend)) }

    private fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repo.update(transform) }
    }
}
