package io.github.raphaeldelag.recorder

import android.Manifest
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.thelightphone.sdk.audio.LightAudioException
import com.thelightphone.sdk.audio.LightAudioPlayer
import com.thelightphone.sdk.audio.LightAudioRecorder
import com.thelightphone.sdk.checkPermission
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.asKotlinResult
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
import com.thelightphone.sdk.ui.gridUnitsAsDp
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class RecordState { PermissionRequired, Ready, Recording, Review, ConfirmDiscard, Saved }

class RecordViewModel(
    private val repository: RecordingRepository,
    private val durations: DurationStore,
    audio: LightAudio,
) : LightViewModel<Unit>() {
    val state = MutableStateFlow(RecordState.PermissionRequired)
    val elapsedMs = MutableStateFlow(0L)
    val positionMs = MutableStateFlow(0L)
    val durationMs = MutableStateFlow(0L)
    val playing = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    /** The recording just saved (state == Saved). */
    val saved = MutableStateFlow<Recording?>(null)

    private val recorder: LightAudioRecorder = audio.newRecorder()
    private val player: LightAudioPlayer = audio.newPlayer()
    private var file: File? = null
    private var startedAt = 0L
    private var ticker: Job? = null
    private val collectors: Job = viewModelScope.launch {
        launch { player.positionMs.collect(positionMs::emit) }
        launch { player.durationMs.collect(durationMs::emit) }
        launch { player.isPlaying.collect(playing::emit) }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        viewModelScope.launch { refreshPermission() }
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        // Leaving the screen (or LightOS backgrounding the tool) ends the take rather than
        // recording blind; the file is kept and shown for review when we come back.
        if (state.value == RecordState.Recording) finishRecording()
        player.pause()
    }

    override fun onBackPressed(): Boolean {
        // Back during a take: stop and review instead of silently leaving.
        if (state.value == RecordState.Recording) { finishRecording(); return true }
        if (state.value == RecordState.ConfirmDiscard) { state.value = RecordState.Review; return true }
        if (state.value == RecordState.Review) { requestDiscard(); return true }
        return false
    }

    fun start() {
        if (state.value != RecordState.Ready) return
        error.value = null
        player.stop()
        val f = repository.newRecordingFile()
        try {
            recorder.start(f)
        } catch (e: LightAudioException) {
            f.delete()
            error.value = e.message ?: "Recording failed"
            return
        }
        file = f
        startedAt = SystemClock.elapsedRealtime()
        elapsedMs.value = 0L
        state.value = RecordState.Recording
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive && state.value == RecordState.Recording) {
                elapsedMs.value = SystemClock.elapsedRealtime() - startedAt
                delay(TICK_MS)
            }
        }
    }

    fun stop() {
        if (state.value == RecordState.Recording) finishRecording()
    }

    fun togglePlayback() {
        if (playing.value) {
            player.pause()
        } else {
            if (durationMs.value > 0L && positionMs.value >= durationMs.value) player.seekTo(0L)
            player.play()
        }
    }

    fun save() {
        val f = file ?: return
        player.stop()
        val recording = repository.parse(f) ?: return
        val dur = elapsedMs.value
        viewModelScope.launch {
            durations.set(recording.name, dur)
            file = null
            saved.value = recording
            state.value = RecordState.Saved
        }
    }

    /** Apply a label typed in the editor to the just-saved recording. */
    fun applyLabel(label: String?) {
        val recording = saved.value ?: return
        if (label.isNullOrBlank()) return
        val renamed = repository.relabel(recording, label) ?: return
        saved.value = renamed
        viewModelScope.launch { durations.move(recording.name, renamed.name) }
    }

    fun requestDiscard() { player.pause(); state.value = RecordState.ConfirmDiscard }
    fun cancelDiscard() { state.value = RecordState.Review }
    fun confirmDiscard() {
        player.stop()
        file?.delete()
        file = null
        resetReview()
        state.value = RecordState.Ready
    }

    override fun onCleared() {
        if (state.value == RecordState.Recording) finishRecording()
        ticker?.cancel()
        collectors.cancel()
        recorder.release()
        player.release()
        super.onCleared()
    }

    private suspend fun refreshPermission() {
        val granted = checkPermission(Manifest.permission.RECORD_AUDIO).asKotlinResult
            .map { it.permissionResult == LightServiceMethod.GetPermission.Result.Granted }
            .getOrDefault(false)
        if (state.value == RecordState.PermissionRequired && granted) state.value = RecordState.Ready
        if (!granted && state.value == RecordState.Ready) state.value = RecordState.PermissionRequired
    }

    private fun finishRecording() {
        ticker?.cancel(); ticker = null
        val dur = recorder.stop()
        elapsedMs.value = dur
        val valid = file?.takeIf { dur > 0L && it.exists() }
        if (valid == null) {
            file?.delete(); file = null
            state.value = RecordState.Ready
        } else {
            player.setSource(valid)
            state.value = RecordState.Review
        }
    }

    private fun resetReview() {
        elapsedMs.value = 0L; positionMs.value = 0L; durationMs.value = 0L; playing.value = false
    }

    companion object { private const val TICK_MS = 100L }
}

