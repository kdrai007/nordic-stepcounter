package com.nordic.stepcounter.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Low-power hardware step counter manager.
 * Exclusively uses [Sensor.TYPE_STEP_COUNTER] (sensor type 19) with 60-second hardware
 * FIFO batching to minimize application and CPU wakeups.
 */
class StepSensorManager(
    private val sensorManager: SensorManager?,
    private val onStepCountChanged: ((Long) -> Unit)? = null
) : SensorEventListener {

    constructor(
        context: Context,
        onStepCountChanged: ((Long) -> Unit)? = null
    ) : this(
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager,
        onStepCountChanged
    )

    private val stepCounterSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    private val _stepEvents = MutableSharedFlow<Long>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val stepEvents: SharedFlow<Long> = _stepEvents.asSharedFlow()

    val isSensorAvailable: Boolean
        get() = stepCounterSensor != null

    /**
     * Registers the hardware step counter listener with 60-second report latency.
     * Returns true if registered successfully, false otherwise.
     */
    fun startListening(): Boolean {
        val sensor = stepCounterSensor ?: return false
        return sensorManager?.registerListener(
            this,
            sensor,
            SensorManager.SENSOR_DELAY_NORMAL,
            BATCH_LATENCY_US
        ) ?: false
    }

    /**
     * Unregisters the sensor listener.
     */
    fun stopListening() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        if (event.sensor?.type == Sensor.TYPE_STEP_COUNTER) {
            val totalStepsSinceBoot = event.values.getOrNull(0)?.toLong() ?: return
            onStepCountChanged?.invoke(totalStepsSinceBoot)
            _stepEvents.tryEmit(totalStepsSinceBoot)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Step counter accuracy does not affect cumulative counting
    }

    companion object {
        const val BATCH_LATENCY_US = 60_000_000 // 60 seconds hardware batching latency
    }
}
