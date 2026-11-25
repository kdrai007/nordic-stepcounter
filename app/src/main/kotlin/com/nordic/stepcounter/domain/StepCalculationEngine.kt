package com.nordic.stepcounter.domain

/**
 * Pure domain calculation engine for physical fitness metrics:
 * Stride length, distance, net walking calories, and active duration.
 */
object StepCalculationEngine {
    private const val MALE_STRIDE_RATIO = 0.415f
    private const val FEMALE_STRIDE_RATIO = 0.413f
    private const val NET_WALKING_KCAL_PER_KG_PER_KM = 0.60f
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

    /**
     * Estimates net active walking expenditure in kcal from distance covered and body mass.
     */
    fun calculateCaloriesKcal(steps: Int, weightKg: Float, strideMeters: Float): Float {
        if (steps <= 0 || !weightKg.isFinite() || weightKg <= 0f) return 0f
        val distanceKm = calculateDistanceKm(steps, strideMeters)
        if (distanceKm <= 0f) return 0f
        return distanceKm * weightKg * NET_WALKING_KCAL_PER_KG_PER_KM
    }

    fun calculateActiveSeconds(steps: Int): Long {
        if (steps <= 0) return 0L
        return (steps.toLong() * 60L) / ESTIMATED_STEPS_PER_MINUTE
    }

    fun calculateActiveMinutes(steps: Int): Int {
        if (steps <= 0) return 0
        return steps / ESTIMATED_STEPS_PER_MINUTE
    }
}
