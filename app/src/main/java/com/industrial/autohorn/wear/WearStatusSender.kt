package com.industrial.autohorn.wear

import android.content.Context
import com.google.android.gms.wearable.Wearable
import com.industrial.autohorn.data.WearProtocol
import com.industrial.autohorn.data.WearStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Best-effort phone -> watch status fan-out over the Wearable Data Layer. */
object WearStatusSender {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun send(context: Context, status: WearStatus) {
        val appContext = context.applicationContext
        scope.launch {
            runCatching {
                Wearable.getNodeClient(appContext).connectedNodes.addOnSuccessListener { nodes ->
                    val payload = WearProtocol.encodeStatus(status)
                    nodes.forEach { node ->
                        Wearable.getMessageClient(appContext)
                            .sendMessage(node.id, WearProtocol.PATH_STATUS, payload)
                    }
                }
            }
        }
    }
}
