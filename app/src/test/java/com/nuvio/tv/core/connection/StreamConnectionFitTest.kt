package com.nuvio.tv.core.connection

import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.StreamClientResolve
import com.nuvio.tv.domain.model.StreamClientResolveRaw
import com.nuvio.tv.domain.model.StreamClientResolveStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class StreamConnectionFitTest {

    private val gb = 1_000_000_000L

    @Test
    fun `average bitrate comes from size and runtime`() {
        // 9 GB over 120 minutes = 10 Mbps.
        assertEquals(10.0, stream(sizeBytes = 9 * gb).averageBitrateMbps(runtimeMinutes = 120)!!, 0.001)
    }

    @Test
    fun `average bitrate prefers the resolved file size`() {
        val stream = stream(sizeBytes = 90 * gb).copy(clientResolve = resolved(size = 9 * gb, folderSize = 90 * gb))

        assertEquals(10.0, stream.averageBitrateMbps(runtimeMinutes = 120)!!, 0.001)
    }

    @Test
    fun `average bitrate is unknown when data is missing or implausible`() {
        assertNull(stream(sizeBytes = null).averageBitrateMbps(runtimeMinutes = 120))
        assertNull(stream(sizeBytes = 9 * gb).averageBitrateMbps(runtimeMinutes = 0))
        assertNull(stream(sizeBytes = 10_000_000).averageBitrateMbps(runtimeMinutes = 120))
        // A 400 GB "episode" is a mislabeled pack, not a 1000+ Mbps stream.
        assertNull(stream(sizeBytes = 400 * gb).averageBitrateMbps(runtimeMinutes = 45))
    }

    @Test
    fun `largest stream that fits comes first, not the smallest`() {
        // ~95 Mbps connection, 120 min movie: the 1 GB file must not beat the 5 GB one.
        val fit = StreamConnectionFit(runtimeMinutes = 120, connectionMbps = 95.0)
        val oneGb = stream(name = "1gb", sizeBytes = 1 * gb)
        val fiveGb = stream(name = "5gb", sizeBytes = 5 * gb)
        val remux = stream(name = "remux", sizeBytes = 126 * gb) // 140 Mbps, too much

        val ordered = fit.apply(listOf(oneGb, remux, fiveGb))

        assertEquals(listOf("5gb", "1gb", "remux"), ordered.map { it.name })
    }

    @Test
    fun `fitting streams by bitrate, then unknown, then exceeding in original order`() {
        // 30 Mbps connection, 120 min runtime: anything above 20 Mbps average is demoted.
        val fit = StreamConnectionFit(runtimeMinutes = 120, connectionMbps = 30.0)
        val remux = stream(name = "remux", sizeBytes = 60 * gb) // 66.7 Mbps
        val uhd = stream(name = "uhd", sizeBytes = 20 * gb) // 22.2 Mbps
        val hd = stream(name = "hd", sizeBytes = 9 * gb) // 10 Mbps
        val unknown = stream(name = "unknown", sizeBytes = null)
        val small = stream(name = "small", sizeBytes = 2 * gb)

        val ordered = fit.apply(listOf(remux, uhd, hd, unknown, small))

        assertEquals(listOf("hd", "small", "unknown", "remux", "uhd"), ordered.map { it.name })
    }

    @Test
    fun `order is untouched when it already matches or everything exceeds the connection`() {
        val streams = listOf(stream(name = "a", sizeBytes = 9 * gb), stream(name = "b", sizeBytes = 4 * gb))

        assertSame(streams, StreamConnectionFit(runtimeMinutes = 120, connectionMbps = 100.0).apply(streams))
        assertSame(streams, StreamConnectionFit(runtimeMinutes = 120, connectionMbps = 2.0).apply(streams))
    }

    @Test
    fun `groups are ordered independently and unchanged groups are kept`() {
        val fit = StreamConnectionFit(runtimeMinutes = 120, connectionMbps = 30.0)
        val unchanged = AddonStreams("A", null, listOf(stream(name = "a", sizeBytes = 9 * gb)))
        val reordered = AddonStreams(
            "B",
            null,
            listOf(stream(name = "remux", sizeBytes = 60 * gb), stream(name = "hd", sizeBytes = 9 * gb))
        )

        val ordered = fit.apply(listOf(unchanged, reordered))

        assertSame(unchanged, ordered[0])
        assertEquals(listOf("hd", "remux"), ordered[1].streams.map { it.name })
    }

    private fun stream(name: String = "stream", sizeBytes: Long?) = Stream(
        name = name,
        title = null,
        description = null,
        url = "https://cdn.example.com/$name.mkv",
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = null,
            countryWhitelist = null,
            proxyHeaders = null,
            videoSize = sizeBytes,
        ),
        addonName = "Addon",
        addonLogo = null,
    )

    private fun resolved(size: Long, folderSize: Long) = StreamClientResolve(
        type = null, infoHash = null, fileIdx = null, magnetUri = null, sources = null,
        torrentName = null, filename = null, mediaType = null, mediaId = null, mediaOnlyId = null,
        title = null, season = null, episode = null, service = null, serviceIndex = null,
        serviceExtension = null, isCached = null,
        stream = StreamClientResolveStream(
            raw = StreamClientResolveRaw(
                torrentName = null, filename = null, size = size, folderSize = folderSize,
                tracker = null, indexer = null, network = null, parsed = null
            )
        )
    )
}
