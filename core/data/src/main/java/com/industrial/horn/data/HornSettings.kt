package com.industrial.horn.data

import com.industrial.horn.audio.AcousticSpace
import com.industrial.horn.audio.CustomHornProfile
import com.industrial.horn.audio.HornType

/** All user preferences persisted by the app. */
data class HornSettings(
    val selectedHorn: HornType = HornType.CAR,
    val acousticSpace: AcousticSpace = AcousticSpace.STREET,
    val customProfile: CustomHornProfile = CustomHornProfile.DEFAULT,
    val volumeTriggerEnabled: Boolean = false,
)
