package com.nuvio.tv.ui.screens.player.autosync

import android.os.SystemClock
import androidx.media3.common.C
import com.nuvio.tv.ui.screens.player.PlayerMediaSourceFactory
import com.nuvio.tv.ui.screens.player.PlayerPlaybackNetworking
import com.nuvio.tv.ui.screens.player.SubtitleSyncCue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Player-independent Matroska/WebM embedded subtitle timeline reader.
 *
 * It performs bounded HTTP range reads only. It never seeks or rebuilds the active ExoPlayer/MPV
 * instance, which keeps AutoSync isolated from NuvioTV's custom Dolby Vision/libass extractor path.
 */
internal object EmbeddedSubtitleTimelineLoader {
    private const val TOTAL_TIMEOUT_MS = 7_000L
    private const val INITIAL_PROBE_BYTES = 512 * 1024
    private const val HEADER_PROBE_BYTES = 64
    private const val TAIL_PROBE_BYTES = 4 * 1024 * 1024
    private const val MAX_SEEK_HEAD_BYTES = 2 * 1024 * 1024
    private const val MAX_INFO_BYTES = 512 * 1024
    private const val MAX_TRACKS_BYTES = 4 * 1024 * 1024
    private const val MAX_CUES_BYTES = 8 * 1024 * 1024
    private const val MAX_TOTAL_DOWNLOAD_BYTES = 16L * 1024L * 1024L
    private const val MAX_RANGE_REQUESTS = 16
    private const val MAX_SEEK_HEAD_HOPS = 4
    private const val DEFAULT_TIMESTAMP_SCALE_NS = 1_000_000L
    private const val DEFAULT_CUE_DURATION_MS = 5_000L
    private const val MIN_INDEXED_CUES = 8
    private const val MIN_INDEXED_SPAN_MS = 30_000L
    private const val MAX_CACHE_ENTRIES = 2
    private const val NEGATIVE_CACHE_TTL_MS = 120_000L

    private const val ID_SEGMENT = 0x18538067L
    private const val ID_SEEK_HEAD = 0x114D9B74L
    private const val ID_INFO = 0x1549A966L
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_CUES = 0x1C53BB6BL
    private const val ID_CLUSTER = 0x1F43B675L

    private const val ID_SEEK = 0x4DBBL
    private const val ID_SEEK_ID = 0x53ABL
    private const val ID_SEEK_POSITION = 0x53ACL

    private const val ID_TIMESTAMP_SCALE = 0x2AD7B1L

    private const val ID_TRACK_ENTRY = 0xAEL
    private const val ID_TRACK_NUMBER = 0xD7L
    private const val ID_TRACK_TYPE = 0x83L
    private const val ID_FLAG_DEFAULT = 0x88L
    private const val ID_FLAG_FORCED = 0x55AAL
    private const val ID_FLAG_HEARING_IMPAIRED = 0x55ABL
    private const val ID_FLAG_VISUAL_IMPAIRED = 0x55ACL
    private const val ID_FLAG_TEXT_DESCRIPTIONS = 0x55ADL
    private const val ID_FLAG_COMMENTARY = 0x55AFL
    private const val ID_NAME = 0x536EL
    private const val ID_LANGUAGE = 0x22B59CL
    private const val ID_LANGUAGE_IETF = 0x22B59DL
    private const val ID_CODEC_ID = 0x86L
    private const val TRACK_TYPE_SUBTITLE = 17L

