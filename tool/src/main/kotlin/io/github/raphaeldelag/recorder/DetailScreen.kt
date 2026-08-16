package io.github.raphaeldelag.recorder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioPlayer
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.LightTouchableProgressBar
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class DetailViewModel(
    initial: Recording,
    private val repository: RecordingRepository,
    private val durations: DurationStore,
    audio: LightAudio,
) : LightViewModel<Unit>() {
    val recording = MutableStateFlow(initial)
    val positionMs = MutableStateFlow(0L)
    val durationMs = MutableStateFlow(0L)
    val playing = MutableStateFlow(false)
    val confirmingDelete = MutableStateFlow(false)
    val deleted = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)

    private val player: LightAudioPlayer = audio.newPlayer()
    private val collectors: Job = viewModelScope.launch {
        launch { player.positionMs.collect(positionMs::emit) }
        launch {
            player.durationMs.collect { d ->
                durationMs.value = d
                // opportunistically backfill the list's duration cache
                if (d > 0L) durations.set(recording.value.name, d)
            }
        }
        launch { player.isPlaying.collect(playing::emit) }
    }

    init {
        player.setSource(initial.file)
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        player.pause()
    }

    override fun onBackPressed(): Boolean {
        if (confirmingDelete.value) { confirmingDelete.value = false; return true }
        return false
    }

    fun togglePlayback() {
        if (playing.value) {
            player.pause()
        } else {
            if (durationMs.value > 0L && positionMs.value >= durationMs.value) player.seekTo(0L)
            player.play()
        }
    }

    fun seekTo(fraction: Float) {
        val d = durationMs.value
        if (d > 0L) player.seekTo((fraction.coerceIn(0f, 1f) * d).toLong())
    }

    fun skipBack() = player.skipBack()
    fun skipForward() = player.skipForward()

    fun relabel(label: String?) {
        if (label == null) return
        val current = recording.value
        val renamed = repository.relabel(current, label)
        if (renamed == null) { message.value = "RENAME FAILED"; return }
        recording.value = renamed
        viewModelScope.launch { durations.move(current.name, renamed.name) }
    }

    fun requestDelete() { player.pause(); confirmingDelete.value = true }
    fun cancelDelete() { confirmingDelete.value = false }
    fun confirmDelete() {
        player.stop()
        val current = recording.value
        if (repository.delete(current)) {
            viewModelScope.launch { durations.remove(current.name) }
            deleted.value = true
        } else {
            message.value = "DELETE FAILED"
            confirmingDelete.value = false
        }
    }

    override fun onCleared() {
        collectors.cancel()
        player.release()
        super.onCleared()
    }
}

class DetailScreen(
    private val sealedActivity: SealedLightActivity,
    private val recording: Recording,
    private val repository: RecordingRepository,
    private val durations: DurationStore,
) : LightScreen<Unit, DetailViewModel>(sealedActivity) {
    override val viewModelClass = DetailViewModel::class.java
    override fun createViewModel() = DetailViewModel(recording, repository, durations, DefaultLightAudio(sealedActivity))

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val rec by viewModel.recording.collectAsState()
        val position by viewModel.positionMs.collectAsState()
        val duration by viewModel.durationMs.collectAsState()
        val playing by viewModel.playing.collectAsState()
        val confirming by viewModel.confirmingDelete.collectAsState()
        val deleted by viewModel.deleted.collectAsState()
        val message by viewModel.message.collectAsState()

        if (deleted) {
            androidx.compose.runtime.LaunchedEffect(Unit) { goBack(Unit) }
        }

        LightTheme(colors = colors) {
            Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack(Unit) }),
                    center = LightTopBarCenter.Text("Recording"),
                )
                if (confirming) {
                    StateView(
                        text = "DELETE RECORDING?",
                        monospace = false,
                        actions = listOf(
                            LightBarButton.Text("CANCEL", onClick = viewModel::cancelDelete),
                            LightBarButton.Text("DELETE", onClick = viewModel::confirmDelete),
                        ),
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Column(Modifier.weight(1f)) {
                        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                LightText(
                                    text = rec.displayTitle,
                                    variant = LightTextVariant.Heading,
                                    align = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 1.5f.gridUnitsAsDp()),
                                )
                                Spacer(Modifier.height(0.5f.gridUnitsAsDp()))
                                LightText(
                                    text = rec.displayDate,
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    align = TextAlign.Center,
                                )
                                Spacer(Modifier.height(1.5f.gridUnitsAsDp()))
                                LightText(
                                    text = "${formatClock(position)} / ${formatClock(duration)}",
                                    variant = LightTextVariant.Copy,
                                    monospace = true,
                                    align = TextAlign.Center,
                                )
                                message?.let {
                                    Spacer(Modifier.height(0.5f.gridUnitsAsDp()))
                                    LightText(text = it, variant = LightTextVariant.Detail, lighten = true)
                                }
                            }
                        }
                        LightTouchableProgressBar(
                            colors = LightThemeTokens.colors,
                            progress = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                            onValueChange = viewModel::seekTo,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 1.5f.gridUnitsAsDp()),
                        )
                        LightBottomBar(
                            listOf(
                                LightBarButton.LightIcon(LightIcons.TRASH, viewModel::requestDelete),
                                LightBarButton.LightIcon(LightIcons.SKIP_BACKWARD_FIFTEEN, viewModel::skipBack),
                                LightBarButton.LightIcon(if (playing) LightIcons.PAUSE else LightIcons.PLAY, viewModel::togglePlayback),
                                LightBarButton.LightIcon(LightIcons.SKIP_FORWARD_FIFTEEN, viewModel::skipForward),
                                LightBarButton.LightIcon(LightIcons.PENCIL, onClick = {
                                    navigateTo(
                                        screenFactory = { LabelEditorScreen(it, rec.label?.replace('-', ' ') ?: "") },
                                        resultCallback = { label -> viewModel.relabel(label) },
                                    )
                                }),
                            ),
                        )
                    }
                }
            }
        }
    }
}
