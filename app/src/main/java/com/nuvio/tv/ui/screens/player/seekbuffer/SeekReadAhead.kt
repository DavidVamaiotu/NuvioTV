@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.ui.screens.player.seekbuffer

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.ByteBufferDataReader
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Disk read-ahead for ExoPlayer, sized by [SeekBufferSettings] (Nuvio Reshaped).
 *
 * ExoPlayer's own buffer lives in memory and is capped by the device budget. Here one connection
 * reads the stream ahead of playback into a ring file of the chosen size and the player reads
 * from that file, so seeks anywhere inside it need no network. Parts behind playback are
 * overwritten: only what is coming up is kept.
 *
 * It stands in for the player's own connection rather than adding one: a read outside the ring
 * moves the read-ahead there, so the stream never has more than the read-ahead's connection
 * (debrid hosts allow one per link). It sits directly on the network source, under the optional
 * VOD disk cache, which keeps what was already played; the ring holds what comes next. The ring
 * is a temporary file, deleted when playback ends and at every launch.
 */
internal object SeekReadAhead {
    private const val TAG = "SeekReadAhead"
    private const val DIR = "nuvio_seek_read_ahead"
    private const val MB = 1024L * 1024L
    private const val MIN_CAPACITY = 32L * MB

    // Same headroom the VOD cache leaves, so low-storage boxes are never filled to the brim.
    private const val FREE_SPACE_RESERVE = 1024L * MB

    @Volatile private var session: ReadAheadSession? = null

    private fun cacheDir(context: Context) = File(context.applicationContext.cacheDir, DIR)

    /** Called at launch: nothing from an earlier run is kept. */
    fun cleanUp(context: Context) {
        // Listed now, so a playback that starts while they are deleted keeps its own file.
        val leftovers = runCatching { cacheDir(context).listFiles() }.getOrNull()
        if (leftovers.isNullOrEmpty()) return
        Thread({ leftovers.forEach { runCatching { it.deleteRecursively() } } }, "NuvioSeekReadAheadCleanup").apply {
            isDaemon = true
        }.start()
    }

    /**
     * [upstream] with the read-ahead in front of it for [sourceUrl], or [upstream] itself when the
     * setting is on Nuvio's default, the stream is HLS/DASH ([progressive] false) or on the device,
     * or there is not enough free storage. A live read-ahead of the same stream is kept, so a
     * re-prepare (track or subtitle change) keeps what was read ahead.
     */
    @Synchronized
    fun wrap(context: Context, sourceUrl: String, progressive: Boolean, upstream: DataSource.Factory): DataSource.Factory {
        val current = session
        if (current != null && current.key == sourceUrl && !current.isClosed) {
            current.upstreamFactory = upstream
            return ReadAheadDataSourceFactory(current, upstream)
        }
        if (current != null && current.key != sourceUrl) {
            current.close()
            session = null
        }
        // A read-ahead that could not write its file is not retried for the same stream.
        if (current != null && current.key == sourceUrl && current.diskFailed) return upstream
        session = null

        SeekBufferSettings.initialize(context)
        val chosen = SeekBufferSettings.bufferMb.value.coerceAtLeast(0) * MB
        if (!progressive || chosen <= 0 || !isRemoteHttp(sourceUrl) || looksAdaptive(sourceUrl)) return upstream
        val dir = cacheDir(context)
        val free = runCatching { dir.mkdirs(); dir.usableSpace }.getOrDefault(0L)
        val capacity = if (free > 0) minOf(chosen, free / 2, free - FREE_SPACE_RESERVE) else chosen
        if (capacity < MIN_CAPACITY) {
            Log.i(TAG, "SEEK_READ_AHEAD: off, free=${free / MB}MB")
            return upstream
        }
        val created = ReadAheadSession(sourceUrl, File(dir, "ring_${System.nanoTime()}.bin"), capacity)
        created.upstreamFactory = upstream
        session = created
        Log.i(TAG, "SEEK_READ_AHEAD: on, capacity=${capacity / MB}MB free=${free / MB}MB")
        return ReadAheadDataSourceFactory(created, upstream)
    }

