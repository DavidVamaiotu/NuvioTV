package com.nuvio.tv.core.connection

import android.content.Context
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream

/**
 * Orders each list around what the current connection can sustain: streams that fit come first,
 * highest bitrate first, so the top is the best quality that plays smoothly rather than the
 * smallest file. Streams whose bitrate can't be known (no size or runtime) follow in their
 * original order, and streams that exceed the connection go last, also in original order.
 */
internal class StreamConnectionFit(
    private val runtimeMinutes: Int,
    private val connectionMbps: Double,
) {
    fun apply(groups: List<AddonStreams>): List<AddonStreams> = groups.map { group ->
        val streams = apply(group.streams)
        if (streams === group.streams) group else group.copy(streams = streams)
    }

    fun apply(streams: List<Stream>): List<Stream> {
        if (streams.size < 2) return streams
        val fitting = mutableListOf<Pair<Stream, Double>>()
        val unknown = mutableListOf<Stream>()
        val exceeding = mutableListOf<Stream>()
        for (stream in streams) {
            val bitrateMbps = stream.averageBitrateMbps(runtimeMinutes)
            when {
                bitrateMbps == null -> unknown += stream
                bitrateMbps * BITRATE_HEADROOM > connectionMbps -> exceeding += stream
                else -> fitting += stream to bitrateMbps
            }
        }
        val ordered = fitting.sortedByDescending { it.second }.map { it.first } + unknown + exceeding
        return if (ordered == streams) streams else ordered
    }

    companion object {
        /** Bitrate peaks run well above a file's average; the player buffer only absorbs part of that. */
        private const val BITRATE_HEADROOM = 1.5

        /** Returns null, leaving order untouched, when disabled or when speed or runtime is unknown. */
        fun capture(context: Context, runtimeMinutes: Int?): StreamConnectionFit? {
            ConnectionSpeedEstimator.ensureLoaded(context)
            if (!ConnectionSpeedEstimator.enabled.value) return null
            val minutes = runtimeMinutes?.takeIf { it > 0 } ?: return null
            val connectionMbps = ConnectionSpeedEstimator.estimateMbps(context) ?: return null
            return StreamConnectionFit(minutes, connectionMbps)
        }

        /** [groups] ordered for the current connection, or unchanged when that isn't possible. */
        fun order(context: Context, runtimeMinutes: Int?, groups: List<AddonStreams>): List<AddonStreams> =
            capture(context, runtimeMinutes)?.apply(groups) ?: groups

        /** As [order], taking the runtime from the playing file's duration. */
        fun orderByDuration(context: Context, durationMs: Long, groups: List<AddonStreams>): List<AddonStreams> =
            order(context, (durationMs / 60_000L).toInt().takeIf { durationMs > 0L }, groups)
    }
}

private const val MIN_RUNTIME_MINUTES = 10
private const val MAX_RUNTIME_MINUTES = 600
private const val MIN_SIZE_BYTES = 50L * 1024 * 1024
private const val MIN_PLAUSIBLE_MBPS = 0.2
private const val MAX_PLAUSIBLE_MBPS = 200.0

/**
 * Average bitrate from file size and runtime, or null when either is missing or the result is
 * implausible (for example a season pack's size reported for a single episode).
 */
internal fun Stream.averageBitrateMbps(runtimeMinutes: Int): Double? {
    if (runtimeMinutes !in MIN_RUNTIME_MINUTES..MAX_RUNTIME_MINUTES) return null
    val sizeBytes = clientResolve?.stream?.raw?.size ?: behaviorHints?.videoSize ?: return null
    if (sizeBytes < MIN_SIZE_BYTES) return null
    val mbps = sizeBytes * 8.0 / (runtimeMinutes * 60.0) / 1_000_000.0
    return mbps.takeIf { it in MIN_PLAUSIBLE_MBPS..MAX_PLAUSIBLE_MBPS }
}
