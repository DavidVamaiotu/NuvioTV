@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Timer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncPreferences
import com.nuvio.tv.ui.screens.player.audiosync.AudioSyncFallback
import com.nuvio.tv.ui.screens.player.audiosync.AudioSyncSettings
import com.nuvio.tv.ui.screens.player.audiosync.SubtitleSyncStatus

/** AutoSync-owned settings rows; keeps AutoSync state out of NuvioTV PlayerSettingsDataStore. */
internal fun LazyListScope.autoSyncSettingsItems(
    enabled: Boolean,
    firstItemModifier: Modifier = Modifier,
) {
    item(key = "subtitle_auto_sync") {
        val context = LocalContext.current
        AutoSyncPreferences.ensureLoaded(context)
        val checked by AutoSyncPreferences.enabled.collectAsStateWithLifecycle()

        var offerSpeechModel by remember { mutableStateOf(false) }

        Box(modifier = firstItemModifier) {
        ToggleSettingsItem(
            icon = Icons.Default.Sync,
            title = stringResource(R.string.autosync_setting_title),
            subtitle = stringResource(R.string.autosync_setting_description),
            isChecked = checked,
            onCheckedChange = { on ->
                AutoSyncPreferences.setEnabled(context, on)
                // The audio fallback that comes with it works best with the speech model: offer it.
                AudioSyncFallback.initialize(context)
                val model = SubtitleSyncStatus.speechModel.value
                offerSpeechModel = on && AudioSyncSettings.fallbackEnabled.value && model.supported &&
                    !model.downloaded && !model.downloading
            },
            enabled = enabled,
        )
        }
        if (offerSpeechModel) SpeechModelOfferDialog(onDismiss = { offerSpeechModel = false })
    }

    item(key = "subtitle_auto_sync_tolerance") {
        val context = LocalContext.current
        AutoSyncPreferences.ensureLoaded(context)
        val toleranceMs by AutoSyncPreferences.syncToleranceMs.collectAsStateWithLifecycle()

        SliderSettingsItem(
            icon = Icons.Default.Timer,
            title = stringResource(R.string.autosync_tolerance_title),
            subtitle = stringResource(R.string.autosync_tolerance_description),
            values = AutoSyncPreferences.syncToleranceOptionsMs,
            selected = toleranceMs,
            valueText = if (toleranceMs > 0) {
                stringResource(R.string.autosync_tolerance_value, toleranceMs)
            } else {
                stringResource(R.string.autosync_tolerance_off)
            },
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
            title = stringResource(R.string.autosync_debug_logs_title),
            subtitle = stringResource(R.string.autosync_debug_logs_description),
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
            title = stringResource(R.string.autosync_thorough_title),
            subtitle = stringResource(R.string.autosync_thorough_description),
            isChecked = checked,
            onCheckedChange = { AutoSyncPreferences.setAggressiveMode(context, it) },
            enabled = enabled,
        )
    }

    audioSyncFallbackSettingsItems(enabled)
}
