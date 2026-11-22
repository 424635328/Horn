package com.industrial.horn.di

import android.content.Context
import com.industrial.horn.audio.AndroidAudioSafetyMonitor
import com.industrial.horn.audio.HornAudioEngineFactory
import com.industrial.horn.audio.HornSession
import com.industrial.horn.audio.HornSessionFactory
import com.industrial.horn.data.HornPreferencesRepository
import com.industrial.horn.data.HornPreferencesRepositoryFactory
import com.industrial.horn.haptic.AndroidHornHaptics

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
