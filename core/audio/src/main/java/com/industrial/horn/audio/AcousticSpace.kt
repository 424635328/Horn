package com.industrial.horn.audio

/**
 * Lightweight Schroeder spaces. Values are used by the zero-allocation Kotlin reverb and mirrored
 * by the native Oboe renderer.
 */
enum class AcousticSpace(
    val storageKey: String,
    val wetMix: Float,
    val feedback: Float,
    val damping: Float,
    val preDelayMillis: Float,
    val tailBlocks: Int,
) {
    STREET(
        storageKey = "street",
        wetMix = 0.10f,
        feedback = 0.58f,
        damping = 0.35f,
        preDelayMillis = 8f,
        tailBlocks = 12,
    ),
    TUNNEL(
        storageKey = "tunnel",
        wetMix = 0.38f,
        feedback = 0.84f,
        damping = 0.22f,
        preDelayMillis = 18f,
        tailBlocks = 240,
    ),
    OPEN_ROAD(
        storageKey = "open_road",
        wetMix = 0.22f,
        feedback = 0.66f,
        damping = 0.52f,
        preDelayMillis = 34f,
        tailBlocks = 80,
    ),
    ;

    companion object {
        fun fromStorageKey(key: String?): AcousticSpace = entries.firstOrNull { it.storageKey == key } ?: STREET
    }
}
