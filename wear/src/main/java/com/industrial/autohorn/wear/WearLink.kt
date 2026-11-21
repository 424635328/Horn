package com.industrial.autohorn.wear

import android.content.Context
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import com.industrial.autohorn.data.WearCommand
import com.industrial.autohorn.data.WearProtocol

/** Thin, failure-tolerant wrapper around the Wearable Data Layer. */
object WearLink {

    fun sendCommand(context: Context, command: WearCommand): Boolean {
        return runCatching {
            Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
                val payload = WearProtocol.encodeCommand(command)
                nodes.forEach { node ->
                    Wearable.getMessageClient(context)
                        .sendMessage(node.id, WearProtocol.PATH_COMMAND, payload)
                }
            }
            true
        }.getOrDefault(false)
    }

    fun registerStatusListener(
        context: Context,
        listener: MessageClient.OnMessageReceivedListener,
    ) {
        runCatching { Wearable.getMessageClient(context).addListener(listener) }
    }

    fun unregisterStatusListener(
        context: Context,
        listener: MessageClient.OnMessageReceivedListener,
    ) {
        runCatching { Wearable.getMessageClient(context).removeListener(listener) }
    }
}
