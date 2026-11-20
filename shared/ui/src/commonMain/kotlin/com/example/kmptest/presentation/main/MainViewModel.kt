package com.example.kmptest.presentation.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.kmptest.domain.model.AppSettings
import com.example.kmptest.domain.model.ProcessingMode
import com.example.kmptest.domain.model.QueueState
import com.example.kmptest.domain.model.VideoSource
import com.example.kmptest.domain.port.ProcessingQueue
import com.example.kmptest.domain.port.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the user is typing in the three option fields (kept as text so "0." is allowed while typing). */
data class OptionTexts(val fps: String = "", val threshold: String = "", val crf: String = "", val suffix: String = AppSettings.DEFAULT_SUFFIX, val loaded: Boolean = false) {
    val fpsValue get() = fps.toIntOrNull()?.takeIf { it in 1..120 }
    val thresholdValue get() = threshold.toDoubleOrNull()?.takeIf { it >= 0.0 }
    val crfValue get() = crf.toIntOrNull()?.takeIf { it in 0..51 }
    val valid get() = fpsValue != null && thresholdValue != null && crfValue != null
}

data class MainUiState(
    val queue: QueueState = QueueState(),
    val settings: AppSettings = AppSettings(),
    val texts: OptionTexts = OptionTexts(),
    val showInvalidOptions: Boolean = false,
)

class MainViewModel(
    private val queue: ProcessingQueue,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {
    private val texts = MutableStateFlow(OptionTexts())
    private val showInvalid = MutableStateFlow(false)

    val state: StateFlow<MainUiState> = combine(queue.state, settingsRepo.settings, texts, showInvalid) { q, s, t, bad ->
        MainUiState(q, s, t, bad)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    init {
        viewModelScope.launch {
            val s = settingsRepo.settings.first()
            val o = s.options
            texts.value = OptionTexts(o.fps.toString(), o.duplicateThreshold.toString(), o.crf.toString(), s.outputSuffix, loaded = true)
        }
    }

    fun addVideos(videos: List<VideoSource>) = queue.add(videos)

    fun remove(source: VideoSource) = queue.remove(source)

    fun clear() = queue.clear()
    fun clearDone() = queue.clearDone()

    fun cancel() = queue.cancel()

    fun setOutputFolder(folder: String?) = viewModelScope.launch {
        settingsRepo.update { it.copy(outputFolderUri = folder) }
    }

    fun setMode(mode: ProcessingMode) = viewModelScope.launch {
        settingsRepo.update { it.copy(options = it.options.copy(mode = mode)) }
    }

    fun onFps(text: String) = edit(text) { copy(fps = it) }

    fun onThreshold(text: String) = edit(text) { copy(threshold = it) }

    fun onCrf(text: String) = edit(text) { copy(crf = it) }

    /** File-name suffix; characters that are illegal in file names are dropped. */
    fun onSuffix(text: String) {
        val clean = text.filter { it !in "\\/:*?\"<>|" }
        texts.update { it.copy(suffix = clean) }
        viewModelScope.launch { settingsRepo.update { it.copy(outputSuffix = clean) } }
    }

    private fun edit(text: String, change: OptionTexts.(String) -> OptionTexts) {
        texts.update { it.change(text) }
        persistIfValid()
    }

    private fun persistIfValid() {
        val t = texts.value
        if (!t.valid) return
        viewModelScope.launch {
            settingsRepo.update { it.copy(options = it.options.copy(fps = t.fpsValue!!, duplicateThreshold = t.thresholdValue!!, crf = t.crfValue!!)) }
        }
    }

    /** Returns false when the option fields are invalid (the screen then highlights them). */
    fun start(): Boolean {
        val t = texts.value
        showInvalid.value = !t.valid
        if (!t.valid) return false
        viewModelScope.launch {
            val options = settingsRepo.settings.first().options
                .copy(fps = t.fpsValue!!, duplicateThreshold = t.thresholdValue!!, crf = t.crfValue!!)
            queue.start(options, t.suffix.ifEmpty { AppSettings.DEFAULT_SUFFIX })
        }
        return true
    }
}
