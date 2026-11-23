package com.industrial.horn.data

/**
 * User-visible theme preference for the Precision Automotive Cockpit identity.
 *
 * [SYSTEM] is the default and follows the platform night mode; [LIGHT] and [DARK] pin the
 * Ceramic Daylight and Stealth Carbon palettes regardless of the system setting. The storage
 * keys are stable strings (never ordinals) so a future reordering cannot silently repaint the
 * cockpit or flip a user out of the theme they picked.
 */
enum class ThemeMode(
    val storageKey: String,
) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    /** Deterministic three-stop cycle used by the single top-bar theme key. */
    fun next(): ThemeMode =
        when (this) {
            SYSTEM -> LIGHT
            LIGHT -> DARK
            DARK -> SYSTEM
        }

    companion object {
        val DEFAULT: ThemeMode = SYSTEM

        fun fromStorageKey(key: String?): ThemeMode = entries.firstOrNull { it.storageKey == key } ?: DEFAULT
    }
}
