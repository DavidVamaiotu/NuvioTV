package com.nuvio.tv.core.connection

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Coarse network class. Throughput is learned separately for each one. */
internal enum class NetworkKind {
    WIFI,
    CELLULAR,
    OTHER,
}

@Serializable
internal data class ConnectionSpeedSample(
    val network: NetworkKind,
    val mbps: Double,
    val recordedAtMs: Long,
)

/**
 * Learns sustained download throughput from real playback, per [NetworkKind], and owns the
 * "match streams to connection" preference.
 *
 * Samples come passively from [PlaybackThroughput]; nothing is measured when a stream list
 * loads, so reading the estimate is a memory lookup. Kept outside PlayerSettingsDataStore so
 * upstream settings need no new fields or migrations.
 */
internal object ConnectionSpeedEstimator {
    private const val PREFS_NAME = "nuvio_tv_connection_speed"
    private const val KEY_SAMPLES = "throughput_samples_v2"
    private const val KEY_ENABLED = "prefer_connection_fit"
    private const val MAX_SAMPLES_PER_NETWORK = 6
    private const val MIN_SAMPLES = 2
    private const val MAX_SAMPLE_AGE_MS = 21L * 24 * 60 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    @Volatile private var preferences: SharedPreferences? = null
    @Volatile private var connectivityManager: ConnectivityManager? = null
    private var cachedSamples: List<ConnectionSpeedSample>? = null

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Bumped on every recorded sample, so a settings row can refresh its status line. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun ensureLoaded(context: Context) {
        if (preferences != null) return
        synchronized(lock) {
            if (preferences != null) return
            val appContext = context.applicationContext
            connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            _enabled.value = prefs.getBoolean(KEY_ENABLED, true)
            preferences = prefs
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        _enabled.value = enabled
        preferences?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
    }

    /** The estimate for the current network, or null while still learning or offline. */
    fun estimateMbps(context: Context): Double? {
        ensureLoaded(context)
        val network = currentNetworkKind() ?: return null
        return estimateMbps(loadedSamples(), network, System.currentTimeMillis())
    }

    fun record(context: Context, mbps: Double) {
        ensureLoaded(context)
        val network = currentNetworkKind() ?: return
        synchronized(lock) {
            val updated = appendSample(
                loadedSamples(),
                ConnectionSpeedSample(network, mbps, System.currentTimeMillis())
            )
            cachedSamples = updated
            preferences?.edit()?.putString(KEY_SAMPLES, json.encodeToString(updated))?.apply()
        }
        _revision.value += 1
    }

    /**
     * The best recent sample. Each sample is capped by whichever server delivered it, so the
     * fastest one is the tightest lower bound on the connection itself; one slow host must not
     * make every other source look unplayable. At least two samples are required so a single
     * session never drives ranking on its own.
     */
    internal fun estimateMbps(
        samples: List<ConnectionSpeedSample>,
        network: NetworkKind,
        nowMs: Long,
    ): Double? {
        val recent = samples.filter { sample ->
            sample.network == network && nowMs - sample.recordedAtMs in 0..MAX_SAMPLE_AGE_MS
        }
        if (recent.size < MIN_SAMPLES) return null
        return recent.maxOf { it.mbps }
    }

    internal fun appendSample(
        samples: List<ConnectionSpeedSample>,
        sample: ConnectionSpeedSample,
    ): List<ConnectionSpeedSample> {
        val (sameNetwork, otherNetworks) = samples.partition { it.network == sample.network }
        return otherNetworks + sameNetwork.takeLast(MAX_SAMPLES_PER_NETWORK - 1) + sample
    }

    private fun loadedSamples(): List<ConnectionSpeedSample> = synchronized(lock) {
        cachedSamples ?: runCatching {
            json.decodeFromString<List<ConnectionSpeedSample>>(
                preferences?.getString(KEY_SAMPLES, null).orEmpty()
            )
        }.getOrDefault(emptyList()).also { cachedSamples = it }
    }

    private fun currentNetworkKind(): NetworkKind? {
        val manager = connectivityManager ?: return null
        val capabilities = runCatching { manager.getNetworkCapabilities(manager.activeNetwork) }.getOrNull()
            ?: return null
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.CELLULAR
            else -> NetworkKind.OTHER
        }
    }
}

