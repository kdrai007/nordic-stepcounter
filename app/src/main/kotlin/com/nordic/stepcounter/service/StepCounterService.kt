package com.nordic.stepcounter.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nordic.stepcounter.MainActivity
import com.nordic.stepcounter.data.local.StepDatabase
import com.nordic.stepcounter.data.preferences.ProfilePreferences
import com.nordic.stepcounter.data.repository.StepRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

class StepCounterService : Service() {

    companion object {
        const val CHANNEL_ID = "step_counter_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.nordic.stepcounter.ACTION_START"
        const val ACTION_PAUSE = "com.nordic.stepcounter.ACTION_PAUSE"
        const val ACTION_RESUME = "com.nordic.stepcounter.ACTION_RESUME"
        const val ACTION_STOP = "com.nordic.stepcounter.ACTION_STOP"
        const val ACTION_TOGGLE_PAUSE = "com.nordic.stepcounter.ACTION_TOGGLE_PAUSE"

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
    }

    internal var serviceScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal lateinit var repository: StepRepository
    internal lateinit var sensorManager: StepSensorManager
    private var isForegroundStarted = false

    override fun onCreate() {
        super.onCreate()
        if (!hasActivityRecognitionPermission(this)) {
            stopSelf()
            return
        }

        val db = StepDatabase.getInstance(this)
        val prefs = ProfilePreferences.getInstance(this)
        repository = StepRepository(db.stepDao(), prefs)

        createNotificationChannel()

        sensorManager = StepSensorManager(this) { rawSteps ->
            serviceScope.launch {
                val updatedDaily = repository.processHardwareSensorReading(rawSteps)
                val profile = repository.userProfileFlow.first()
                updateNotification(updatedDaily.steps, updatedDaily.goalSteps, profile.isPaused)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasActivityRecognitionPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_STOP -> {
                handleStop()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                serviceScope.launch {
                    repository.setPaused(true)
                    updateNotificationForCurrentState()
                }
            }
            ACTION_RESUME -> {
                serviceScope.launch {
                    repository.setPaused(false)
                    updateNotificationForCurrentState()
                }
            }
            ACTION_TOGGLE_PAUSE -> {
                serviceScope.launch {
                    val profile = repository.userProfileFlow.first()
                    val newPaused = !profile.isPaused
                    repository.setPaused(newPaused)
                    updateNotificationForCurrentState()
                }
            }
            ACTION_START, null -> {
                startTracking()
            }
            else -> {
                startTracking()
            }
        }

        return START_STICKY
    }

    private fun startTracking() {
        if (!isForegroundStarted) {
            val initialNotification = buildNotification(steps = 0, goal = 6000, isPaused = false)
            promoteToForeground(initialNotification)
            isForegroundStarted = true
        }

        sensorManager.startListening()

        serviceScope.launch {
            repository.initializeTodayRecord()
            updateNotificationForCurrentState()
        }
    }

    private fun handleStop() {
        if (::sensorManager.isInitialized) {
            sensorManager.stopListening()
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun promoteToForeground(notification: Notification) {
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            serviceType
        )
    }

    internal suspend fun updateNotificationForCurrentState() {
        val today = LocalDate.now().toString()
        val todayRecord = repository.getDailyRecordDirect(today)
        val profile = repository.userProfileFlow.first()
        val steps = todayRecord?.steps ?: 0
        val goal = profile.dailyGoal
        val isPaused = profile.isPaused
        updateNotification(steps, goal, isPaused)
    }

    internal fun updateNotification(steps: Int, goal: Int, isPaused: Boolean) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, buildNotification(steps, goal, isPaused))
    }

    internal fun buildNotification(steps: Int, goal: Int, isPaused: Boolean): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val actionIntent = Intent(this, StepCounterService::class.java).apply {
            action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
        }
        val actionPendingIntent = PendingIntent.getService(
            this,
            1,
            actionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val percent = if (goal > 0) ((steps * 100) / goal).coerceAtMost(100) else 0
        val statusText = if (isPaused) "Paused • $percent% of goal" else "$percent% of $goal goal"
        val actionLabel = if (isPaused) "Resume" else "Pause"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle("$steps STEPS")
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
            "Step Tracking Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows live daily step count and goal progress"
            setShowBadge(false)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        if (::sensorManager.isInitialized) {
            sensorManager.stopListening()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
