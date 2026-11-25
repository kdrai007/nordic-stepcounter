package com.nordic.stepcounter.service

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.nordic.stepcounter.data.preferences.ProfilePreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class StepServiceTest {

    private lateinit var application: Application
    private lateinit var preferences: ProfilePreferences
    private var stepSensor: android.hardware.Sensor? = null

    @Before
    fun setUp() = runTest {
        application = ApplicationProvider.getApplicationContext()
        val sensorManager = application.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val shadowSensorManager = shadowOf(sensorManager)
        val sensor = org.robolectric.shadows.ShadowSensor.newInstance(android.hardware.Sensor.TYPE_STEP_COUNTER)
        stepSensor = sensor
        shadowSensorManager.addSensor(sensor)
        preferences = ProfilePreferences(application)
        preferences.clear()
    }

    @After
    fun tearDown() = runTest {
        preferences.clear()
        com.nordic.stepcounter.data.repository.StepRepository.resetInstance()
    }

    @Test
    fun onCreate_stopsSelfIfActivityRecognitionPermissionNotGranted() {
        val shadowApp = shadowOf(application)
        shadowApp.denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION)

        val controller = Robolectric.buildService(StepCounterService::class.java)
        val service = controller.get()
        controller.create()

        val shadowService = shadowOf(service)
        assertTrue(shadowService.isStoppedBySelf)
    }

    @Test
    fun onStartCommand_stopsSelfAndReturnsNotStickyIfPermissionMissing() {
        val shadowApp = shadowOf(application)
        shadowApp.denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION)

        val controller = Robolectric.buildService(StepCounterService::class.java)
        controller.create()
        val service = controller.get()

        val startCommandResult = service.onStartCommand(
            Intent(application, StepCounterService::class.java).apply {
                action = StepCounterService.ACTION_START
            },
            0,
            1
        )

        assertEquals(Service.START_NOT_STICKY, startCommandResult)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun onStartCommand_startsForegroundTrackingWhenPermissionGranted() {
        val shadowApp = shadowOf(application)
        shadowApp.grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)

        val controller = Robolectric.buildService(StepCounterService::class.java)
        controller.create()
        val service = controller.get()

        val startCommandResult = service.onStartCommand(
            Intent(application, StepCounterService::class.java).apply {
                action = StepCounterService.ACTION_START
            },
            0,
            1
        )

        assertEquals(Service.START_STICKY, startCommandResult)
        assertFalse(shadowOf(service).isStoppedBySelf)

        val shadowService = shadowOf(service)
        val notification = shadowService.lastForegroundNotification
        assertNotNull(notification)
        assertEquals(StepCounterService.NOTIFICATION_ID, shadowService.lastForegroundNotificationId)

        val notificationManager = application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = notificationManager.getNotificationChannel(StepCounterService.CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)

        controller.destroy()
    }

    @Test
    fun actionPauseAndResume_updatesPreferencesAccurately() {
        kotlinx.coroutines.runBlocking {
            val shadowApp = shadowOf(application)
            shadowApp.grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)

            val controller = Robolectric.buildService(StepCounterService::class.java)
            controller.create()
            val service = controller.get()
            service.onStartCommand(
                Intent(application, StepCounterService::class.java).apply {
                    action = StepCounterService.ACTION_START
                },
                0,
                1
            )

            // Action Pause
            service.onStartCommand(
                Intent(application, StepCounterService::class.java).apply {
                    action = StepCounterService.ACTION_PAUSE
                },
                0,
                2
            )

            val pausedState = kotlinx.coroutines.withTimeout(5000) {
                preferences.isPausedFlow.first { it }
            }
            assertTrue(pausedState)

            // Action Resume
            service.onStartCommand(
                Intent(application, StepCounterService::class.java).apply {
                    action = StepCounterService.ACTION_RESUME
                },
                0,
                3
            )

            val resumedState = kotlinx.coroutines.withTimeout(5000) {
                preferences.isPausedFlow.first { !it }
            }
            assertFalse(resumedState)

            controller.destroy()
        }
    }

    @Test
    fun actionStop_stopsForegroundServiceAndSelf() {
        val shadowApp = shadowOf(application)
        shadowApp.grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)

        val controller = Robolectric.buildService(StepCounterService::class.java)
        controller.create()
        val service = controller.get()
        service.onStartCommand(
            Intent(application, StepCounterService::class.java).apply {
                action = StepCounterService.ACTION_START
            },
            0,
            1
        )

        val stopResult = service.onStartCommand(
            Intent(application, StepCounterService::class.java).apply {
                action = StepCounterService.ACTION_STOP
            },
            0,
            2
        )

        assertEquals(Service.START_NOT_STICKY, stopResult)
        val shadowService = shadowOf(service)
        assertTrue(shadowService.isStoppedBySelf)

        controller.destroy()
    }

    @Test
    fun bootReceiver_startsServiceWhenPermissionGrantedOnBootCompleted() {
        val shadowApp = shadowOf(application)
        shadowApp.grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)

        val receiver = BootReceiver()
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)
        receiver.onReceive(application, intent)

        val startedServiceIntent = shadowApp.nextStartedService
        assertNotNull(startedServiceIntent)
        assertEquals(StepCounterService::class.java.name, startedServiceIntent.component?.className)
        assertEquals(StepCounterService.ACTION_START, startedServiceIntent.action)
    }

    @Test
    fun bootReceiver_startsServiceWhenPermissionGrantedOnPackageReplaced() {
        val shadowApp = shadowOf(application)
        shadowApp.grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)

        val receiver = BootReceiver()
        val intent = Intent(Intent.ACTION_MY_PACKAGE_REPLACED)
        receiver.onReceive(application, intent)

        val startedServiceIntent = shadowApp.nextStartedService
        assertNotNull(startedServiceIntent)
        assertEquals(StepCounterService::class.java.name, startedServiceIntent.component?.className)
        assertEquals(StepCounterService.ACTION_START, startedServiceIntent.action)
    }

    @Test
    fun onStartCommand_stopsCleanlyWhenSensorNotAvailable() {
        val shadowApp = shadowOf(application)
        shadowApp.grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        val sensorManager = application.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val shadowSensorManager = shadowOf(sensorManager)
        stepSensor?.let { shadowSensorManager.removeSensor(it) }

        val controller = Robolectric.buildService(StepCounterService::class.java)
        controller.create()
        val service = controller.get()

        val startCommandResult = service.onStartCommand(
            Intent(application, StepCounterService::class.java).apply {
                action = StepCounterService.ACTION_START
            },
            0,
            1
        )

        assertEquals(Service.START_NOT_STICKY, startCommandResult)
        assertTrue(shadowOf(service).isStoppedBySelf)
        controller.destroy()
    }

    @Test
    fun bootReceiver_doesNotStartServiceWhenSensorMissing() {
        val shadowApp = shadowOf(application)
        shadowApp.grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        val sensorManager = application.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val shadowSensorManager = shadowOf(sensorManager)
        stepSensor?.let { shadowSensorManager.removeSensor(it) }

        val receiver = BootReceiver()
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)
        receiver.onReceive(application, intent)

        val startedServiceIntent = shadowApp.nextStartedService
        assertNull(startedServiceIntent)
    }

    @Test
    fun bootReceiver_doesNotStartServiceWhenPermissionDenied() {
        val shadowApp = shadowOf(application)
        shadowApp.denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION)

        val receiver = BootReceiver()
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)
        receiver.onReceive(application, intent)

        val startedServiceIntent = shadowApp.nextStartedService
        assertNull(startedServiceIntent)
    }
}
