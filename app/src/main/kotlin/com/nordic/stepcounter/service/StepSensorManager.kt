package com.nordic.stepcounter.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToLong

data class StepSensorReading(
    val totalStepsSinceBoot: Long,
    val timestampNanos: Long = 0L
)

/**
 * Low-power hardware step counter manager.
 * Exclusively uses [Sensor.TYPE_STEP_COUNTER] (sensor type 19), switching between
 * immediate delivery while the UI is visible and 60-second hardware FIFO batching in background.
 */
class StepSensorManager(
    private val sensorManager: SensorManager?,
    private val onStepCountChanged: ((StepSensorReading) -> Unit)? = null
) : SensorEventListener2 {

    constructor(
        context: Context,
        onStepCountChanged: ((StepSensorReading) -> Unit)? = null
    ) : this(
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager,
        onStepCountChanged
    )

    private val stepCounterSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    val isSensorAvailable: Boolean
        get() = stepCounterSensor != null

    @Volatile
    private var isListening = false
    @Volatile
    private var isForegroundMode = false
    private val flushMutex = Mutex()
    @Volatile
    private var flushDeferred: CompletableDeferred<Boolean>? = null

    private fun resolveReportLatencyUs(sensor: Sensor, foregroundMode: Boolean): Int {
        if (foregroundMode) return 0
        val supportsBatching = runCatching { sensor.fifoMaxEventCount > 0 }.getOrDefault(true)
        return if (supportsBatching) BATCH_LATENCY_US else 0
    }

    /**
     * Registers the hardware step counter listener and immediately triggers a FIFO flush
     * so an initial baseline reading is delivered without waiting for a full batch cycle.
     */
    @Synchronized
    fun startListening(foregroundMode: Boolean = isForegroundMode): Boolean {
        val sensor = stepCounterSensor ?: return false
        if (isListening && isForegroundMode == foregroundMode) return true
        if (isListening) {
            sensorManager?.unregisterListener(this)
            isListening = false
        }

        val latencyUs = resolveReportLatencyUs(sensor, foregroundMode)
        val success = sensorManager?.registerListener(
            this,
            sensor,
            SensorManager.SENSOR_DELAY_NORMAL,
            latencyUs
        ) ?: false
        if (success) {
            isListening = true
            isForegroundMode = foregroundMode
            sensorManager.flush(this)
        }
        return success
    }

    /**
     * Updates batching latency between foreground interactive mode (0us) and background batching (60s).
     */
    @Synchronized
    fun setForegroundMode(foregroundMode: Boolean): Boolean {
        if (!isListening) {
            isForegroundMode = foregroundMode
            return false
        }
        if (isForegroundMode == foregroundMode) {
            if (foregroundMode) {
                flush()
            }
            return true
        }
        return startListening(foregroundMode)
    }

    /**
     * Unregisters the sensor listener and completes any pending flush waiter cleanly.
     */
    @Synchronized
    fun stopListening() {
        sensorManager?.unregisterListener(this)
        isListening = false
        flushDeferred?.complete(false)
        flushDeferred = null
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
     * or [timeoutMs] elapses. Returns true only when flush actually completed before timing out.
     */
    suspend fun flushAndWait(timeoutMs: Long = 500L): Boolean = flushMutex.withLock {
        val deferred = CompletableDeferred<Boolean>()
        flushDeferred = deferred
        try {
            val initiated = sensorManager?.flush(this) ?: false
            if (!initiated) return@withLock false
            withTimeoutOrNull(timeoutMs) {
                deferred.await()
            } ?: false
        } finally {
            if (flushDeferred === deferred) {
                flushDeferred = null
            }
        }
    }

    override fun onFlushCompleted(sensor: Sensor?) {
        flushDeferred?.complete(true)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        if (event.sensor?.type == Sensor.TYPE_STEP_COUNTER) {
            val rawValue = event.values.getOrNull(0) ?: return
            if (!rawValue.isFinite() || rawValue < 0f) return
            val totalStepsSinceBoot = rawValue.roundToLong()
            if (totalStepsSinceBoot < 0L) return
            onStepCountChanged?.invoke(
                StepSensorReading(
                    totalStepsSinceBoot = totalStepsSinceBoot,
                    timestampNanos = event.timestamp
                )
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Step counter accuracy does not affect cumulative counting
    }

    companion object {
        const val BATCH_LATENCY_US = 60_000_000 // 60 seconds hardware batching latency
    }
}
