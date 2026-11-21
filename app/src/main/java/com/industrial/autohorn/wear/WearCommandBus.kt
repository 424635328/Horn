package com.industrial.autohorn.wear

import com.industrial.autohorn.data.WearCommand
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * In-process handoff from the WearableListenerService to the visible activity. A command is only
 * acted on while the horn UI is STARTED, which keeps background audio policy predictable.
 */
object WearCommandBus {
    private val _commands = MutableSharedFlow<WearCommand>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val commands = _commands.asSharedFlow()

    fun publish(command: WearCommand) {
        _commands.tryEmit(command)
    }
}
