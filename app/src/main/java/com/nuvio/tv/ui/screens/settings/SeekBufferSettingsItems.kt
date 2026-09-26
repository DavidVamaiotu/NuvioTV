@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storage
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.player.seekbuffer.SeekBufferSettings

/** "Seek buffer" row: selecting it cycles Nuvio default / 256 MB / 512 MB / 1 GB. */
internal fun LazyListScope.seekBufferSettingsItems(
    onItemFocused: () -> Unit = {},
) {
    item(key = "seek_buffer") {
        val context = LocalContext.current
        SeekBufferSettings.initialize(context)
        val bufferMb by SeekBufferSettings.bufferMb.collectAsStateWithLifecycle()
        val value = when {
            bufferMb == SeekBufferSettings.NUVIO_DEFAULT_MB -> stringResource(R.string.settings_seek_buffer_default)
            bufferMb % 1024 == 0 -> stringResource(R.string.settings_seek_buffer_value_gb, bufferMb / 1024)
            else -> stringResource(R.string.settings_seek_buffer_value_mb, bufferMb)
        }

        NavigationSettingsItem(
            icon = Icons.Default.Storage,
            title = stringResource(R.string.settings_seek_buffer_title, value),
            subtitle = stringResource(R.string.settings_seek_buffer_description),
            onClick = { SeekBufferSettings.cycle(context) },
            onFocused = onItemFocused,
        )
    }
}
