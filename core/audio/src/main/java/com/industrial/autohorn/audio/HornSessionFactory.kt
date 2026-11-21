package com.industrial.autohorn.audio

import android.content.Context

/** Public composition seam; concrete session plumbing stays internal to :core:audio. */
object HornSessionFactory {
    fun create(
        context: Context,
        engine: HornAudioEngine,
        haptics: HornHapticFeedback,
    ): HornSession =
        DefaultHornSession(
            context = context.applicationContext,
            engine = engine,
            haptics = haptics,
        )
}
