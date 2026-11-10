package com.nordic.stepcounter.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class StepDeltaProcessorTest {

    @Test
    fun `first sensor reading initializes baseline with zero delta`() {
        val result = StepDeltaProcessor.computeDelta(
            currentSensorSteps = 45000L,
            lastSensorSteps = -1L,
            isPaused = false
        )
        assertEquals(0, result.stepsToAdd)
        assertEquals(45000L, result.newBaselineSensorSteps)
    }

    @Test
    fun `normal sensor increment returns positive delta and updates baseline`() {
        val result = StepDeltaProcessor.computeDelta(
            currentSensorSteps = 45120L,
            lastSensorSteps = 45000L,
            isPaused = false
        )
        assertEquals(120, result.stepsToAdd)
        assertEquals(45120L, result.newBaselineSensorSteps)
    }

    @Test
    fun `device reboot where current is less than last resets baseline and counts current`() {
        val result = StepDeltaProcessor.computeDelta(
            currentSensorSteps = 35L,
            lastSensorSteps = 45120L,
            isPaused = false
        )
        assertEquals(35, result.stepsToAdd)
        assertEquals(35L, result.newBaselineSensorSteps)
    }

    @Test
    fun `when paused stepsToAdd is zero but baseline advances so paused steps are never counted on resume`() {
        // User pauses at 1000, walks 500 steps while in transit
        val pausedEvent = StepDeltaProcessor.computeDelta(
            currentSensorSteps = 1500L,
            lastSensorSteps = 1000L,
            isPaused = true
        )
        assertEquals(0, pausedEvent.stepsToAdd)
        assertEquals(1500L, pausedEvent.newBaselineSensorSteps)

        // User unpauses and takes 25 real steps
        val resumedEvent = StepDeltaProcessor.computeDelta(
            currentSensorSteps = 1525L,
            lastSensorSteps = pausedEvent.newBaselineSensorSteps,
            isPaused = false
        )
        assertEquals(25, resumedEvent.stepsToAdd)
        assertEquals(1525L, resumedEvent.newBaselineSensorSteps)
    }

    @Test
    fun `edge case negative currentSensorSteps returns zero delta and preserves lastSensorSteps`() {
        val result = StepDeltaProcessor.computeDelta(
            currentSensorSteps = -5L,
            lastSensorSteps = 1000L,
            isPaused = false
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
}
