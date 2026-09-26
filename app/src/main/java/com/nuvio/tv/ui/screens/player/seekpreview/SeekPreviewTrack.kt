package com.nuvio.tv.ui.screens.player.seekpreview

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tv.seekr.previews.android.SeekrTrack

/**
 * A preview frame with the cue window it stands for.
 *
 * Cue times are on the preview timeline: the position asked for plus the track's [SeekPreviewTrack.offsetMs].
 * [approximate] marks a stand-in from a nearby moment (an on-device frame for a slot that has
 * none yet); its [bitmap] is already a low-resolution copy, so drawing it scaled up reads as
 * blurred on every API level without a blur shader.
 */
data class SeekPreviewThumbnail(
    val bitmap: Bitmap,
    val cueStartMs: Long,
    val cueEndMs: Long,
    val approximate: Boolean = false,
)

/**
 * Seek-preview thumbnails queried by playback position: Seekr sprite sheets, frames generated on
 * the device from the playing stream, or both (see [HybridSeekPreviewTrack]).
 */
interface SeekPreviewTrack {
    /** Signed milliseconds added to a requested position before the lookup (Preview Sync). */
    var offsetMs: Long

    /** Built from the playing stream itself, so it never needs Preview Sync. */
    val isLocal: Boolean get() = false

    /** Bumped whenever thumbnails are added, so a visible preview re-reads its frame. */
    val revision: StateFlow<Int> get() = StaticRevision

    /** The thumbnail covering [positionMs] (after [offsetMs]) with its cue window, or null. */
    suspend fun thumbnailFor(positionMs: Long): SeekPreviewThumbnail?

    /** Stops background work; the track is not fed again. */
    fun close() = Unit
}

private val StaticRevision: StateFlow<Int> = MutableStateFlow(0)

/** Adapts the Seekr library's track. Its sheets are prefetched by [SeekPreviewState]. */
internal class SeekrPreviewTrack(private val seekr: SeekrTrack) : SeekPreviewTrack {
    override var offsetMs: Long
        get() = seekr.offsetMs
        set(value) {
            seekr.offsetMs = value
        }

    override suspend fun thumbnailFor(positionMs: Long): SeekPreviewThumbnail? =
        seekr.thumbnailFor(positionMs)?.let { thumbnail ->
            SeekPreviewThumbnail(
                bitmap = thumbnail.bitmap,
                cueStartMs = thumbnail.cueStartMs,
                cueEndMs = thumbnail.cueEndMs,
            )
        }
}

/**
 * On-device thumbnails where playback has already been, Seekr everywhere else.
 *
 * The on-device track only has frames for parts of the title that were buffered, now or on an
 * earlier watch. Where it has an exact frame that frame wins, since it always lines up; where it
 * would only offer a nearby stand-in, the Seekr frame is shown instead. The Preview Sync offset
 * only moves Seekr frames: on-device ones are already on playback's timeline, so their cue times
 * are shifted onto the preview timeline here to keep the caller's `cue - offset` conversion right.
 *
 * Owns neither track: [SeekPreviewState] opens and closes both.
 */
internal class HybridSeekPreviewTrack(
    private val local: SeekPreviewTrack,
    private val seekr: SeekPreviewTrack,
) : SeekPreviewTrack {
    override var offsetMs: Long
        get() = seekr.offsetMs
        set(value) {
            seekr.offsetMs = value
        }

    override val revision: StateFlow<Int> get() = local.revision

    override suspend fun thumbnailFor(positionMs: Long): SeekPreviewThumbnail? {
        val shift = seekr.offsetMs
        val own = local.thumbnailFor(positionMs)?.let { thumbnail ->
            if (shift == 0L) thumbnail else thumbnail.copy(
                cueStartMs = thumbnail.cueStartMs + shift,
                cueEndMs = thumbnail.cueEndMs + shift,
            )
        }
        if (own != null && !own.approximate) return own
        return seekr.thumbnailFor(positionMs) ?: own
    }
}
