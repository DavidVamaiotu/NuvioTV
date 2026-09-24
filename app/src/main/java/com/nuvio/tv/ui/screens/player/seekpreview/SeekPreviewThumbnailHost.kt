package com.nuvio.tv.ui.screens.player.seekpreview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.player.PlayerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import android.graphics.Bitmap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private val CenterWidth = 176.dp
private val CenterHeight = 99.dp
private val NeighborWidth = 104.dp
private val NeighborHeight = 59.dp
private val FrameGap = 6.dp
private val StripWidth = CenterWidth + (NeighborWidth + FrameGap) * 2
private const val LingerAfterScrubMs = 1500L

/**
 * Distance beyond which the frame on screen is admitted to describe a different moment than
 * the scrub position. Grid-locked scrubbing normally keeps the two identical, so the
 * disclosure only appears where we genuinely cannot guarantee agreement.
 */
private const val FrameLabelToleranceMs = 1_000L

/**
 * The frames rendered by [SeekPreviewThumbnailHost]: the cue covering the scrub position plus
 * its two immediate neighbours.
 *
 * [neighborsOwnerCueStartMs] pins the neighbours to the centre cue they were resolved for. The
 * centre is published as soon as it crops so the frame the user asked for is never gated on
 * context, which briefly leaves the previous neighbours in place; rendering them only while
 * they still belong to the current centre keeps that from showing a mismatched strip.
 */
private data class SeekPreviewFrames(
    val center: Bitmap? = null,
    val centerCueStartMs: Long? = null,
    val previous: Bitmap? = null,
    val previousCueStartMs: Long? = null,
    val next: Bitmap? = null,
    val nextCueStartMs: Long? = null,
    val neighborsOwnerCueStartMs: Long? = null
) {
    val neighborsMatchCenter: Boolean
        get() = centerCueStartMs != null && neighborsOwnerCueStartMs == centerCueStartMs
}

/**
 * The scrub-time preview: a three-frame strip centred on the cue the playhead sits in.
 *
 * Sprite sheets hold one frame per ~10 second cue, so a single thumbnail cannot say whether a
 * cut happens just out of shot — the failure users actually feel is landing in the wrong
 * scene, not being a few seconds out. Showing the neighbouring cues makes the granularity
 * self-evident and turns scrubbing into reading a sequence, which is what hunting for a scene
 * needs. It also lets the position label stay a single honest number, because grid-locked
 * scrubbing (see [SeekPreviewCueStepper]) parks the playhead on the centre frame's own
 * timestamp.
 */
