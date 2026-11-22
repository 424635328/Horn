package com.industrial.horn.audio

/**
 * Playback spaces of the scene engine. The enum carries only its persistence identity: every DSP
 * parameter lives in `dsp/SceneSpecCatalog` (docs/spec/research/02 §4 is the single source of
 * truth) so UI/persistence concerns stay out of the acoustic model.
 *
 * Ordinals are part of the JNI contract (`nativeSetSpace(int)`): STREET=0, TUNNEL=1,
 * OPEN_ROAD=2, GARAGE=3. Appending GARAGE at the end keeps persisted strings and native
 * ordinals compatible; [fromStorageKey] falls back to [STREET] for unknown keys so older
 * installs degrade gracefully.
 */
enum class AcousticSpace(
    val storageKey: String,
) {
    STREET("street"),
    TUNNEL("tunnel"),
    OPEN_ROAD("open_road"),
    GARAGE("garage"),
    ;

    companion object {
        fun fromStorageKey(key: String?): AcousticSpace = entries.firstOrNull { it.storageKey == key } ?: STREET
    }
}
