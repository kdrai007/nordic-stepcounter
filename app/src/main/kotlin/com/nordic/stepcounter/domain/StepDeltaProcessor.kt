package com.nordic.stepcounter.domain

data class StepDeltaResult(
    val stepsToAdd: Int,
    val newBaselineSensorSteps: Long
)

/**
 * Resolves cumulative Sensor.TYPE_STEP_COUNTER values into valid step increments,
 * handling first initialization (-1L), device reboots, and pause states.
 */
object StepDeltaProcessor {

    fun computeDelta(
        currentSensorSteps: Long,
        lastSensorSteps: Long,
        isPaused: Boolean
    ): StepDeltaResult {
        if (currentSensorSteps < 0L) {
            return StepDeltaResult(
                stepsToAdd = 0,
                newBaselineSensorSteps = lastSensorSteps
            )
        }

        // First sensor reading ever recorded
        if (lastSensorSteps < 0L) {
            return StepDeltaResult(
                stepsToAdd = 0,
                newBaselineSensorSteps = currentSensorSteps
            )
        }

        // While paused, advance baseline so steps taken during pause are discarded
        if (isPaused) {
            return StepDeltaResult(
                stepsToAdd = 0,
                newBaselineSensorSteps = currentSensorSteps
            )
        }

        // Device rebooted since last reading (hardware counter reset to 0)
        val rawDelta = if (currentSensorSteps < lastSensorSteps) {
            currentSensorSteps
        } else {
            currentSensorSteps - lastSensorSteps
        }

        return StepDeltaResult(
            stepsToAdd = rawDelta.coerceAtLeast(0L).toInt(),
            newBaselineSensorSteps = currentSensorSteps
        )
    }
}
