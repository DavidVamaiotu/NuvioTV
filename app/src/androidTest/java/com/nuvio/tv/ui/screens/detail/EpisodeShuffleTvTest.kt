package com.nuvio.tv.ui.screens.detail

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.ContinueWatchingCardStyle
import com.nuvio.tv.domain.model.EpisodeShuffleSettings
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.ui.components.ContinueWatchingCard
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.screens.home.NextUpInfo
import com.nuvio.tv.ui.theme.NuvioTheme
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpisodeShuffleTvTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val videos = (1..4).map { Video("fixture:1:$it", "Episode $it", "2020-01-01", null,
        season = 1, episode = it, overview = "An episode used to check shuffle playback on a TV remote.") }
    private val meta = Meta(
        id = "fixture", type = ContentType.SERIES, name = "Shuffle test series", poster = null,
        posterShape = PosterShape.POSTER, background = null, logo = null, description = "TV playback controls",
        releaseInfo = "2020", imdbRating = null, genres = emptyList(), runtime = null,
        director = emptyList(), cast = emptyList(), videos = videos, country = null,
        awards = null, language = null, links = emptyList()
    )

    @Test
    fun unwatchedChoiceStartsShuffleWithoutAnotherScreen() {
        var settings by mutableStateOf(EpisodeShuffleSettings(false, true))
        var played: Video? = null
        setContent {
            EpisodeShuffleDialog(meta, settings, { settings = it; true }, setOf(1 to 1, 1 to 3), emptyMap(),
                {}, { played = it })
        }
        compose.onNodeWithText("All episodes").assertIsFocused().assertIsDisplayed()
        capture("shuffle-choices")
        val down = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 2))
        instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        compose.runOnIdle { assertNull(played); assertFalse(settings.enabled) }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
        compose.onNodeWithText("Unwatched episodes").assertIsFocused()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle {
            assertEquals(EpisodeShuffleSettings(true, false), settings)
            assertTrue(played?.episode in listOf(2, 4))
        }
    }

    @Test
    fun caughtUpShowsOfferAllEpisodesAndStartImmediately() {
        var settings by mutableStateOf(EpisodeShuffleSettings())
        var played: Video? = null
        val onlyEpisode = videos.first()
        setContent {
            EpisodeShuffleDialog(meta.copy(videos = listOf(onlyEpisode)), settings, { settings = it; true },
                setOf(1 to 1), emptyMap(), {}, { played = it })
        }
        compose.onNodeWithText("Unwatched episodes").assertIsNotEnabled()
        compose.onNodeWithText("All episodes").assertIsFocused().assertIsDisplayed()
        capture("shuffle-caught-up")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle {
            assertEquals(onlyEpisode, played)
            assertEquals(EpisodeShuffleSettings(true, true), settings)
        }
    }

    @Test
    fun playbackWaitsForSavingAndIgnoresRepeatedInput() {
        var settings by mutableStateOf(EpisodeShuffleSettings())
        val saved = CompletableDeferred<Unit>()
        var saves = 0
        var dismissals = 0
        var played: Video? = null
        setContent {
            EpisodeShuffleDialog(meta, settings, {
                saves++
                saved.await()
                settings = it
                true
            }, emptySet(), emptyMap(), { dismissals++ }, { played = it })
        }
        compose.onNodeWithText("Unwatched episodes").assertIsFocused()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("Starting shuffle…").assertIsDisplayed()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.runOnIdle {
            assertEquals(1, saves)
            assertEquals(0, dismissals)
            assertNull(played)
            assertFalse(settings.enabled)
            saved.complete(Unit)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(EpisodeShuffleSettings(true, false), settings); assertTrue(played != null) }
    }

    @Test
    fun failedSaveAllowsRetryWithoutStartingPlayback() {
        var settings by mutableStateOf(EpisodeShuffleSettings(false, true))
        var allowSave = false
        var played: Video? = null
        setContent {
            EpisodeShuffleDialog(meta, settings, {
                if (allowSave) settings = it
                allowSave
            }, emptySet(), emptyMap(), {}, { played = it })
        }
        compose.onNodeWithText("All episodes").assertIsFocused()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle { assertNull(played); assertFalse(settings.enabled); allowSave = true }
        compose.onNodeWithText("All episodes").assertIsFocused()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle { assertEquals(EpisodeShuffleSettings(true, true), settings); assertTrue(played != null) }
    }

    @Test
    fun backLeavesSavedSettingsAlone() {
        val settings = EpisodeShuffleSettings(false, true)
        var saves = 0
        var dismissals = 0
        setContent {
            EpisodeShuffleDialog(meta, settings, { saves++; true }, emptySet(), emptyMap(), { dismissals++ }, {})
        }
        compose.onNodeWithText("All episodes").assertIsFocused()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.runOnIdle { assertEquals(1, dismissals); assertEquals(0, saves) }
    }

    @Test
    fun emptyEpisodeListsHaveAnExit() {
        var dismissals = 0
        setContent {
            EpisodeShuffleDialog(meta.copy(videos = emptyList()), EpisodeShuffleSettings(), { true },
                emptySet(), emptyMap(), { dismissals++ }, {})
        }
        compose.onNodeWithText("No episodes are available to shuffle.").assertIsDisplayed()
        compose.onNodeWithText("Close").assertIsFocused()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test
    fun theSameButtonStopsShuffleAndThenOffersShuffleAgain() {
        var settings by mutableStateOf(EpisodeShuffleSettings(true, false))
        var stops = 0
        var opens = 0
        setContent {
            HeroContentSection(meta, null, null, onPlayClick = {}, isInLibrary = false,
                onToggleLibrary = {}, onLibraryLongPress = {}, isMovieWatched = false,
                isMovieWatchedPending = false, onToggleMovieWatched = {}, showRandomEpisodeButton = true,
                episodeShuffle = settings, onRandomEpisodeClick = {
                    if (settings.enabled) { settings = settings.copy(enabled = false); stops++ } else opens++
                })
        }
        compose.onNodeWithText("Stop shuffle").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        capture("shuffle-detail-controls")
        val down = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 1))
        instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        compose.onNodeWithText("Shuffle").assertIsFocused().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, stops); assertEquals(0, opens) }
        capture("shuffle-controls-off")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MENU)
        compose.runOnIdle { assertEquals(1, stops); assertEquals(0, opens) }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test
    fun shuffleBadgeAppearsInEveryCardStyleAndClickKeepsTheEpisode() {
        var played: String? = null
        val item = ContinueWatchingItem.NextUp(NextUpInfo(
            "fixture", "series", "Shuffle test series", null, null, null, "fixture:1:3", 1, 3,
            "Episode 3", thumbnail = null, lastWatched = 100, sortTimestamp = 100
        ), shufflePlayback = true)
        setContent {
            Row(Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                for (style in ContinueWatchingCardStyle.entries) {
                    ContinueWatchingCard(item, { played = item.info.videoId }, {},
                        modifier = Modifier.testTag(style.name), cardWidth = 240.dp,
                        imageHeight = 135.dp, cardStyle = style)
                }
            }
        }
        compose.onAllNodesWithContentDescription("Episode shuffle enabled", useUnmergedTree = true)
            .assertCountEquals(ContinueWatchingCardStyle.entries.size)
        compose.onNodeWithTag(ContinueWatchingCardStyle.WIDE.name)
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle { assertEquals(item.info.videoId, played) }
        capture("shuffle-home-cards")
    }

    private fun setContent(content: @Composable () -> Unit) {
        compose.setContent {
            NuvioTheme {
                Box(Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) { content() }
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val directory = instrumentation.targetContext.getExternalFilesDir(null)!!
        File(directory, "$name.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
