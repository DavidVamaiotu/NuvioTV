package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.ui.screens.player.autosync.AutomaticSubtitleSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Thin bridge between the isolated AutoSync matcher and NuvioTV's existing player/subtitle code.
 */
private const val AUTOMATIC_SUBTITLE_SYNC_ENABLED = true

internal fun PlayerRuntimeController.maybeRunAutomaticSubtitleSync(
    selectedSubtitle: Subtitle,
) {
    if (!AUTOMATIC_SUBTITLE_SYNC_ENABLED) return
    if (selectedSubtitle.lang.isBlank()) return
    if (!currentStreamUrl.startsWith("http://", ignoreCase = true) &&
        !currentStreamUrl.startsWith("https://", ignoreCase = true)
    ) {
        return
    }

    automaticSubtitleSyncJob?.cancel()

    val sourceUrlAtStart = currentStreamUrl
    val sourceHeadersAtStart = currentHeaders.toMap()
    val selectedKey = addonSubtitleKey(selectedSubtitle)
    val candidatesAtStart = (_uiState.value.addonSubtitles + selectedSubtitle)
        .distinctBy(::addonSubtitleKey)

    automaticSubtitleSyncJob = scope.launch {
        try {
            Log.d(
                PlayerRuntimeController.TAG,
                "AUTO_SYNC_TV start lang=${selectedSubtitle.lang} candidates=${candidatesAtStart.size}",
            )

            val recommendation = AutomaticSubtitleSync.findBestSubtitleRecommendation(
                sourceUrl = sourceUrlAtStart,
                sourceHeaders = sourceHeadersAtStart,
                selectedSubtitle = selectedSubtitle,
                candidates = candidatesAtStart,
                subtitleBodyLoader = { subtitle ->
                    downloadSubtitleBody(
                        url = subtitle.url,
                        languageHint = subtitle.lang,
                        headers = subtitle.headers,
                    )
                },
            ) ?: run {
                Log.d(PlayerRuntimeController.TAG, "AUTO_SYNC_TV no reliable match")
                return@launch
            }

            if (currentStreamUrl != sourceUrlAtStart) return@launch

            val activeAddon = _uiState.value.selectedAddonSubtitle
            if (
                activeAddon != null &&
                addonSubtitleKey(activeAddon) != selectedKey &&
                addonSubtitleKey(activeAddon) != addonSubtitleKey(recommendation.subtitle)
            ) {
                // The user or another selection path changed subtitle while matching was running.
                Log.d(PlayerRuntimeController.TAG, "AUTO_SYNC_TV discarded: subtitle changed")
                return@launch
            }

            if (!recommendation.isCurrentSubtitle) {
                selectAddonSubtitle(recommendation.subtitle)
            }

            val correctionMs = (
                recommendation.correctionMs / SUBTITLE_DELAY_STEP_MS.toDouble()
                ).roundToInt() * SUBTITLE_DELAY_STEP_MS

            setSubtitleDelayMs(
                targetMs = correctionMs.coerceIn(
                    SUBTITLE_DELAY_MIN_MS,
                    SUBTITLE_DELAY_MAX_MS,
                ),
                showOverlay = false,
            )

            Log.i(
                PlayerRuntimeController.TAG,
                "AUTO_SYNC_TV applied addon=${recommendation.subtitle.id} " +
                    "correction=${correctionMs}ms score=${"%.4f".format(recommendation.score)} " +
                    "matches=${recommendation.matchedCues} reference=${recommendation.referenceKey}",
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            Log.w(PlayerRuntimeController.TAG, "AUTO_SYNC_TV failed", error)
        }
    }
}
