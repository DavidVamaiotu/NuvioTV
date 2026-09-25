@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.updater.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.reshaped.ReshapedIdentity
import com.nuvio.tv.reshaped.ReshapedMigration
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.updater.ReshapedApkAssets
import com.nuvio.tv.updater.UpdateUiState

/** "Later" hides the prompts until the app process restarts. */
private object ReshapedPromptSession {
    var dismissed = false
}

private data class ReshapedPrompt(
    val title: Int,
    val message: String,
    val actions: List<Pair<Int, () -> Unit>>,
)

/**
 * The prompts that move people from the pre-rename fork to Nuvio RS: the old build (bridge)
 * installs Nuvio RS through the regular updater, Nuvio RS imports the settings and then offers
 * to uninstall the old app.
 */
@Composable
internal fun ReshapedMigrationHost(state: UpdateUiState, onDownload: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Re-evaluated on every resume: installs, uninstalls and updates happen outside the app.
    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val prompt = remember(resumeTick, state.update, state.isDownloading, state.downloadProgress) {
        if (ReshapedPromptSession.dismissed) {
            null
        } else if (ReshapedIdentity.isLegacyBuild(context)) {
            bridgePrompt(context, state, onDownload)
        } else if (ReshapedIdentity.isReshapedBuild(context)) {
            reshapedPrompt(context) { resumeTick++ }
        } else {
            null
        }
    } ?: return

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(prompt.title, prompt.actions.size) { firstFocus.requestFocusAfterFrames() }
    val dismiss: () -> Unit = {
        ReshapedPromptSession.dismissed = true
        resumeTick++
    }

    NuvioDialog(
        onDismiss = dismiss,
        title = stringResource(prompt.title),
        subtitle = prompt.message,
        width = 640.dp,
        suppressFirstKeyUp = false,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
        ) {
            (prompt.actions + (R.string.nuvio_rs_later to dismiss)).forEachIndexed { index, (label, action) ->
                PromptButton(
                    label = stringResource(label),
                    primary = index == 0 && prompt.actions.isNotEmpty(),
                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                    onClick = action,
                )
            }
        }
    }
}

@Composable
private fun PromptButton(label: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = if (primary) {
            ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.Secondary,
                focusedContainerColor = NuvioTheme.colors.SecondaryVariant,
                contentColor = NuvioTheme.colors.OnSecondary,
                focusedContentColor = NuvioTheme.colors.OnSecondaryVariant,
            )
        } else {
            ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                focusedContainerColor = NuvioTheme.colors.FocusBackground,
                contentColor = NuvioTheme.colors.TextPrimary,
                focusedContentColor = NuvioTheme.colors.Primary,
            )
        },
        shape = ButtonDefaults.shape(RoundedCornerShape(50)),
    ) {
        Text(label)
    }
}

private fun bridgePrompt(context: Context, state: UpdateUiState, onDownload: () -> Unit): ReshapedPrompt? {
    if (ReshapedIdentity.isOurInstalledPackage(context, ReshapedIdentity.RESHAPED_PACKAGE)) {
        return ReshapedPrompt(
            title = R.string.nuvio_rs_moved_title,
            message = context.getString(R.string.nuvio_rs_bridge_installed_message),
            actions = listOf(
                R.string.nuvio_rs_open to { launch(context, ReshapedIdentity.RESHAPED_PACKAGE) },
                R.string.nuvio_rs_uninstall_old to { uninstall(context, context.packageName) },
            ),
        )
    }
    // The regular updater has already picked the Nuvio RS APK (see ReshapedBridge).
    if (state.update?.assetName?.startsWith(ReshapedApkAssets.PREFIX) != true) return null
    val message = if (state.isDownloading) {
        context.getString(R.string.nuvio_rs_downloading, ((state.downloadProgress ?: 0f) * 100).toInt())
    } else {
        context.getString(R.string.nuvio_rs_bridge_install_message)
    }
    return ReshapedPrompt(
        title = R.string.nuvio_rs_moved_title,
        message = message,
        actions = if (state.isDownloading) emptyList() else listOf(R.string.nuvio_rs_install to onDownload),
    )
}

private fun reshapedPrompt(context: Context, refresh: () -> Unit): ReshapedPrompt? {
    if (!ReshapedIdentity.isOurInstalledPackage(context, ReshapedIdentity.LEGACY_PACKAGE)) return null
    return when (ReshapedMigration.state(context)) {
        ReshapedMigration.STATE_IMPORTED -> ReshapedPrompt(
            title = R.string.nuvio_rs_imported_title,
            message = context.getString(R.string.nuvio_rs_imported_message),
            actions = listOf(
                R.string.nuvio_rs_uninstall_old to {
                    ReshapedMigration.setState(context, ReshapedMigration.STATE_DONE)
                    uninstall(context, ReshapedIdentity.LEGACY_PACKAGE)
                    refresh()
                },
                R.string.nuvio_rs_keep to {
                    ReshapedMigration.setState(context, ReshapedMigration.STATE_DONE)
                    refresh()
                },
            ),
        )
        ReshapedMigration.STATE_PENDING -> if (ReshapedMigration.legacyExportAvailable(context)) {
            // Settings are imported only at process start, before anything reads them.
            ReshapedPrompt(
                title = R.string.nuvio_rs_pending_title,
                message = context.getString(R.string.nuvio_rs_ready_message),
                actions = listOf(R.string.nuvio_rs_restart to { restart(context) }),
            )
        } else {
            ReshapedPrompt(
                title = R.string.nuvio_rs_pending_title,
                message = context.getString(R.string.nuvio_rs_pending_message),
                actions = listOf(
                    R.string.nuvio_rs_open_old to { launch(context, ReshapedIdentity.LEGACY_PACKAGE) },
                    R.string.nuvio_rs_start_fresh to {
                        ReshapedMigration.setState(context, ReshapedMigration.STATE_DONE)
                        refresh()
                    },
                ),
            )
        }
        else -> null
    }
}

private fun launch(context: Context, packageName: String) {
    val pm = context.packageManager
    val intent = pm.getLeanbackLaunchIntentForPackage(packageName) ?: pm.getLaunchIntentForPackage(packageName) ?: return
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun uninstall(context: Context, packageName: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun restart(context: Context) {
    val pm = context.packageManager
    val launchIntent = pm.getLeanbackLaunchIntentForPackage(context.packageName)
        ?: pm.getLaunchIntentForPackage(context.packageName)
        ?: return
    context.startActivity(Intent.makeRestartActivityTask(launchIntent.component))
    (context as? Activity)?.finishAffinity()
    Runtime.getRuntime().exit(0)
}
