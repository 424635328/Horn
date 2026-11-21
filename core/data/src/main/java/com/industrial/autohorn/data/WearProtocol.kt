package com.industrial.autohorn.data

import com.industrial.autohorn.audio.HornType
import java.nio.charset.StandardCharsets

/**
 * Platform-neutral Wearable Data Layer protocol. Keeping it in core:data lets both the phone
 * service and the watch activity be tested without Google Play services.
 */
object WearProtocol {
    const val PATH_COMMAND = "/autohorn/command"
    const val PATH_STATUS = "/autohorn/status"
    const val PATH_PREFIX = "/autohorn"

    fun encodeCommand(command: WearCommand): ByteArray =
        command.name.toByteArray(StandardCharsets.UTF_8)

    fun decodeCommand(bytes: ByteArray): WearCommand? =
        runCatching { WearCommand.valueOf(bytes.toString(StandardCharsets.UTF_8).trim()) }
            .getOrNull()

    fun encodeStatus(status: WearStatus): ByteArray =
        "${if (status.isHonking) "1" else "0"}|${status.voice.name}"
            .toByteArray(StandardCharsets.UTF_8)

    fun decodeStatus(bytes: ByteArray): WearStatus? {
        val parts = bytes.toString(StandardCharsets.UTF_8).split('|')
        if (parts.size != 2) return null
        val voice = runCatching { HornType.valueOf(parts[1].trim()) }.getOrNull() ?: return null
        return WearStatus(isHonking = parts[0].trim() == "1", voice = voice)
    }
}

enum class WearCommand {
    START,
    STOP,
}

data class WearStatus(
    val isHonking: Boolean,
    val voice: HornType = HornType.CAR,
)