class RecordScreen(
    private val sealedActivity: SealedLightActivity,
    private val repository: RecordingRepository,
    private val durations: DurationStore,
) : LightScreen<Unit, RecordViewModel>(sealedActivity) {
    override val viewModelClass = RecordViewModel::class.java
    override fun createViewModel() = RecordViewModel(repository, durations, DefaultLightAudio(sealedActivity))

    @Composable
    override fun Content() {
        val permissionLauncher = rememberPermissionRequestLauncher(Manifest.permission.RECORD_AUDIO)
        val colors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()
        val elapsed by viewModel.elapsedMs.collectAsState()
        val position by viewModel.positionMs.collectAsState()
        val duration by viewModel.durationMs.collectAsState()
        val playing by viewModel.playing.collectAsState()
        val error by viewModel.error.collectAsState()
        val saved by viewModel.saved.collectAsState()

        LightTheme(colors = colors) {
            Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Record"),
                )
                when (state) {
                    RecordState.PermissionRequired -> StateView(
                        text = "MICROPHONE ACCESS REQUIRED",
                        monospace = false,
                        actions = listOf(LightBarButton.Text("ALLOW", onClick = { permissionLauncher?.launch() })),
                        modifier = Modifier.weight(1f),
                    )
                    RecordState.Ready -> StateView(
                        text = error?.uppercase() ?: "READY",
                        actions = listOf(LightBarButton.LightIcon(LightIcons.MICROPHONE, viewModel::start)),
                        modifier = Modifier.weight(1f),
                    )
                    RecordState.Recording -> StateView(
                        text = "RECORDING\n${formatClock(elapsed)}",
                        actions = listOf(LightBarButton.LightIcon(LightIcons.STOP, viewModel::stop)),
                        modifier = Modifier.weight(1f),
                    )
                    RecordState.Review -> StateView(
                        text = "REVIEW\n${formatClock(position)} / ${formatClock(duration)}",
                        actions = listOf(
                            LightBarButton.LightIcon(LightIcons.TRASH, viewModel::requestDiscard),
                            LightBarButton.LightIcon(if (playing) LightIcons.PAUSE else LightIcons.PLAY, viewModel::togglePlayback),
                            LightBarButton.LightIcon(LightIcons.ACCEPT, viewModel::save),
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    RecordState.ConfirmDiscard -> StateView(
                        text = "DISCARD RECORDING?",
                        monospace = false,
                        actions = listOf(
                            LightBarButton.Text("CANCEL", onClick = viewModel::cancelDiscard),
                            LightBarButton.Text("DISCARD", onClick = viewModel::confirmDiscard),
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    RecordState.Saved -> StateView(
                        text = "SAVED\n${saved?.displayTitle ?: ""}",
                        monospace = false,
                        actions = listOf(
                            LightBarButton.LightIcon(LightIcons.PENCIL, onClick = {
                                navigateTo(
                                    screenFactory = { LabelEditorScreen(it, saved?.label?.replace('-', ' ') ?: "") },
                                    resultCallback = { label -> viewModel.applyLabel(label) },
                                )
                            }),
                            LightBarButton.LightIcon(LightIcons.ACCEPT, onClick = { goBack() }),
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
internal fun StateView(
    text: String,
    actions: List<LightBarButton>,
    modifier: Modifier = Modifier,
    monospace: Boolean = true,
) {
    Column(modifier) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            LightText(
                text = text,
                variant = LightTextVariant.Copy,
                align = TextAlign.Center,
                monospace = monospace,
                modifier = Modifier.padding(horizontal = 2f.gridUnitsAsDp()),
            )
        }
        LightBottomBar(actions)
    }
}
