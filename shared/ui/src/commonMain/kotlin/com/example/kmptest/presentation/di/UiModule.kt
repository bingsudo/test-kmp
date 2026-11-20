package com.example.kmptest.presentation.di

import com.example.kmptest.data.queue.ProcessingQueueImpl
import com.example.kmptest.domain.port.ProcessingQueue
import com.example.kmptest.domain.usecase.FixVideoUseCase
import com.example.kmptest.presentation.main.MainViewModel
import com.example.kmptest.presentation.settings.SettingsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Everything that is the same on both platforms. Each app adds: SettingsRepository, OutputStore, VideoBackends
 * and WorkKeeper.
 */
val sharedModule = module {
    single { FixVideoUseCase(get(), get()) }
    single<ProcessingQueue> { ProcessingQueueImpl(get(), get()) }
    viewModel { MainViewModel(get(), get()) }
    viewModel { SettingsViewModel(get(), get()) }
}
