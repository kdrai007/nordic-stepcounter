package com.nordic.stepcounter.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StepDeltaProcessorTest {

    @Test
    fun `first sensor reading initializes baseline with zero delta`() {
        val result = StepDeltaProcessor.computeDelta(
            currentSensorSteps = 45000L,
            lastSensorSteps = -1L,
            isPaused = false,
            currentBootCount = 5,
            lastBootCount = -1
        )
        assertEquals(0, result.stepsToAdd)
        assertEquals(45000L, result.newBaselineSensorSteps)
        assertEquals(5, result.newBootCount)
        assertFalse(result.newPendingResumeBaseline)
    }

    @Test
    fun `normal sensor increment returns positive delta and updates baseline`() {
        val result = StepDeltaProcessor.computeDelta(
            currentSensorSteps = 45120L,
            lastSensorSteps = 45000L,
            isPaused = false,
            currentBootCount = 5,
            lastBootCount = 5
        )
        assertEquals(120, result.stepsToAdd)
        assertEquals(45120L, result.newBaselineSensorSteps)
        assertEquals(5, result.newBootCount)
    }

    @Test
    fun `legacy fallback when boot count is unavailable resets baseline when current is less than last`() {
        val result = StepDeltaProcessor.computeDelta(
            currentSensorSteps = 35L,
            lastSensorSteps = 45120L,
            isPaused = false
        )
        assertEquals(35, result.stepsToAdd)
        assertEquals(35L, result.newBaselineSensorSteps)
    }

    @Test
    fun `boot count change detects real reboot even when current exceeds previous baseline`() {
        // Before reboot: last=50 at boot=10. Device reboots (boot=11) and user walks 200 steps before event arrives.
        val result = StepDeltaProcessor.computeDelta(
            currentSensorSteps = 200L,
            lastSensorSteps = 50L,
            isPaused = false,
            currentBootCount = 11,
            lastBootCount = 10
        )
        assertEquals(200, result.stepsToAdd)
        assertEquals(200L, result.newBaselineSensorSteps)
        assertEquals(11, result.newBootCount)
    }

    @Test
    fun `same boot count ignores stale or out-of-order regression instead of adding phantom reboot spike`() {
        val result = StepDeltaProcessor.computeDelta(
            currentSensorSteps = 10050L,
            lastSensorSteps = 10080L,
            isPaused = false,
            currentBootCount = 10,
            lastBootCount = 10
        )
        assertEquals(0, result.stepsToAdd)
        assertEquals(10080L, result.newBaselineSensorSteps)
        assertEquals(10, result.newBootCount)
    }

    @Test
    fun `same boot count resets baseline when sensor hub drops near zero without phantom spike`() {
        val resetResult = StepDeltaProcessor.computeDelta(
            currentSensorSteps = 12L,
            lastSensorSteps = 25000L,
            isPaused = false,
            currentBootCount = 10,
            lastBootCount = 10
        )
        assertEquals(0, resetResult.stepsToAdd)
        assertEquals(12L, resetResult.newBaselineSensorSteps)

        val nextResult = StepDeltaProcessor.computeDelta(
            currentSensorSteps = 42L,
            lastSensorSteps = resetResult.newBaselineSensorSteps,
            isPaused = false,
            currentBootCount = 10,
            lastBootCount = resetResult.newBootCount
        )
        assertEquals(30, nextResult.stepsToAdd)
        assertEquals(42L, nextResult.newBaselineSensorSteps)
    }

    @Test
    fun `pendingResumeBaseline absorbs first post-resume reading without adding paused steps`() {
        val firstAfterResume = StepDeltaProcessor.computeDelta(
            currentSensorSteps = 1800L,
            lastSensorSteps = 1000L,
            isPaused = false,
            currentBootCount = 4,
            lastBootCount = 4,
            pendingResumeBaseline = true
        )
        assertEquals(0, firstAfterResume.stepsToAdd)
        assertEquals(1800L, firstAfterResume.newBaselineSensorSteps)
        assertFalse(firstAfterResume.newPendingResumeBaseline)

        val nextActive = StepDeltaProcessor.computeDelta(
            currentSensorSteps = 1830L,
            lastSensorSteps = firstAfterResume.newBaselineSensorSteps,
            isPaused = false,
            currentBootCount = 4,
            lastBootCount = 4,
            pendingResumeBaseline = firstAfterResume.newPendingResumeBaseline
        )
        assertEquals(30, nextActive.stepsToAdd)
    }

    @Test
    fun `forceBaselineReset sets new baseline with zero delta`() {
        val result = StepDeltaProcessor.computeDelta(
            currentSensorSteps = 25000L,
            lastSensorSteps = 10000L,
            isPaused = false,
            currentBootCount = 5,
            lastBootCount = 5,
            forceBaselineReset = true
        )
        assertEquals(0, result.stepsToAdd)
        assertEquals(25000L, result.newBaselineSensorSteps)
        assertEquals(5, result.newBootCount)
    }

    @Test
    fun `when paused stepsToAdd is zero but baseline advances so paused steps are never counted on resume`() {
        val pausedEvent = StepDeltaProcessor.computeDelta(
            currentSensorSteps = 1500L,
            lastSensorSteps = 1000L,
            isPaused = true,
            currentBootCount = 3,
            lastBootCount = 3
        )
        assertEquals(0, pausedEvent.stepsToAdd)
        assertEquals(1500L, pausedEvent.newBaselineSensorSteps)

        val resumedEvent = StepDeltaProcessor.computeDelta(
            currentSensorSteps = 1525L,
            lastSensorSteps = pausedEvent.newBaselineSensorSteps,
            isPaused = false,
            currentBootCount = 3,
            lastBootCount = 3
        )
        assertEquals(25, resumedEvent.stepsToAdd)
        assertEquals(1525L, resumedEvent.newBaselineSensorSteps)
    }

    @Test
    fun `edge case negative currentSensorSteps returns zero delta and preserves lastSensorSteps`() {
        val result = StepDeltaProcessor.computeDelta(
            currentSensorSteps = -5L,
            lastSensorSteps = 1000L,
            isPaused = false,
            currentBootCount = 2,
            lastBootCount = 2
        )
        assertEquals(0, result.stepsToAdd)
        assertEquals(1000L, result.newBaselineSensorSteps)

        val pausedResult = StepDeltaProcessor.computeDelta(
            currentSensorSteps = -1L,
            lastSensorSteps = 2000L,
            isPaused = true
        )
        assertEquals(0, pausedResult.stepsToAdd)
        assertEquals(2000L, pausedResult.newBaselineSensorSteps)
    }

    @Test
    fun `huge delta exceeding Int MAX_VALUE is safely coerced to Int MAX_VALUE`() {
        val result = StepDeltaProcessor.computeDelta(
            currentSensorSteps = Long.MAX_VALUE,
            lastSensorSteps = 0L,
            isPaused = false
        )
        assertEquals(Int.MAX_VALUE, result.stepsToAdd)
        assertEquals(Long.MAX_VALUE, result.newBaselineSensorSteps)
    }
}
