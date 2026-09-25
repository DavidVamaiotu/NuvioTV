package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.extractor.ExtractorsFactory
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncAnalysisOutcome
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncCandidateScope
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncExtractorsFactory
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncMatchAssessment
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncMatchStrength
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncPreferences
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncSubtitleCandidate
import com.nuvio.tv.ui.screens.player.autosync.AutomaticSubtitleSync
import com.nuvio.tv.ui.screens.player.autosync.EmbeddedSubtitleTimelineLoader
import com.nuvio.tv.ui.screens.player.autosync.applyAutoSyncSidecarTimeline
import com.nuvio.tv.ui.screens.player.autosync.maxAlignmentShiftMs
import com.nuvio.tv.ui.screens.player.autosync.replaceAutoSyncSidecarSubtitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import kotlin.math.abs
import kotlin.math.roundToInt

/** Thin TV adapter around the feature-owned Mobile AutoSync V2 pipeline. */
private val autoSyncToastHandler = Handler(Looper.getMainLooper())

private fun PlayerRuntimeController.showAutoSyncToast(
    message: String,
    duration: Int = Toast.LENGTH_SHORT,
) {
    autoSyncToastHandler.post {
        Toast.makeText(context, message, duration).show()
    }
}
/**
 * Wraps Nuvio's extractors so AutoSync can observe embedded subtitle timing (output is forwarded
 * unchanged), and starts AutoSync's embedded subtitle index download while the stream opens.
 */
internal fun PlayerRuntimeController.autoSyncExtractorsFactory(
    delegate: ExtractorsFactory,
    url: String,
    headers: Map<String, String>,
): ExtractorsFactory {
    val factory = AutoSyncExtractorsFactory(delegate = delegate, sourceKey = url)
    prefetchAutoSyncIndex(url, headers)
    return factory
}

/**
 * Starts the embedded subtitle index download while the stream opens, so a later AutoSync run
 * finds it cached or joins the in-flight load instead of starting when a subtitle is selected.
 */
private fun PlayerRuntimeController.prefetchAutoSyncIndex(
    url: String,
    headers: Map<String, String>,
) {
    AutoSyncPreferences.ensureLoaded(context)
    if (!AutoSyncPreferences.isEnabled(context)) return
    EmbeddedSubtitleTimelineLoader.prefetch(scope, url, headers)
}

/** The user picked [subtitle]: check only that subtitle, never swap in another one. */
internal fun PlayerRuntimeController.runSelectedAutomaticSubtitleSync(subtitle: Subtitle) =
    maybeRunAutomaticSubtitleSync(subtitle, AutoSyncCandidateScope.SELECTED_ONLY)

internal fun PlayerRuntimeController.cancelAutomaticSubtitleSync() {
    automaticSubtitleSyncJob?.cancel()
    automaticSubtitleSyncJob = null
}

