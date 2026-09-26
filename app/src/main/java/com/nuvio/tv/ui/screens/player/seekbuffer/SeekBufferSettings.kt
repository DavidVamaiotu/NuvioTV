package com.nuvio.tv.ui.screens.player.seekbuffer

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Seek buffer" (Nuvio Reshaped): how much of what comes next is loaded ahead, so seeks inside it
 * are instant. ExoPlayer keeps it on disk ([SeekReadAhead]), libmpv in its demuxer cache in
 * memory. Kept outside PlayerSettingsDataStore so upstream settings need no new fields or
 * migrations. It applies from the next playback.
 */
internal object SeekBufferSettings {
    /** 0 keeps Nuvio's own behaviour: no read-ahead file, libmpv's own cache sizes. */
    const val NUVIO_DEFAULT_MB = 0
    val optionsMb = listOf(NUVIO_DEFAULT_MB, 256, 512, 1024)
    private const val DEFAULT_MB = 512

    private const val PREFS_NAME = "nuvio_tv_seek_buffer"
    private const val KEY_BUFFER_MB = "seek_buffer_mb"
    private const val MB = 1024L * 1024L

    // NuvioMpvSurfaceView's own demuxer cache sizes: the setting never goes below them.
    private const val MPV_NUVIO_CACHE_BYTES = 64L * MB

    @Volatile private var preferences: SharedPreferences? = null
    @Volatile private var totalRamBytes = -1L

    private val _bufferMb = MutableStateFlow(DEFAULT_MB)
    val bufferMb: StateFlow<Int> = _bufferMb.asStateFlow()

    fun initialize(context: Context) {
        if (preferences != null) return
        synchronized(this) {
            if (preferences != null) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            _bufferMb.value = prefs.getInt(KEY_BUFFER_MB, DEFAULT_MB).takeIf { it in optionsMb } ?: DEFAULT_MB
            preferences = prefs
        }
    }

    /** At launch: loads the setting and deletes a read-ahead file an earlier run left behind. */
    fun onAppStart(context: Context) {
        initialize(context)
        SeekReadAhead.cleanUp(context)
    }

    fun setBufferMb(context: Context, mb: Int) {
        initialize(context)
        if (mb !in optionsMb || _bufferMb.value == mb) return
        _bufferMb.value = mb
        preferences?.edit()?.putInt(KEY_BUFFER_MB, mb)?.apply()
    }

    fun cycle(context: Context) {
        initialize(context)
        setBufferMb(context, optionsMb[(optionsMb.indexOf(_bufferMb.value) + 1) % optionsMb.size])
    }

    /**
     * libmpv's forward and back demuxer cache in bytes: two thirds of the setting ahead of
     * playback and one third behind it, capped at an eighth of the device's RAM (it is native
     * memory), and never below Nuvio's own 64 MB each.
     */
    fun mpvCacheBytes(context: Context): Pair<Long, Long> {
        initialize(context)
        var budget = _bufferMb.value.coerceAtLeast(0) * MB
        if (budget <= 0L) return MPV_NUVIO_CACHE_BYTES to MPV_NUVIO_CACHE_BYTES
        val ram = deviceRamBytes(context)
        if (ram > 0L) budget = budget.coerceAtMost(ram / 8)
        return maxOf(budget * 2 / 3, MPV_NUVIO_CACHE_BYTES) to maxOf(budget / 3, MPV_NUVIO_CACHE_BYTES)
    }

    private fun deviceRamBytes(context: Context): Long {
        if (totalRamBytes >= 0L) return totalRamBytes
        val memoryInfo = ActivityManager.MemoryInfo()
        val manager = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val total = runCatching {
            manager?.getMemoryInfo(memoryInfo)
            memoryInfo.totalMem
        }.getOrDefault(0L)
        totalRamBytes = total
        return total
    }
}
