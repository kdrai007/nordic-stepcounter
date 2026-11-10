package com.nordic.stepcounter.service

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Field

@RunWith(RobolectricTestRunner::class)
class StepSensorManagerTest {

    private fun createSensorEvent(sensorType: Int, value: Float): SensorEvent {
        val constructor = SensorEvent::class.java.declaredConstructors.first()
        constructor.isAccessible = true
        val params = constructor.parameterTypes
        val args = params.map { type ->
            when (type) {
                Int::class.javaPrimitiveType -> 16
                Long::class.javaPrimitiveType -> 0L
                FloatArray::class.java -> FloatArray(16)
                Sensor::class.java -> mockk<Sensor>(relaxed = true)
                else -> null
            }
        }.toTypedArray()
        val event = constructor.newInstance(*args) as SensorEvent
        val valuesField: Field = SensorEvent::class.java.getField("values")
        valuesField.isAccessible = true
        valuesField.set(event, floatArrayOf(value))

        val mockSensor = mockk<Sensor>()
        every { mockSensor.type } returns sensorType

        val sensorField: Field = SensorEvent::class.java.getField("sensor")
        sensorField.isAccessible = true
        sensorField.set(event, mockSensor)

        return event
    }

    @Test
    fun startListening_registersListenerWithBatchLatencyWhenSensorExists() {
        val mockSensorManager = mockk<SensorManager>(relaxed = true)
        val mockSensor = mockk<Sensor>()
        every { mockSensor.type } returns Sensor.TYPE_STEP_COUNTER
        every { mockSensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) } returns mockSensor
        every {
            mockSensorManager.registerListener(
                any(),
                mockSensor,
                SensorManager.SENSOR_DELAY_NORMAL,
                60_000_000
            )
        } returns true

        val manager = StepSensorManager(mockSensorManager)
        assertTrue(manager.isSensorAvailable)

        val success = manager.startListening()
        assertTrue(success)

        verify(exactly = 1) {
            mockSensorManager.registerListener(
                manager,
                mockSensor,
                SensorManager.SENSOR_DELAY_NORMAL,
                60_000_000
            )
        }
    }

    @Test
    fun startListening_returnsFalseWhenSensorMissing() {
        val mockSensorManager = mockk<SensorManager>(relaxed = true)
        every { mockSensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) } returns null

        val manager = StepSensorManager(mockSensorManager)
        assertFalse(manager.isSensorAvailable)
        assertFalse(manager.startListening())
    }

    @Test
    fun stopListening_unregistersListener() {
        val mockSensorManager = mockk<SensorManager>(relaxed = true)
        val manager = StepSensorManager(mockSensorManager)
        manager.stopListening()

        verify(exactly = 1) {
            mockSensorManager.unregisterListener(manager)
        }
    }

    @Test
    fun onSensorChanged_invokesCallbackAndEmitsFlowForStepCounter() = runTest {
        val mockSensorManager = mockk<SensorManager>(relaxed = true)
        var receivedSteps = 0L

        val manager = StepSensorManager(mockSensorManager) { steps ->
            receivedSteps = steps
        }

        val event = createSensorEvent(Sensor.TYPE_STEP_COUNTER, 45200f)
        manager.onSensorChanged(event)

        assertEquals(45200L, receivedSteps)
        assertEquals(45200L, manager.stepEvents.first())
    }

    @Test
    fun onSensorChanged_ignoresOtherSensorTypes() = runTest {
        val mockSensorManager = mockk<SensorManager>(relaxed = true)
        var receivedSteps = -1L

        val manager = StepSensorManager(mockSensorManager) { steps ->
            receivedSteps = steps
        }

        val event = createSensorEvent(Sensor.TYPE_ACCELEROMETER, 9.8f)
        manager.onSensorChanged(event)

        assertEquals(-1L, receivedSteps)
    }
}
