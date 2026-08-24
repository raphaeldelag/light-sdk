package io.github.raphaeldelag.desk

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightQrCodeScanner
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class FeedSettingsViewModel(private val feed: Feed) : LightViewModel<Unit>() {
    val url = MutableStateFlow<String?>(null)
    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        viewModelScope.launch { url.value = feed.url() }
    }
    fun setUrl(raw: String?) {
        if (raw == null) return
        viewModelScope.launch { feed.setUrl(raw); url.value = feed.url() }
    }
}

class FeedSettingsScreen(
    sealedActivity: SealedLightActivity,
    private val feed: Feed,
) : LightScreen<Unit, FeedSettingsViewModel>(sealedActivity) {
    override val viewModelClass = FeedSettingsViewModel::class.java
    override fun createViewModel() = FeedSettingsViewModel(feed)

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val url by viewModel.url.collectAsState()
        LightTheme(colors = colors) {
            Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Feed"),
                )
                Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 1.5f.gridUnitsAsDp())) {
                    Spacer(Modifier.height(1f.gridUnitsAsDp()))
                    LightText("FEED URL", variant = LightTextVariant.Superfine, lighten = true)
                    LightText(
                        text = url ?: "NOT SET",
                        variant = LightTextVariant.Detail, monospace = url != null, lighten = url == null,
                    )
                    Spacer(Modifier.height(1.5f.gridUnitsAsDp()))
                    Action("SCAN QR") { navigateTo({ FeedQrScreen(it) }) { viewModel.setUrl(it) } }
                    Action("TYPE URL") {
                        navigateTo({ FeedUrlEditor(it, url ?: "") }) { viewModel.setUrl(it) }
                    }
                    if (url != null) Action("CLEAR") { viewModel.setUrl("") }
                    Spacer(Modifier.height(1.5f.gridUnitsAsDp()))
                    LightText(
                        "Run ~/LightPhone/digest/build_digest.py on the Mac; it prints the feed URL (a secret gist updated hourly). qrencode -t ANSI <url> prints a scannable code.",
                        variant = LightTextVariant.Fine, lighten = true,
                    )
                }
                LightBottomBar(emptyList())
            }
        }
    }
}

@Composable
private fun Action(label: String, onClick: () -> Unit) {
    LightText(
        text = label,
        variant = LightTextVariant.Copy,
        modifier = Modifier.fillMaxWidth().lightClickable(onClick = onClick).padding(vertical = 0.6f.gridUnitsAsDp()),
    )
}

class FeedQrScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<String>(sealedActivity) {
    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        var pending by remember { mutableStateOf<String?>(null) }
        LightTheme(colors = colors) {
            LightQrCodeScanner(
                title = "Scan feed QR",
                onScanned = { pending = it },
                onBack = { goBack() },
                modifier = Modifier.background(LightThemeTokens.colors.background),
            )
        }
        LaunchedEffect(pending) {
            val v = pending ?: return@LaunchedEffect
            pending = null
            goBack(v)
        }
    }
}

class FeedUrlEditor(
    sealedActivity: SealedLightActivity,
    private val initial: String,
) : SimpleLightScreen<String>(sealedActivity) {
    @Composable
    override fun Content() {
        val keyboardOptionsFlow = rememberKeyboardOptions()
        val textState = rememberTextFieldState(initial)
        val colors by LightThemeController.colors.collectAsState()
        LightTheme(colors = colors) {
            LightTextInputEditor(
                title = "Feed URL",
                state = textState,
                keyboardOptionsFlow = keyboardOptionsFlow,
                onSubmit = { goBack(it.toString()) },
                onBack = { goBack(null) },
                modifier = Modifier.background(LightThemeTokens.colors.background),
                submitLabel = "SAVE",
                singleLine = true,
            )
        }
    }
}
