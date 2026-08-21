package io.github.raphaeldelag.recorder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

/** Read-only view of a fetched transcript. */
class TranscriptScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val text: String,
) : SimpleLightScreen<Unit>(sealedActivity) {
    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        LightTheme(colors = colors) {
            Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text(title),
                )
                LightScrollView(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 1.5f.gridUnitsAsDp(), end = 0.5f.gridUnitsAsDp()),
                ) {
                    LightText(
                        text = text.ifBlank { "(empty transcript)" },
                        variant = LightTextVariant.Paragraph,
                        modifier = Modifier.padding(vertical = 1f.gridUnitsAsDp()),
                    )
                }
            }
        }
    }
}
