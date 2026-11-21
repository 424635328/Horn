package com.industrial.autohorn.di

import android.content.Context
import com.industrial.autohorn.audio.AndroidAudioSafetyMonitor
import com.industrial.autohorn.audio.HornAudioEngineFactory
import com.industrial.autohorn.audio.HornSession
import com.industrial.autohorn.audio.HornSessionFactory
import com.industrial.autohorn.data.HornPreferencesRepository
import com.industrial.autohorn.data.HornPreferencesRepositoryFactory
import com.industrial.autohorn.haptic.AndroidHornHaptics

/**
 * Application-scoped composition root. Dependencies are constructed once and injected through
 * interfaces; no mutable global singleton is exposed to feature code.
 */
class AppContainer(
    context: Context,
) {
    private val appContext = context.applicationContext

    val preferencesRepository: HornPreferencesRepository by lazy {
        HornPreferencesRepositoryFactory.create(appContext)
    }

    val safetyMonitor: AndroidAudioSafetyMonitor by lazy {
        AndroidAudioSafetyMonitor(appContext)
    }

    val session: HornSession by lazy {
        HornSessionFactory.create(
            context = appContext,
            engine = HornAudioEngineFactory.create(),
            haptics = AndroidHornHaptics(appContext),
        )
    }
}