    private const val ID_CUE_POINT = 0xBBL
    private const val ID_CUE_TIME = 0xB3L
    private const val ID_CUE_TRACK_POSITIONS = 0xB7L
    private const val ID_CUE_TRACK = 0xF7L
    private const val ID_CUE_DURATION = 0xB2L

    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<String, CachedLoadResult>(
        MAX_CACHE_ENTRIES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, CachedLoadResult>?,
        ): Boolean = size > MAX_CACHE_ENTRIES
    }

    suspend fun load(
        sourceUrl: String,
        sourceHeaders: Map<String, String> = emptyMap(),
    ): IndexedEmbeddedTimeline? {
        if (!sourceUrl.startsWith("http://", ignoreCase = true) &&
            !sourceUrl.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }

        val cacheKey = "$sourceUrl#${sourceHeaders.hashCode()}"
        val nowMs = SystemClock.elapsedRealtime()
        synchronized(cacheLock) {
            val cached = cache[cacheKey]
            if (cached != null) {
                cached.timeline?.let { return it }
                if (nowMs - cached.createdAtMs < NEGATIVE_CACHE_TTL_MS) return null
                cache.remove(cacheKey)
            }
        }

        return try {
            val loaded = withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    loadMatroskaCueIndex(sourceUrl, sourceHeaders)
                }
            }
            synchronized(cacheLock) {
                cache[cacheKey] = CachedLoadResult(
                    timeline = loaded,
                    createdAtMs = SystemClock.elapsedRealtime(),
                )
            }
            loaded
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            synchronized(cacheLock) {
                cache[cacheKey] = CachedLoadResult(
                    timeline = null,
                    createdAtMs = SystemClock.elapsedRealtime(),
                )
            }
            null
        }
    }

    private fun loadMatroskaCueIndex(
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
    ): IndexedEmbeddedTimeline? {
        val startedMs = SystemClock.elapsedRealtime()
        val client = PlayerPlaybackNetworking.createHttpClient(sourceHeaders)
            .newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()

        val budget = RangeBudget(
            deadlineMs = SystemClock.elapsedRealtime() + TOTAL_TIMEOUT_MS,
            maxBytes = MAX_TOTAL_DOWNLOAD_BYTES,
            maxRequests = MAX_RANGE_REQUESTS,
        )

        val initial = fetchRange(
            client = client,
            sourceUrl = sourceUrl,
            sourceHeaders = sourceHeaders,
            start = 0L,
            length = INITIAL_PROBE_BYTES,
            requirePartialContent = false,
            budget = budget,
        ) ?: return null

        val segment = findSegment(initial.bytes) ?: return null
        val segmentDataStart = segment.dataStart.toLong()
        val directPositions = findInitialTopLevelPositions(initial.bytes, segment)

        val resolvedPositions = mutableMapOf<Long, Long>()
        directPositions.forEach { (id, position) ->
            resolvedPositions.putIfAbsent(id, position)
        }

        val queue = ArrayDeque<Long>()
        directPositions[ID_SEEK_HEAD]?.let(queue::addLast)
        val visited = mutableSetOf<Long>()
        var hops = 0

        while (queue.isNotEmpty() && hops < MAX_SEEK_HEAD_HOPS) {
            val position = queue.removeFirst()
            if (!visited.add(position)) continue
            hops++

            val seekHead = extractElementFromInitialProbe(
                initialBytes = initial.bytes,
                absolutePosition = position,
                expectedId = ID_SEEK_HEAD,
                maxElementBytes = MAX_SEEK_HEAD_BYTES,
            ) ?: fetchElementAt(
                client = client,
                sourceUrl = sourceUrl,
                sourceHeaders = sourceHeaders,
                absolutePosition = position,
                expectedId = ID_SEEK_HEAD,
                maxElementBytes = MAX_SEEK_HEAD_BYTES,
                budget = budget,
            ) ?: continue

            parseSeekHead(seekHead).forEach { (id, relativePosition) ->
                val absolute = segmentDataStart + relativePosition
                if (resolvedPositions.putIfAbsent(id, absolute) == null && id == ID_SEEK_HEAD) {
                    queue.addLast(absolute)
                } else if (id == ID_SEEK_HEAD && absolute !in visited) {
                    queue.addLast(absolute)
                }
            }
        }

        val infoPosition = resolvedPositions[ID_INFO] ?: directPositions[ID_INFO]
        val timestampScaleNs = infoPosition
            ?.let { position ->
                extractElementFromInitialProbe(
                    initialBytes = initial.bytes,
                    absolutePosition = position,
                    expectedId = ID_INFO,
                    maxElementBytes = MAX_INFO_BYTES,
                ) ?: fetchElementAt(
                    client = client,
                    sourceUrl = sourceUrl,
                    sourceHeaders = sourceHeaders,
                    absolutePosition = position,
                    expectedId = ID_INFO,
                    maxElementBytes = MAX_INFO_BYTES,
                    budget = budget,
                )
            }
            ?.let(::parseTimestampScaleNs)
            ?: DEFAULT_TIMESTAMP_SCALE_NS

        val tracksPosition = resolvedPositions[ID_TRACKS] ?: directPositions[ID_TRACKS]
            ?: return null
        val tracksBytes = extractElementFromInitialProbe(
            initialBytes = initial.bytes,
            absolutePosition = tracksPosition,
            expectedId = ID_TRACKS,
            maxElementBytes = MAX_TRACKS_BYTES,
        ) ?: fetchElementAt(
            client = client,
            sourceUrl = sourceUrl,
            sourceHeaders = sourceHeaders,
            absolutePosition = tracksPosition,
            expectedId = ID_TRACKS,
            maxElementBytes = MAX_TRACKS_BYTES,
            budget = budget,
        ) ?: return null

        val subtitleTracks = parseSubtitleTracks(tracksBytes)
        if (subtitleTracks.isEmpty()) return null

        val cuesPosition = resolvedPositions[ID_CUES] ?: directPositions[ID_CUES]
        val parsedCues = if (cuesPosition != null) {
            val cuesBytes = extractElementFromInitialProbe(
                initialBytes = initial.bytes,
                absolutePosition = cuesPosition,
                expectedId = ID_CUES,
                maxElementBytes = MAX_CUES_BYTES,
            ) ?: fetchElementAt(
                client = client,
                sourceUrl = sourceUrl,
                sourceHeaders = sourceHeaders,
                absolutePosition = cuesPosition,
                expectedId = ID_CUES,
                maxElementBytes = MAX_CUES_BYTES,
                budget = budget,
            )
            cuesBytes?.let {
                parseSubtitleCueTimelines(
                    cuesElement = it,
                    subtitleTracks = subtitleTracks,
                    timestampScaleNs = timestampScaleNs,
                )
            }
        } else {
            null
        } ?: findAndParseCuesNearFileEnd(
            client = client,
            sourceUrl = sourceUrl,
            sourceHeaders = sourceHeaders,
            totalLength = initial.totalLength,
            subtitleTracks = subtitleTracks,
            timestampScaleNs = timestampScaleNs,
            budget = budget,
        ) ?: return null

        val referenceTracks = subtitleTracks.mapNotNull { track ->
            val cues = parsedCues[track.number]
                .orEmpty()
                .sortedBy { it.startTimeMs }
                .distinctBy { it.startTimeMs }

            if (cues.size < MIN_INDEXED_CUES) return@mapNotNull null
            val spanMs = cues.last().startTimeMs - cues.first().startTimeMs
            if (spanMs < MIN_INDEXED_SPAN_MS) return@mapNotNull null

            var selectionFlags = 0
            if (track.isDefault) selectionFlags = selectionFlags or C.SELECTION_FLAG_DEFAULT
            if (track.forced) selectionFlags = selectionFlags or C.SELECTION_FLAG_FORCED

            var roleFlags = 0
            if (track.commentary) roleFlags = roleFlags or C.ROLE_FLAG_COMMENTARY
            if (track.hearingImpaired) {
                roleFlags = roleFlags or C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND
            }
            if (track.visualImpaired || track.textDescriptions) {
                roleFlags = roleFlags or C.ROLE_FLAG_DESCRIBES_VIDEO
            }

            ReferenceTrack(
                key = "mkv-cues:${track.number}",
                language = track.languageIetf?.takeIf { it.isNotBlank() }
                    ?: track.language?.takeIf { it.isNotBlank() },
                cues = cues,
                label = track.name?.takeIf { it.isNotBlank() }
                    ?: track.codecId.takeIf { it.isNotBlank() },
                selectionFlags = selectionFlags,
                roleFlags = roleFlags,
            )
        }

        if (referenceTracks.isEmpty()) return null

        return IndexedEmbeddedTimeline(
            tracks = referenceTracks,
            source = "matroska-cues",
            bytesDownloaded = budget.bytesDownloaded,
            rangeRequests = budget.requests,
            loadMs = SystemClock.elapsedRealtime() - startedMs,
        )
    }

    private fun findAndParseCuesNearFileEnd(
        client: OkHttpClient,
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
        totalLength: Long?,
        subtitleTracks: List<MatroskaSubtitleTrack>,
        timestampScaleNs: Long,
        budget: RangeBudget,
    ): Map<Int, List<SubtitleSyncCue>>? {
        val fileLength = totalLength?.takeIf { it > 0L } ?: return null
        val start = max(0L, fileLength - TAIL_PROBE_BYTES)
        if (start == 0L) return null

        val tailLength = (fileLength - start)
            .coerceAtMost(TAIL_PROBE_BYTES.toLong())
            .toInt()

        val tail = fetchRange(
            client = client,
            sourceUrl = sourceUrl,
            sourceHeaders = sourceHeaders,
            start = start,
            length = tailLength,
            requirePartialContent = true,
            budget = budget,
        ) ?: return null

        val offsets = findElementIdOffsets(tail.bytes, ID_CUES).asReversed()
        for (relativeOffset in offsets) {
            val cuesBytes = fetchElementAt(
                client = client,
                sourceUrl = sourceUrl,
                sourceHeaders = sourceHeaders,
                absolutePosition = start + relativeOffset,
                expectedId = ID_CUES,
                maxElementBytes = MAX_CUES_BYTES,
                budget = budget,
            ) ?: continue

            val parsed = parseSubtitleCueTimelines(
                cuesElement = cuesBytes,
                subtitleTracks = subtitleTracks,
                timestampScaleNs = timestampScaleNs,
            )
            if (parsed.values.any { it.size >= MIN_INDEXED_CUES }) return parsed
        }
        return null
    }

    private fun fetchElementAt(
        client: OkHttpClient,
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
        absolutePosition: Long,
        expectedId: Long,
        maxElementBytes: Int,
        budget: RangeBudget,
    ): ByteArray? {
        if (absolutePosition < 0L) return null

        val header = fetchRange(
            client = client,
            sourceUrl = sourceUrl,
            sourceHeaders = sourceHeaders,
            start = absolutePosition,
            length = HEADER_PROBE_BYTES,
            requirePartialContent = absolutePosition > 0L,
            budget = budget,
        ) ?: return null

        val element = readElement(header.bytes, 0, header.bytes.size) ?: return null
        if (element.id != expectedId) return null
        val dataSize = element.dataSize ?: return null
        val totalSize = element.dataStart.toLong() + dataSize
        if (totalSize <= 0L || totalSize > maxElementBytes.toLong()) return null

        if (header.bytes.size >= totalSize.toInt()) {
            return header.bytes.copyOfRange(0, totalSize.toInt())
        }

        return fetchRange(
            client = client,
            sourceUrl = sourceUrl,
            sourceHeaders = sourceHeaders,
            start = absolutePosition,
            length = totalSize.toInt(),
            requirePartialContent = absolutePosition > 0L,
            budget = budget,
        )?.bytes
    }

    private fun extractElementFromInitialProbe(
        initialBytes: ByteArray,
        absolutePosition: Long,
        expectedId: Long,
        maxElementBytes: Int,
    ): ByteArray? {
        if (absolutePosition < 0L || absolutePosition > Int.MAX_VALUE) return null
        val position = absolutePosition.toInt()
        if (position !in initialBytes.indices) return null

        val element = readElement(initialBytes, position, initialBytes.size) ?: return null
        if (element.id != expectedId) return null
        val end = element.endWithin(initialBytes.size) ?: return null
        val totalSize = end - position
        if (totalSize <= 0 || totalSize > maxElementBytes) return null
        return initialBytes.copyOfRange(position, end)
    }

    private fun fetchRange(
        client: OkHttpClient,
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
        start: Long,
        length: Int,
        requirePartialContent: Boolean,
        budget: RangeBudget,
    ): RangeResult? {
        if (length <= 0 || start < 0L) return null
        if (!budget.canRequest(length)) return null

        val end = start + length.toLong() - 1L
        val builder = Request.Builder()
            .url(sourceUrl)
            .header("Range", "bytes=$start-$end")

        var hasUserAgent = false
        sourceHeaders.forEach { (name, value) ->
            if (name.equals("Range", ignoreCase = true)) return@forEach
            if (name.equals("Host", ignoreCase = true)) return@forEach
            if (name.equals("Connection", ignoreCase = true)) return@forEach
            if (name.equals("Content-Length", ignoreCase = true)) return@forEach
            if (name.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
            builder.header(name, value)
        }
        if (!hasUserAgent) {
            builder.header("User-Agent", PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
        }

        budget.requests++

        return client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            if (requirePartialContent && response.code != 206) return@use null
            if (start > 0L && response.code != 206) return@use null

            val body = response.body ?: return@use null
            val bytes = readBounded(body.byteStream(), length)
            if (bytes.isEmpty()) return@use null

            budget.bytesDownloaded += bytes.size.toLong()
            if (budget.bytesDownloaded > budget.maxBytes) return@use null

            RangeResult(
                bytes = bytes,
                totalLength = parseTotalLength(
                    contentRange = response.header("Content-Range"),
                    contentLength = response.header("Content-Length"),
                    responseCode = response.code,
                ),
            )
        }
    }

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var remaining = maxBytes

        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read <= 0) break
            output.write(buffer, 0, read)
            remaining -= read
        }
        return output.toByteArray()
    }

    private fun parseTotalLength(
        contentRange: String?,
        contentLength: String?,
        responseCode: Int,
    ): Long? {
        val totalFromRange = contentRange
            ?.substringAfter('/', "")
            ?.takeIf { it.isNotBlank() && it != "*" }
            ?.toLongOrNull()
        if (totalFromRange != null) return totalFromRange
        return if (responseCode == 200) contentLength?.toLongOrNull() else null
    }

    private fun findSegment(bytes: ByteArray): EbmlElement? {
        var position = 0
        var count = 0
        while (position < bytes.size && count++ < 32) {
            val element = readElement(bytes, position, bytes.size) ?: return null
            if (element.id == ID_SEGMENT) return element
            val end = element.endWithin(bytes.size) ?: return null
            if (end <= position) return null
            position = end
        }
        return null
    }

    private fun findInitialTopLevelPositions(
        bytes: ByteArray,
        segment: EbmlElement,
    ): Map<Long, Long> {
        val result = mutableMapOf<Long, Long>()
        var position = segment.dataStart
        val segmentEnd = segment.endWithin(bytes.size) ?: bytes.size
        var count = 0

        while (position < segmentEnd && position < bytes.size && count++ < 128) {
            val element = readElement(bytes, position, minOf(segmentEnd, bytes.size)) ?: break
            when (element.id) {
                ID_SEEK_HEAD, ID_INFO, ID_TRACKS, ID_CUES ->
                    result.putIfAbsent(element.id, position.toLong())
                ID_CLUSTER -> break
            }
            val end = element.endWithin(minOf(segmentEnd, bytes.size)) ?: break
            if (end <= position) break
            position = end
        }
        return result
    }

    private fun parseSeekHead(bytes: ByteArray): Map<Long, Long> {
        val root = readElement(bytes, 0, bytes.size) ?: return emptyMap()
        if (root.id != ID_SEEK_HEAD) return emptyMap()
        val rootEnd = root.endWithin(bytes.size) ?: return emptyMap()
        val result = mutableMapOf<Long, Long>()

        forEachChild(bytes, root.dataStart, rootEnd) { seek ->
            if (seek.id != ID_SEEK) return@forEachChild
            val seekEnd = seek.endWithin(rootEnd) ?: return@forEachChild
            var targetId: Long? = null
            var position: Long? = null

            forEachChild(bytes, seek.dataStart, seekEnd) { child ->
                when (child.id) {
                    ID_SEEK_ID -> targetId = readBinaryId(bytes, child)
                    ID_SEEK_POSITION -> position = readUnsigned(bytes, child)
                }
            }

            val id = targetId
            val pos = position
            if (id != null && pos != null) result.putIfAbsent(id, pos)
        }
        return result
    }

    private fun parseTimestampScaleNs(bytes: ByteArray): Long {
        val root = readElement(bytes, 0, bytes.size) ?: return DEFAULT_TIMESTAMP_SCALE_NS
        if (root.id != ID_INFO) return DEFAULT_TIMESTAMP_SCALE_NS
        val rootEnd = root.endWithin(bytes.size) ?: return DEFAULT_TIMESTAMP_SCALE_NS
        var scale = DEFAULT_TIMESTAMP_SCALE_NS

        forEachChild(bytes, root.dataStart, rootEnd) { child ->
            if (child.id == ID_TIMESTAMP_SCALE) {
                readUnsigned(bytes, child)?.takeIf { it > 0L }?.let { scale = it }
            }
        }
        return scale
    }

    private fun parseSubtitleTracks(bytes: ByteArray): List<MatroskaSubtitleTrack> {
        val root = readElement(bytes, 0, bytes.size) ?: return emptyList()
        if (root.id != ID_TRACKS) return emptyList()
        val rootEnd = root.endWithin(bytes.size) ?: return emptyList()
        val result = mutableListOf<MatroskaSubtitleTrack>()

        forEachChild(bytes, root.dataStart, rootEnd) { entry ->
            if (entry.id != ID_TRACK_ENTRY) return@forEachChild
            val entryEnd = entry.endWithin(rootEnd) ?: return@forEachChild

            var number: Int? = null
            var type: Long? = null
            var name: String? = null
            var language: String? = null
            var languageIetf: String? = null
            var codecId = ""
            var isDefault = true
            var forced = false
            var hearingImpaired = false
            var visualImpaired = false
            var textDescriptions = false
            var commentary = false

            forEachChild(bytes, entry.dataStart, entryEnd) { child ->
                when (child.id) {
                    ID_TRACK_NUMBER -> number = readUnsigned(bytes, child)?.toInt()
                    ID_TRACK_TYPE -> type = readUnsigned(bytes, child)
                    ID_FLAG_DEFAULT -> isDefault = readUnsigned(bytes, child) != 0L
                    ID_FLAG_FORCED -> forced = readUnsigned(bytes, child) == 1L
                    ID_FLAG_HEARING_IMPAIRED -> hearingImpaired = readUnsigned(bytes, child) == 1L
                    ID_FLAG_VISUAL_IMPAIRED -> visualImpaired = readUnsigned(bytes, child) == 1L
                    ID_FLAG_TEXT_DESCRIPTIONS -> textDescriptions = readUnsigned(bytes, child) == 1L
                    ID_FLAG_COMMENTARY -> commentary = readUnsigned(bytes, child) == 1L
                    ID_NAME -> name = readUtf8(bytes, child)
                    ID_LANGUAGE -> language = readUtf8(bytes, child)
                    ID_LANGUAGE_IETF -> languageIetf = readUtf8(bytes, child)
                    ID_CODEC_ID -> codecId = readUtf8(bytes, child).orEmpty()
                }
            }

            val trackNumber = number
            if (trackNumber != null && type == TRACK_TYPE_SUBTITLE) {
                result += MatroskaSubtitleTrack(
                    number = trackNumber,
                    name = name,
                    language = language,
                    languageIetf = languageIetf,
                    codecId = codecId,
                    isDefault = isDefault,
                    forced = forced,
                    hearingImpaired = hearingImpaired,
                    visualImpaired = visualImpaired,
                    textDescriptions = textDescriptions,
                    commentary = commentary,
                )
            }
        }

        return result
    }

    private fun parseSubtitleCueTimelines(
        cuesElement: ByteArray,
        subtitleTracks: List<MatroskaSubtitleTrack>,
        timestampScaleNs: Long,
    ): Map<Int, List<SubtitleSyncCue>> {
        val root = readElement(cuesElement, 0, cuesElement.size) ?: return emptyMap()
        if (root.id != ID_CUES) return emptyMap()
        val rootEnd = root.endWithin(cuesElement.size) ?: return emptyMap()

        val subtitleTrackNumbers = subtitleTracks.map { it.number }.toSet()
        val result = mutableMapOf<Int, MutableList<SubtitleSyncCue>>()

        forEachChild(cuesElement, root.dataStart, rootEnd) cuePointLoop@ { cuePoint ->
            if (cuePoint.id != ID_CUE_POINT) return@cuePointLoop
            val cuePointEnd = cuePoint.endWithin(rootEnd) ?: return@cuePointLoop

            var cueTime: Long? = null
            val trackEntries = mutableListOf<Pair<Int, Long?>>()

            forEachChild(cuesElement, cuePoint.dataStart, cuePointEnd) childLoop@ { child ->
                when (child.id) {
                    ID_CUE_TIME -> cueTime = readUnsigned(cuesElement, child)
                    ID_CUE_TRACK_POSITIONS -> {
                        val positionsEnd = child.endWithin(cuePointEnd) ?: return@childLoop
                        var track: Int? = null
                        var duration: Long? = null

                        forEachChild(cuesElement, child.dataStart, positionsEnd) { positionChild ->
                            when (positionChild.id) {
                                ID_CUE_TRACK -> track = readUnsigned(cuesElement, positionChild)?.toInt()
                                ID_CUE_DURATION -> duration = readUnsigned(cuesElement, positionChild)
                            }
                        }

                        val trackNumber = track
                        if (trackNumber != null) {
                            trackEntries += trackNumber to duration
                        }
                    }
                }
            }

            val rawCueTime = cueTime ?: return@cuePointLoop
            val startMs = timecodeToMs(rawCueTime, timestampScaleNs)
            trackEntries.forEach { (trackNumber, rawDuration) ->
                if (trackNumber !in subtitleTrackNumbers) return@forEach

                val durationMs = rawDuration
                    ?.let { timecodeToMs(it, timestampScaleNs) }
                    ?.takeIf { it > 0L }
                    ?: DEFAULT_CUE_DURATION_MS

                result.getOrPut(trackNumber) { mutableListOf() } += SubtitleSyncCue(
                    startTimeMs = startMs,
                    endTimeMs = startMs + durationMs,
                    text = "",
                )
            }
        }

        return result
    }

    private fun timecodeToMs(value: Long, timestampScaleNs: Long): Long {
        if (value <= 0L) return 0L
        return (value * timestampScaleNs) / 1_000_000L
    }

    private fun findElementIdOffsets(bytes: ByteArray, id: Long): List<Int> {
        val idBytes = encodeElementId(id)
        if (idBytes.isEmpty() || bytes.size < idBytes.size) return emptyList()

        val result = mutableListOf<Int>()
        outer@ for (index in 0..bytes.size - idBytes.size) {
            for (offset in idBytes.indices) {
                if (bytes[index + offset] != idBytes[offset]) continue@outer
            }
            result += index
        }
        return result
    }

    private fun encodeElementId(id: Long): ByteArray {
        val length = when {
            id <= 0xFFL -> 1
            id <= 0xFFFFL -> 2
            id <= 0xFFFFFFL -> 3
            id <= 0xFFFFFFFFL -> 4
            else -> return ByteArray(0)
        }
        return ByteArray(length) { index ->
            val shift = (length - index - 1) * 8
            ((id shr shift) and 0xFFL).toByte()
        }
    }

    private fun forEachChild(
        bytes: ByteArray,
        start: Int,
        end: Int,
        block: (EbmlElement) -> Unit,
    ) {
        var position = start
        var count = 0
        while (position < end && count++ < 100_000) {
            val element = readElement(bytes, position, end) ?: break
            block(element)
            val next = element.endWithin(end) ?: break
            if (next <= position) break
            position = next
        }
    }

    private fun readElement(
        bytes: ByteArray,
        position: Int,
        limit: Int,
    ): EbmlElement? {
        val id = readVint(bytes, position, limit, removeMarker = false, maxLength = 4)
            ?: return null
        val size = readVint(
            bytes = bytes,
            position = position + id.length,
            limit = limit,
            removeMarker = true,
            maxLength = 8,
        ) ?: return null

        val dataStart = position + id.length + size.length
        if (dataStart > limit) return null

        return EbmlElement(
            id = id.value,
            dataStart = dataStart,
            dataSize = if (size.unknown) null else size.value,
        )
    }

    private fun readVint(
        bytes: ByteArray,
        position: Int,
        limit: Int,
        removeMarker: Boolean,
        maxLength: Int,
    ): Vint? {
        if (position !in 0 until limit || position >= bytes.size) return null
        val first = bytes[position].toInt() and 0xFF
        if (first == 0) return null

        var marker = 0x80
        var length = 1
        while (length <= 8 && first and marker == 0) {
            marker = marker ushr 1
            length++
        }
        if (length > maxLength || position + length > limit || position + length > bytes.size) {
            return null
        }

        var value = if (removeMarker) {
            (first and (marker - 1)).toLong()
        } else {
            first.toLong()
        }

        for (index in 1 until length) {
            value = (value shl 8) or (bytes[position + index].toLong() and 0xFFL)
        }

        val unknown = if (removeMarker) {
            val valueBits = 7 * length
            val maxValue = (1L shl valueBits) - 1L
            value == maxValue
        } else {
            false
        }

        return Vint(
            value = value,
            length = length,
            unknown = unknown,
        )
    }

    private fun readUnsigned(bytes: ByteArray, element: EbmlElement): Long? {
        val size = element.dataSize ?: return null
        if (size <= 0L || size > 8L) return null
        val end = element.dataStart + size.toInt()
        if (end > bytes.size) return null

        var value = 0L
        for (index in element.dataStart until end) {
            value = (value shl 8) or (bytes[index].toLong() and 0xFFL)
        }
        return value
    }

    private fun readBinaryId(bytes: ByteArray, element: EbmlElement): Long? {
        val size = element.dataSize ?: return null
        if (size <= 0L || size > 4L) return null
        val end = element.dataStart + size.toInt()
        if (end > bytes.size) return null

        var value = 0L
        for (index in element.dataStart until end) {
            value = (value shl 8) or (bytes[index].toLong() and 0xFFL)
        }
        return value
    }

    private fun readUtf8(bytes: ByteArray, element: EbmlElement): String? {
        val size = element.dataSize ?: return null
        if (size < 0L || size > Int.MAX_VALUE) return null
        val end = element.dataStart + size.toInt()
        if (end > bytes.size) return null
        return bytes.copyOfRange(element.dataStart, end)
            .toString(Charsets.UTF_8)
            .trimEnd('\u0000')
            .trim()
    }

    private data class CachedLoadResult(
        val timeline: IndexedEmbeddedTimeline?,
        val createdAtMs: Long,
    )

    private data class RangeResult(
        val bytes: ByteArray,
        val totalLength: Long?,
    )

    private class RangeBudget(
        val deadlineMs: Long,
        val maxBytes: Long,
        val maxRequests: Int,
    ) {
        var requests: Int = 0
        var bytesDownloaded: Long = 0L

        fun canRequest(length: Int): Boolean {
            if (SystemClock.elapsedRealtime() >= deadlineMs) return false
            if (requests >= maxRequests) return false
            if (bytesDownloaded >= maxBytes) return false
            return length.toLong() <= maxBytes - bytesDownloaded
        }
    }

    private data class MatroskaSubtitleTrack(
        val number: Int,
        val name: String?,
        val language: String?,
        val languageIetf: String?,
        val codecId: String,
        val isDefault: Boolean,
        val forced: Boolean,
        val hearingImpaired: Boolean,
        val visualImpaired: Boolean,
        val textDescriptions: Boolean,
        val commentary: Boolean,
    )

    private data class EbmlElement(
        val id: Long,
        val dataStart: Int,
        val dataSize: Long?,
    ) {
        fun endWithin(limit: Int): Int? {
            val size = dataSize ?: return null
            if (size < 0L || size > Int.MAX_VALUE) return null
            val end = dataStart.toLong() + size
            if (end > limit.toLong() || end > Int.MAX_VALUE.toLong()) return null
            return end.toInt()
        }
    }

    private data class Vint(
        val value: Long,
        val length: Int,
        val unknown: Boolean,
    )
}
