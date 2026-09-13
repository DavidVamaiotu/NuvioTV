package com.nuvio.tv.ui.screens.detail

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.EpisodeShuffleSettings
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.RandomEpisodePicker
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun EpisodeShuffleDialog(
    meta: Meta,
    shuffleSettings: EpisodeShuffleSettings,
    onSaveSettings: suspend (EpisodeShuffleSettings) -> Boolean,
    watchedEpisodes: Set<Pair<Int, Int>>,
    episodeProgress: Map<Pair<Int, Int>, WatchProgress>,
    onDismiss: () -> Unit,
    onPlay: (Video) -> Unit
) {
    val picker by produceState<RandomEpisodePicker?>(null, meta.videos, watchedEpisodes, episodeProgress) {
        value = withContext(Dispatchers.Default) { RandomEpisodePicker(meta, watchedEpisodes, episodeProgress) }
    }
    var starting by remember { mutableStateOf(false) }
    var includeWatched by remember { mutableStateOf(shuffleSettings.includeWatched) }
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val readyPicker = picker
    val unwatchedCount = readyPicker?.count(false) ?: 0
    val allCount = readyPicker?.count(true) ?: 0
    val focusAll = includeWatched || unwatchedCount == 0

    LaunchedEffect(readyPicker != null, starting, focusAll) {
        if (readyPicker != null && !starting) focusRequester.requestFocusAfterFrames(frames = 0)
    }

    fun startShuffle(include: Boolean) {
        if (starting) return
        val episode = readyPicker?.pick(include) ?: return
        includeWatched = include
        starting = true
        scope.launch {
            try {
                if (onSaveSettings(EpisodeShuffleSettings(enabled = true, includeWatched = include))) onPlay(episode)
            } finally {
                starting = false
            }
        }
    }

    NuvioDialog(
        onDismiss = { if (!starting) onDismiss() },
        title = stringResource(R.string.random_episode_title),
        subtitle = stringResource(if (starting) R.string.shuffle_starting else R.string.shuffle_choose_episodes),
        width = 420.dp
    ) {
        when {
            readyPicker == null -> Text(stringResource(R.string.random_episode_loading))
            allCount == 0 -> {
                Text(stringResource(R.string.random_episode_empty_subtitle))
                Button(onClick = onDismiss, modifier = Modifier.focusRequester(focusRequester)) {
                    Text(stringResource(R.string.action_close))
                }
            }
            else -> {
                for (include in listOf(false, true)) {
                    Button(
                        onClick = { startShuffle(include) },
                        enabled = !starting && (include || unwatchedCount > 0),
                        modifier = Modifier.fillMaxWidth()
                            .then(if (include == focusAll) Modifier.focusRequester(focusRequester) else Modifier),
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.BackgroundCard,
                            contentColor = NuvioTheme.colors.TextPrimary
                        )
                    ) {
                        Text(stringResource(if (include) R.string.random_episode_include_watched else R.string.random_episode_unwatched))
                    }
                }
                if (unwatchedCount == 0) Text(stringResource(R.string.random_episode_caught_up))
            }
        }
    }
}
