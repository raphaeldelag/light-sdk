package io.github.raphaeldelag.desk

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

/** All recent items for one tracker, newest first. */
class ItemsScreen(
    sealedActivity: SealedLightActivity,
    private val section: FeedSection,
) : SimpleLightScreen<Unit>(sealedActivity) {
    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        LightTheme(colors = colors) {
            Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text(section.label),
                )
                if (section.items.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        LightText(
                            if (section.health == "dormant") "DORMANT (OUT OF SESSION)" else "NOTHING NEW",
                            variant = LightTextVariant.Copy, lighten = true,
                        )
                    }
                } else {
                    LightScrollView(
                        modifier = Modifier.weight(1f).fillMaxWidth()
                            .padding(start = 1.5f.gridUnitsAsDp(), end = 0.5f.gridUnitsAsDp()),
                    ) {
                        for (item in section.items) {
                            LightText(
                                text = item.date,
                                variant = LightTextVariant.Superfine, lighten = true, monospace = true,
                                modifier = Modifier.padding(top = 0.8f.gridUnitsAsDp()),
                            )
                            LightText(text = item.title, variant = LightTextVariant.Copy)
                        }
                        LightText(text = "", variant = LightTextVariant.Copy,
                            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()))
                    }
                }
            }
        }
    }
}