    /** Streams on the device (TorrServer, Usenet and other local proxies) need no read-ahead. */
    private fun isRemoteHttp(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase(Locale.US) ?: return false
        return host != "localhost" && host != "::1" && host != "[::1]" && !host.startsWith("127.")
    }

    /** HLS/DASH playlists are re-read for updates and small anyway; they are left alone. */
    private fun looksAdaptive(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        val path = lower.substringBefore('?').substringBefore('#')
        return path.endsWith(".m3u8") || path.endsWith(".m3u") || path.endsWith(".mpd") ||
            path.contains(".ism") || lower.contains("m3u8") || lower.contains("format=mpd")
    }

    /**
     * [exoBufferedMs] extended by what the read-ahead holds beyond the player's buffer, converted
     * to time with the file's average bitrate, so the seek bar shows it. An estimate: variable
     * bitrate files can be a little off either way.
     */
    fun bufferedPositionMs(exoBufferedMs: Long, durationMs: Long): Long {
        val base = exoBufferedMs.coerceAtLeast(0L)
        if (durationMs <= 0L) return base
        val (aheadBytes, totalBytes) = session?.aheadOfPlayer() ?: return base
        if (aheadBytes <= 0 || totalBytes <= 0) return base
        val aheadMs = (aheadBytes.toDouble() / totalBytes * durationMs).toLong()
        return minOf(base + aheadMs, durationMs)
    }

    /**
     * Whether playback is downloading right now, for the connection speed sampler: with the
     * read-ahead on, that is its connection, not ExoPlayer's reads from the file. Null when no
     * read-ahead is running, so ExoPlayer's own loading state applies.
     */
    fun isDownloading(): Boolean? = session?.takeIf { !it.isClosed }?.isDownloading

    /** Called when the player is released: stops reading and deletes the file. */
    @Synchronized
    fun release() {
        session?.close()
        session = null
    }
}

private class ReadAheadDataSourceFactory(
    private val session: ReadAheadSession,
    private val upstream: DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = ReadAheadDataSource(session, upstream)
}

/**
 * The ring file and the one connection filling it. Valid bytes are the stream range
 * [windowStart, windowEnd); stream position p lives at file offset p % capacity.
 */
private class ReadAheadSession(val key: String, private val file: File, private val capacity: Long) {
    @Volatile var upstreamFactory: DataSource.Factory? = null

    // Opened by the filler thread, so the player thread never touches storage to set it up.
    @Volatile private var channel: FileChannel? = null
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    // Guarded by lock.
    private var windowStart = 0L
    private var windowEnd = 0L
    private var generation = 0
    private var started = false
    private var ended = false
    private var error: IOException? = null
    private var contentLength = C.LENGTH_UNSET.toLong()
    private var closed = false
    private var filler: Thread? = null
    private var playerPosition = -1L
    private var retryNow = false
    /** Set when the server says the stream is an HLS/DASH playlist: later reads go direct. */
    private var adaptive = false

    /** True while the connection is receiving data, false while it waits (full, ended, retry). */
    @Volatile var isDownloading = false
        private set
    /** The ring file could not be written (storage full or gone): the player reads directly. */
    @Volatile var diskFailed = false
        private set
    @Volatile var uri: Uri? = null
        private set
    @Volatile var responseHeaders: Map<String, List<String>> = emptyMap()
        private set

    val isClosed: Boolean get() = lock.withLock { closed }

    /**
     * Prepares the ring for a player read at [position]: inside (or just past) it, the read waits
     * for the connection; anywhere else the read-ahead moves there. A connection error the
     * player is retrying after is retried at once. False once closed.
     */
    fun serve(position: Long): Boolean {
        lock.withLock {
            if (closed || adaptive) return false
            if (started && position >= windowStart && position <= windowEnd + NEAR_BYTES) {
                if (error != null) {
                    error = null
                    retryNow = true
                    changed.signalAll()
                }
                return true
            }
        }
        relocate(position)
        return !isClosed
    }

