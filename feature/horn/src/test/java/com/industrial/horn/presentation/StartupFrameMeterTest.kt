package com.industrial.horn.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupFrameMeterTest {
    @Test
    fun reportsMedianAndP95OverRecordedSamples() {
        val meter = StartupFrameMeter(capacity = 8)
        listOf(16.7f, 16.7f, 16.7f, 16.7f, 33.4f, 50.0f, 16.7f, 16.7f).forEach(meter::record)

        assertEquals(16.7f, meter.median(), 0.01f)
        assertTrue("p95 should land on the tail", meter.p95() >= 33.4f)
        assertEquals(1, meter.slowFrames())
    }

    @Test
    fun ringBufferKeepsTheLatestWindow() {
        val meter = StartupFrameMeter(capacity = 4)
        listOf(100f, 100f, 100f, 16f, 16f, 16f).forEach(meter::record)

        // Only the last four samples survive: one 100 ms frame and three fast frames.
        assertEquals(16f, meter.median(), 0.01f)
        assertEquals(1, meter.slowFrames())
    }

    @Test
    fun invalidDeltasAreIgnored() {
        val meter = StartupFrameMeter()
        meter.record(0f)
        meter.record(-4f)
        meter.record(Float.NaN)
        meter.record(Float.POSITIVE_INFINITY)
        meter.record(20_000f)

        assertEquals(0f, meter.median(), 0f)
        assertEquals("startup frames: no samples", meter.summary())
    }

    @Test
    fun summaryCarriesTheNumbers() {
        val meter = StartupFrameMeter()
        repeat(10) { meter.record(16f) }
        meter.record(40f)

        val summary = meter.summary()
        assertTrue(summary, summary.contains("n=11"))
        assertTrue(summary, summary.contains("slow(>33.4ms)=1"))
    }
}
