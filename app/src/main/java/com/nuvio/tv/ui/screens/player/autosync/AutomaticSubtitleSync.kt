package com.nuvio.tv.ui.screens.player.autosync

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.ui.screens.player.PlayerSubtitleCueParser
import com.nuvio.tv.ui.screens.player.PlayerSubtitleUtils
import com.nuvio.tv.ui.screens.player.SUBTITLE_DELAY_MAX_MS
import com.nuvio.tv.ui.screens.player.SUBTITLE_DELAY_MIN_MS
import com.nuvio.tv.ui.screens.player.SubtitleSyncCue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Automatic subtitle synchronization for NuvioTV.
 *
 * V1 deliberately reuses NuvioTV's existing subtitle downloader/parser and delay application.
 * Embedded reference timing is loaded independently from Matroska/WebM Cues, so the active player
 * does not need to seek, rebuild, or expose its custom extractor internals.
 */
internal object AutomaticSubtitleSync {
    private const val TAG = "NuvioAutoSync"

    private const val MIN_CUES = 8
    private const val MIN_SPAN_MS = 30_000L
    private const val MATCH_TOLERANCE_MS = 1_800L
    private const val OFFSET_BUCKET_MS = 500L
    private const val MAX_OFFSET_CANDIDATES = 12
    private const val MAX_OFFSET_SAMPLE_CUES = 64
    private const val MAX_REFERENCE_TRACKS = 8
    private const val MAX_PARALLEL_DOWNLOADS = 6
    private const val SUBTITLE_LOAD_TIMEOUT_MS = 20_000L

    private const val MIN_MATCHES = 8
    private const val MIN_TARGET_PARTICIPATION = 0.55
    private const val MIN_REFERENCE_PARTICIPATION_FALLBACK = 0.45
    private const val MIN_SPACING_AGREEMENT = 0.68
    private const val MAX_MEDIAN_RESIDUAL_MS = 600.0
    private const val MAX_SCALE_DEVIATION = 0.008
    private const val MIN_SCORE = 0.62
    private const val MIN_OFFSET_MARGIN = 0.025

    private const val STRONG_SCORE = 0.90
    private const val STRONG_TARGET_PARTICIPATION = 0.82
    private const val STRONG_MEDIAN_RESIDUAL_MS = 250.0
    private const val STRONG_SPACING_AGREEMENT = 0.85

    private const val MAX_PARSED_CACHE_ENTRIES = 64

