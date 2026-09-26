@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.ui.screens.player.seekpreview.local

import android.content.Context
import androidx.media3.common.Format
import androidx.media3.extractor.ExtractorsFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The ExoPlayer stream a player is showing, as far as on-device previews need it. */
internal class LocalPreviewSource(
    val owner: Any,
    val sourceKey: String,
    val context: Context,
) {
    @Volatile var released = false
    @Volatile var track: LocalPreviewTrack? = null
}

/**
 * Connects the player to on-device seek previews. The player makes two calls: [register] when
 * it builds an ExoPlayer for a stream (the returned factory copies playback's own keyframes to
 * the preview track) and [unregister] when it releases it. [SeekPreviewState] watches
 * [sourceFor] and [open]s a track for the registered stream. The MPV engine never registers, so
 * it gets no on-device track.
 */
internal object LocalPreviewSources {
    private val current = MutableStateFlow<LocalPreviewSource?>(null)

    /**
     * Registers [sourceKey] as [owner]'s playing stream and returns [factory] wrapped so its
     * video keyframes reach that stream's preview track while playback demuxes them.
     */
    fun register(owner: Any, context: Context, sourceKey: String, factory: ExtractorsFactory): ExtractorsFactory {
        val source = LocalPreviewSource(owner, sourceKey, context.applicationContext)
        current.value?.let { stale ->
            stale.released = true
            stale.track = null
        }
        current.value = source
        return VideoKeyframeExtractorsFactory(factory, object : KeyframeSink {
            private fun track() = source.takeIf { !it.released }?.track

            override fun wantsKeyframes(): Boolean = track()?.wantsKeyframes() == true

            override fun wantsKeyframe(timeUs: Long): Boolean = track()?.wantsKeyframe(timeUs) == true

            override fun onKeyframe(format: Format, timeUs: Long, data: ByteArray, offset: Int, size: Int) {
                track()?.onKeyframe(format, timeUs, data, offset, size)
            }
        })
    }

    /** [owner] released its player; its stream stops feeding previews. */
    fun unregister(owner: Any) {
        val source = current.value?.takeIf { it.owner === owner } ?: return
        source.released = true
        source.track = null
        current.compareAndSet(source, null)
    }

    /** The stream [owner] is playing with ExoPlayer, or null. */
    fun sourceFor(owner: Any): Flow<LocalPreviewSource?> =
        current.map { source -> source?.takeIf { it.owner === owner } }.distinctUntilChanged()

    /**
     * A started track fed by [source]'s keyframes, or null when [source] is no longer playing.
     * The caller closes it.
     */
    fun open(source: LocalPreviewSource, cacheKey: String, durationMs: Long): LocalPreviewTrack? {
        if (source.released || durationMs <= 0L) return null
        val track = LocalPreviewTrack(source.context, cacheKey, durationMs)
        source.track = track
        track.start()
        return track
    }

    /** Detaches [track] from [source] (if still attached) and closes it. */
    fun close(source: LocalPreviewSource, track: LocalPreviewTrack) {
        if (source.track === track) source.track = null
        track.close()
    }
}

/** Names a title and release for the thumbnail cache; the duration tells releases apart. */
internal fun localSeekPreviewCacheKey(
    contentId: String?,
    season: Int?,
    episode: Int?,
    durationMs: Long,
): String = listOf(
    contentId.orEmpty(),
    season?.toString().orEmpty(),
    episode?.toString().orEmpty(),
    (durationMs / 1000L).toString(),
).joinToString("|")
