@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player.seekpreview

import android.view.KeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.player.PlayerViewModel
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import tv.seekr.previews.android.SeekrTrack

private val SyncThumbnailWidth = 208.dp
private val SyncThumbnailHeight = 117.dp

/**
 * Manual seek-preview sync control.
 *
 * Sprite sheets are generated once per release, but the user may be playing a different
 * release of the same title. The backend intentionally anchors cue times 1:1 at the start
 * and never shifts them (a duration gap is usually a different credits length, not a
 * different head), so when the gap *is* at the head the only thing that can resolve it is
 * the user looking at the picture. This overlay is that control: nudge until the thumbnail
 * matches the frame playing behind it.
 */
@Composable
internal fun SeekPreviewSyncOverlayHost(viewModel: PlayerViewModel) {
    val seekPreview = viewModel.seekPreview
    val track by seekPreview.track.collectAsStateWithLifecycle()
    val offsetMs by seekPreview.offsetMs.collectAsStateWithLifecycle()
    val timeline by viewModel.playbackTimeline.collectAsStateWithLifecycle()
    val suggestedOffsetMs by seekPreview.suggestedOffsetMs.collectAsStateWithLifecycle()

    val focusRequester = remember { FocusRequester() }
    // Opening this panel hides the player controls, which destroys the button that had
    // focus. Without claiming focus here the whole player is left with no focused node, so
    // key events stop being delivered to the player's Back handler and Back escapes to the
    // navigation stack — closing the player instead of this panel.
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    SeekPreviewSyncOverlay(
        track = track,
        positionMs = timeline.currentPosition,
        offsetMs = offsetMs,
        suggestedOffsetMs = suggestedOffsetMs,
        modifier = Modifier
            .focusRequester(focusRequester)
            .onKeyEvent { event ->
                val keyCode = event.nativeKeyEvent.keyCode
                val ownsKey = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                    keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                    keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                    keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                    keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == KeyEvent.KEYCODE_ENTER ||
                    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                    keyCode == KeyEvent.KEYCODE_BACK ||
                    keyCode == KeyEvent.KEYCODE_ESCAPE
                if (!ownsKey) return@onKeyEvent false
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onKeyEvent true
                when (keyCode) {
                    KeyEvent.KEYCODE_BACK,
                    KeyEvent.KEYCODE_ESCAPE,
                    KeyEvent.KEYCODE_DPAD_UP ->
                        seekPreview.hideSyncOverlay()
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT -> seekPreview.adjustOffset(
                        seekPreviewOffsetStepMs(
                            repeatCount = event.nativeKeyEvent.repeatCount,
                            forward = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                        )
                    )
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER ->
                        seekPreview.setOffset(0)
                    KeyEvent.KEYCODE_DPAD_DOWN -> seekPreview.setOffset(
                        suggestedOffsetMs.coerceIn(
                            SEEK_PREVIEW_OFFSET_MIN_MS.toLong(),
                            SEEK_PREVIEW_OFFSET_MAX_MS.toLong()
                        ).toInt()
                    )
                }
                true
            }
            .focusable()
    )
}

@Composable
private fun SeekPreviewSyncOverlay(
    track: SeekrTrack?,
    positionMs: Long,
    offsetMs: Int,
    suggestedOffsetMs: Long,
    modifier: Modifier = Modifier
) {
    val fraction = ((offsetMs - SEEK_PREVIEW_OFFSET_MIN_MS).toFloat() /
        (SEEK_PREVIEW_OFFSET_MAX_MS - SEEK_PREVIEW_OFFSET_MIN_MS).toFloat()).coerceIn(0f, 1f)

    Column(
        modifier = modifier
            .fillMaxWidth(0.62f)
            .clip(RoundedCornerShape(26.dp))
            .background(Color(0xCC0F0F0F))
            .padding(horizontal = 26.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.player_seek_preview_sync),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White
            )
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    text = formatSeekPreviewOffset(offsetMs),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White.copy(alpha = 0.95f)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SyncPreviewImage(track = track, positionMs = positionMs, offsetMs = offsetMs)

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
            ) {
                Text(
                    text = stringResource(R.string.player_seek_preview_sync_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.72f)
                )
                Text(
                    text = stringResource(R.string.player_seek_preview_sync_keys),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.55f)
                )
                if (suggestedOffsetMs != 0L) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = stringResource(
                                R.string.player_seek_preview_sync_suggested,
                                formatSeekPreviewOffset(
                                    suggestedOffsetMs.coerceIn(
                                        SEEK_PREVIEW_OFFSET_MIN_MS.toLong(),
                                        SEEK_PREVIEW_OFFSET_MAX_MS.toLong()
                                    ).toInt()
                                )
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF9BE2AF)
                        )
                    }
                }
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(NuvioTheme.spacing.lg)
        ) {
            val thumbWidth = 22.dp
            val thumbOffset = (maxWidth - thumbWidth) * fraction

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(NuvioTheme.spacing.xs)
                    .clip(RoundedCornerShape(NuvioTheme.radii.xxs))
                    .align(Alignment.CenterStart)
                    .background(Color.White.copy(alpha = 0.15f))
            )
            Box(
                modifier = Modifier
                    .padding(start = thumbOffset)
                    .size(width = thumbWidth, height = NuvioTheme.spacing.md)
                    .clip(RoundedCornerShape(NuvioTheme.radii.xxs))
                    .align(Alignment.CenterStart)
                    .background(Color(0xFF4AA3FF))
            )
        }
    }
}

@Composable
private fun SyncPreviewImage(
    track: SeekrTrack?,
    positionMs: Long,
    offsetMs: Int
) {
    var bitmap by remember(track) { mutableStateOf<android.graphics.Bitmap?>(null) }
    val requestFlow = remember(track) { MutableStateFlow(positionMs to offsetMs) }
    LaunchedEffect(track, positionMs, offsetMs) {
        requestFlow.value = positionMs to offsetMs
    }
    LaunchedEffect(track) {
        requestFlow.collectLatest { (position, offset) ->
            val active = track ?: return@collectLatest
            active.offsetMs = offset.toLong()
            active.thumbnailAt(position)?.let { bitmap = it }
        }
    }

    Box(
        modifier = Modifier
            .size(SyncThumbnailWidth, SyncThumbnailHeight)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
            .border(1.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
    ) {
        bitmap?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(SyncThumbnailWidth, SyncThumbnailHeight)
            )
        }
    }
}
