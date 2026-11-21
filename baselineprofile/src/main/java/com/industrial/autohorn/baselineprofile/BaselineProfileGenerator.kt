package com.industrial.autohorn.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Generates app startup and horn-screen interaction baseline profiles on a connected device. */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generateHornStartupProfile() {
        baselineProfileRule.collect(
            packageName = "com.industrial.autohorn",
            includeInStartupProfile = true,
        ) {
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.textContains("AUTOHORN")), 8_000)
            device.waitForIdle()
        }
    }
}
