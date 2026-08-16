package io.github.raphaeldelag.recorder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
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
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(
    private val repository: RecordingRepository,
    private val uploader: Uploader,
) : LightViewModel<Unit>() {
    val receiverUrl = MutableStateFlow<String?>(null)
    val unsentCount = MutableStateFlow(0)
    val status = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) { refresh() }

    fun refresh() {
        viewModelScope.launch {
            receiverUrl.value = uploader.receiverUrl()
            val sent = uploader.sentNames()
            val all = withContext(Dispatchers.IO) { repository.list() }
            unsentCount.value = all.count { it.name !in sent }
        }
    }

    fun setUrl(raw: String?) {
        if (raw == null) return
        viewModelScope.launch {
            uploader.setReceiverUrl(raw)
            status.value = if (raw.isBlank()) "RECEIVER CLEARED" else null
            refresh()
        }
    }

    fun test() {
        viewModelScope.launch {
            busy.value = true; status.value = "TESTING…"
            status.value = uploader.ping().fold({ "RECEIVER OK: $it" }, { "FAILED: ${it.message}" })
            busy.value = false
        }
    }

    fun sendAllUnsent() {
        viewModelScope.launch {
            busy.value = true
            val sent = uploader.sentNames()
            val todo = withContext(Dispatchers.IO) { repository.list() }.filter { it.name !in sent }
            var ok = 0; var failed: String? = null
            for ((i, rec) in todo.withIndex()) {
                status.value = "SENDING ${i + 1}/${todo.size}"
                uploader.send(rec.file).fold({ ok++ }, { failed = it.message; })
                if (failed != null) break
            }
            status.value = if (failed == null) "SENT $ok FILE(S)" else "SENT $ok, THEN FAILED: $failed"
            busy.value = false
            refresh()
        }
    }
}

class SettingsScreen(
    private val sealedActivity: SealedLightActivity,
    private val repository: RecordingRepository,
    private val uploader: Uploader,
) : LightScreen<Unit, SettingsViewModel>(sealedActivity) {
    override val viewModelClass = SettingsViewModel::class.java
    override fun createViewModel() = SettingsViewModel(repository, uploader)

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val url by viewModel.receiverUrl.collectAsState()
        val unsent by viewModel.unsentCount.collectAsState()
        val status by viewModel.status.collectAsState()
        val busy by viewModel.busy.collectAsState()

        LightTheme(colors = colors) {
            Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Send to Mac"),
                )
                Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 1.5f.gridUnitsAsDp())) {
                    Spacer(Modifier.height(1f.gridUnitsAsDp()))
                    LightText("RECEIVER", variant = LightTextVariant.Superfine, lighten = true)
                    LightText(
                        text = url ?: "NOT SET",
                        variant = LightTextVariant.Copy,
                        monospace = url != null,
                        lighten = url == null,
                    )
                    Spacer(Modifier.height(1.5f.gridUnitsAsDp()))
                    SettingsAction("SCAN QR FROM RECEIVER") {
                        navigateTo({ QrScanScreen(it) }) { viewModel.setUrl(it) }
                    }
                    SettingsAction("TYPE ADDRESS") {
                        navigateTo({ LabelEditorScreen(it, url ?: "https://", title = "Receiver address", initialCaps = false) }) {
                            viewModel.setUrl(it)
                        }
                    }
                    if (url != null) {
                        SettingsAction(if (busy) "…" else "TEST CONNECTION") { if (!busy) viewModel.test() }
                        SettingsAction(if (busy) "…" else "SEND $unsent UNSENT") { if (!busy && unsent > 0) viewModel.sendAllUnsent() }
                        SettingsAction("CLEAR RECEIVER") { viewModel.setUrl("") }
                    }
                    Spacer(Modifier.height(1.5f.gridUnitsAsDp()))
                    status?.let { LightText(it, variant = LightTextVariant.Detail, lighten = true) }
                    Spacer(Modifier.height(1f.gridUnitsAsDp()))
                    LightText(
                        "Run scripts/recorder_receiver.py on your Mac, on the same Wi-Fi, then scan the QR it prints. The address carries the receiver's certificate fingerprint, so only that Mac is trusted.",
                        variant = LightTextVariant.Fine,
                        lighten = true,
                    )
                }
                LightBottomBar(emptyList())
            }
        }
    }
}

@Composable
private fun SettingsAction(label: String, onClick: () -> Unit) {
    LightText(
        text = label,
        variant = LightTextVariant.Copy,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.6f.gridUnitsAsDp()),
    )
}
