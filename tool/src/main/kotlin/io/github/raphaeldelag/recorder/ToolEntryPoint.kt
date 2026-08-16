package io.github.raphaeldelag.recorder

import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.shared.LightServerData
import kotlinx.coroutines.flow.StateFlow

@EntryPoint
object ToolEntryPoint : LightEntryPoint {
    override suspend fun onToolCreate(serverData: StateFlow<LightServerData?>) {
        // Nothing to initialise: the recorder keeps everything in files + DataStore.
    }

    override suspend fun onPushNotification(data: ByteArray) {
        // This tool does not use push.
    }
}