    /** Bytes read ahead past where the player last read, and the stream length; null if unknown. */
    fun aheadOfPlayer(): Pair<Long, Long>? = lock.withLock {
        if (closed || !started || contentLength == C.LENGTH_UNSET.toLong()) return null
        if (playerPosition < windowStart || playerPosition > windowEnd) return null
        (windowEnd - playerPosition) to contentLength
    }

    /** Stream length, when the server told us. */
    fun length(): Long = lock.withLock { contentLength }

    /** Moves the read-ahead to [position]; what was read ahead elsewhere is dropped. */
    private fun relocate(position: Long) = lock.withLock {
        if (closed) return@withLock
        generation++
        windowStart = position
        windowEnd = position
        ended = contentLength != C.LENGTH_UNSET.toLong() && position >= contentLength
        error = null
        started = true
        playerPosition = position
        changed.signalAll()
        if (filler == null) {
            filler = Thread(::fillLoop, "NuvioSeekReadAhead").apply {
                isDaemon = true
                start()
            }
        }
    }

    /**
     * Reads up to [length] bytes at [position] from the ring into [target] (from its position),
     * waiting for the connection when they are not there yet. Returns [C.RESULT_END_OF_INPUT] at
     * the end of the stream.
     */
    fun read(position: Long, target: ByteBuffer, length: Int): Int {
        val available = lock.withLock {
            while (!closed && position >= windowStart && position >= windowEnd && !ended && error == null) {
                try {
                    changed.await()
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException()
                }
            }
            if (closed) throw ReadAheadClosedException()
            if (position < windowStart) throw IOException("read-ahead moved")
            if (position >= windowEnd) {
                if (ended) return C.RESULT_END_OF_INPUT
                throw error ?: IOException("read-ahead failed")
            }
            minOf(length.toLong(), target.remaining().toLong(), windowEnd - position).toInt()
        }
        if (available <= 0) return 0
        readFile(position, target, available)
        lock.withLock {
            // Keep a little behind the player for re-reads; the rest of the ring is free again.
            playerPosition = position + available
            val keepFrom = position + available - BACK_KEEP_BYTES
            if (keepFrom > windowStart) {
                windowStart = minOf(keepFrom, windowEnd)
                changed.signalAll()
            }
        }
        return available
    }

    fun close() {
        val hadFiller = lock.withLock {
            if (closed) return
            closed = true
            changed.signalAll()
            filler != null
        }
        // The filler deletes the file itself once its connection is closed.
        if (!hadFiller) disposeFile()
    }

    private fun fillLoop() {
        try {
            channel = RandomAccessFile(file, "rw").channel
        } catch (failure: IOException) {
            failDisk(failure)
        }
        val buffer = ByteArray(CHUNK_BYTES)
        var source: DataSource? = null
        var myGeneration = -1
        var position = 0L
        var failures = 0
        while (true) {
            val space = lock.withLock {
                while (!closed && myGeneration == generation && (ended || windowEnd - windowStart >= capacity)) {
                    isDownloading = false
                    changed.await()
                }
                if (closed) return@withLock null
                if (myGeneration != generation) {
                    myGeneration = generation
                    position = windowEnd
                    failures = 0
                    source?.closeQuietly()
                    source = null
                }
                minOf(CHUNK_BYTES.toLong(), capacity - (windowEnd - windowStart)).toInt()
            } ?: break
            isDownloading = true
            try {
                val open = source ?: openAt(position, myGeneration)?.also { source = it } ?: continue
                val read = open.read(buffer, 0, space)
                if (read == C.RESULT_END_OF_INPUT) {
                    open.closeQuietly()
                    source = null
                    lock.withLock {
                        if (myGeneration == generation) {
                            contentLength = windowEnd
                            ended = true
                            changed.signalAll()
                        }
                    }
                    continue
                }
                // Data for a place the read-ahead already left is dropped, not written over the new one.
                if (lock.withLock { closed || myGeneration != generation }) continue
                try {
                    writeFile(position, buffer, read)
                } catch (diskFailure: IOException) {
                    // Storage full or gone: stop, and the player falls back to reading directly.
                    // The connection closes first, so the player's own never sits next to it.
                    open.closeQuietly()
                    source = null
                    failDisk(diskFailure)
                    continue
                }
                position += read
                failures = 0
                lock.withLock {
                    if (myGeneration == generation) {
                        windowEnd = position
                        changed.signalAll()
                    }
                }
            } catch (failure: IOException) {
                source?.closeQuietly()
                source = null
                isDownloading = false
                // A refused or missing link will not come back by retrying: tell the player now.
                failures = if (isPermanent(failure)) SURFACE_AFTER_FAILURES else failures + 1
                lock.withLock {
                    // Brief drops are retried quietly, like a slow network; repeated failures
                    // reach the player so its own error handling (and error screen) applies.
                    if (myGeneration == generation && failures >= SURFACE_AFTER_FAILURES) {
                        error = failure
                        changed.signalAll()
                    }
                    val waitMs = minOf(RETRY_BASE_MS shl minOf(failures - 1, 3), RETRY_MAX_MS)
                    var leftNs = TimeUnit.MILLISECONDS.toNanos(waitMs)
                    while (!closed && myGeneration == generation && !retryNow && leftNs > 0) {
                        leftNs = changed.awaitNanos(leftNs)
                    }
                    retryNow = false
                }
            }
        }
        isDownloading = false
        source?.closeQuietly()
        disposeFile()
    }

