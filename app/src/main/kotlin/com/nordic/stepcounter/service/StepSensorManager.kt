package com.nordic.stepcounter.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Low-power hardware step counter manager.
 * Exclusively uses [Sensor.TYPE_STEP_COUNTER] (sensor type 19) with 60-second hardware
 * FIFO batching to minimize application and CPU wakeups.
 */
class StepSensorManager(
    private val sensorManager: SensorManager?,
    private val onStepCountChanged: ((Long) -> Unit)? = null
) : SensorEventListener2 {

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

    private var isListening = false
    private val flushMutex = Mutex()
    @Volatile
    private var flushDeferred: CompletableDeferred<Unit>? = null

    /**
     * Registers the hardware step counter listener with 60-second report latency.
     * Returns true if registered successfully, false otherwise.
     */
    fun startListening(): Boolean {
        if (isListening) return true
        val sensor = stepCounterSensor ?: return false
        val success = sensorManager?.registerListener(
            this,
            sensor,
            SensorManager.SENSOR_DELAY_NORMAL,
            BATCH_LATENCY_US
        ) ?: false
        if (success) {
            isListening = true
        }
        return success
    }

    /**
     * Unregisters the sensor listener.
     */
    fun stopListening() {
        sensorManager?.unregisterListener(this)
        isListening = false
    }

    /**
     * Flushes the hardware sensor FIFO buffer to immediately process batched steps.
     * Returns true if flush was initiated, false otherwise.
     */
    fun flush(): Boolean {
        return sensorManager?.flush(this) ?: false
    }

    /**
     * Flushes the hardware sensor FIFO buffer and suspends until [onFlushCompleted] is invoked
     * or [timeoutMs] elapses.
     */
    suspend fun flushAndWait(timeoutMs: Long = 500L): Boolean = flushMutex.withLock {
        val deferred = CompletableDeferred<Unit>()
        flushDeferred = deferred
        try {
            val initiated = sensorManager?.flush(this) ?: false
            if (initiated) {
                withTimeoutOrNull(timeoutMs) {
                    deferred.await()
                }
            }
            initiated
        } finally {
            flushDeferred = null
        }
    }

    override fun onFlushCompleted(sensor: Sensor?) {
        flushDeferred?.complete(Unit)
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
