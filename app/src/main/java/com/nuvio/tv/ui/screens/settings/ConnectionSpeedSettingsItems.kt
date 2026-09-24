@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.core.connection.ConnectionSpeedEstimator
import kotlin.math.roundToInt

/** "Match streams to connection" row; its state lives in ConnectionSpeedEstimator, not PlayerSettingsDataStore. */
internal fun LazyListScope.connectionSpeedSettingsItems(
    onItemFocused: () -> Unit = {},
) {
    item(key = "stream_connection_fit") {
        val context = LocalContext.current
        ConnectionSpeedEstimator.ensureLoaded(context)
        val checked by ConnectionSpeedEstimator.enabled.collectAsStateWithLifecycle()
        val revision by ConnectionSpeedEstimator.revision.collectAsStateWithLifecycle()
        val estimateMbps = remember(revision) { ConnectionSpeedEstimator.estimateMbps(context) }
        val status = if (estimateMbps == null) {
            stringResource(R.string.settings_stream_connection_fit_learning)
        } else {
            stringResource(R.string.settings_stream_connection_fit_measured, estimateMbps.roundToInt())
        }

        ToggleSettingsItem(
            icon = Icons.Default.Speed,
            title = stringResource(R.string.settings_stream_connection_fit_title),
            subtitle = stringResource(R.string.settings_stream_connection_fit_description) + "\n" + status,
            isChecked = checked,
            onCheckedChange = { ConnectionSpeedEstimator.setEnabled(context, it) },
            onFocused = onItemFocused,
        )
    }
}
