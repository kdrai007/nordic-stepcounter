package com.nordic.stepcounter.domain

data class StepDeltaResult(
    val stepsToAdd: Int,
    val newBaselineSensorSteps: Long,
    val newBootCount: Int = -1,
    val newPendingResumeBaseline: Boolean = false
)

/**
 * Resolves cumulative Sensor.TYPE_STEP_COUNTER values into valid step increments,
 * handling first initialization (-1L), authoritative OS boot-count reboots,
 * stale/out-of-order regressions, pause states, and post-resume baseline sync.
 */
object StepDeltaProcessor {

    private const val SENSOR_HUB_RESET_HIGH_WATERMARK = 1_000L
    private const val SENSOR_HUB_RESET_LOW_THRESHOLD = 50L

    fun computeDelta(
        currentSensorSteps: Long,
        lastSensorSteps: Long,
        isPaused: Boolean,
        currentBootCount: Int = -1,
        lastBootCount: Int = -1,
        pendingResumeBaseline: Boolean = false,
        forceBaselineReset: Boolean = false
    ): StepDeltaResult {
        if (currentSensorSteps < 0L) {
            return StepDeltaResult(
                stepsToAdd = 0,
                newBaselineSensorSteps = lastSensorSteps,
                newBootCount = lastBootCount,
                newPendingResumeBaseline = pendingResumeBaseline
            )
        }

        val resolvedBootCount = if (currentBootCount >= 0) currentBootCount else lastBootCount

        // First sensor reading ever recorded or explicit baseline reset (e.g., cross-device restore / multi-day gap)
        if (forceBaselineReset || lastSensorSteps < 0L) {
            return StepDeltaResult(
                stepsToAdd = 0,
                newBaselineSensorSteps = currentSensorSteps,
                newBootCount = resolvedBootCount,
                newPendingResumeBaseline = false
            )
        }

        // While paused, advance baseline so steps taken during pause are discarded
        if (isPaused) {
            return StepDeltaResult(
                stepsToAdd = 0,
                newBaselineSensorSteps = currentSensorSteps,
                newBootCount = resolvedBootCount,
                newPendingResumeBaseline = false
            )
        }

        // Cold-start resume sync: absorb the first sensor reading after resuming as the new baseline
        if (pendingResumeBaseline) {
            return StepDeltaResult(
                stepsToAdd = 0,
                newBaselineSensorSteps = currentSensorSteps,
                newBootCount = resolvedBootCount,
                newPendingResumeBaseline = false
            )
        }

        // Authoritative boot count comparison when both current and stored boot counts are valid
        if (currentBootCount >= 0 && lastBootCount >= 0) {
            if (currentBootCount != lastBootCount) {
                // Real device reboot occurred since last reading
                return StepDeltaResult(
                    stepsToAdd = currentSensorSteps.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                    newBaselineSensorSteps = currentSensorSteps,
                    newBootCount = currentBootCount,
                    newPendingResumeBaseline = false
                )
            }

            // Same boot session
            if (currentSensorSteps >= lastSensorSteps) {
                val rawDelta = currentSensorSteps - lastSensorSteps
                return StepDeltaResult(
                    stepsToAdd = rawDelta.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                    newBaselineSensorSteps = currentSensorSteps,
                    newBootCount = currentBootCount,
                    newPendingResumeBaseline = false
                )
            }

            // Regression within the same boot session: either stale reading or rare sensor-hub crash reset near 0
            val isLikelySensorHubReset =
                lastSensorSteps >= SENSOR_HUB_RESET_HIGH_WATERMARK &&
                    currentSensorSteps <= SENSOR_HUB_RESET_LOW_THRESHOLD
            val nextBaseline = if (isLikelySensorHubReset) currentSensorSteps else lastSensorSteps
            return StepDeltaResult(
                stepsToAdd = 0,
                newBaselineSensorSteps = nextBaseline,
                newBootCount = currentBootCount,
                newPendingResumeBaseline = false
            )
        }

        // Fallback when boot count is unavailable (legacy data or test environments)
        val rawDelta = if (currentSensorSteps < lastSensorSteps) {
            currentSensorSteps
        } else {
            currentSensorSteps - lastSensorSteps
        }

        return StepDeltaResult(
            stepsToAdd = rawDelta.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            newBaselineSensorSteps = currentSensorSteps,
            newBootCount = resolvedBootCount,
            newPendingResumeBaseline = false
        )
    }
}