/**
 * Measures sustained download throughput over one playback session and reports it once.
 *
 * Only ticks where the player is fetching and data actually arrives count. Players stop
 * downloading once their buffer is full, and they also wait on connection setup, redirects and
 * seeks (resume position, file index) without receiving anything; counting either would make a
 * fast connection look slow. A genuinely slow link still delivers data on every tick.
 */
internal class PlaybackThroughputSampler(
    sourceUrl: String,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val onSample: (Double) -> Unit,
) {
    private val isEligible = sourceUrl.isInternetPlaybackSource()
    private var lastTick: TimeMark? = null
    private var activeBytes = 0L
    private var activeMs = 0L
    private var isFinished = false

    /** [bytes] is the number of bytes received since the previous tick. */
    fun onBytesTick(bytes: Long, isFetching: Boolean) {
        tick(isFetching) { bytes }
    }

    /** For players that report a transfer rate rather than a byte count. */
    fun onRateTick(bytesPerSecond: Long, isFetching: Boolean) {
        tick(isFetching) { elapsedMs -> bytesPerSecond * elapsedMs / 1000 }
    }

    fun finish() {
        if (isFinished) return
        isFinished = true
        if (activeMs < MIN_WINDOW_MS) return
        if (activeBytes < MIN_WINDOW_BYTES && activeMs < SLOW_WINDOW_MS) return
        onSample(activeBytes * 8.0 / activeMs / 1000.0)
    }

    private inline fun tick(isFetching: Boolean, bytesFor: (elapsedMs: Long) -> Long) {
        if (!isEligible || isFinished) return
        val previous = lastTick
        lastTick = timeSource.markNow()
        val elapsedMs = previous?.elapsedNow()?.inWholeMilliseconds ?: return
        // A long gap means the app was suspended; the interval says nothing about the network.
        if (!isFetching || elapsedMs <= 0 || elapsedMs > MAX_TICK_GAP_MS) return
        val bytes = bytesFor(elapsedMs).coerceAtLeast(0L)
        if (bytes == 0L) return
        activeBytes += bytes
        activeMs += elapsedMs
        if (activeMs >= MAX_WINDOW_MS) finish()
    }

    private companion object {
        const val MIN_WINDOW_MS = 3_000L
        const val MIN_WINDOW_BYTES = 8L * 1024 * 1024
        const val SLOW_WINDOW_MS = 10_000L
        const val MAX_WINDOW_MS = 30_000L
        const val MAX_TICK_GAP_MS = 2_000L
    }
}

/**
 * True for http(s) sources reached over the internet. Local files, the on-device torrent
 * proxy and LAN servers measure something other than the internet connection.
 */
internal fun String.isInternetPlaybackSource(): Boolean {
    val value = trim()
    val scheme = value.substringBefore("://", missingDelimiterValue = "").lowercase()
    if (scheme != "http" && scheme != "https") return false
    val authority = value.substringAfter("://")
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('@')
    val host = if (authority.startsWith("[")) {
        authority.removePrefix("[").substringBefore(']')
    } else {
        authority.substringBefore(':')
    }.lowercase()
    if (host.isEmpty() || host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) {
        return false
    }
    if (':' in host) {
        return host != "::1" && !host.startsWith("fe80:") && !host.startsWith("fc") && !host.startsWith("fd")
    }
    val octets = host.split('.').map { it.toIntOrNull() }
    if (octets.size != 4 || octets.any { it == null || it !in 0..255 }) return true
    val first = octets[0]!!
    val second = octets[1]!!
    return !(
        first == 0 ||
            first == 10 ||
            first == 127 ||
            (first == 169 && second == 254) ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168)
        )
}
