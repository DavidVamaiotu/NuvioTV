@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Timer
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncPreferences

/** AutoSync-owned settings rows; keeps fork-specific state out of NuvioTV PlayerSettingsDataStore. */
internal fun LazyListScope.autoSyncSettingsItems(
    enabled: Boolean,
    firstItemModifier: Modifier = Modifier,
) {
    item(key = "subtitle_auto_sync") {
        val context = LocalContext.current
        AutoSyncPreferences.ensureLoaded(context)
        val checked by AutoSyncPreferences.enabled.collectAsStateWithLifecycle()

        Box(modifier = firstItemModifier) {
        ToggleSettingsItem(
            icon = Icons.Default.Sync,
            title = "Auto Sync Subtitle",
            subtitle = "Automatically sync the preferred add-on subtitle against embedded subtitle timing when playback starts.",
            isChecked = checked,
            onCheckedChange = { AutoSyncPreferences.setEnabled(context, it) },
            enabled = enabled,
        )
        }
    }

    item(key = "subtitle_auto_sync_tolerance") {
        val context = LocalContext.current
        AutoSyncPreferences.ensureLoaded(context)
        val toleranceMs by AutoSyncPreferences.syncToleranceMs.collectAsStateWithLifecycle()

        SliderSettingsItem(
            icon = Icons.Default.Timer,
            title = "Auto Sync Tolerance",
            subtitle = "Keep the original subtitle timing when the sync would move it by no more than this.",
            values = AutoSyncPreferences.syncToleranceOptionsMs,
            selected = toleranceMs,
            valueText = if (toleranceMs > 0) "$toleranceMs ms" else "Off",
            onValueChange = { AutoSyncPreferences.setSyncToleranceMs(context, it) },
            enabled = enabled,
        )
    }

    item(key = "subtitle_auto_sync_debug_logs") {
        val context = LocalContext.current
        AutoSyncPreferences.ensureLoaded(context)
        val checked by AutoSyncPreferences.debugLogsEnabled.collectAsStateWithLifecycle()

        ToggleSettingsItem(
            icon = Icons.Default.BugReport,
            title = "Debug Logs for Auto Sync",
            subtitle = "Save a verbose AutoSync timing report and mirror it to Logcat for difficult subtitle cases.",
            isChecked = checked,
            onCheckedChange = { AutoSyncPreferences.setDebugLogsEnabled(context, it) },
            enabled = enabled,
        )
    }

    item(key = "subtitle_auto_sync_aggressive_mode") {
        val context = LocalContext.current
        AutoSyncPreferences.ensureLoaded(context)
        val checked by AutoSyncPreferences.aggressiveMode.collectAsStateWithLifecycle()

        ToggleSettingsItem(
            icon = Icons.Default.Sync,
            title = "Aggressive Auto Sync",
            subtitle = "Search more same-language subtitle candidates before giving up. Disable for the faster passive V2 policy.",
            isChecked = checked,
            onCheckedChange = { AutoSyncPreferences.setAggressiveMode(context, it) },
            enabled = enabled,
        )
    }
}
