package io.github.raphaeldelag.desk

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(private val feed: Feed) : LightViewModel<Unit>() {
    val digest = MutableStateFlow<Digest?>(null)
    val status = MutableStateFlow<String?>(null)
    val refreshing = MutableStateFlow(false)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        if (digest.value == null) digest.value = feed.cached()
        refresh()
    }

    fun refresh() {
        if (refreshing.value) return
        viewModelScope.launch {
            if (feed.url() == null) { status.value = "SET THE FEED IN SETTINGS"; return@launch }
            refreshing.value = true
            status.value = if (digest.value == null) "LOADING…" else null
            feed.refresh().fold(
                { digest.value = it; status.value = null },
                { status.value = (it.message ?: "FETCH FAILED").uppercase() + if (digest.value != null) " — SHOWING CACHED" else "" },
            )
            refreshing.value = false
        }
    }
}

@InitialScreen
class HomeScreen(private val sealedActivity: SealedLightActivity) : LightScreen<Unit, HomeViewModel>(sealedActivity) {
    private val feed = Feed(lightContext.dataStore, lightContext.filesDir)

    override val viewModelClass = HomeViewModel::class.java
    override fun createViewModel() = HomeViewModel(feed)

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val digest by viewModel.digest.collectAsState()
        val status by viewModel.status.collectAsState()

        LightTheme(colors = colors) {
            Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(
                    center = LightTopBarCenter.Text("Desk"),
                    rightButton = LightBarButton.LightIcon(LightIcons.SETTINGS, onClick = {
                        navigateTo({ FeedSettingsScreen(it, feed) }) { viewModel.refresh() }
                    }),
                )
                val d = digest
                status?.let {
                    LightText(
                        it, variant = LightTextVariant.Detail, lighten = true, align = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 0.5f.gridUnitsAsDp()),
                    )
                }
                if (d == null) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        LightText("NO DIGEST YET", variant = LightTextVariant.Copy, lighten = true)
                    }
                } else {
                    LightText(
                        "AS OF ${d.generated}   LAST ${d.window_days} DAYS",
                        variant = LightTextVariant.Superfine, lighten = true,
                        modifier = Modifier.padding(start = 1.5f.gridUnitsAsDp(), bottom = 0.5f.gridUnitsAsDp()),
                    )
                    LightLazyScrollView(
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 1f.gridUnitsAsDp()),
                        uniformItemHeightGridUnits = ROW_UNITS,
                    ) {
                        items(d.sections.sortedByDescending { it.items.size }, key = { it.repo }) { s ->
                            SectionRow(s) {
                                navigateTo({ ItemsScreen(it, s) })
                            }
                        }
                    }
                }
                LightBottomBar(
                    listOf(LightBarButton.LightIcon(LightIcons.REFRESH, onClick = viewModel::refresh)),
                )
            }
        }
    }

    companion object { const val ROW_UNITS = 2.2f }
}

@Composable
private fun SectionRow(s: FeedSection, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(HomeScreen.ROW_UNITS.gridUnitsAsDp())
            .lightClickable(onClick = onClick)
            .padding(horizontal = 0.5f.gridUnitsAsDp()),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(
            text = s.label.uppercase(),
            variant = LightTextVariant.Copy,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        val badge = when (s.health) {
            "failure", "timed_out", "stale" -> "DOWN"
            "dormant" -> "ZZZ"
            else -> ""
        }
        LightText(
            text = (if (badge.isNotEmpty()) "$badge  " else "") + "${s.items.size}",
            variant = LightTextVariant.Copy, monospace = true,
            lighten = s.items.isEmpty(),
        )
    }
}
