package com.nuvio.tv.ui.screens.player.seekpreview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.ui.screens.player.PlayerEvent
import com.nuvio.tv.ui.screens.player.PlayerUiState
import com.nuvio.tv.ui.screens.player.PlayerViewModel

/*
 * Seek-preview (Seekr) integration points for the TV player. Everything the player needs lives
 * here and in this package, so upstream player files only carry short calls in.
 */

/** Ticks closer than this are noise rather than information, so the grid is hidden instead. */
private val MinCueTickSpacing = 5.dp

/** Upper bound on tick count, so a long title cannot turn the scrubber into a solid block. */
private const val MaxCueTicks = 400L

/**
 * Seek-preview thumbnails above the controls' progress bar. Takes no height in the layout —
 * it is drawn above whatever precedes the bar — so the controls never shift.
 */
@Composable
fun SeekPreviewAboveProgressBar(viewModel: PlayerViewModel) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, 0) {
                    placeable.placeRelative(0, -placeable.height)
                }
            }
    ) {
        SeekPreviewThumbnailHost(viewModel = viewModel)
    }
}

/**
 * Cue ticks across a progress bar, at the positions grid-locked scrubbing can stop on.
 * Suppressed once they would be denser than the eye can separate — at that point the 10s grid
 * is a rounding error on the bar, and the preview frame carries the granularity.
 */
@Composable
fun SeekPreviewCueTicks(viewModel: PlayerViewModel, durationMs: Long, modifier: Modifier) {
    val cueIntervalMs by viewModel.seekPreview.cueIntervalMs.collectAsStateWithLifecycle()
    if (cueIntervalMs <= 0L || durationMs <= 0L) return
    val tickCount = durationMs / cueIntervalMs
    if (tickCount !in 2..MaxCueTicks) return
    Canvas(modifier = modifier) {
        val stepPx = size.width * (cueIntervalMs.toFloat() / durationMs.toFloat())
        if (stepPx < MinCueTickSpacing.toPx()) return@Canvas
        var x = stepPx
        while (x < size.width) {
            drawLine(
                color = Color.White.copy(alpha = 0.28f),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 1.dp.toPx()
            )
            x += stepPx
        }
    }
}

/** Opens Preview Sync, or null when no Seekr track loaded for this title. */
@Composable
fun seekPreviewSyncAction(viewModel: PlayerViewModel): (() -> Unit)? {
    val track by viewModel.seekPreview.track.collectAsStateWithLifecycle()
    if (track == null) return null
    return { viewModel.seekPreview.showSyncOverlay() }
}

/**
 * Whether the Preview Sync panel is actually on screen. Shared by the render site and the Back
 * handler: if they diverged, Back could be consumed by a panel the user cannot see.
 */
fun SeekPreviewState.isSyncVisible(uiState: PlayerUiState): Boolean =
    isSyncOverlayOpen && uiState.error == null && !uiState.showLoadingOverlay

/** Closes Preview Sync on Back; returns true when it consumed the press. */
fun SeekPreviewState.handleBack(uiState: PlayerUiState): Boolean {
    if (!isSyncOverlayOpen) return false
    val visible = isSyncVisible(uiState)
    hideSyncOverlay()
    return visible
}

/**
 * The Preview Sync panel. While it is open it owns the screen: controls or the pause overlay
 * raised underneath by an async path are dismissed again, and [onDismissed] hands focus back
 * to the player when it closes.
 */
@Composable
fun BoxScope.SeekPreviewSyncLayer(
    viewModel: PlayerViewModel,
    uiState: PlayerUiState,
    onDismissed: () -> Unit,
) {
    val seekPreview = viewModel.seekPreview
    val isOpen by seekPreview.showSyncOverlay.collectAsStateWithLifecycle()
    val visible = isOpen && uiState.error == null && !uiState.showLoadingOverlay

    LaunchedEffect(isOpen, uiState.showControls, uiState.showPauseOverlay) {
        if (!isOpen) return@LaunchedEffect
        if (uiState.showControls) viewModel.hideControls()
        if (uiState.showPauseOverlay) viewModel.onEvent(PlayerEvent.OnDismissPauseOverlay)
    }
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(isOpen) {
        if (wasOpen && !isOpen) onDismissed()
        wasOpen = isOpen
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(120)),
        exit = fadeOut(animationSpec = tween(120)),
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 44.dp)
            .zIndex(2.32f)
    ) {
        SeekPreviewSyncOverlayHost(viewModel = viewModel)
    }
}
