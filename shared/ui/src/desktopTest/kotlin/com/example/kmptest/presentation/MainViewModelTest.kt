package com.example.kmptest.presentation

import com.example.kmptest.domain.model.AppSettings
import com.example.kmptest.domain.model.ProcessingOptions
import com.example.kmptest.domain.model.QueueState
import com.example.kmptest.domain.model.VideoSource
import com.example.kmptest.domain.port.ProcessingQueue
import com.example.kmptest.domain.port.SettingsRepository
import com.example.kmptest.presentation.main.MainViewModel
import com.example.kmptest.presentation.main.OptionTexts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private class FakeSettings(initial: AppSettings) : SettingsRepository {
        override val settings = MutableStateFlow(initial)
        override suspend fun update(transform: (AppSettings) -> AppSettings) = settings.update(transform)
    }

    private class FakeQueue : ProcessingQueue {
        var started: Pair<ProcessingOptions, String>? = null
        override val state: StateFlow<QueueState> = MutableStateFlow(QueueState())
        override fun add(sources: List<VideoSource>) = Unit
        override fun start(options: ProcessingOptions, outputSuffix: String) { started = options to outputSuffix }
        override fun cancel() = Unit
        override fun remove(source: VideoSource) = Unit
        override fun clear() = Unit
        override fun clearDone() = Unit
    }

    private val settings = FakeSettings(AppSettings(options = ProcessingOptions(fps = 25, crf = 30), outputSuffix = "_ok"))
    private val queue = FakeQueue()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun optionFieldsAreValidatedAsText() {
        assertTrue(OptionTexts("20", "0.08", "20").valid)
        assertTrue(OptionTexts("120", "0", "51").valid)
        assertFalse(OptionTexts("0", "0.08", "20").valid)
        assertFalse(OptionTexts("20", "-1", "20").valid)
        assertFalse(OptionTexts("20", "abc", "20").valid)
        assertFalse(OptionTexts("20", "0.08", "52").valid)
    }

    @Test
    fun validEditsAreSavedAndHalfTypedOnesAreNot() = runTest {
        val vm = MainViewModel(queue, settings)
        vm.onFps("30")
        assertEquals(30, settings.settings.value.options.fps)
        vm.onFps("")  // half-typed: not saved
        assertEquals(30, settings.settings.value.options.fps)
    }

    @Test
    fun suffixDropsCharactersNotAllowedInFileNames() = runTest {
        val vm = MainViewModel(queue, settings)
        vm.onSuffix("_a/b:c*?")
        assertEquals("_abc", settings.settings.value.outputSuffix)
    }

    @Test
    fun startRefusesInvalidFieldsAndOtherwiseRunsTheQueue() = runTest {
        val vm = MainViewModel(queue, settings)
        vm.onCrf("99")
        assertFalse(vm.start())
        assertNull(queue.started)
        vm.onCrf("18")
        vm.onSuffix("")
        assertTrue(vm.start())
        val (options, suffix) = queue.started!!
        assertEquals(25, options.fps)
        assertEquals(18, options.crf)
        assertEquals(AppSettings.DEFAULT_SUFFIX, suffix, "an empty suffix falls back to the default")
    }
}