    private fun failDisk(failure: IOException) {
        Log.w("SeekReadAhead", "read-ahead file unwritable, reading directly", failure)
        diskFailed = true
        close()
    }

    /** Opens the connection at [position]; null when the read-ahead moved meanwhile. */
    private fun openAt(position: Long, forGeneration: Int): DataSource? {
        val factory = upstreamFactory ?: throw IOException("no upstream")
        val source = factory.createDataSource()
        val opened = try {
            source.open(DataSpec.Builder().setUri(key).setPosition(position).build())
        } catch (failure: IOException) {
            source.closeQuietly()
            throw failure
        }
        lock.withLock {
            if (forGeneration != generation || closed) {
                source.closeQuietly()
                return null
            }
            if (opened != C.LENGTH_UNSET.toLong()) contentLength = position + opened
            uri = source.uri
            responseHeaders = source.responseHeaders
            val contentType = responseHeaders.entries
                .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                ?.value?.firstOrNull()?.lowercase(Locale.US).orEmpty()
            if ("mpegurl" in contentType || "dash+xml" in contentType) adaptive = true
            error = null
        }
        return source
    }

    private fun isPermanent(failure: IOException): Boolean {
        var cause: Throwable? = failure
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                return cause.responseCode in PERMANENT_HTTP_CODES
            }
            cause = cause.cause
        }
        return false
    }

    private fun writeFile(position: Long, buffer: ByteArray, length: Int) {
        val ch = channel ?: throw IOException("read-ahead file not open")
        var done = 0
        while (done < length) {
            var at = (position + done) % capacity
            val part = minOf((length - done).toLong(), capacity - at).toInt()
            val bytes = ByteBuffer.wrap(buffer, done, part)
            while (bytes.hasRemaining()) at += ch.write(bytes, at)
            done += part
        }
    }

    private fun readFile(position: Long, target: ByteBuffer, length: Int) {
        val ch = channel ?: throw IOException("read-ahead file not open")
        val limit = target.limit()
        try {
            var done = 0
            while (done < length) {
                var at = (position + done) % capacity
                val part = minOf((length - done).toLong(), capacity - at).toInt()
                target.limit(target.position() + part)
                while (target.hasRemaining()) {
                    val read = ch.read(target, at)
                    if (read < 0) throw IOException("read-ahead file truncated")
                    at += read
                }
                done += part
            }
        } finally {
            target.limit(limit)
        }
    }

    private fun disposeFile() {
        runCatching { channel?.close() }
        runCatching { file.delete() }
    }

    private companion object {
        const val CHUNK_BYTES = 256 * 1024
        const val NEAR_BYTES = 4L * 1024 * 1024
        const val BACK_KEEP_BYTES = 8L * 1024 * 1024
        const val SURFACE_AFTER_FAILURES = 3
        const val RETRY_BASE_MS = 1_000L
        const val RETRY_MAX_MS = 8_000L

        // The codes the player's own load error policy does not retry either.
        val PERMANENT_HTTP_CODES = setOf(400, 401, 403, 404, 410)
    }
}

