package com.industrial.horn.audio

/**
 * The five independent horn voices supported by the v3 synthesis engine.
 *
 * The enum is deliberately the only value persisted and passed across the UI/audio boundary;
 * acoustic parameters live in [HornVoiceCatalog], never in the UI layer.
 */
enum class HornType(
    val storageKey: String,
) {
    CAR("car"),
    TRAIN("train"),
    EBIKE("ebike"),
    MOTORCYCLE("motorcycle"),
    CUSTOM("custom"),
    ;

    companion object {
        fun fromStorageKey(key: String?): HornType = entries.firstOrNull { it.storageKey == key } ?: CAR
    }
}
