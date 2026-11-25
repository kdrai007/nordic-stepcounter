package com.nordic.stepcounter.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nordic.stepcounter.MainActivity
import com.nordic.stepcounter.R
import com.nordic.stepcounter.data.repository.StepRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale

class StepCounterService : Service() {

    companion object {
        const val CHANNEL_ID = "step_counter_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.nordic.stepcounter.ACTION_START"
        const val ACTION_PAUSE = "com.nordic.stepcounter.ACTION_PAUSE"
        const val ACTION_RESUME = "com.nordic.stepcounter.ACTION_RESUME"
        const val ACTION_STOP = "com.nordic.stepcounter.ACTION_STOP"
        const val ACTION_TOGGLE_PAUSE = "com.nordic.stepcounter.ACTION_TOGGLE_PAUSE"
        const val ACTION_FLUSH = "com.nordic.stepcounter.ACTION_FLUSH"
        const val ACTION_SET_FOREGROUND_MODE = "com.nordic.stepcounter.ACTION_SET_FOREGROUND_MODE"
        const val EXTRA_FOREGROUND_MODE = "extra_foreground_mode"

        fun hasActivityRecognitionPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACTIVITY_RECOGNITION
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        }

        fun hasNotificationPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        }

        fun hasStepCounterSensor(context: Context): Boolean {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
            return sm?.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_COUNTER) != null
        }
    }

    internal sealed interface ServiceCommand {
        data object Initialize : ServiceCommand
        data class SensorReading(val reading: StepSensorReading) : ServiceCommand
        data object Flush : ServiceCommand
        data class SetForegroundMode(val isForeground: Boolean) : ServiceCommand
        data class SetPaused(val isPaused: Boolean) : ServiceCommand
        data object TogglePause : ServiceCommand
        data object DateOrTimeChanged : ServiceCommand
    }

    inner class LocalBinder : Binder() {
        fun flush() {
            commandChannel.trySend(ServiceCommand.Flush)
        }

        fun setUiForeground(isForeground: Boolean) {
            isUiBound = isForeground
            commandChannel.trySend(ServiceCommand.SetForegroundMode(isForeground))
        }
    }

    internal var serviceScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal lateinit var repository: StepRepository
    internal lateinit var sensorManager: StepSensorManager
    internal var currentDateProvider: () -> String = { LocalDate.now().toString() }

    private val localBinder = LocalBinder()
    private val commandChannel = Channel<ServiceCommand>(Channel.UNLIMITED)
    private val deferredCommands = ArrayDeque<ServiceCommand>()
    private var actorJob: Job? = null
    private var profileObserverJob: Job? = null

    @Volatile
    private var isForegroundStarted = false
    @Volatile
    private var isUiBound = false
    @Volatile
    private var hasReceivedReadingSinceListen = false
    private var isDateReceiverRegistered = false

    private val dateChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == Intent.ACTION_DATE_CHANGED ||
                action == Intent.ACTION_TIME_CHANGED ||
                action == Intent.ACTION_TIMEZONE_CHANGED
            ) {
                commandChannel.trySend(ServiceCommand.DateOrTimeChanged)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        if (!hasActivityRecognitionPermission(this)) {
            stopSelf()
            return
        }

        repository = StepRepository.getInstance(this)
        sensorManager = StepSensorManager(this) { reading ->
            commandChannel.trySend(ServiceCommand.SensorReading(reading))
        }

        registerDateChangeReceiver()
        startActorIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (!hasActivityRecognitionPermission(this)) {
            if (!isForegroundStarted) {
                tryPromoteMinimalForegroundForStop()
            }
            handleStop()
            return START_NOT_STICKY
        }

        if (!::repository.isInitialized) {
            repository = StepRepository.getInstance(this)
        }
        if (!::sensorManager.isInitialized) {
            sensorManager = StepSensorManager(this) { reading ->
                commandChannel.trySend(ServiceCommand.SensorReading(reading))
            }
        }
        registerDateChangeReceiver()
        startActorIfNeeded()

        if (action == ACTION_STOP) {
            if (!isForegroundStarted) {
                tryPromoteMinimalForegroundForStop()
            }
            handleStop()
            return START_NOT_STICKY
        }

        val wasAlreadyRunning = isForegroundStarted
        if (!isForegroundStarted) {
            if (!startTracking()) {
                return START_NOT_STICKY
            }
        }

        when (action) {
            ACTION_PAUSE -> commandChannel.trySend(ServiceCommand.SetPaused(true))
            ACTION_RESUME -> commandChannel.trySend(ServiceCommand.SetPaused(false))
            ACTION_TOGGLE_PAUSE -> commandChannel.trySend(ServiceCommand.TogglePause)
            ACTION_FLUSH -> commandChannel.trySend(ServiceCommand.Flush)
            ACTION_SET_FOREGROUND_MODE -> {
                val isForeground = intent.getBooleanExtra(EXTRA_FOREGROUND_MODE, false)
                isUiBound = isForeground
                commandChannel.trySend(ServiceCommand.SetForegroundMode(isForeground))
            }
            else -> {
                if (wasAlreadyRunning) {
                    commandChannel.trySend(ServiceCommand.Flush)
                }
            }
        }

        return START_STICKY
    }

    internal fun enqueueSensorReadingForTest(reading: StepSensorReading) {
        startActorIfNeeded()
        commandChannel.trySend(ServiceCommand.SensorReading(reading))
    }

    internal fun restartActorWithCurrentScope() {
        actorJob?.cancel()
        profileObserverJob?.cancel()
        actorJob = null
        profileObserverJob = null
        startActorIfNeeded()
    }

    private fun startActorIfNeeded() {
        if (actorJob?.isActive == true) return
        actorJob = serviceScope.launch {
            while (true) {
                val command = if (deferredCommands.isNotEmpty()) {
                    deferredCommands.removeFirst()
                } else {
                    commandChannel.receiveCatching().getOrNull() ?: break
                }
                handleCommand(command)
            }
        }
        if (profileObserverJob?.isActive != true) {
            profileObserverJob = serviceScope.launch {
                combine(
                    repository.isPausedFlow,
                    repository.dailyGoalFlow
                ) { isPaused, dailyGoal -> isPaused to dailyGoal }
                    .distinctUntilChanged()
                    .collect {
                        if (isForegroundStarted) {
                            updateNotificationForCurrentState()
                        }
                    }
            }
        }
    }

    private suspend fun handleCommand(command: ServiceCommand) {
        when (command) {
            ServiceCommand.Initialize -> {
                repository.initializeTodayRecord(currentDateProvider())
                updateNotificationForCurrentState()
            }
            is ServiceCommand.SensorReading -> {
                processSingleReading(command.reading)
            }
            ServiceCommand.Flush -> {
                if (::sensorManager.isInitialized) {
                    sensorManager.flushAndWait()
                }
                drainBufferedSensorReadings()
                updateNotificationForCurrentState()
            }
            is ServiceCommand.SetForegroundMode -> {
                if (::sensorManager.isInitialized) {
                    sensorManager.setForegroundMode(command.isForeground)
                }
            }
            is ServiceCommand.SetPaused -> {
                applyPauseStateChange(targetPaused = command.isPaused)
            }
            ServiceCommand.TogglePause -> {
                val currentlyPaused = repository.isPausedFlow.first()
                applyPauseStateChange(targetPaused = !currentlyPaused)
            }
            ServiceCommand.DateOrTimeChanged -> {
                repository.initializeTodayRecord(currentDateProvider())
                updateNotificationForCurrentState()
            }
        }
    }

    private suspend fun processSingleReading(reading: StepSensorReading) {
        hasReceivedReadingSinceListen = true
        val updatedDaily = repository.processHardwareSensorReading(
            rawSensorSteps = reading.totalStepsSinceBoot,
            eventTimestampNanos = reading.timestampNanos
        )
        val isPaused = repository.isPausedFlow.first()
        if (updatedDaily.date == currentDateProvider()) {
            updateNotification(updatedDaily.steps, updatedDaily.goalSteps, isPaused)
        } else {
            updateNotificationForCurrentState()
        }
    }

    private suspend fun drainBufferedSensorReadings() {
        while (true) {
            val buffered = commandChannel.tryReceive().getOrNull() ?: break
            if (buffered is ServiceCommand.SensorReading) {
                processSingleReading(buffered.reading)
            } else {
                deferredCommands.addLast(buffered)
            }
        }
    }

    private suspend fun applyPauseStateChange(targetPaused: Boolean) {
        if (::sensorManager.isInitialized) {
            sensorManager.flushAndWait()
        }
        // Drain any readings delivered by the flush under the previous pause state before flipping isPaused
        drainBufferedSensorReadings()
        val needsBaselineSyncOnNextReading = !targetPaused && !hasReceivedReadingSinceListen
        repository.setPaused(
            isPaused = targetPaused,
            pendingResumeBaseline = needsBaselineSyncOnNextReading
        )
        updateNotificationForCurrentState()
    }

    private fun startTracking(): Boolean {
        if (!isForegroundStarted) {
            val initialNotification = buildNotification(steps = 0, goal = 6000, isPaused = false)
            if (!promoteToForeground(initialNotification)) {
                handleStop()
                return false
            }
            isForegroundStarted = true
        }

        if (!::sensorManager.isInitialized ||
            !sensorManager.isSensorAvailable ||
            !sensorManager.startListening(foregroundMode = isUiBound)
        ) {
            handleStop()
            return false
        }
        hasReceivedReadingSinceListen = false

        commandChannel.trySend(ServiceCommand.Initialize)
        return true
    }

    private fun tryPromoteMinimalForegroundForStop() {
        val notification = buildNotification(steps = 0, goal = 6000, isPaused = false)
        if (promoteToForeground(notification)) {
            isForegroundStarted = true
        }
    }

    private fun handleStop() {
        isForegroundStarted = false
        hasReceivedReadingSinceListen = false
        if (::sensorManager.isInitialized) {
            sensorManager.stopListening()
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        stopSelf()
    }

    private fun promoteToForeground(notification: Notification): Boolean {
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        } else {
            0
        }
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                serviceType
            )
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalStateException) {
            false
        }
    }

    internal suspend fun updateNotificationForCurrentState() {
        if (!isForegroundStarted) return
        val today = currentDateProvider()
        val todayRecord = repository.getDailyRecordDirect(today)
        val profile = repository.userProfileFlow.first()
        val isPaused = repository.isPausedFlow.first()
        val steps = todayRecord?.steps ?: 0
        val goal = profile.dailyGoal
        updateNotification(steps, goal, isPaused)
    }

    internal fun updateNotification(steps: Int, goal: Int, isPaused: Boolean) {
        if (!isForegroundStarted) return
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, buildNotification(steps, goal, isPaused))
    }

    internal fun buildNotification(steps: Int, goal: Int, isPaused: Boolean): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val actionIntent = Intent(this, StepCounterService::class.java).apply {
            action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
        }
        val actionPendingIntent = PendingIntent.getForegroundService(
            this,
            1,
            actionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val percent = if (goal > 0) {
            ((steps.toLong() * 100L) / goal.toLong()).coerceIn(0L, 100L).toInt()
        } else {
            0
        }
        val formattedSteps = String.format(Locale.getDefault(), "%,d", steps)
        val formattedGoal = String.format(Locale.getDefault(), "%,d", goal)
        val statusText = if (isPaused) {
            getString(R.string.notification_status_paused, percent)
        } else {
            getString(R.string.notification_status_active, percent, formattedGoal)
        }
        val actionLabel = getString(
            if (isPaused) R.string.notification_action_resume else R.string.notification_action_pause
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tab_today)
            .setContentTitle(getString(R.string.notification_title_steps, formattedSteps))
            .setContentText(statusText)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openAppIntent)
            .addAction(0, actionLabel, actionPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.createNotificationChannel(channel)
    }

    private fun registerDateChangeReceiver() {
        if (isDateReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(
            this,
            dateChangeReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        isDateReceiverRegistered = true
    }

    private fun unregisterDateChangeReceiver() {
        if (!isDateReceiverRegistered) return
        runCatching { unregisterReceiver(dateChangeReceiver) }
        isDateReceiverRegistered = false
    }

    override fun onBind(intent: Intent?): IBinder {
        isUiBound = true
        commandChannel.trySend(ServiceCommand.SetForegroundMode(true))
        return localBinder
    }

    override fun onRebind(intent: Intent?) {
        isUiBound = true
        commandChannel.trySend(ServiceCommand.SetForegroundMode(true))
        super.onRebind(intent)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isUiBound = false
        commandChannel.trySend(ServiceCommand.SetForegroundMode(false))
        return true
    }

    override fun onDestroy() {
        isForegroundStarted = false
        unregisterDateChangeReceiver()
        if (::sensorManager.isInitialized) {
            sensorManager.stopListening()
        }
        commandChannel.close()
        serviceScope.cancel()
        super.onDestroy()
    }
}
