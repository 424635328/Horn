package com.industrial.autohorn.data

import com.industrial.autohorn.audio.AcousticSpace
import com.industrial.autohorn.audio.CustomHornProfile
import com.industrial.autohorn.audio.HornType

/** All user preferences persisted by the app. */
data class HornSettings(
    val selectedHorn: HornType = HornType.CAR,
    val acousticSpace: AcousticSpace = AcousticSpace.STREET,
    val customProfile: CustomHornProfile = CustomHornProfile.DEFAULT,
    val volumeTriggerEnabled: Boolean = false,
)
