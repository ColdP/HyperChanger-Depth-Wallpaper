// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package io.github.btm_m.depthwallpaper

import android.app.Application
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class HookApplication : Application() {
    companion object {
        // Legacy preference editors retain their nullable service API and use local storage.
        // The standalone wallpaper app never binds to or detects an Xposed service.
        private val serviceState = MutableStateFlow<XposedService?>(null)
        val service = serviceState.asStateFlow()
    }
}
