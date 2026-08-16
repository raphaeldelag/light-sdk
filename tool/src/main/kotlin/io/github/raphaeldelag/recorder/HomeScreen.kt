package io.github.raphaeldelag.recorder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RecordingRow(val recording: Recording, val durationMs: Long?)

class HomeViewModel(
    private val repository: RecordingRepository,
    private val durations: DurationStore,
) : LightViewModel<Unit>() {
    val rows = MutableStateFlow<List<RecordingRow>>(emptyList())
    val loaded = MutableStateFlow(false)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { repository.list() }
            val known = durations.all()
            rows.value = list.map { RecordingRow(it, known[it.name]) }
            loaded.value = true
        }
    }
}

@InitialScreen
class HomeScreen(private val sealedActivity: SealedLightActivity) : LightScreen<Unit, HomeViewModel>(sealedActivity) {

    private val repository = RecordingRepository(lightContext.filesDir)
    private val durations = DurationStore(lightContext.dataStore)

    override val viewModelClass = HomeViewModel::class.java
    override fun createViewModel() = HomeViewModel(repository, durations)

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val rows by viewModel.rows.collectAsState()
        val loaded by viewModel.loaded.collectAsState()

        LightTheme(colors = colors) {
            Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(center = LightTopBarCenter.Text("Recorder"))

                if (loaded && rows.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        LightText(
                            text = "NO RECORDINGS",
                            variant = LightTextVariant.Copy,
                            align = TextAlign.Center,
                            lighten = true,
                        )
                    }
                } else {
                    LightLazyScrollView(
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 1f.gridUnitsAsDp()),
                        uniformItemHeightGridUnits = ROW_HEIGHT_UNITS,
                    ) {
                        items(rows, key = { it.recording.name }) { row ->
                            RecordingListItem(row) {
                                navigateTo(
                                    screenFactory = { DetailScreen(it, row.recording, repository, durations) },
                                    resultCallback = { viewModel.refresh() },
                                )
                            }
                        }
                    }
                }

                LightBottomBar(
                    listOf(
                        LightBarButton.LightIcon(
                            icon = LightIcons.MICROPHONE,
                            onClick = {
                                navigateTo(
                                    screenFactory = { RecordScreen(it, repository, durations) },
                                    resultCallback = { viewModel.refresh() },
                                )
                            },
                        ),
                    ),
                )
            }
        }
    }

    companion object {
        const val ROW_HEIGHT_UNITS = 3f
    }
}

@Composable
private fun RecordingListItem(row: RecordingRow, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .height(HomeScreen.ROW_HEIGHT_UNITS.gridUnitsAsDp())
            .lightClickable(onClick = onClick)
            .padding(horizontal = 0.5f.gridUnitsAsDp()),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        LightText(
            text = row.recording.displayTitle,
            variant = LightTextVariant.Copy,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val duration = row.durationMs?.let { formatClock(it) } ?: "--:--"
        LightText(
            text = "${row.recording.displayDate}   $duration",
            variant = LightTextVariant.Detail,
            lighten = true,
            monospace = true,
            maxLines = 1,
        )
    }
}
