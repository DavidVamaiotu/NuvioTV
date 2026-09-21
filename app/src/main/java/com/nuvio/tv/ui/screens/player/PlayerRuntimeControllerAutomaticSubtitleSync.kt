package com.nuvio.tv.ui.screens.player

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.media3.common.C
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncCandidateScope
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncDebugLog
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncPreferences
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncSubtitleCandidate
import com.nuvio.tv.ui.screens.player.autosync.AutomaticSubtitleSync
import com.nuvio.tv.ui.screens.player.autosync.applyAutoSyncSidecarTimeline
import com.nuvio.tv.ui.screens.player.autosync.replaceAutoSyncSidecarSubtitle
import kotlinx.coroutines.CancellationException
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
    if (!canAttachAddonSubtitleViaSidecar(selectedSubtitle)) return

    automaticSubtitleSyncJob?.cancel()

    val sourceUrlAtStart = currentStreamUrl
    val sourceHeadersAtStart = currentHeaders.toMap()
    val selectedUrl = selectedSubtitle.url
    val candidatesAtStart = (_uiState.value.addonSubtitles + selectedSubtitle)
        .distinctBy { it.url }
    val candidateByUrl = candidatesAtStart.associateBy { it.url }

    val started = startSidecarAddonSubtitle(
        subtitle = selectedSubtitle,
        rawBodyLoader = {
            AutomaticSubtitleSync.downloadSubtitleBody(
                url = selectedUrl,
                headers = selectedSubtitle.headers.orEmpty(),
            )
        },
    )
    if (!started) return

    val selectedBodyDeferred = sidecarRawBodyDeferredFor(selectedUrl)
    player.trackSelectionParameters = player.trackSelectionParameters
        .buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        .build()

    automaticSubtitleSyncJob = scope.launch {
        try {
            Log.d(
                PlayerRuntimeController.TAG,
                "AUTO_SYNC_V2 start scope=${candidateScope.name} " +
                    "lang=${selectedSubtitle.lang} candidates=${candidatesAtStart.size}",
            )
            showAutoSyncToast("Auto Sync V2: checking embedded subtitles…")

            var noSubtitleTracks = false
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
                onReferenceReady = {
                    showAutoSyncToast("Auto Sync V2: comparing subtitles…")
                },
                onNoSubtitleTracks = { noSubtitleTracks = true },
            )

            if (resolved == null) {
                if (activeSidecarSubtitleKey == null) {
                    startSidecarAddonSubtitle(selectedSubtitle)
                }
                AutoSyncDebugLog.finishAndCopy(
                    context,
                    "REJECT V2 - original subtitle timing kept",
                )
                showAutoSyncToast(
                    if (noSubtitleTracks) "No subtitles in tracks"
                    else "Auto Sync V2: no reliable match",
                    Toast.LENGTH_LONG,
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

            val applied = when {
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
                AutoSyncDebugLog.finishAndCopy(
                    context,
                    "REJECT V2 - sidecar changed or apply failed",
                )
                showAutoSyncToast("Auto Sync V2: could not apply sync", Toast.LENGTH_LONG)
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
            AutoSyncDebugLog.info {
                "AUTO APPLY V2 sidecar=true bufferPreserved=true " +
                    "externalChanged=${chosenSubtitle.url != selectedUrl} " +
                    "groups=${timeline.groups.size} alignment=${timeline.alignmentSource} " +
                    "targetCoverage=${"%.4f".format(timeline.targetCoverage)} " +
                    "referenceCoverage=${"%.4f".format(timeline.referenceCoverage)}"
            }
            AutoSyncDebugLog.finishAndCopy(
                context,
                "APPLIED V2 sidecar timeline url=${chosenSubtitle.url}",
            )
            showAutoSyncToast(
                buildAutoSyncSuccessToast(
                    replacedSubtitle = chosenSubtitle.url != selectedUrl,
                    scale = timeline.alignmentScale,
                    interceptMs = timeline.alignmentInterceptMs,
                ),
                Toast.LENGTH_LONG,
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            Log.w(PlayerRuntimeController.TAG, "AUTO_SYNC_V2 failed", error)
            AutoSyncDebugLog.error(error) { "TV bridge failed" }
            AutoSyncDebugLog.finishAndCopy(context, "failed")
            if (activeSidecarSubtitleKey == null) {
                startSidecarAddonSubtitle(selectedSubtitle)
            }
            showAutoSyncToast("Auto Sync V2: failed", Toast.LENGTH_LONG)
        }
    }
}

private fun buildAutoSyncSuccessToast(
    replacedSubtitle: Boolean,
    scale: Double,
    interceptMs: Double,
): String {
    val prefix = if (replacedSubtitle) {
        "Auto Sync V2: subtitle replaced"
    } else {
        "Auto Sync V2 succeeded"
    }
    return when {
        abs(scale - 1.0) >= 0.0005 -> "$prefix • drift corrected"
        abs(interceptMs) >= 50.0 -> "$prefix • ${formatAutoSyncOffset(interceptMs)}"
        else -> "$prefix • already in sync"
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
