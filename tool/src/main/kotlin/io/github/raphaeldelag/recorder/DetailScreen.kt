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
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.lightClickable
import androidx.compose.foundation.lazy.items
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class DetailViewModel(
    initial: Recording,
    private val repository: RecordingRepository,
    private val durations: DurationStore,
    private val uploader: Uploader,
    audio: LightAudio,
) : LightViewModel<Unit>() {
    val sending = MutableStateFlow(false)
    val sent = MutableStateFlow(false)
    val meta = MutableStateFlow<RecordingMeta?>(null)
    val transcript = MutableStateFlow<String?>(null)      // local copy if present
    val transcriptStatus = MutableStateFlow<String?>(null)
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
        viewModelScope.launch { sent.value = uploader.sentAt(initial.name) != null }
        meta.value = Sidecar.forRecording(initial)
        transcript.value = transcriptFile(initial).takeIf { it.isFile }?.readText()
    }

    private fun transcriptFile(rec: Recording) =
        java.io.File(rec.file.parentFile, rec.file.name.removeSuffix(RecordingRepository.EXT) + ".txt")

    /** Fetch the Whisper transcript from the receiver; keep a local copy. */
    fun fetchTranscript(onReady: (String) -> Unit) {
        viewModelScope.launch {
            transcriptStatus.value = "FETCHING…"
            when (val r = uploader.fetchTranscript(recording.value.name)) {
                is Uploader.TranscriptResult.Ready -> {
                    transcriptFile(recording.value).writeText(r.text)
                    transcript.value = r.text
                    transcriptStatus.value = null
                    onReady(r.text)
                }
                is Uploader.TranscriptResult.Pending -> transcriptStatus.value = "TRANSCRIBING ON MAC — TRY AGAIN SHORTLY"
                is Uploader.TranscriptResult.Failed -> transcriptStatus.value = r.message.uppercase()
            }
        }
    }

    fun seekToMarker(m: Marker) { player.seekTo(m.ms); if (!playing.value) player.play() }

    fun cycleConsent() {
        val cur = meta.value ?: Sidecar.forRecording(recording.value)
        val updated = cur.copy(consent = cur.consentEnum.next().name)
        Sidecar.write(recording.value.file, updated)
        meta.value = updated
    }

    fun send() {
        if (sending.value) return
        viewModelScope.launch {
            val url = uploader.receiverUrl()
            if (url == null) { message.value = "SET A RECEIVER IN SETTINGS FIRST"; return@launch }
            sending.value = true; message.value = "SENDING…"
            uploader.send(recording.value.file).fold(
                { sent.value = true; message.value = "SENT" },
                { message.value = "SEND FAILED: ${it.message}" },
            )
            sending.value = false
        }
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
        meta.value = Sidecar.forRecording(renamed)
        viewModelScope.launch {
            durations.move(current.name, renamed.name)
            uploader.moveSent(current.name, renamed.name)
        }
    }

    fun requestDelete() { player.pause(); confirmingDelete.value = true }
    fun cancelDelete() { confirmingDelete.value = false }
    fun confirmDelete() {
        player.stop()
        val current = recording.value
        transcriptFile(current).delete()
        if (repository.delete(current)) {
            viewModelScope.launch { durations.remove(current.name); uploader.forgetSent(current.name) }
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
    private val uploader: Uploader,
) : LightScreen<Unit, DetailViewModel>(sealedActivity) {
    override val viewModelClass = DetailViewModel::class.java
    override fun createViewModel() = DetailViewModel(recording, repository, durations, uploader, DefaultLightAudio(sealedActivity))

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
        val sending by viewModel.sending.collectAsState()
        val isSent by viewModel.sent.collectAsState()
        val meta by viewModel.meta.collectAsState()
        val transcript by viewModel.transcript.collectAsState()
        val transcriptStatus by viewModel.transcriptStatus.collectAsState()

        if (deleted) {
            androidx.compose.runtime.LaunchedEffect(Unit) { goBack(Unit) }
        }

        LightTheme(colors = colors) {
            Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack(Unit) }),
                    center = LightTopBarCenter.Text(if (isSent) "Recording · sent" else "Recording"),
                    rightButton = LightBarButton.LightIcon(LightIcons.SEND, onClick = { if (!sending) viewModel.send() }),
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
                        Column(Modifier.fillMaxWidth().padding(horizontal = 1.5f.gridUnitsAsDp()), horizontalAlignment = Alignment.CenterHorizontally) {
                            Spacer(Modifier.height(1f.gridUnitsAsDp()))
                            LightText(text = rec.displayTitle, variant = LightTextVariant.Heading, align = TextAlign.Center)
                            LightText(text = rec.displayDate, variant = LightTextVariant.Detail, lighten = true, align = TextAlign.Center)
                            Spacer(Modifier.height(0.5f.gridUnitsAsDp()))
                            LightText(
                                text = (meta?.consentEnum ?: Consent.NotDiscussed).label,
                                variant = LightTextVariant.Detail,
                                align = TextAlign.Center,
                                underline = true,
                                modifier = Modifier.lightClickable { viewModel.cycleConsent() },
                            )
                            Spacer(Modifier.height(0.75f.gridUnitsAsDp()))
                            LightText(
                                text = "${formatClock(position)} / ${formatClock(duration)}",
                                variant = LightTextVariant.Copy, monospace = true, align = TextAlign.Center,
                            )
                            LightText(
                                text = transcriptStatus ?: if (transcript != null) "TRANSCRIPT" else "FETCH TRANSCRIPT",
                                variant = LightTextVariant.Detail,
                                align = TextAlign.Center,
                                underline = transcriptStatus == null,
                                lighten = transcriptStatus != null,
                                modifier = Modifier.lightClickable {
                                    val local = transcript
                                    if (local != null) {
                                        navigateTo({ TranscriptScreen(it, rec.displayTitle, local) })
                                    } else {
                                        viewModel.fetchTranscript { text ->
                                            navigateTo({ TranscriptScreen(it, rec.displayTitle, text) })
                                        }
                                    }
                                },
                            )
                            message?.let { LightText(text = it, variant = LightTextVariant.Detail, lighten = true) }
                        }
                        val marks = meta?.markers.orEmpty()
                        if (marks.isEmpty()) {
                            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                LightText("NO MARKS", variant = LightTextVariant.Detail, lighten = true)
                            }
                        } else {
                            LightLazyScrollView(
                                modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 1.5f.gridUnitsAsDp(), top = 0.5f.gridUnitsAsDp()),
                                uniformItemHeightGridUnits = 1.6f,
                            ) {
                                items(marks.size) { i ->
                                    val m = marks[i]
                                    LightText(
                                        text = "MARK ${i + 1}   ${formatClock(m.ms)}" + (if (m.note.isNotBlank()) "   ${m.note}" else ""),
                                        variant = LightTextVariant.Copy, monospace = true,
                                        modifier = Modifier.fillMaxWidth().lightClickable { viewModel.seekToMarker(m) },
                                    )
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
