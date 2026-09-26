@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.player.autosync.bubble.AutoSyncBubbleToasts

/** "Bubble notifications": AutoSync's messages in the glass bubble instead of plain toasts. On by default. */
internal fun LazyListScope.autoSyncBubbleSettingsItems(enabled: Boolean) {
    item(key = "autosync_bubble_toast") {
        val context = LocalContext.current
        AutoSyncBubbleToasts.ensureLoaded(context)
        val checked by AutoSyncBubbleToasts.enabled.collectAsStateWithLifecycle()
        ToggleSettingsItem(
            icon = Icons.Default.ChatBubble,
            title = stringResource(R.string.settings_autosync_bubble_toast),
            subtitle = stringResource(R.string.settings_autosync_bubble_toast_description),
            isChecked = checked,
            onCheckedChange = { AutoSyncBubbleToasts.setEnabled(context, it) },
            enabled = enabled,
        )
    }
}
