package com.nuvio.tv.ui.screens.player.seekpreview

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.ui.screens.player.PlayerEvent
import com.nuvio.tv.ui.screens.player.PlayerRuntimeController
import com.nuvio.tv.ui.screens.player.currentPlaybackDurationMs
import com.nuvio.tv.ui.screens.player.currentPlaybackPositionMs
import com.nuvio.tv.ui.screens.player.hideControls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import tv.seekr.previews.android.Seekr
import tv.seekr.previews.android.SeekrTrack

/**
 * Seek-preview (Seekr) state for one player session: the loaded track, the cue behind the
 * frame on screen, and the manual Preview Sync correction.
 *
 * Holds what the seek-preview fork (AKhalil609/NuvioTV) keeps in PlayerUiState, PlayerViewModel
 * and the runtime controller's events, so upstream player state stays untouched. The key comes
 * from BuildConfig.SEEKR_API_KEY (the SEEKR_API_KEY secret); without one, nothing loads.
 */
class SeekPreviewState internal constructor(
    scope: CoroutineScope,
    private val controller: PlayerRuntimeController,
) {
    private val _previewCue = MutableStateFlow<SeekPreviewCue?>(null)

    /**
     * Cue window (playback timebase) behind the preview frame currently on screen, or `null`
     * while no preview has resolved. Drives grid-locked scrubbing and the scrubber's cue ticks.
     */
    val previewCue: StateFlow<SeekPreviewCue?> = _previewCue.asStateFlow()

    private val _offsetMs = MutableStateFlow(0)

    /**
     * Manual sync correction applied to the position before the thumbnail lookup.
     * Session-scoped: it describes the gap between the playing release and the one the
     * sprites were generated from, so it is dropped whenever a new track loads.
     */
    val offsetMs: StateFlow<Int> = _offsetMs.asStateFlow()

    private val _showSyncOverlay = MutableStateFlow(false)
    val showSyncOverlay: StateFlow<Boolean> = _showSyncOverlay.asStateFlow()
    val isSyncOverlayOpen: Boolean get() = _showSyncOverlay.value

    @OptIn(ExperimentalCoroutinesApi::class)
    val track: StateFlow<SeekrTrack?> =
        controller.playbackTimeline
            .map { it.duration }
            .distinctUntilChanged()
            .mapLatest { durationMs ->
                // A new track describes a different release, so any manual sync dialled in
                // for the previous one is meaningless. Drop it.
                setOffset(0)
                _previewCue.value = null
                _showSyncOverlay.value = false
                val apiKey = BuildConfig.SEEKR_API_KEY
                if (apiKey.isBlank() || durationMs <= 0L) return@mapLatest null
                val content = seekrContentFor(
                    contentId = controller.contentId,
                    contentType = controller.contentType,
                    season = controller.currentSeason,
                    episode = controller.currentEpisode
                ) ?: return@mapLatest null
                Seekr.create(apiKey)
                    .loadTrack(content, durationMs)
                    ?.also { track -> track.prefetchSheets() }
            }
            .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * The duration gap between the playing release and the preview source, offered as a
     * starting point for Preview Sync. Deliberately not applied automatically: the backend
     * anchors cues at the start, and most gaps are a different credits length around an
     * identical body, for which shifting by the gap would make every thumbnail wrong.
     */
    val suggestedOffsetMs: StateFlow<Long> =
        combine(
            track,
            controller.playbackTimeline.map { it.duration }.distinctUntilChanged()
        ) { track, durationMs ->
            val sourceDurationMs = track?.sourceDurationMs ?: 0L
            if (sourceDurationMs > 0L && durationMs > 0L) sourceDurationMs - durationMs else 0L
        }.stateIn(scope, SharingStarted.Eagerly, 0L)

    /** Spacing between preview cues, or 0 when no preview has resolved. */
    val cueIntervalMs: StateFlow<Long> =
        previewCue
            .map { it?.durationMs ?: 0L }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, 0L)

    /**
     * Grid-locked scrubbing: rewrites a D-pad preview step so it lands on a position an actual
     * preview frame exists for, so the thumbnail and the eventual seek can never disagree.
     * Every other event, and every step without a resolved cue, passes through unchanged.
     */
    internal fun intercept(event: PlayerEvent): PlayerEvent {
        if (event !is PlayerEvent.OnPreviewSeekBy || track.value == null) return event
        if (controller.playbackTimeline.value.isLive) return event
        val maxDuration = controller.currentPlaybackDurationMs().takeIf { it >= 0 } ?: Long.MAX_VALUE
        val basePosition = controller.pendingPreviewSeekPosition
            ?: controller.currentPlaybackPositionMs()?.coerceAtLeast(0L)
            ?: 0L
        val target = SeekPreviewCueStepper.targetMs(
            cue = _previewCue.value,
            fromMs = basePosition,
            deltaMs = event.deltaMs,
            durationMs = maxDuration
        )
        return PlayerEvent.OnPreviewSeekBy(target - basePosition)
    }

    /**
     * Reported by the thumbnail host once it knows which cue the frame on screen came from.
     * Snapping the pending scrub position onto its start keeps the number under the thumbnail
     * honest even when the cue grid is not perfectly uniform.
     */
    fun onPreviewCueResolved(cue: SeekPreviewCue?) {
        _previewCue.value = cue
        if (controller.playbackTimeline.value.isLive) return
        val maxDuration = controller.currentPlaybackDurationMs().takeIf { it >= 0 } ?: Long.MAX_VALUE
        val aligned = SeekPreviewCueStepper.alignedTargetMs(
            cue = cue,
            pendingMs = controller.pendingPreviewSeekPosition,
            durationMs = maxDuration
        ) ?: return
        controller.pendingPreviewSeekPosition = aligned
        controller.updatePlaybackTimeline(currentPosition = aligned)
    }

    fun showSyncOverlay() {
        controller.hideControls()
        _showSyncOverlay.value = true
    }

    fun hideSyncOverlay() {
        _showSyncOverlay.value = false
    }

    /**
     * Sets the manual correction. Only the thumbnail composables consume it, pushing it onto the
     * track before every lookup, so this is safe at D-pad repeat rate.
     */
    fun setOffset(targetMs: Int) {
        val clamped = targetMs.coerceIn(SEEK_PREVIEW_OFFSET_MIN_MS, SEEK_PREVIEW_OFFSET_MAX_MS)
        if (_offsetMs.value == clamped) return
        // The cached cue was converted to the playback timebase with the old offset.
        _offsetMs.value = clamped
        _previewCue.value = null
    }

    fun adjustOffset(deltaMs: Int) = setOffset(_offsetMs.value + deltaMs)
}