    private val parsedCacheLock = Any()
    private val parsedCache = object : LinkedHashMap<CandidateCacheKey, List<SubtitleSyncCue>>(
        MAX_PARSED_CACHE_ENTRIES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<CandidateCacheKey, List<SubtitleSyncCue>>?,
        ): Boolean = size > MAX_PARSED_CACHE_ENTRIES
    }

    suspend fun findBestSubtitleRecommendation(
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
        selectedSubtitle: Subtitle,
        candidates: List<Subtitle>,
        subtitleBodyLoader: suspend (Subtitle) -> String,
        onReferenceReady: () -> Unit = {},
    ): AutoSyncSubtitleRecommendation? {
        if (!sourceUrl.startsWith("http://", ignoreCase = true) &&
            !sourceUrl.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }

        val selectedLanguage = selectedSubtitle.lang
        val sameLanguageCandidates = buildList<Subtitle> {
            candidates.forEach { candidate ->
                if (PlayerSubtitleUtils.matchesLanguageCode(candidate.lang, selectedLanguage)) {
                    add(candidate)
                }
            }
            if (none { it.url == selectedSubtitle.url }) {
                add(selectedSubtitle)
            }
        }.distinctBy { it.url }

        if (sameLanguageCandidates.isEmpty()) return null

        val startedMs = SystemClock.elapsedRealtime()
        val indexedTimeline = EmbeddedSubtitleTimelineLoader.load(
            sourceUrl = sourceUrl,
            sourceHeaders = sourceHeaders,
        ) ?: run {
            Log.d(TAG, "No indexed Matroska subtitle timeline available")
            return null
        }

        currentCoroutineContext().ensureActive()

        val usableReferences = rankReferenceTracks(
            tracks = indexedTimeline.tracks,
            preferredLanguage = selectedLanguage,
        )
        if (usableReferences.isEmpty()) {
            Log.d(TAG, "Indexed timeline had no usable full-dialogue subtitle tracks")
            return null
        }

        onReferenceReady()

        val parsedCandidates = supervisorScope {
            val semaphore = Semaphore(MAX_PARALLEL_DOWNLOADS)
            sameLanguageCandidates.map { subtitle ->
                async {
                    loadCandidate(
                        subtitle = subtitle,
                        semaphore = semaphore,
                        subtitleBodyLoader = subtitleBodyLoader,
                    )
                }
            }.mapNotNull { deferred ->
                try {
                    deferred.await()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Throwable) {
                    Log.d(TAG, "Subtitle candidate failed: ${error.message}")
                    null
                }
            }
        }

        if (parsedCandidates.isEmpty()) return null

        var best: CandidateMatch? = null
        for (candidate in parsedCandidates) {
            currentCoroutineContext().ensureActive()
            val result = bestMatchForCandidate(
                candidate = candidate,
                references = usableReferences,
                selectedSubtitleUrl = selectedSubtitle.url,
            ) ?: continue

            val currentBest = best
            best = when {
                currentBest == null -> result
                result.score > currentBest.score + 0.015 -> result
                currentBest.score > result.score + 0.015 -> currentBest
                result.isCurrentSubtitle && !currentBest.isCurrentSubtitle -> result
                else -> currentBest
            }
        }

        val winner = best ?: return null
        val correctionMs = winner.offsetMs
            .coerceIn(SUBTITLE_DELAY_MIN_MS.toLong(), SUBTITLE_DELAY_MAX_MS.toLong())
            .toInt()

        Log.i(
            TAG,
            "match=${winner.subtitle.id} score=${"%.4f".format(winner.score)} " +
                "matches=${winner.matches} targetPart=${"%.3f".format(winner.targetParticipation)} " +
                "residual=${"%.1f".format(winner.medianResidualMs)}ms correction=${correctionMs}ms " +
                "reference=${winner.referenceKey} total=${SystemClock.elapsedRealtime() - startedMs}ms",
        )

        return AutoSyncSubtitleRecommendation(
            subtitle = winner.subtitle,
            correctionMs = correctionMs,
            score = winner.score,
            matchedCues = winner.matches,
            referenceKey = winner.referenceKey,
            isCurrentSubtitle = winner.isCurrentSubtitle,
        )
    }

    private suspend fun loadCandidate(
        subtitle: Subtitle,
        semaphore: Semaphore,
        subtitleBodyLoader: suspend (Subtitle) -> String,
    ): ParsedCandidate? {
        val cacheKey = CandidateCacheKey(
            url = subtitle.url,
            headerHash = stableHeaderHash(subtitle.headers.orEmpty()),
        )
        synchronized(parsedCacheLock) {
            parsedCache[cacheKey]
        }?.let { cached ->
            return ParsedCandidate(subtitle, cached)
        }

        return semaphore.withPermit {
            val rawText = try {
                withTimeoutOrNull(SUBTITLE_LOAD_TIMEOUT_MS) {
                    subtitleBodyLoader(subtitle)
                } ?: return@withPermit null
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                return@withPermit null
            }

            val cues = try {
                withContext(Dispatchers.Default) {
                    PlayerSubtitleCueParser.parseFromText(
                        rawText = rawText,
                        sourceUrl = subtitle.url,
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                return@withPermit null
            }

            val normalized = cues
                .asSequence()
                .filter { it.startTimeMs >= 0L && it.endTimeMs > it.startTimeMs }
                .sortedBy { it.startTimeMs }
                .distinctBy { it.startTimeMs }
                .toList()

            if (normalized.size < MIN_CUES || timelineSpanMs(normalized) < MIN_SPAN_MS) {
                return@withPermit null
            }

            synchronized(parsedCacheLock) {
                parsedCache[cacheKey] = normalized
            }
            ParsedCandidate(subtitle, normalized)
        }
    }

    private suspend fun bestMatchForCandidate(
        candidate: ParsedCandidate,
        references: List<ReferenceTrack>,
        selectedSubtitleUrl: String,
    ): CandidateMatch? {
        var best: CandidateMatch? = null

        for (reference in references.take(MAX_REFERENCE_TRACKS)) {
            currentCoroutineContext().ensureActive()

            val alignment = alignTimelines(
                reference = reference.cues,
                target = candidate.cues,
            ) ?: continue

            val match = CandidateMatch(
                subtitle = candidate.subtitle,
                offsetMs = alignment.offsetMs,
                score = alignment.score,
                matches = alignment.matches,
                targetParticipation = alignment.targetParticipation,
                medianResidualMs = alignment.medianResidualMs,
                referenceKey = reference.key,
                isCurrentSubtitle = candidate.subtitle.url == selectedSubtitleUrl,
            )

            val currentBest = best
            if (
                currentBest == null ||
                match.score > currentBest.score ||
                (match.score == currentBest.score && match.medianResidualMs < currentBest.medianResidualMs)
            ) {
                best = match
            }

            if (
                alignment.score >= 0.97 &&
                alignment.targetParticipation >= 0.95 &&
                alignment.medianResidualMs <= 120.0
            ) {
                break
            }
        }

        return best
    }

    internal suspend fun alignTimelines(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
    ): TimelineAlignment? {
        if (
            reference.size < MIN_CUES ||
            target.size < MIN_CUES ||
            timelineSpanMs(reference) < MIN_SPAN_MS ||
            timelineSpanMs(target) < MIN_SPAN_MS
        ) {
            return null
        }

        val initialOffsets = candidateOffsets(reference, target)
        if (initialOffsets.isEmpty()) return null

        val evaluations = mutableListOf<AlignmentEvaluation>()
        for (initialOffset in initialOffsets) {
            currentCoroutineContext().ensureActive()

            val firstPass = buildMatches(
                reference = reference,
                target = target,
                offsetMs = initialOffset,
                toleranceMs = MATCH_TOLERANCE_MS,
            )
            if (firstPass.size < MIN_MATCHES) continue

            val refinedOffset = medianLong(
                firstPass.map { pair ->
                    reference[pair.referenceIndex].startTimeMs -
                        target[pair.targetIndex].startTimeMs
                },
            ).coerceIn(
                SUBTITLE_DELAY_MIN_MS.toLong(),
                SUBTITLE_DELAY_MAX_MS.toLong(),
            )

            val secondPass = buildMatches(
                reference = reference,
                target = target,
                offsetMs = refinedOffset,
                toleranceMs = MATCH_TOLERANCE_MS,
            )
            val evaluation = evaluateMatches(
                reference = reference,
                target = target,
                offsetMs = refinedOffset,
                pairs = secondPass,
            ) ?: continue
            evaluations += evaluation
        }

        if (evaluations.isEmpty()) return null

        val ordered = evaluations
            .distinctBy { it.offsetMs / 100L }
            .sortedWith(
                compareByDescending<AlignmentEvaluation> { it.score }
                    .thenByDescending { it.targetParticipation }
                    .thenBy { it.medianResidualMs },
            )

        val best = ordered.first()
        val alternative = ordered.firstOrNull {
            abs(it.offsetMs - best.offsetMs) >= 1_000L
        }
        val margin = if (alternative == null) 1.0 else best.score - alternative.score
        val strong = best.score >= STRONG_SCORE &&
            best.targetParticipation >= STRONG_TARGET_PARTICIPATION &&
            best.medianResidualMs <= STRONG_MEDIAN_RESIDUAL_MS &&
            best.spacingAgreement >= STRONG_SPACING_AGREEMENT

        if (!strong && margin < MIN_OFFSET_MARGIN) return null
        if (best.score < MIN_SCORE) return null

        return TimelineAlignment(
            offsetMs = best.offsetMs,
            score = best.score,
            matches = best.matches,
            targetParticipation = best.targetParticipation,
            referenceParticipation = best.referenceParticipation,
            medianResidualMs = best.medianResidualMs,
            spacingAgreement = best.spacingAgreement,
            scaleDeviation = best.scaleDeviation,
        )
    }

    private fun candidateOffsets(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
    ): List<Long> {
        val sampledReference = sampleEvenly(reference, MAX_OFFSET_SAMPLE_CUES)
        val sampledTarget = sampleEvenly(target, MAX_OFFSET_SAMPLE_CUES)
        val votes = HashMap<Long, Int>()

        for (targetCue in sampledTarget) {
            for (referenceCue in sampledReference) {
                val delta = referenceCue.startTimeMs - targetCue.startTimeMs
                if (delta < SUBTITLE_DELAY_MIN_MS || delta > SUBTITLE_DELAY_MAX_MS) continue
                val bucket = (delta / OFFSET_BUCKET_MS.toDouble()).roundToLong() * OFFSET_BUCKET_MS
                votes[bucket] = (votes[bucket] ?: 0) + 1
            }
        }

        return votes.entries
            .sortedWith(
                compareByDescending<Map.Entry<Long, Int>> { it.value }
                    .thenBy { abs(it.key) },
            )
            .take(MAX_OFFSET_CANDIDATES)
            .map { it.key }
    }

    private fun buildMatches(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
        offsetMs: Long,
        toleranceMs: Long,
    ): List<MatchedPair> {
        val referenceStarts = LongArray(reference.size) { index -> reference[index].startTimeMs }
        val pairs = ArrayList<MatchedPair>(min(reference.size, target.size))
        var minimumReferenceIndex = 0

        for (targetIndex in target.indices) {
            val shifted = target[targetIndex].startTimeMs + offsetMs
            val referenceIndex = nearestReferenceIndex(
                starts = referenceStarts,
                value = shifted,
                minimumIndex = minimumReferenceIndex,
            )
            if (referenceIndex < 0) continue

            val errorMs = referenceStarts[referenceIndex] - shifted
            if (abs(errorMs) > toleranceMs) continue

            pairs += MatchedPair(
                targetIndex = targetIndex,
                referenceIndex = referenceIndex,
                residualMs = abs(errorMs),
            )
            minimumReferenceIndex = referenceIndex + 1
            if (minimumReferenceIndex >= reference.size) break
        }

        return pairs
    }

    private fun evaluateMatches(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
        offsetMs: Long,
        pairs: List<MatchedPair>,
    ): AlignmentEvaluation? {
        if (pairs.size < MIN_MATCHES) return null

        val targetParticipation = pairs.size.toDouble() / target.size.toDouble()
        val referenceParticipation = pairs.size.toDouble() / reference.size.toDouble()
        val participationAccepted =
            targetParticipation >= MIN_TARGET_PARTICIPATION ||
                (
                    pairs.size >= 40 &&
                        targetParticipation >= 0.45 &&
                        referenceParticipation >= MIN_REFERENCE_PARTICIPATION_FALLBACK
                    )

        if (!participationAccepted) return null

        val residuals = pairs.map { it.residualMs.toDouble() }
        val medianResidual = medianDouble(residuals)
        if (medianResidual > MAX_MEDIAN_RESIDUAL_MS) return null

        val spacingAgreement = spacingAgreement(reference, target, pairs)
        if (spacingAgreement < MIN_SPACING_AGREEMENT) return null

        val scaleDeviation = estimateScaleDeviation(reference, target, pairs)
        if (scaleDeviation > MAX_SCALE_DEVIATION) return null

        val matchedSpanRatio = matchedSpanRatio(target, pairs)
        val consecutiveScore = longestConsecutiveRatio(pairs)

        val residualScore = (1.0 - (medianResidual / MATCH_TOLERANCE_MS.toDouble()))
            .coerceIn(0.0, 1.0)
        val referenceCoverageScore = (referenceParticipation * 1.5).coerceIn(0.0, 1.0)

        val score = (
            0.35 * targetParticipation.coerceIn(0.0, 1.0) +
                0.15 * referenceCoverageScore +
                0.20 * spacingAgreement +
                0.15 * residualScore +
                0.10 * matchedSpanRatio +
                0.05 * consecutiveScore
            ).coerceIn(0.0, 1.0)

        return AlignmentEvaluation(
            offsetMs = offsetMs,
            score = score,
            matches = pairs.size,
            targetParticipation = targetParticipation,
            referenceParticipation = referenceParticipation,
            medianResidualMs = medianResidual,
            spacingAgreement = spacingAgreement,
            scaleDeviation = scaleDeviation,
        )
    }

    private fun spacingAgreement(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
        pairs: List<MatchedPair>,
    ): Double {
        if (pairs.size < 3) return 0.0

        var checked = 0
        var agreed = 0

        for (index in 1 until pairs.size) {
            val previous = pairs[index - 1]
            val current = pairs[index]
            val targetGap = target[current.targetIndex].startTimeMs -
                target[previous.targetIndex].startTimeMs
            val referenceGap = reference[current.referenceIndex].startTimeMs -
                reference[previous.referenceIndex].startTimeMs

            if (targetGap < 500L || referenceGap < 500L) continue

            checked++
            val allowedError = max(
                1_500L,
                (targetGap * 0.10).roundToLong(),
            )
            if (abs(referenceGap - targetGap) <= allowedError) {
                agreed++
            }
        }

        return if (checked == 0) 0.0 else agreed.toDouble() / checked.toDouble()
    }

    private fun estimateScaleDeviation(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
        pairs: List<MatchedPair>,
    ): Double {
        if (pairs.size < 8) return 0.0

        val first = pairs.first()
        val last = pairs.last()
        val targetSpan = target[last.targetIndex].startTimeMs - target[first.targetIndex].startTimeMs
        val referenceSpan =
            reference[last.referenceIndex].startTimeMs - reference[first.referenceIndex].startTimeMs

        if (targetSpan < 20_000L || referenceSpan < 20_000L) return 0.0
        val scale = referenceSpan.toDouble() / targetSpan.toDouble()
        return abs(scale - 1.0)
    }

    private fun matchedSpanRatio(
        target: List<SubtitleSyncCue>,
        pairs: List<MatchedPair>,
    ): Double {
        if (pairs.size < 2) return 0.0
        val fullSpan = timelineSpanMs(target)
        if (fullSpan <= 0L) return 0.0
        val matchedSpan = target[pairs.last().targetIndex].startTimeMs -
            target[pairs.first().targetIndex].startTimeMs
        return (matchedSpan.toDouble() / fullSpan.toDouble()).coerceIn(0.0, 1.0)
    }

    private fun longestConsecutiveRatio(pairs: List<MatchedPair>): Double {
        if (pairs.isEmpty()) return 0.0

        var longest = 1
        var current = 1
        for (index in 1 until pairs.size) {
            val previous = pairs[index - 1]
            val next = pairs[index]
            val targetStep = next.targetIndex - previous.targetIndex
            val referenceStep = next.referenceIndex - previous.referenceIndex
            if (targetStep in 1..3 && referenceStep in 1..3) {
                current++
                longest = max(longest, current)
            } else {
                current = 1
            }
        }
        return (longest.toDouble() / pairs.size.toDouble()).coerceIn(0.0, 1.0)
    }

    private fun rankReferenceTracks(
        tracks: List<ReferenceTrack>,
        preferredLanguage: String,
    ): List<ReferenceTrack> {
        val usable = tracks.filter { track ->
            track.cues.size >= MIN_CUES && timelineSpanMs(track.cues) >= MIN_SPAN_MS
        }
        if (usable.isEmpty()) return emptyList()

        val fullDialogue = usable.filter(::isLikelyFullDialogueReference)
        val pool = if (fullDialogue.isNotEmpty()) fullDialogue else usable

        return pool.sortedWith(
            compareByDescending<ReferenceTrack> {
                referenceLanguageRank(it.language, preferredLanguage)
            }.thenByDescending {
                it.cues.size
            }.thenByDescending {
                timelineSpanMs(it.cues)
            },
        )
    }

    private fun isLikelyFullDialogueReference(track: ReferenceTrack): Boolean {
        if ((track.selectionFlags and C.SELECTION_FLAG_FORCED) != 0) return false
        if ((track.roleFlags and C.ROLE_FLAG_COMMENTARY) != 0) return false
        if ((track.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO) != 0) return false

        val spanMs = timelineSpanMs(track.cues)
        if (spanMs < MIN_SPAN_MS) return false
        val densityPerMinute = track.cues.size / (spanMs / 60_000.0).coerceAtLeast(0.5)
        return densityPerMinute >= 1.5
    }

    private fun referenceLanguageRank(language: String?, preferredLanguage: String): Int {
        if (!language.isNullOrBlank() &&
            PlayerSubtitleUtils.matchesLanguageCode(language, preferredLanguage)
        ) {
            return 4
        }

        val normalized = PlayerSubtitleUtils.normalizeLanguageCode(language.orEmpty())
        val preferred = PlayerSubtitleUtils.normalizeLanguageCode(preferredLanguage)
        if (
            normalized.isNotBlank() &&
            preferred.isNotBlank() &&
            normalized.substringBefore('-') == preferred.substringBefore('-')
        ) {
            return 3
        }
        if (normalized == "en" || normalized.startsWith("en-")) return 2
        return if (normalized.isNotBlank()) 1 else 0
    }

    private fun nearestReferenceIndex(
        starts: LongArray,
        value: Long,
        minimumIndex: Int,
    ): Int {
        if (minimumIndex >= starts.size) return -1

        var low = minimumIndex
        var high = starts.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (starts[mid] < value) {
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        var bestIndex = -1
        var bestDistance = Long.MAX_VALUE
        val candidates = intArrayOf(low - 1, low)
        for (index in candidates) {
            if (index < minimumIndex || index !in starts.indices) continue
            val distance = abs(starts[index] - value)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        return bestIndex
    }

    private fun <T> sampleEvenly(values: List<T>, maxCount: Int): List<T> {
        if (values.size <= maxCount) return values
        if (maxCount <= 1) return listOf(values.first())

        val last = values.lastIndex.toDouble()
        return List(maxCount) { index ->
            val position = (index * last / (maxCount - 1).toDouble()).roundToLong().toInt()
            values[position.coerceIn(0, values.lastIndex)]
        }
    }

    private fun timelineSpanMs(cues: List<SubtitleSyncCue>): Long =
        if (cues.size < 2) 0L else
            (cues.last().startTimeMs - cues.first().startTimeMs).coerceAtLeast(0L)

    private fun medianLong(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            ((sorted[middle - 1].toDouble() + sorted[middle].toDouble()) / 2.0).roundToLong()
        }
    }

    private fun medianDouble(values: List<Double>): Double {
        if (values.isEmpty()) return Double.POSITIVE_INFINITY
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }

    private fun stableHeaderHash(headers: Map<String, String>): Int {
        var hash = 1
        headers.entries
            .sortedBy { it.key.lowercase() }
            .forEach { (key, value) ->
                hash = 31 * hash + key.lowercase().hashCode()
                hash = 31 * hash + value.hashCode()
            }
        return hash
    }

    private data class CandidateCacheKey(
        val url: String,
        val headerHash: Int,
    )

    private data class ParsedCandidate(
        val subtitle: Subtitle,
        val cues: List<SubtitleSyncCue>,
    )

    private data class MatchedPair(
        val targetIndex: Int,
        val referenceIndex: Int,
        val residualMs: Long,
    )

    private data class AlignmentEvaluation(
        val offsetMs: Long,
        val score: Double,
        val matches: Int,
        val targetParticipation: Double,
        val referenceParticipation: Double,
        val medianResidualMs: Double,
        val spacingAgreement: Double,
        val scaleDeviation: Double,
    )

    private data class CandidateMatch(
        val subtitle: Subtitle,
        val offsetMs: Long,
        val score: Double,
        val matches: Int,
        val targetParticipation: Double,
        val medianResidualMs: Double,
        val referenceKey: String,
        val isCurrentSubtitle: Boolean,
    )
}

internal data class TimelineAlignment(
    val offsetMs: Long,
    val score: Double,
    val matches: Int,
    val targetParticipation: Double,
    val referenceParticipation: Double,
    val medianResidualMs: Double,
    val spacingAgreement: Double,
    val scaleDeviation: Double,
)

internal data class AutoSyncSubtitleRecommendation(
    val subtitle: Subtitle,
    val correctionMs: Int,
    val score: Double,
    val matchedCues: Int,
    val referenceKey: String,
    val isCurrentSubtitle: Boolean,
)

internal data class ReferenceTrack(
    val key: String,
    val language: String?,
    val cues: List<SubtitleSyncCue>,
    val label: String? = null,
    val selectionFlags: Int = 0,
    val roleFlags: Int = 0,
)

internal data class IndexedEmbeddedTimeline(
    val tracks: List<ReferenceTrack>,
    val source: String,
    val bytesDownloaded: Long,
    val rangeRequests: Int,
    val loadMs: Long,
)
