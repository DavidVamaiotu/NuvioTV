package com.nuvio.tv.ui.screens.player.seekpreview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.ui.screens.player.PlayerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest

/**
 * Frame width as a share of the width available (the progress bar's), clamped for a 10-foot
 * screen: about 260 dp on a typical 960 dp wide TV, never smaller than 240 dp nor wider than
 * 400 dp on large layouts.
 */
private const val FrameWidthFraction = 0.30f
private val MinFrameWidth = 240.dp
private val MaxFrameWidth = 400.dp
private const val FrameAspect = 16f / 9f
private val FrameCorner = 8.dp
private val FrameGapAboveBar = 10.dp
private const val LingerAfterScrubMs = 1500L

/**
 * The scrub-time preview: only the frame of the cue the scrub lands on, above the scrub
 * position on the progress bar.
 *
 * Grid-locked scrubbing (see [SeekPreviewCueStepper]) parks the playhead on this frame's own
 * timestamp, so the frame shown is the frame playback resumes on, and the controls' own time
 * readout stays the single, honest position label.
 */
@Composable
fun SeekPreviewThumbnailHost(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val seekPreview = viewModel.seekPreview
    val track by seekPreview.previewTrack.collectAsStateWithLifecycle()
    val offsetState by seekPreview.offsetMs.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val timeline by viewModel.playbackTimeline.collectAsStateWithLifecycle()
    val activeTrack = track

    val previewTs = uiState.pendingPreviewSeekPosition
    val scrubActive = previewTs != null || uiState.showSeekOverlay

    // Prevent flicker between repeated seek inputs.
    var lingerVisible by remember { mutableStateOf(false) }
    LaunchedEffect(scrubActive) {
        if (scrubActive) {
            lingerVisible = true
        } else {
            delay(LingerAfterScrubMs)
            lingerVisible = false
        }
    }

    val displayTs = previewTs ?: timeline.currentPosition
    val duration = timeline.duration.coerceAtLeast(1L)
    val fraction = (displayTs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    val offsetMs = offsetState.toLong()
    // On-device tracks fill in while playing; a new revision means the frame may have sharpened.
    val revision by (activeTrack?.revision ?: NoRevision).collectAsStateWithLifecycle()
    var frame by remember(activeTrack) { mutableStateOf<SeekPreviewThumbnail?>(null) }
    // Conflate rapid scrub/nudge changes so only the latest request triggers a lookup.
    val requestFlow = remember(activeTrack) {
        MutableStateFlow(PreviewRequest(displayTs, offsetMs, revision, lingerVisible))
    }
    LaunchedEffect(activeTrack, displayTs, offsetMs, revision, lingerVisible) {
        requestFlow.value = PreviewRequest(displayTs, offsetMs, revision, lingerVisible)
    }
    LaunchedEffect(activeTrack) {
        if (activeTrack == null) {
            seekPreview.onPreviewCueResolved(null)
            return@LaunchedEffect
        }
        // The host stays composed while the controls are up, so the playhead alone would
        // re-read the frame every progress tick for a frame that cannot have changed.
        // Caching the *inputs* to the re-centring decision below — the covering cue and which
        // side of its midpoint the position falls — replays that decision exactly, so the
        // cache expires precisely when the frame is due to hand over to its successor. New
        // on-device frames only matter while the preview is on screen.
        var cachedCovering: SeekPreviewCue? = null
        var cachedPrefersSuccessor = false
        var cachedOffsetMs: Long? = null
        var cachedRevision: Int? = null
        requestFlow.collectLatest { (positionMs, offset, rev, visible) ->
            val covering = cachedCovering
            if (offset == cachedOffsetMs &&
                (rev == cachedRevision || !visible) &&
                covering != null &&
                covering.contains(positionMs) &&
                covering.prefersSuccessorFor(positionMs) == cachedPrefersSuccessor
            ) {
                return@collectLatest
            }
            // Single writer for the track's offset: the manual sync correction is pushed in
            // right before the lookup so a nudge is reflected on the very next frame.
            activeTrack.offsetMs = offset
            // Only overwrite on success — keeps the last good frame visible during a fetch.
            val coveringThumbnail = activeTrack.thumbnailFor(positionMs) ?: return@collectLatest
            // Cue times arrive on the preview timeline; undo the sync offset so they can be
            // compared with, and assigned to, playback positions.
            val coveringCue = SeekPreviewCue(
                startMs = coveringThumbnail.cueStartMs - offset,
                endMs = coveringThumbnail.cueEndMs - offset
            )

            // A cue's frame is captured at its start, so past the halfway mark the next cue's
            // frame is the closer one.
            val prefersSuccessor = coveringCue.prefersSuccessorFor(positionMs)
            val successor = if (prefersSuccessor) {
                activeTrack.thumbnailFor(coveringCue.endMs)
                    ?.takeIf { it.cueStartMs != coveringThumbnail.cueStartMs }
            } else {
                null
            }
            cachedCovering = coveringCue
            cachedPrefersSuccessor = prefersSuccessor
            cachedOffsetMs = offset
            cachedRevision = rev

            val center = successor ?: coveringThumbnail
            frame = center
            seekPreview.onPreviewCueResolved(
                SeekPreviewCue(center.cueStartMs - offset, center.cueEndMs - offset)
            )
        }
    }

    AnimatedVisibility(
        visible = lingerVisible && activeTrack != null,
        enter = fadeIn(animationSpec = tween(120)),
        exit = fadeOut(animationSpec = tween(200)),
        modifier = modifier
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val frameWidth = (maxWidth * FrameWidthFraction)
                .coerceIn(MinFrameWidth, MaxFrameWidth)
                .coerceAtMost(maxWidth)
            val frameHeight = frameWidth / FrameAspect
            Box(
                modifier = Modifier
                    .offset(x = previewOffset(maxWidth, frameWidth, fraction))
                    .padding(bottom = FrameGapAboveBar)
                    .size(frameWidth, frameHeight)
                    .clip(RoundedCornerShape(FrameCorner))
                    .background(Color.Black)
            ) {
                frame?.let { thumbnail ->
                    val image = remember(thumbnail.bitmap) { thumbnail.bitmap.asImageBitmap() }
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

private data class PreviewRequest(
    val positionMs: Long,
    val offsetMs: Long,
    val revision: Int,
    val visible: Boolean
)

private val NoRevision: StateFlow<Int> = MutableStateFlow(0)

private fun previewOffset(trackWidth: Dp, thumbWidth: Dp, fraction: Float): Dp {
    val centerX = trackWidth * fraction
    val leftUnclamped = centerX - thumbWidth / 2
    val maxLeft = (trackWidth - thumbWidth).coerceAtLeast(0.dp)
    return leftUnclamped.coerceIn(0.dp, maxLeft)
}