@Composable
fun SeekPreviewThumbnailHost(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val seekPreview = viewModel.seekPreview
    val track by seekPreview.track.collectAsStateWithLifecycle()
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
    var frames by remember(activeTrack) { mutableStateOf(SeekPreviewFrames()) }
    // Conflate rapid scrub/nudge changes so only the latest pair triggers a crop.
    val requestFlow = remember(activeTrack) { MutableStateFlow(displayTs to offsetMs) }
    LaunchedEffect(activeTrack, displayTs, offsetMs) {
        requestFlow.value = displayTs to offsetMs
    }
    LaunchedEffect(activeTrack) {
        if (activeTrack == null) {
            seekPreview.onPreviewCueResolved(null)
            return@LaunchedEffect
        }
        // The host stays composed while the controls are up, so the playhead alone would
        // re-crop three bitmaps every progress tick for a frame that cannot have changed.
        // Caching the *inputs* to the re-centring decision below — the covering cue and which
        // side of its midpoint the position falls — replays that decision exactly, so the
        // cache expires precisely when the centre frame is due to hand over to its successor.
        var cachedCovering: SeekPreviewCue? = null
        var cachedPrefersSuccessor = false
        var cachedOffsetMs: Long? = null
        requestFlow.collectLatest { (positionMs, offset) ->
            val covering = cachedCovering
            if (offset == cachedOffsetMs &&
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

            // The SDK resolves the cue *containing* the position, but a cue's frame is captured
            // at its start — so past the halfway mark the next cue's frame is the closer one.
            // Centring on it is what makes the strip read symmetrically around the playhead.
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

            val center = successor ?: coveringThumbnail
            val centerStartMs = center.cueStartMs - offset
            val centerEndMs = center.cueEndMs - offset
            frames = frames.copy(center = center.bitmap, centerCueStartMs = centerStartMs)
            seekPreview.onPreviewCueResolved(SeekPreviewCue(centerStartMs, centerEndMs))

            // A lookup that clamps at either end of the track resolves back to the centre cue;
            // dropping those keeps the strip from showing the same frame twice.
            val previous = if (successor != null) {
                // Re-centring made the covering cue the predecessor — no need to fetch it again.
                coveringThumbnail
            } else {
                activeTrack.thumbnailFor(centerStartMs - 1)
                    ?.takeIf { it.cueStartMs != center.cueStartMs }
            }
            val next = activeTrack.thumbnailFor(centerEndMs)
                ?.takeIf { it.cueStartMs != center.cueStartMs }
            frames = frames.copy(
                previous = previous?.bitmap,
                previousCueStartMs = previous?.let { it.cueStartMs - offset },
                next = next?.bitmap,
                nextCueStartMs = next?.let { it.cueStartMs - offset },
                neighborsOwnerCueStartMs = centerStartMs
            )
        }
    }

    AnimatedVisibility(
        visible = lingerVisible && track != null,
        enter = fadeIn(animationSpec = tween(120)),
        exit = fadeOut(animationSpec = tween(200)),
        modifier = modifier
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(CenterHeight + 46.dp)
        ) {
            // Below this width the strip would be clipped by the scrubber's own bounds, so
            // fall back to the single frame rather than showing a cropped filmstrip.
            val showNeighbors = maxWidth >= StripWidth
            val stripWidth = if (showNeighbors) StripWidth else CenterWidth
            val left = previewOffset(maxWidth, stripWidth, fraction)
            val frameTs = frames.centerCueStartMs
            val showFrameLabel = frameTs != null && abs(frameTs - displayTs) > FrameLabelToleranceMs

            Column(
                modifier = Modifier
                    .offset(x = left)
                    .width(stripWidth)
                    .align(Alignment.TopStart),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(FrameGap),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (showNeighbors) {
                        NeighborFrame(
                            bitmap = frames.previous.takeIf { frames.neighborsMatchCenter },
                            timeMs = frames.previousCueStartMs.takeIf { frames.neighborsMatchCenter }
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(CenterWidth, CenterHeight)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black)
                            .border(1.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                    ) {
                        frames.center?.let { bitmap ->
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(CenterWidth, CenterHeight)
                            )
                        }
                    }
                    if (showNeighbors) {
                        NeighborFrame(
                            bitmap = frames.next.takeIf { frames.neighborsMatchCenter },
                            timeMs = frames.nextCueStartMs.takeIf { frames.neighborsMatchCenter }
                        )
                    }
                }
                Text(
                    text = formatScrubTime(displayTs),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                    color = Color.White.copy(alpha = 0.95f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                if (showFrameLabel && frameTs != null) {
                    Text(
                        text = stringResource(
                            R.string.player_seek_preview_frame_at,
                            formatScrubTime(frameTs)
                        ),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

/**
 * A dimmed context frame either side of the centre cue, labelled with the moment it holds so
 * the size of the gap between previews is visible rather than implied.
 */
@Composable
private fun NeighborFrame(bitmap: Bitmap?, timeMs: Long?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.width(NeighborWidth)
    ) {
        Box(
            modifier = Modifier
                .size(NeighborWidth, NeighborHeight)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = 0.6f))
        ) {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(NeighborWidth, NeighborHeight)
                        .alpha(0.45f)
                )
            }
        }
        Text(
            text = timeMs?.let(::formatScrubTime).orEmpty(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = Color.White.copy(alpha = 0.55f)
        )
    }
}

private fun previewOffset(trackWidth: Dp, thumbWidth: Dp, fraction: Float): Dp {
    val centerX = trackWidth * fraction
    val leftUnclamped = centerX - thumbWidth / 2
    val maxLeft = (trackWidth - thumbWidth).coerceAtLeast(0.dp)
    return leftUnclamped.coerceIn(0.dp, maxLeft)
}

private fun formatScrubTime(millis: Long): String {
    val safe = millis.coerceAtLeast(0L)
    val hours = TimeUnit.MILLISECONDS.toHours(safe)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(safe) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(safe) % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
