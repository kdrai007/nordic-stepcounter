package com.nordic.stepcounter.domain

import org.junit.Assert.assertEquals
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
    fun testCaloriesCalculation() {
        val calories = StepCalculationEngine.calculateCaloriesKcal(steps = 8000, weightKg = 70f)
        assertEquals(420f, calories, 0.1f)
    }

    @Test
    fun testActiveTimeEstimation() {
        val minutes = StepCalculationEngine.calculateActiveMinutes(steps = 6000)
        assertEquals(60, minutes)
    }

    @Test
    fun testEdgeCasesZeroOrNegativeInputs() {
        assertEquals(0f, StepCalculationEngine.calculateDefaultStrideLengthMeters(0f, isMale = true), 0.0001f)
        assertEquals(0f, StepCalculationEngine.calculateDefaultStrideLengthMeters(-10f, isMale = false), 0.0001f)

        assertEquals(0f, StepCalculationEngine.calculateDistanceKm(steps = 0, strideMeters = 0.75f), 0.0001f)
        assertEquals(0f, StepCalculationEngine.calculateDistanceKm(steps = -100, strideMeters = 0.75f), 0.0001f)
        assertEquals(0f, StepCalculationEngine.calculateDistanceKm(steps = 1000, strideMeters = 0f), 0.0001f)
        assertEquals(0f, StepCalculationEngine.calculateDistanceKm(steps = 1000, strideMeters = -0.5f), 0.0001f)

        assertEquals(0f, StepCalculationEngine.calculateCaloriesKcal(steps = 0, weightKg = 70f), 0.0001f)
        assertEquals(0f, StepCalculationEngine.calculateCaloriesKcal(steps = -500, weightKg = 70f), 0.0001f)
        assertEquals(0f, StepCalculationEngine.calculateCaloriesKcal(steps = 1000, weightKg = 0f), 0.0001f)
        assertEquals(0f, StepCalculationEngine.calculateCaloriesKcal(steps = 1000, weightKg = -70f), 0.0001f)

        assertEquals(0, StepCalculationEngine.calculateActiveMinutes(steps = 0))
        assertEquals(0, StepCalculationEngine.calculateActiveMinutes(steps = -100))
    }
}
