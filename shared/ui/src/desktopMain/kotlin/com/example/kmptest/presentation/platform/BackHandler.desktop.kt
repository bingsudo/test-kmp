package com.example.kmptest.presentation.platform

import androidx.compose.runtime.Composable

/** The desktop has no system back; the settings screen has its own back arrow. */
@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
