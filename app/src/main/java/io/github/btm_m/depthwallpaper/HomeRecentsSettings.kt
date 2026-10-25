// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package io.github.btm_m.depthwallpaper

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal const val KEY_HOME_RECENTS_CLEAR_MODE = "home_recents_clear_mode"

internal enum class HomeRecentsClearMode(val value: Int) {
    NONE(0),
    HIDE_KEEP_FUNCTION(1),
    HIDE_NO_FUNCTION(2),
    ;

    companion object {
        fun fromValue(value: Int): HomeRecentsClearMode = entries.firstOrNull { it.value == value } ?: NONE
    }
}

internal data class HomeRecentsSettings(
    val clearMode: HomeRecentsClearMode = HomeRecentsClearMode.NONE,
)

internal class HomeRecentsSettingsStore(private val context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)

    var settings: HomeRecentsSettings = read(local)
        private set

    fun reload() {
        settings = read(local)
    }

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        settings = if (remote.contains(KEY_HOME_RECENTS_CLEAR_MODE)) read(remote) else settings
        write(local, settings)
        write(remote, settings)
    }

    fun update(service: XposedService?, transform: (HomeRecentsSettings) -> HomeRecentsSettings): Boolean {
        settings = transform(settings)
        write(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }
        return true
    }

    private fun read(prefs: SharedPreferences) = readHomeRecentsSettings(prefs)

    private fun write(prefs: SharedPreferences, value: HomeRecentsSettings) {
        prefs.edit().putInt(KEY_HOME_RECENTS_CLEAR_MODE, value.clearMode.value).apply()
    }
}

internal fun readHomeRecentsSettings(prefs: SharedPreferences) = HomeRecentsSettings(
        clearMode = HomeRecentsClearMode.fromValue(
            prefs.getInt(KEY_HOME_RECENTS_CLEAR_MODE, HomeRecentsClearMode.NONE.value),
        ),
    )
