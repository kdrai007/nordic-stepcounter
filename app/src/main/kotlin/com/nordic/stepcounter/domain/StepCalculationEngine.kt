package com.nordic.stepcounter.domain

/**
 * Pure domain calculation engine for physical fitness metrics:
 * Stride length, distance, active calories, and active duration.
 */
object StepCalculationEngine {
    private const val MALE_STRIDE_RATIO = 0.415f
    private const val FEMALE_STRIDE_RATIO = 0.413f
    private const val CALORIE_MET_FACTOR = 0.00075f
    private const val ESTIMATED_STEPS_PER_MINUTE = 100

    fun calculateDefaultStrideLengthMeters(heightCm: Float, isMale: Boolean): Float {
        if (!heightCm.isFinite() || heightCm <= 0f) return 0f
        val ratio = if (isMale) MALE_STRIDE_RATIO else FEMALE_STRIDE_RATIO
        return (heightCm * ratio) / 100f
    }

    fun calculateDistanceKm(steps: Int, strideMeters: Float): Float {
        if (steps <= 0 || !strideMeters.isFinite() || strideMeters <= 0f) return 0f
        return (steps * strideMeters) / 1000f
    }

    fun calculateCaloriesKcal(steps: Int, weightKg: Float): Float {
        if (steps <= 0 || !weightKg.isFinite() || weightKg <= 0f) return 0f
        return steps * weightKg * CALORIE_MET_FACTOR
    }

    fun calculateActiveMinutes(steps: Int): Int {
        if (steps <= 0) return 0
        return steps / ESTIMATED_STEPS_PER_MINUTE
    }
}
