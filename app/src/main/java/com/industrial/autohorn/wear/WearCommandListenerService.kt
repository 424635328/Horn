package com.industrial.autohorn.wear

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.industrial.autohorn.data.WearProtocol

/**
 * Receives command messages from the paired watch. The message is published to the in-process
 * bus; the activity's STARTED lifecycle is the authority for actually starting audio.
 */
class WearCommandListenerService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != WearProtocol.PATH_COMMAND) return
        val command = WearProtocol.decodeCommand(messageEvent.data) ?: return
        WearCommandBus.publish(command)
    }
}