internal fun PlayerRuntimeController.maybeRunAutomaticSubtitleSync(
    selectedSubtitle: Subtitle,
    candidateScope: AutoSyncCandidateScope = AutoSyncCandidateScope.STARTUP_SEARCH,
) {
    AutoSyncPreferences.ensureLoaded(context)
    if (!AutoSyncPreferences.isEnabled(context)) return
    if (selectedSubtitle.lang.isBlank()) return
    if (!currentStreamUrl.startsWith("http://", ignoreCase = true) &&
        !currentStreamUrl.startsWith("https://", ignoreCase = true)
    ) {
        return
    }
    if (
        candidateScope == AutoSyncCandidateScope.STARTUP_SEARCH &&
        !AutoSyncPreferences.claimStartupRun(hashCode(), currentStreamUrl)
    ) {
        return
    }

    val player = _exoPlayer ?: return
    val useLibass = requestedUseLibassByUser || activePlayerUsesLibass

    showAutoSyncToast(context.getString(R.string.autosync_toast_analyzing))

    if (!canAttachAddonSubtitleViaSidecar(selectedSubtitle)) {
        showAutoSyncToast(context.getString(R.string.autosync_toast_unsupported_renderer))
        return
    }

    automaticSubtitleSyncJob?.cancel()

    val sourceUrlAtStart = currentStreamUrl
    val sourceHeadersAtStart = currentHeaders.toMap()
    val selectedUrl = selectedSubtitle.url
    val candidatesAtStart = (_uiState.value.addonSubtitles + selectedSubtitle)
        .distinctBy { it.url }
    val candidateByUrl = candidatesAtStart.associateBy { it.url }

    // One download feeds both the sidecar renderer and the analysis. It completes with null
    // on failure or cancellation so neither side can wait on it forever.
    val selectedBodyDeferred = CompletableDeferred<String?>()
    val started = startSidecarAddonSubtitle(
        subtitle = selectedSubtitle,
        rawBodyLoader = {
            selectedBodyDeferred.await()
                ?: throw IllegalStateException("Subtitle body unavailable")
        },
    )
    if (!started) {
        showAutoSyncToast(context.getString(R.string.autosync_toast_load_failed))
        return
    }

    player.trackSelectionParameters = player.trackSelectionParameters
        .buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        .build()

    automaticSubtitleSyncJob = scope.launch {
        launch {
            val body = try {
                AutomaticSubtitleSync.downloadSubtitleBody(
                    url = selectedUrl,
                    headers = selectedSubtitle.headers.orEmpty(),
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Log.w(PlayerRuntimeController.TAG, "AUTO_SYNC_V2 subtitle download failed", error)
                null
            }
            selectedBodyDeferred.complete(body)
        }
        try {
            Log.d(
                PlayerRuntimeController.TAG,
                "AUTO_SYNC_V2 start scope=${candidateScope.name} " +
                    "lang=${selectedSubtitle.lang} candidates=${candidatesAtStart.size}",
            )
            var analysisOutcome: AutoSyncAnalysisOutcome? = null
            var rejectedAssessment: AutoSyncMatchAssessment? = null
            val resolved = AutomaticSubtitleSync.findTimelineRetime(
                sourceKey = sourceUrlAtStart,
                sourceHeaders = sourceHeadersAtStart,
                selectedSubtitleUrl = selectedUrl,
                selectedSubtitleHeaders = selectedSubtitle.headers.orEmpty(),
                selectedSubtitleBodyDeferred = selectedBodyDeferred,
                preferredLanguage = selectedSubtitle.lang,
                alternativeSubtitles = if (candidateScope == AutoSyncCandidateScope.STARTUP_SEARCH) {
                    candidatesAtStart.map { subtitle ->
                        AutoSyncSubtitleCandidate(
                            url = subtitle.url,
                            language = subtitle.lang,
                            name = subtitle.addonName.ifBlank { subtitle.id },
                        )
                    }
                } else {
                    emptyList()
                },
                alternativeSubtitlesProvider = if (
                    candidateScope == AutoSyncCandidateScope.STARTUP_SEARCH
                ) {
                    {
                        _uiState.value.addonSubtitles.map { subtitle ->
                            AutoSyncSubtitleCandidate(
                                url = subtitle.url,
                                language = subtitle.lang,
                                name = subtitle.addonName.ifBlank { subtitle.id },
                            )
                        }
                    }
                } else {
                    null
                },
                onReferenceReady = {},
                onAnalysisOutcome = { outcome -> analysisOutcome = outcome },
                onMatchAssessment = { assessment -> rejectedAssessment = assessment },
            )

            if (resolved == null) {
                if (activeSidecarSubtitleKey == null) {
                    startSidecarAddonSubtitle(selectedSubtitle)
                }
                showAutoSyncToast(
                    context.buildAutoSyncFailureToast(
                        analysisOutcome = analysisOutcome,
                        assessment = rejectedAssessment,
                    ),
                )
                return@launch
            }

            if (currentStreamUrl != sourceUrlAtStart) return@launch
            val activeSubtitleUrl = _uiState.value.selectedAddonSubtitle?.url
            if (activeSubtitleUrl != selectedUrl && activeSubtitleUrl != resolved.subtitleUrl) {
                return@launch
            }

            val chosenSubtitle = candidateByUrl[resolved.subtitleUrl]
                ?: _uiState.value.addonSubtitles.firstOrNull { it.url == resolved.subtitleUrl }
                ?: selectedSubtitle.takeIf { it.url == resolved.subtitleUrl }
                ?: return@launch

            // A confident match whose whole-film correction is within the user's tolerance keeps
            // the selected subtitle's original timing instead of retiming it.
            val toleranceMs = AutoSyncPreferences.syncToleranceMs.value
            val withinToleranceMs = toleranceMs.takeIf {
                it > 0 &&
                    resolved.subtitleUrl == selectedUrl &&
                    resolved.timeline.maxAlignmentShiftMs() <= it
            }
            val applied = when {
                withinToleranceMs != null -> activeSidecarSubtitleKey == selectedUrl
                resolved.subtitleUrl == selectedUrl -> {
                    applyAutoSyncSidecarTimeline(
                        sidecar = this@maybeRunAutomaticSubtitleSync,
                        url = selectedUrl,
                        timeline = resolved.timeline,
                    )
                }
                activeSidecarSubtitleKey == null &&
                    startSidecarAddonSubtitle(
                        subtitle = chosenSubtitle,
                        rawBodyLoader = resolved.subtitleBody?.let { body ->
                            suspend { body }
                        },
                    ) -> {
                    applyAutoSyncSidecarTimeline(
                        sidecar = this@maybeRunAutomaticSubtitleSync,
                        url = resolved.subtitleUrl,
                        timeline = resolved.timeline,
                    )
                }
                else -> {
                    replaceAutoSyncSidecarSubtitle(
                        sidecar = this@maybeRunAutomaticSubtitleSync,
                        expectedCurrentUrl = selectedUrl,
                        url = resolved.subtitleUrl,
                        headers = resolved.subtitleHeaders,
                        rawBody = resolved.subtitleBody,
                        useLibass = useLibass,
                        timeline = resolved.timeline,
                    )
                }
            }

            if (!applied) {
                if (activeSidecarSubtitleKey == null) {
                    startSidecarAddonSubtitle(selectedSubtitle)
                }
                showAutoSyncToast(context.getString(R.string.autosync_toast_apply_failed))
                return@launch
            }

            if (chosenSubtitle.url != selectedUrl) {
                _uiState.update {
                    it.copy(
                        selectedAddonSubtitle = chosenSubtitle,
                        selectedSubtitleTrackIndex = -1,
                    )
                }
                rememberAddonSubtitleSelection(chosenSubtitle)
            }
            setSubtitleDelayMs(targetMs = 0, showOverlay = false)

            val timeline = resolved.timeline
            showAutoSyncToast(
                context.buildAutoSyncSuccessToast(
                    replacedSubtitle = chosenSubtitle.url != selectedUrl,
                    scale = timeline.alignmentScale,
                    interceptMs = timeline.alignmentInterceptMs,
                    assessment = resolved.assessment,
                    localizedMismatchIgnored = timeline.localizedMismatchIgnored,
                    withinToleranceMs = withinToleranceMs,
                ),
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            Log.w(PlayerRuntimeController.TAG, "AUTO_SYNC_V2 failed", error)
            if (activeSidecarSubtitleKey == null) {
                startSidecarAddonSubtitle(selectedSubtitle)
            }
            showAutoSyncToast(context.getString(R.string.autosync_toast_sync_failed))
        }
    }.also { job ->
        job.invokeOnCompletion { selectedBodyDeferred.complete(null) }
    }
}

private fun Context.autoSyncToastPrefix(assessment: AutoSyncMatchAssessment): String =
    getString(
        when (assessment.strength) {
            AutoSyncMatchStrength.EXCELLENT -> R.string.autosync_toast_match_excellent
            AutoSyncMatchStrength.STRONG -> R.string.autosync_toast_match_strong
            AutoSyncMatchStrength.POSSIBLE -> R.string.autosync_toast_match_possible
            AutoSyncMatchStrength.WEAK -> R.string.autosync_toast_match_weak
        },
        assessment.confidencePercent,
    )

private fun Context.buildAutoSyncSuccessToast(
    replacedSubtitle: Boolean,
    scale: Double,
    interceptMs: Double,
    assessment: AutoSyncMatchAssessment,
    localizedMismatchIgnored: Boolean,
    withinToleranceMs: Int?,
): String {
    val result = when {
        withinToleranceMs != null ->
            getString(R.string.autosync_toast_result_within_tolerance, withinToleranceMs)
        localizedMismatchIgnored -> getString(R.string.autosync_toast_result_localized_ignored)
        replacedSubtitle -> getString(R.string.autosync_toast_result_replaced)
        abs(scale - 1.0) >= 0.0005 -> getString(R.string.autosync_toast_result_drift_corrected)
        abs(interceptMs) >= 250.0 -> formatAutoSyncOffset(interceptMs)
        else -> getString(R.string.autosync_toast_result_in_sync)
    }
    return "${autoSyncToastPrefix(assessment)} • $result"
}

private fun Context.buildAutoSyncFailureToast(
    analysisOutcome: AutoSyncAnalysisOutcome?,
    assessment: AutoSyncMatchAssessment?,
): String =
    when (analysisOutcome) {
        AutoSyncAnalysisOutcome.SUBTITLE_UNAVAILABLE ->
            getString(R.string.autosync_toast_analyze_failed)
        AutoSyncAnalysisOutcome.NO_SUBTITLE_TRACKS ->
            getString(R.string.autosync_toast_no_embedded_subtitles)
        AutoSyncAnalysisOutcome.NO_USABLE_REFERENCE ->
            getString(R.string.autosync_toast_no_reference)
        null -> {
            val resolvedAssessment =
                assessment ?: AutoSyncMatchAssessment(
                    confidencePercent = 0,
                    strength = AutoSyncMatchStrength.WEAK,
                )
            "${autoSyncToastPrefix(resolvedAssessment)} • ${getString(R.string.autosync_toast_result_kept)}"
        }
    }

private fun formatAutoSyncOffset(offsetMs: Double): String {
    val roundedMs = offsetMs.roundToInt()
    if (abs(roundedMs) < 1_000) {
        return "${if (roundedMs > 0) "+" else ""}$roundedMs ms"
    }
    val tenths = (roundedMs / 100.0).roundToInt()
    return "${if (tenths > 0) "+" else ""}${tenths / 10}.${abs(tenths % 10)} s"
}
