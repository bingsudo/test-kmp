package com.example.kmptest

import android.app.Application
import com.example.kmptest.data.backend.androidBackends
import com.example.kmptest.data.output.AndroidOutputStore
import com.example.kmptest.data.queue.WorkKeeper
import com.example.kmptest.data.settings.DataStoreSettingsRepository
import com.example.kmptest.domain.port.OutputStore
import com.example.kmptest.domain.port.SettingsRepository
import com.example.kmptest.domain.port.VideoBackends
import com.example.kmptest.presentation.di.sharedModule
import com.example.kmptest.service.ServiceWorkKeeper
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.dsl.module
import java.io.File

class KmpTestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@KmpTestApplication)
            modules(sharedModule, androidModule)
        }
    }
}

/** The Android implementations of what the shared module needs. */
private val androidModule = module {
    // the same file the former preferencesDataStore("settings") used, so saved settings survive
    single<SettingsRepository> { DataStoreSettingsRepository(File(androidContext().filesDir, "datastore/settings.preferences_pb").path) }
    single<OutputStore> { AndroidOutputStore(androidContext(), get()) }
    single<VideoBackends> { androidBackends(androidContext()) }
    single<WorkKeeper> { ServiceWorkKeeper(androidContext()) }
}
