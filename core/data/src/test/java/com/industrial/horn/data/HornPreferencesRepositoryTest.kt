package com.industrial.horn.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.industrial.horn.audio.AcousticSpace
import com.industrial.horn.audio.CustomHornProfile
import com.industrial.horn.audio.HornType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HornPreferencesRepositoryTest {
    @Test
    fun defaultsAreCarStreetDefaultCustomAndVolumeTriggerOff() =
        runTest {
            val settings =
                DataStoreHornPreferencesRepository(InMemoryPreferenceDataStore())
                    .settings
                    .first()

            assertEquals(HornType.CAR, settings.selectedHorn)
            assertEquals(AcousticSpace.STREET, settings.acousticSpace)
            assertEquals(CustomHornProfile.DEFAULT, settings.customProfile)
            assertTrue(!settings.volumeTriggerEnabled)
            assertEquals(ThemeMode.SYSTEM, settings.themeMode)
        }

    @Test
    fun everyPreferenceRoundTripsThroughDataStore() =
        runTest {
            val repository = DataStoreHornPreferencesRepository(InMemoryPreferenceDataStore())
            val custom =
                CustomHornProfile(
                    primaryFrequencyHz = 1_250f,
                    secondaryFrequencyRatio = 1.7f,
                    dualTone = false,
                    harmonicRichness = 1f,
                    attackMillis = 12f,
                    releaseMillis = 130f,
                    saturation = 0.8f,
                )

            repository.setSelectedHorn(HornType.CUSTOM)
            repository.setAcousticSpace(AcousticSpace.TUNNEL)
            repository.setCustomProfile(custom)
            repository.setVolumeTriggerEnabled(true)
            repository.setThemeMode(ThemeMode.DARK)

            val settings = repository.settings.first()
            assertEquals(HornType.CUSTOM, settings.selectedHorn)
            assertEquals(AcousticSpace.TUNNEL, settings.acousticSpace)
            assertEquals(custom, settings.customProfile)
            assertTrue(settings.volumeTriggerEnabled)
            assertEquals(ThemeMode.DARK, settings.themeMode)
        }

    @Test
    fun themeModeRoundTripsThroughEveryPositionAndSurvivesANewRepository() =
        runTest {
            val store = InMemoryPreferenceDataStore()
            val repository = DataStoreHornPreferencesRepository(store)

            ThemeMode.entries.forEach { mode ->
                repository.setThemeMode(mode)
                assertEquals(mode, repository.settings.first().themeMode)
            }
            // A second repository on the same store must read the persisted key, not a default.
            assertEquals(ThemeMode.DARK, DataStoreHornPreferencesRepository(store).settings.first().themeMode)
        }

    @Test
    fun unknownStoredThemeKeyFallsBackToSystem() =
        runTest {
            // Forward compatibility: a key written by a newer build must not pin an unknown theme
            // or crash the read path; the safe fallback is "follow the system".
            assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorageKey("solarized"))
            assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorageKey(null))
        }

    @Test
    fun themeCycleIsDeterministicAndThreeStop() {
        assertEquals(ThemeMode.LIGHT, ThemeMode.SYSTEM.next())
        assertEquals(ThemeMode.DARK, ThemeMode.LIGHT.next())
        assertEquals(ThemeMode.SYSTEM, ThemeMode.DARK.next())
        // Cycling three times returns to the origin for every starting position.
        ThemeMode.entries.forEach { start ->
            assertEquals(start, start.next().next().next())
        }
    }

    @Test
    fun resetRestoresDefaultCustomProfile() =
        runTest {
            val repository = DataStoreHornPreferencesRepository(InMemoryPreferenceDataStore())
            repository.setCustomProfile(CustomHornProfile(primaryFrequencyHz = 2_000f))
            repository.resetCustomProfile()

            assertEquals(CustomHornProfile.DEFAULT, repository.settings.first().customProfile)
        }

    @Test
    fun writtenValueIsVisibleToANewRepositoryOnTheSameStore() =
        runTest {
            val store = InMemoryPreferenceDataStore()
            val repository = DataStoreHornPreferencesRepository(store)
            repository.setSelectedHorn(HornType.MOTORCYCLE)

            val second = DataStoreHornPreferencesRepository(store)
            assertEquals(HornType.MOTORCYCLE, second.selectedHorn.first())
        }

    private class InMemoryPreferenceDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        override val data: Flow<Preferences> = state.asStateFlow()

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }
}
