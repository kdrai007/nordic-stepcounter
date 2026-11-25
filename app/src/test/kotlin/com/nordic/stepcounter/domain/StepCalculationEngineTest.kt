package com.nordic.stepcounter.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StepCalculationEngineTest {

    @Test
    fun testDefaultStrideLengthForMale() {
        val heightCm = 180f
        val strideMeters = StepCalculationEngine.calculateDefaultStrideLengthMeters(heightCm, isMale = true)
        assertEquals(0.747f, strideMeters, 0.001f)
    }

    @Test
    fun testDefaultStrideLengthForFemale() {
        val heightCm = 165f
        val strideMeters = StepCalculationEngine.calculateDefaultStrideLengthMeters(heightCm, isMale = false)
        assertEquals(0.681f, strideMeters, 0.001f)
    }

    @Test
    fun testDistanceCalculationInKm() {
        val distanceKm = StepCalculationEngine.calculateDistanceKm(steps = 10000, strideMeters = 0.75f)
        assertEquals(7.5f, distanceKm, 0.01f)
    }

    @Test
    fun testCaloriesCalculationUsesDistanceAndWeight() {
        // 10,000 steps * 0.75 m = 7.5 km * 70 kg * 0.60 = 315 kcal
        val calories = StepCalculationEngine.calculateCaloriesKcal(
            steps = 10000,
            weightKg = 70f,
            strideMeters = 0.75f
        )
        assertEquals(315f, calories, 0.1f)

        // Longer stride covers more distance and burns proportionally more calories
        val longerStrideCalories = StepCalculationEngine.calculateCaloriesKcal(
            steps = 10000,
            weightKg = 70f,
            strideMeters = 0.90f
        )
        assertTrue(longerStrideCalories > calories)
        assertEquals(378f, longerStrideCalories, 0.1f)
    }

    @Test
    fun testActiveTimeEstimationMinutesAndSeconds() {
        assertEquals(60, StepCalculationEngine.calculateActiveMinutes(steps = 6000))
        assertEquals(3600L, StepCalculationEngine.calculateActiveSeconds(steps = 6000))
        // Sub-minute accuracy: 199 steps * 60 / 100 = 119s
        assertEquals(119L, StepCalculationEngine.calculateActiveSeconds(steps = 199))
    }

    @Test
    fun testEdgeCasesZeroOrNegativeInputs() {
        assertEquals(0f, StepCalculationEngine.calculateDefaultStrideLengthMeters(0f, isMale = true), 0.0001f)
        assertEquals(0f, StepCalculationEngine.calculateDefaultStrideLengthMeters(-10f, isMale = false), 0.0001f)

        assertEquals(0f, StepCalculationEngine.calculateDistanceKm(steps = 0, strideMeters = 0.75f), 0.0001f)
        assertEquals(0f, StepCalculationEngine.calculateDistanceKm(steps = -100, strideMeters = 0.75f), 0.0001f)
        assertEquals(0f, StepCalculationEngine.calculateDistanceKm(steps = 1000, strideMeters = 0f), 0.0001f)
        assertEquals(0f, StepCalculationEngine.calculateDistanceKm(steps = 1000, strideMeters = -0.5f), 0.0001f)

        assertEquals(0f, StepCalculationEngine.calculateCaloriesKcal(steps = 0, weightKg = 70f, strideMeters = 0.75f), 0.0001f)
        assertEquals(0f, StepCalculationEngine.calculateCaloriesKcal(steps = -500, weightKg = 70f, strideMeters = 0.75f), 0.0001f)
        assertEquals(0f, StepCalculationEngine.calculateCaloriesKcal(steps = 1000, weightKg = 0f, strideMeters = 0.75f), 0.0001f)
        assertEquals(0f, StepCalculationEngine.calculateCaloriesKcal(steps = 1000, weightKg = -70f, strideMeters = 0.75f), 0.0001f)
        assertEquals(0f, StepCalculationEngine.calculateCaloriesKcal(steps = 1000, weightKg = 70f, strideMeters = 0f), 0.0001f)

        assertEquals(0, StepCalculationEngine.calculateActiveMinutes(steps = 0))
        assertEquals(0, StepCalculationEngine.calculateActiveMinutes(steps = -100))
        assertEquals(0L, StepCalculationEngine.calculateActiveSeconds(steps = 0))
        assertEquals(0L, StepCalculationEngine.calculateActiveSeconds(steps = -100))

        assertEquals(0f, StepCalculationEngine.calculateDefaultStrideLengthMeters(Float.NaN, isMale = true), 0.0001f)
        assertEquals(0f, StepCalculationEngine.calculateDefaultStrideLengthMeters(Float.POSITIVE_INFINITY, isMale = true), 0.0001f)
        assertEquals(0f, StepCalculationEngine.calculateDistanceKm(steps = 1000, strideMeters = Float.NaN), 0.0001f)
        assertEquals(0f, StepCalculationEngine.calculateCaloriesKcal(steps = 1000, weightKg = Float.POSITIVE_INFINITY, strideMeters = 0.75f), 0.0001f)
        assertEquals(0f, StepCalculationEngine.calculateCaloriesKcal(steps = 1000, weightKg = 70f, strideMeters = Float.NaN), 0.0001f)
    }
}
