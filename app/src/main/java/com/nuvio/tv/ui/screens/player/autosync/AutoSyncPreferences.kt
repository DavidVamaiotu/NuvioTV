package com.nuvio.tv.ui.screens.player.autosync

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AutoSync-owned preferences.
 *
 * Kept outside PlayerSettingsDataStore on purpose: upstream NuvioTV settings architecture does not
 * need new fields, migrations, or constructor wiring just for this fork feature.
 */
internal object AutoSyncPreferences {
    private const val PREFS_NAME = "nuvio_tv_autosync"
    private const val KEY_ENABLED = "automatic_subtitle_sync_enabled"
    private const val KEY_AGGRESSIVE_MODE = "automatic_subtitle_sync_aggressive_mode"
    private const val KEY_DEBUG_LOGS = "automatic_subtitle_sync_debug_logs"

    private val lock = Any()
    @Volatile private var initialized = false
    private var lastStartupSessionKey: Int? = null
    private var lastStartupPlaybackKey: String? = null

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _debugLogsEnabled = MutableStateFlow(false)
    val debugLogsEnabled: StateFlow<Boolean> = _debugLogsEnabled.asStateFlow()

    private val _aggressiveMode = MutableStateFlow(true)
    val aggressiveMode: StateFlow<Boolean> = _aggressiveMode.asStateFlow()

    fun ensureLoaded(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            _enabled.value = prefs.getBoolean(KEY_ENABLED, false)
            _aggressiveMode.value = prefs.getBoolean(KEY_AGGRESSIVE_MODE, true)
            _debugLogsEnabled.value = prefs.getBoolean(KEY_DEBUG_LOGS, false)
            initialized = true
        }
    }

    fun isEnabled(context: Context): Boolean {
        ensureLoaded(context)
        return _enabled.value
    }

    fun isDebugLogsEnabled(context: Context): Boolean {
        ensureLoaded(context)
        return _debugLogsEnabled.value
    }

    fun setAggressiveMode(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        if (_aggressiveMode.value == enabled) return
        _aggressiveMode.value = enabled
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AGGRESSIVE_MODE, enabled)
            .apply()
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        if (_enabled.value == enabled) return
        _enabled.value = enabled
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun setDebugLogsEnabled(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        if (_debugLogsEnabled.value == enabled) return
        _debugLogsEnabled.value = enabled
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DEBUG_LOGS, enabled)
            .apply()
    }

    fun claimStartupRun(sessionKey: Int, playbackKey: String): Boolean {
        synchronized(lock) {
            if (!_enabled.value) return false
            if (
                lastStartupSessionKey == sessionKey &&
                lastStartupPlaybackKey == playbackKey
            ) {
                return false
            }
            lastStartupSessionKey = sessionKey
            lastStartupPlaybackKey = playbackKey
            return true
        }
    }
}
