package io.github.raphaeldelag.recorder

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.LightQrCodeScanner
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens

/** Scans a QR code (the receiver script prints one) and returns its text as the screen result. */
class QrScanScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<String>(sealedActivity) {
    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        var pending by remember { mutableStateOf<String?>(null) }
        LightTheme(colors = colors) {
            LightQrCodeScanner(
                title = "Scan receiver QR",
                onScanned = { pending = it },
                onBack = { goBack() },
                modifier = Modifier.background(LightThemeTokens.colors.background),
            )
        }
        LaunchedEffect(pending) {
            val value = pending ?: return@LaunchedEffect
            pending = null
            goBack(value)
        }
    }
}
