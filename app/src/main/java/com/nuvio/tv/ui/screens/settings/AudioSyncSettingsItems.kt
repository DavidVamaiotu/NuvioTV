@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NetworkCell
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.player.audiosync.AudioSyncFallback
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncPreferences
import com.nuvio.tv.ui.screens.player.audiosync.AudioSyncSettings
import com.nuvio.tv.ui.screens.player.audiosync.SubtitleSyncStatus
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Settings of the audio sync fallback, shown under AutoSync's own: it runs only after AutoSync,
 * so the rows are available only while AutoSync is on ([enabled]).
 */
internal fun LazyListScope.audioSyncFallbackSettingsItems(enabled: Boolean) {
    item(key = "audio_sync_fallback") {
        val rowsEnabled = rowsEnabled(enabled)
        val checked by AudioSyncSettings.fallbackEnabled.collectAsStateWithLifecycle()
        ToggleSettingsItem(
            icon = Icons.Default.GraphicEq,
            title = "Sync to audio when AutoSync can't",
            subtitle = "When there are no embedded subtitles or the match is too weak, aligns the subtitle with the dialogue by listening to the audio.",
            isChecked = checked,
            onCheckedChange = AudioSyncSettings::setFallbackEnabled,
            enabled = rowsEnabled,
        )
    }

    item(key = "audio_sync_speech_model") {
        val rowsEnabled = rowsEnabled(enabled)
        val fallbackEnabled by AudioSyncSettings.fallbackEnabled.collectAsStateWithLifecycle()
        val model by SubtitleSyncStatus.speechModel.collectAsStateWithLifecycle()
        if (!model.supported) return@item
        NavigationSettingsItem(
            icon = Icons.Default.CloudDownload,
            title = "Speech recognition model (${model.sizeMb} MB)",
            subtitle = when {
                model.downloading -> "Downloading… ${(model.progress * 100).toInt()}%"
                model.downloaded -> "Downloaded and ready. Select to delete"
                model.error != null -> "Download failed (${model.error}). Select to retry"
                else -> "Not downloaded. Select to download for much faster and more precise sync of English-audio content"
            },
            onClick = {
                val actions = SubtitleSyncStatus.modelActions
                if (model.downloaded) actions?.delete() else actions?.download()
            },
            enabled = rowsEnabled && fallbackEnabled && !model.downloading,
        )
    }

    item(key = "audio_sync_mobile_data") {
        val rowsEnabled = rowsEnabled(enabled)
        val fallbackEnabled by AudioSyncSettings.fallbackEnabled.collectAsStateWithLifecycle()
        val checked by AudioSyncSettings.samplingOnMobileData.collectAsStateWithLifecycle()
        ToggleSettingsItem(
            icon = Icons.Default.NetworkCell,
            title = "Sample across the film on metered networks",
            subtitle = "Syncs much faster by reading a few short parts of the film ahead of playback, also on metered connections. Uses up to 150 MB per film.",
            isChecked = checked,
            onCheckedChange = AudioSyncSettings::setSamplingOnMobileData,
            enabled = rowsEnabled && fallbackEnabled,
        )
    }

    item(key = "audio_sync_show_statistics") {
        val rowsEnabled = rowsEnabled(enabled)
        val fallbackEnabled by AudioSyncSettings.fallbackEnabled.collectAsStateWithLifecycle()
        val checked by AudioSyncSettings.showStatistics.collectAsStateWithLifecycle()
        ToggleSettingsItem(
            icon = Icons.Default.Info,
            title = stringResource(R.string.audio_sync_show_statistics_title),
            subtitle = stringResource(R.string.audio_sync_show_statistics_subtitle),
            isChecked = checked,
            onCheckedChange = AudioSyncSettings::setShowStatistics,
            enabled = rowsEnabled && fallbackEnabled,
        )
    }

    item(key = "audio_sync_share_log") {
        AudioSyncFallback.initialize(LocalContext.current)
        val actions = SubtitleSyncStatus.logActions ?: return@item
        NavigationSettingsItem(
            icon = Icons.Default.Share,
            title = "Share audio sync log",
            subtitle = "Timings and decisions of recent syncs, for reporting a problem. Links are shortened and no audio or subtitle text is included.",
            onClick = { actions.share() },
        )
    }
}

/** The rows follow AutoSync's own switch: the fallback runs only after AutoSync. */
@Composable
private fun rowsEnabled(enabled: Boolean): Boolean {
    val context = LocalContext.current
    AudioSyncFallback.initialize(context)
    AutoSyncPreferences.ensureLoaded(context)
    val autoSyncOn by AutoSyncPreferences.enabled.collectAsStateWithLifecycle()
    return enabled && autoSyncOn
}

/**
 * Offered when AutoSync is turned on without the speech model: the audio sync fallback works
 * without it, but recognising the dialogue makes it much faster and more precise.
 */
@Composable
internal fun SpeechModelOfferDialog(onDismiss: () -> Unit) {
    val model by SubtitleSyncStatus.speechModel.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(NuvioTheme.radii.xl))
                .background(NuvioTheme.colors.BackgroundCard),
        ) {
            Column(modifier = Modifier.width(520.dp).padding(NuvioTheme.spacing.xl)) {
                Text(
                    text = "Download the speech model?",
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioTheme.colors.TextPrimary,
                )
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
                Text(
                    text = "When a stream has no embedded subtitles or AutoSync's match is too weak, subtitles can " +
                        "still be synced by listening to the dialogue. Recognising the spoken words makes this much " +
                        "faster and more precise. The model is ${model.sizeMb} MB and is downloaded once.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary,
                )
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xl))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    DialogButton(
                        text = "Not now",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    )
                    DialogButton(
                        text = "Download",
                        onClick = {
                            SubtitleSyncStatus.modelActions?.download()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        highlighted = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun DialogButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundElevated,
            focusedContainerColor = if (highlighted) NuvioTheme.colors.Secondary else NuvioTheme.colors.BackgroundElevated,
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(NuvioTheme.radii.md),
            ),
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(NuvioTheme.radii.md)),
        scale = CardDefaults.scale(focusedScale = 1.05f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = if (highlighted && focused) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.TextPrimary,
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg, vertical = 14.dp).fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}
