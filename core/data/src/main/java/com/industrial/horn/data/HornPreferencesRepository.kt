package com.industrial.horn.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.industrial.horn.audio.AcousticSpace
import com.industrial.horn.audio.CustomHornProfile
import com.industrial.horn.audio.HornType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

/** Persistence boundary for all user-facing acoustic preferences. */
interface HornPreferencesRepository {
    val settings: Flow<HornSettings>
    val selectedHorn: Flow<HornType>

    suspend fun setSelectedHorn(type: HornType)

    suspend fun setAcousticSpace(space: AcousticSpace)

    suspend fun setCustomProfile(profile: CustomHornProfile)

    suspend fun setVolumeTriggerEnabled(enabled: Boolean)

    suspend fun resetCustomProfile()
}

/**
 * DataStore-backed implementation. Reads are asynchronous and never block the main thread.
 * Corrupt/unknown keys fall back to safe defaults rather than making the app unusable.
 */
class DataStoreHornPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) : HornPreferencesRepository {
    override val settings: Flow<HornSettings> =
        dataStore.data
            .catch { failure ->
                if (failure is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw failure
                }
            }.map(::toSettings)
            .distinctUntilChanged()

    override val selectedHorn: Flow<HornType> =
        settings
            .map { it.selectedHorn }
            .distinctUntilChanged()

    override suspend fun setSelectedHorn(type: HornType) {
        dataStore.edit { preferences ->
            preferences[SELECTED_HORN_KEY] = type.storageKey
        }
    }

    override suspend fun setAcousticSpace(space: AcousticSpace) {
        dataStore.edit { preferences ->
            preferences[ACOUSTIC_SPACE_KEY] = space.storageKey
        }
    }

    override suspend fun setCustomProfile(profile: CustomHornProfile) {
        val safe = profile.sanitized()
        dataStore.edit { preferences ->
            preferences[CUSTOM_PRIMARY_KEY] = safe.primaryFrequencyHz
            preferences[CUSTOM_RATIO_KEY] = safe.secondaryFrequencyRatio
            preferences[CUSTOM_DUAL_KEY] = safe.dualTone
            preferences[CUSTOM_RICHNESS_KEY] = safe.harmonicRichness
            preferences[CUSTOM_ATTACK_KEY] = safe.attackMillis
            preferences[CUSTOM_RELEASE_KEY] = safe.releaseMillis
            preferences[CUSTOM_SATURATION_KEY] = safe.saturation
        }
    }

    override suspend fun setVolumeTriggerEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[VOLUME_TRIGGER_KEY] = enabled
        }
    }

    override suspend fun resetCustomProfile() {
        setCustomProfile(CustomHornProfile.DEFAULT)
    }

    private fun toSettings(preferences: Preferences): HornSettings {
        val default = CustomHornProfile.DEFAULT
        val custom =
            CustomHornProfile(
                primaryFrequencyHz = preferences[CUSTOM_PRIMARY_KEY] ?: default.primaryFrequencyHz,
                secondaryFrequencyRatio =
                    preferences[CUSTOM_RATIO_KEY] ?: default.secondaryFrequencyRatio,
                dualTone = preferences[CUSTOM_DUAL_KEY] ?: default.dualTone,
                harmonicRichness =
                    preferences[CUSTOM_RICHNESS_KEY] ?: default.harmonicRichness,
                attackMillis = preferences[CUSTOM_ATTACK_KEY] ?: default.attackMillis,
                releaseMillis = preferences[CUSTOM_RELEASE_KEY] ?: default.releaseMillis,
                saturation = preferences[CUSTOM_SATURATION_KEY] ?: default.saturation,
            ).sanitized()

        return HornSettings(
            selectedHorn = HornType.fromStorageKey(preferences[SELECTED_HORN_KEY]),
            acousticSpace = AcousticSpace.fromStorageKey(preferences[ACOUSTIC_SPACE_KEY]),
            customProfile = custom,
            volumeTriggerEnabled = preferences[VOLUME_TRIGGER_KEY] ?: false,
        )
    }

    private companion object {
        val SELECTED_HORN_KEY = stringPreferencesKey("selected_horn")
        val ACOUSTIC_SPACE_KEY = stringPreferencesKey("acoustic_space")
        val VOLUME_TRIGGER_KEY = booleanPreferencesKey("volume_trigger_enabled")
        val CUSTOM_PRIMARY_KEY = floatPreferencesKey("custom_primary_hz")
        val CUSTOM_RATIO_KEY = floatPreferencesKey("custom_secondary_ratio")
        val CUSTOM_DUAL_KEY = booleanPreferencesKey("custom_dual_tone")
        val CUSTOM_RICHNESS_KEY = floatPreferencesKey("custom_richness")
        val CUSTOM_ATTACK_KEY = floatPreferencesKey("custom_attack_ms")
        val CUSTOM_RELEASE_KEY = floatPreferencesKey("custom_release_ms")
        val CUSTOM_SATURATION_KEY = floatPreferencesKey("custom_saturation")
    }
}
