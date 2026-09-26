@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.data.local.PlayerPreference
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.ui.theme.NuvioTheme

/** The Nuvio Reshaped settings category: everything the fork adds on top of NuvioTV, in one place. */
@Composable
internal fun NuvioReshapedSettingsContent(
    initialFocusRequester: FocusRequester?,
    viewModel: PlaybackSettingsViewModel = hiltViewModel(),
) {
    val playerSettings by viewModel.playerSettings.collectAsStateWithLifecycle(initialValue = PlayerSettings())

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
    ) {
        item(key = "nuvio_reshaped_header") {
            SettingsDetailHeader(
                title = stringResource(R.string.settings_nuvio_reshaped),
                subtitle = stringResource(R.string.settings_nuvio_reshaped_description),
            )
        }
        autoSyncSettingsItems(
            enabled = playerSettings.playerPreference != PlayerPreference.EXTERNAL,
            firstItemModifier = if (initialFocusRequester != null) {
                Modifier.focusRequester(initialFocusRequester)
            } else {
                Modifier
            },
        )
        autoSyncBubbleSettingsItems(enabled = playerSettings.playerPreference != PlayerPreference.EXTERNAL) // Nuvio RS hook: AutoSync bubble
        seekrKeySettingsItems()
        seekPreviewSettingsItems()
        connectionSpeedSettingsItems()
    }
}
