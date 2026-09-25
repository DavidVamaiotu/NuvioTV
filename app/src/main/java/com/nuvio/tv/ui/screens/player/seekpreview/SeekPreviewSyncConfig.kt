package com.nuvio.tv.ui.screens.player.seekpreview

/**
 * Bounds for the manual seek-preview sync correction.
 *
 * The backend only serves a sprite version whose duration is within `DURATION_TOLERANCE_SEC`
 * (240s) of the playing release, so the true preview/playback gap can never exceed that.
 * Anything beyond it would be the user fighting a different problem.
 */
internal const val SEEK_PREVIEW_OFFSET_MIN_MS = -240_000
internal const val SEEK_PREVIEW_OFFSET_MAX_MS = 240_000

/** Fine step for a single D-pad press. */
internal const val SEEK_PREVIEW_OFFSET_STEP_MS = 250

/** Coarse step once the D-pad key starts auto-repeating, so ±4min stays reachable. */
internal const val SEEK_PREVIEW_OFFSET_COARSE_STEP_MS = 2_000

/** Repeat count at which a held key switches from the fine step to the coarse one. */
internal const val SEEK_PREVIEW_OFFSET_COARSE_AFTER_REPEATS = 3

internal fun seekPreviewOffsetStepMs(repeatCount: Int, forward: Boolean): Int {
    val magnitude = if (repeatCount >= SEEK_PREVIEW_OFFSET_COARSE_AFTER_REPEATS) {
        SEEK_PREVIEW_OFFSET_COARSE_STEP_MS
    } else {
        SEEK_PREVIEW_OFFSET_STEP_MS
    }
    return if (forward) magnitude else -magnitude
}

internal fun formatSeekPreviewOffset(offsetMs: Int): String {
    val sign = if (offsetMs >= 0) "+" else "-"
    val absMs = kotlin.math.abs(offsetMs)
    return "$sign${absMs / 1000}.${(absMs % 1000).toString().padStart(3, '0')}s"
}
