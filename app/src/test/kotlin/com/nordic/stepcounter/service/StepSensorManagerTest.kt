package com.nordic.stepcounter.service

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.async
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

    private fun createSensorEvent(
        sensorType: Int,
        value: Float,
        timestampNanos: Long = 123_456_789L
    ): SensorEvent {
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

        val timestampField: Field = SensorEvent::class.java.getField("timestamp")
        timestampField.isAccessible = true
        timestampField.setLong(event, timestampNanos)

        val mockSensor = mockk<Sensor>()
        every { mockSensor.type } returns sensorType

        val sensorField: Field = SensorEvent::class.java.getField("sensor")
        sensorField.isAccessible = true
        sensorField.set(event, mockSensor)

        return event
    }

    private fun mockStepSensor(fifoMaxEventCount: Int = 3000): Sensor {
        val sensor = mockk<Sensor>()
        every { sensor.type } returns Sensor.TYPE_STEP_COUNTER
        every { sensor.fifoMaxEventCount } returns fifoMaxEventCount
        return sensor
    }

    @Test
    fun startListening_registersWithBatchLatencyAndFlushesImmediately() {
        val mockSensorManager = mockk<SensorManager>(relaxed = true)
        val mockSensor = mockStepSensor(fifoMaxEventCount = 3000)
        every { mockSensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) } returns mockSensor
        every {
            mockSensorManager.registerListener(any(), mockSensor, SensorManager.SENSOR_DELAY_NORMAL, 60_000_000)
        } returns true

        val manager = StepSensorManager(mockSensorManager)
        assertTrue(manager.isSensorAvailable)
        assertTrue(manager.startListening())

        verify(exactly = 1) {
            mockSensorManager.registerListener(manager, mockSensor, SensorManager.SENSOR_DELAY_NORMAL, 60_000_000)
        }
        verify(exactly = 1) { mockSensorManager.flush(manager) }
    }

    @Test
    fun setForegroundMode_switchesBetweenZeroLatencyAndBatching() {
        val mockSensorManager = mockk<SensorManager>(relaxed = true)
        val mockSensor = mockStepSensor(fifoMaxEventCount = 3000)
        every { mockSensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) } returns mockSensor
        every {
            mockSensorManager.registerListener(any(), mockSensor, SensorManager.SENSOR_DELAY_NORMAL, any<Int>())
        } returns true

        val manager = StepSensorManager(mockSensorManager)
        assertTrue(manager.startListening(foregroundMode = false))
        assertTrue(manager.setForegroundMode(true))
        assertTrue(manager.setForegroundMode(false))

        verify(exactly = 2) {
            mockSensorManager.registerListener(manager, mockSensor, SensorManager.SENSOR_DELAY_NORMAL, 60_000_000)
        }
        verify(exactly = 1) {
            mockSensorManager.registerListener(manager, mockSensor, SensorManager.SENSOR_DELAY_NORMAL, 0)
        }
        verify(exactly = 2) { mockSensorManager.unregisterListener(manager) }
    }

    @Test
    fun startListening_usesZeroLatencyInBackgroundWhenSensorHasNoFifo() {
        val mockSensorManager = mockk<SensorManager>(relaxed = true)
        val mockSensor = mockStepSensor(fifoMaxEventCount = 0)
        every { mockSensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) } returns mockSensor
        every {
            mockSensorManager.registerListener(any(), mockSensor, SensorManager.SENSOR_DELAY_NORMAL, 0)
        } returns true

        val manager = StepSensorManager(mockSensorManager)
        assertTrue(manager.startListening(foregroundMode = false))

        verify(exactly = 1) {
            mockSensorManager.registerListener(manager, mockSensor, SensorManager.SENSOR_DELAY_NORMAL, 0)
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
    fun onSensorChanged_roundsAndPassesReadingWithTimestampForStepCounter() {
        val mockSensorManager = mockk<SensorManager>(relaxed = true)
        var received: StepSensorReading? = null

        val manager = StepSensorManager(mockSensorManager) { reading ->
            received = reading
        }

        val event = createSensorEvent(Sensor.TYPE_STEP_COUNTER, 45200.6f, timestampNanos = 999_888_777L)
        manager.onSensorChanged(event)

        assertEquals(45201L, received?.totalStepsSinceBoot)
        assertEquals(999_888_777L, received?.timestampNanos)
    }

    @Test
    fun onSensorChanged_ignoresNaNInfinityNegativeAndOtherSensors() {
        val mockSensorManager = mockk<SensorManager>(relaxed = true)
        val received = mutableListOf<StepSensorReading>()

        val manager = StepSensorManager(mockSensorManager) { reading ->
            received.add(reading)
        }

        manager.onSensorChanged(createSensorEvent(Sensor.TYPE_STEP_COUNTER, Float.NaN))
        manager.onSensorChanged(createSensorEvent(Sensor.TYPE_STEP_COUNTER, Float.POSITIVE_INFINITY))
        manager.onSensorChanged(createSensorEvent(Sensor.TYPE_STEP_COUNTER, Float.NEGATIVE_INFINITY))
        manager.onSensorChanged(createSensorEvent(Sensor.TYPE_STEP_COUNTER, -5f))
        manager.onSensorChanged(createSensorEvent(Sensor.TYPE_ACCELEROMETER, 9.8f))

        assertTrue(received.isEmpty())
    }

    @Test
    fun startListening_preventsDuplicateListenerRegistrationsInSameMode() {
        val mockSensorManager = mockk<SensorManager>(relaxed = true)
        val mockSensor = mockStepSensor()
        every { mockSensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) } returns mockSensor
        every {
            mockSensorManager.registerListener(any(), mockSensor, SensorManager.SENSOR_DELAY_NORMAL, 60_000_000)
        } returns true

        val manager = StepSensorManager(mockSensorManager)
        assertTrue(manager.startListening())
        assertTrue(manager.startListening())

        verify(exactly = 1) {
            mockSensorManager.registerListener(manager, mockSensor, SensorManager.SENSOR_DELAY_NORMAL, 60_000_000)
        }
    }

    @Test
    fun flushAndWait_returnsTrueOnCompletionAndFalseOnTimeoutOrStopListening() = runTest {
        val mockSensorManager = mockk<SensorManager>(relaxed = true)
        val mockSensor = mockk<Sensor>()
        every { mockSensorManager.flush(any()) } answers {
            val listener = firstArg<StepSensorManager>()
            listener.onFlushCompleted(mockSensor)
            true
        }

        val manager = StepSensorManager(mockSensorManager)
        assertTrue(manager.flushAndWait(timeoutMs = 100L))

        // Timeout case returns false
        every { mockSensorManager.flush(any()) } returns true
        assertFalse(manager.flushAndWait(timeoutMs = 30L))

        // Flush not initiated returns false immediately
        every { mockSensorManager.flush(any()) } returns false
        assertFalse(manager.flushAndWait(timeoutMs = 100L))

        // stopListening interrupts pending flushAndWait immediately
        every { mockSensorManager.flush(any()) } answers {
            true
        }
        val deferred = async { manager.flushAndWait(timeoutMs = 5_000L) }
        kotlinx.coroutines.yield()
        manager.stopListening()
        assertFalse(deferred.await())
    }
}
