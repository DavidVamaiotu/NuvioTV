@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.ui.reshaped.pillnav.PillNavPreferences

/** "Pill menu" row: a floating top menu that replaces the sidebar. Off by default. */
internal fun LazyListScope.pillNavSettingsItems(
    onItemFocused: () -> Unit = {},
) {
    item(key = "pill_nav_enabled") {
        val context = LocalContext.current
        PillNavPreferences.ensureLoaded(context)
        val checked by PillNavPreferences.enabled.collectAsStateWithLifecycle()

        ToggleSettingsItem(
            icon = Icons.Default.Menu,
            title = stringResource(R.string.settings_pill_nav_title),
            subtitle = stringResource(R.string.settings_pill_nav_description),
            isChecked = checked,
            onCheckedChange = { PillNavPreferences.setEnabled(context, it) },
            onFocused = onItemFocused,
        )
    }
}
