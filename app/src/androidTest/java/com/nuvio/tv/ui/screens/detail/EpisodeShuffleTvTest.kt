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
import androidx.compose.ui.test.onNodeWithContentDescription
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
import org.junit.Assert.assertEquals
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
    fun savedFilterReceivesFocusAndOpeningKeyReleaseCannotChooseIt() {
        var settings by mutableStateOf(EpisodeShuffleSettings(true, true))
        setContent {
            RandomEpisodeOverlay(meta, settings, { settings = it }, emptySet(), emptyMap(),
                false, true, {}, {}, {}, {})
        }
        compose.onNodeWithText("Include watched").assertIsFocused()
        val down = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 2))
        instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        compose.onNodeWithText("What are you in the mood for?").assertIsDisplayed()
        compose.onNodeWithText("Include watched").assertIsFocused()
        capture("shuffle-choices")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("Your random pick").assertIsDisplayed()
        capture("shuffle-preview")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("Include watched").assertIsFocused()
        assertTrue(settings.enabled && settings.includeWatched)
    }

    @Test
    fun exhaustedUnwatchedPoolOffersAllAndPlaysTheDisplayedEpisode() {
        var settings by mutableStateOf(EpisodeShuffleSettings(true, false))
        var played: Video? = null
        val onlyEpisode = videos.first()
        setContent {
            RandomEpisodeOverlay(meta.copy(videos = listOf(onlyEpisode)), settings, { settings = it },
                setOf(1 to 1), emptyMap(), true, true, {}, { played = it }, {}, {})
        }
        compose.onNodeWithText("Unwatched only").assertIsNotEnabled()
        compose.onNodeWithText("Include watched").assertIsFocused()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("Your random pick").assertIsDisplayed()
        compose.onNodeWithText("Play").assertIsFocused()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle { assertEquals(onlyEpisode, played); assertTrue(settings.includeWatched) }
    }

    @Test
    fun heldSelectAndMenuToggleOnceAndLeaveShortPressAvailable() {
        var settings by mutableStateOf(EpisodeShuffleSettings())
        var toggles = 0
        var opens = 0
        setContent {
            HeroContentSection(meta, null, null, onPlayClick = {}, isInLibrary = false,
                onToggleLibrary = {}, onLibraryLongPress = {}, isMovieWatched = false,
                isMovieWatchedPending = false, onToggleMovieWatched = {}, showRandomEpisodeButton = true,
                episodeShuffle = settings, onRandomEpisodeClick = { opens++ },
                onToggleEpisodeShuffle = { settings = settings.copy(enabled = !settings.enabled); toggles++ })
        }
        val button = compose.onNodeWithContentDescription("Random episode")
        button.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        val keys = listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MENU)
        keys.forEachIndexed { index, key ->
            val down = SystemClock.uptimeMillis()
            instrumentation.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, key, 0))
            instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, key, 1))
            instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, key, 2))
            instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, key, 0))
            compose.runOnIdle { assertEquals(index + 1, toggles); assertEquals(index, opens) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.runOnIdle { assertEquals(index + 1, opens) }
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MENU)
        compose.runOnIdle { assertEquals(5, toggles); assertEquals(4, opens) }
        compose.onNodeWithText("Shuffle · Unwatched only · Hold OK to turn off").assertIsDisplayed()
        capture("shuffle-detail-controls")
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