private class ReadAheadClosedException : IOException("read-ahead closed")

/**
 * The player's data source. Every read of the playing stream comes from the ring: an open
 * outside it (a seek, or a container index at the end of the file) moves the read-ahead there
 * first, so the stream only ever has the read-ahead's one connection. Other URLs (subtitles, a
 * separate audio track) read directly. Byte buffer reads go straight from the file into the
 * player's (native) buffers, like the network sources they replace.
 */
private class ReadAheadDataSource(
    private val session: ReadAheadSession,
    private val upstream: DataSource.Factory,
) : DataSource, ByteBufferDataReader {
    private val transferListeners = ArrayList<TransferListener>(2)
    private var direct: DataSource? = null
    private var openedSpec: DataSpec? = null
    private var fromRing = false
    private var directOpen = false
    private var position = 0L
    private var remaining = C.LENGTH_UNSET.toLong()

    private fun direct(): DataSource = direct ?: upstream.createDataSource().also { created ->
        transferListeners.forEach(created::addTransferListener)
        direct = created
    }

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
        direct?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        openedSpec = dataSpec
        position = dataSpec.position
        remaining = dataSpec.length
        if (dataSpec.uri.toString() == session.key && session.serve(position)) {
            fromRing = true
            val length = session.length()
            return when {
                remaining != C.LENGTH_UNSET.toLong() -> remaining
                length != C.LENGTH_UNSET.toLong() -> (length - position).coerceAtLeast(0L)
                else -> C.LENGTH_UNSET.toLong()
            }
        }
        fromRing = false
        directOpen = true
        return direct().open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val wanted = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), remaining).toInt()
        val read = if (fromRing) {
            try {
                session.read(position, ByteBuffer.wrap(buffer, offset, wanted), wanted)
            } catch (closed: ReadAheadClosedException) {
                if (!session.diskFailed) throw closed
                continueDirect()
                direct().read(buffer, offset, wanted)
            }
        } else {
            direct().read(buffer, offset, wanted)
        }
        return advance(read)
    }

    override fun supportsByteBufferRead(): Boolean {
        if (fromRing) return true
        val source = direct ?: return false
        return directOpen && source is ByteBufferDataReader && source.supportsByteBufferRead()
    }

    override fun read(buffer: ByteBuffer, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val wanted = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), remaining).toInt()
        val read = if (fromRing) {
            try {
                session.read(position, buffer, wanted)
            } catch (closed: ReadAheadClosedException) {
                if (!session.diskFailed) throw closed
                continueDirect()
                readDirect(buffer, wanted)
            }
        } else {
            readDirect(buffer, wanted)
        }
        return advance(read)
    }

    private fun readDirect(buffer: ByteBuffer, length: Int): Int {
        val source = direct()
        if (source is ByteBufferDataReader && source.supportsByteBufferRead()) return source.read(buffer, length)
        val temp = ByteArray(minOf(length, buffer.remaining()))
        val read = source.read(temp, 0, temp.size)
        if (read > 0) buffer.put(temp, 0, read)
        return read
    }

    private fun advance(read: Int): Int {
        if (read == C.RESULT_END_OF_INPUT) return read
        position += read
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= read
        return read
    }

    /** The ring could not be written: carry on from the same place over a direct connection. */
    private fun continueDirect() {
        val spec = openedSpec ?: throw ReadAheadClosedException()
        fromRing = false
        directOpen = true
        direct().open(spec.buildUpon().setPosition(position).setLength(remaining).build())
    }

    override fun getUri(): Uri? = if (fromRing) session.uri ?: Uri.parse(session.key) else direct?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        if (fromRing) session.responseHeaders else direct?.responseHeaders ?: emptyMap()

    override fun close() {
        fromRing = false
        openedSpec = null
        if (directOpen) {
            directOpen = false
            direct?.close()
        }
    }
}

private fun DataSource.closeQuietly() {
    runCatching { close() }
}
