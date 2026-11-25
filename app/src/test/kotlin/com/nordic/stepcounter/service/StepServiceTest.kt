package com.nordic.stepcounter.service

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nordic.stepcounter.data.local.StepDatabase
import com.nordic.stepcounter.data.preferences.ProfilePreferences
import com.nordic.stepcounter.data.repository.StepRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StepServiceTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private lateinit var application: Application
    private lateinit var database: StepDatabase
    private lateinit var preferences: ProfilePreferences
    private lateinit var repository: StepRepository
    private var stepSensor: android.hardware.Sensor? = null
    private var fakeBootCount = 10
    private var fakeCurrentDate = "2026-11-10"
    private var fakeNowMillis = ZonedDateTime.of(2026, 11, 10, 12, 0, 0, 0, ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        val sensorManager = application.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val shadowSensorManager = shadowOf(sensorManager)
        val sensor = org.robolectric.shadows.ShadowSensor.newInstance(android.hardware.Sensor.TYPE_STEP_COUNTER)
        stepSensor = sensor
        shadowSensorManager.addSensor(sensor)

        database = Room.inMemoryDatabaseBuilder(application, StepDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dataStoreScope = TestScope(UnconfinedTestDispatcher())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { tempFolder.newFile("svc_prefs_${UUID.randomUUID()}.preferences_pb") }
        )
        preferences = ProfilePreferences(dataStore)
        repository = StepRepository(
            stepDao = database.stepDao(),
            preferences = preferences,
            bootCountProvider = { fakeBootCount },
            installationIdProvider = { "svc-test-device" },
            currentTimeMillisProvider = { fakeNowMillis },
            zoneIdProvider = { ZoneOffset.UTC }
        )
        StepRepository.setInstanceForTesting(repository)
    }

    @After
    fun tearDown() {
        shadowOf(Looper.getMainLooper()).idle()
        database.close()
        StepRepository.resetInstance()
    }

    @Test
    fun onCreate_stopsSelfIfActivityRecognitionPermissionNotGranted() {
        val shadowApp = shadowOf(application)
        shadowApp.denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION)

        val controller = Robolectric.buildService(StepCounterService::class.java)
        val service = controller.get()
        controller.create()

        assertTrue(shadowOf(service).isStoppedBySelf)
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
    fun onStartCommand_startsForegroundTrackingWhenPermissionGranted(): Unit = runBlocking {
        val shadowApp = shadowOf(application)
        shadowApp.grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)

        val controller = Robolectric.buildService(StepCounterService::class.java)
        controller.create()
        val service = controller.get()
        service.currentDateProvider = { fakeCurrentDate }

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

        withTimeout(5000) {
            while (repository.getDailyRecordDirect(fakeCurrentDate) == null) {
                delay(10)
            }
        }
        controller.destroy()
    }

    @Test
    fun actionPauseResumeAndTogglePause_serializeAccurately(): Unit = runBlocking {
        val shadowApp = shadowOf(application)
        shadowApp.grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)

        val controller = Robolectric.buildService(StepCounterService::class.java)
        controller.create()
        val service = controller.get()
        service.currentDateProvider = { fakeCurrentDate }

        // Establish initial reading so warm pause/resume does not flag pendingResumeBaseline
        service.onStartCommand(
            Intent(application, StepCounterService::class.java).apply {
                action = StepCounterService.ACTION_START
            },
            0,
            1
        )
        service.enqueueSensorReadingForTest(StepSensorReading(1000L))
        withTimeout(5000) {
            while (repository.getSensorStateDirect()?.lastSensorStepValue != 1000L) {
                delay(10)
            }
        }

        // ACTION_PAUSE
        service.onStartCommand(
            Intent(application, StepCounterService::class.java).apply {
                action = StepCounterService.ACTION_PAUSE
            },
            0,
            2
        )
        assertTrue(withTimeout(5000) { repository.isPausedFlow.first { it } })

        // ACTION_RESUME
        service.onStartCommand(
            Intent(application, StepCounterService::class.java).apply {
                action = StepCounterService.ACTION_RESUME
            },
            0,
            3
        )
        assertFalse(withTimeout(5000) { repository.isPausedFlow.first { !it } })

        // ACTION_TOGGLE_PAUSE twice rapidly: true then false
        service.onStartCommand(
            Intent(application, StepCounterService::class.java).apply {
                action = StepCounterService.ACTION_TOGGLE_PAUSE
            },
            0,
            4
        )
        assertTrue(withTimeout(5000) { repository.isPausedFlow.first { it } })

        service.onStartCommand(
            Intent(application, StepCounterService::class.java).apply {
                action = StepCounterService.ACTION_TOGGLE_PAUSE
            },
            0,
            5
        )
        assertFalse(withTimeout(5000) { repository.isPausedFlow.first { !it } })

        controller.destroy()
    }

    @Test
    fun coldStartResume_whilePausedBeforeInitialSensorEvent_ignoresPausedSteps(): Unit = runBlocking {
        val shadowApp = shadowOf(application)
        shadowApp.grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)

        // Prior session paused at 1000 steps with 200 today steps
        repository.processHardwareSensorReading(1000L, fakeCurrentDate, 10)
        repository.processHardwareSensorReading(1200L, fakeCurrentDate, 10)
        repository.setPaused(true)

        val controller = Robolectric.buildService(StepCounterService::class.java)
        controller.create()
        val service = controller.get()
        service.currentDateProvider = { fakeCurrentDate }

        // User taps Resume immediately on cold start before initial sensor event arrives
        service.onStartCommand(
            Intent(application, StepCounterService::class.java).apply {
                action = StepCounterService.ACTION_TOGGLE_PAUSE
            },
            0,
            1
        )
        assertFalse(withTimeout(5000) { repository.isPausedFlow.first { !it } })

        // Initial sensor event arrives milliseconds later with 800 paused transit steps (1200 -> 2000)
        service.enqueueSensorReadingForTest(StepSensorReading(2000L))
        withTimeout(5000) {
            while (repository.getSensorStateDirect()?.lastSensorStepValue != 2000L) {
                delay(10)
            }
        }
        assertEquals(200, repository.getDailyRecordDirect(fakeCurrentDate)?.steps)

        // Real steps taken after resuming DO count
        service.enqueueSensorReadingForTest(StepSensorReading(2045L))
        withTimeout(5000) {
            while (repository.getDailyRecordDirect(fakeCurrentDate)?.steps != 245) {
                delay(10)
            }
        }
        assertEquals(245, repository.getDailyRecordDirect(fakeCurrentDate)?.steps)

        controller.destroy()
    }

    @Test
    fun fifoActor_serializesConcurrentBurstsAndBlocksOutOfOrderPhantomSpike(): Unit = runBlocking {
        val shadowApp = shadowOf(application)
        shadowApp.grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)

        val controller = Robolectric.buildService(StepCounterService::class.java)
        controller.create()
        val service = controller.get()
        service.currentDateProvider = { fakeCurrentDate }
        service.onStartCommand(
            Intent(application, StepCounterService::class.java).apply {
                action = StepCounterService.ACTION_START
            },
            0,
            1
        )

        // Rapid burst: 10000 baseline, +50, +65, +80, plus out-of-order regression 10070
        service.enqueueSensorReadingForTest(StepSensorReading(10000L))
        service.enqueueSensorReadingForTest(StepSensorReading(10050L))
        service.enqueueSensorReadingForTest(StepSensorReading(10065L))
        service.enqueueSensorReadingForTest(StepSensorReading(10080L))
        service.enqueueSensorReadingForTest(StepSensorReading(10070L))
        service.enqueueSensorReadingForTest(StepSensorReading(10090L))

        val finalState = withTimeout(5000) {
            while (repository.getSensorStateDirect()?.lastSensorStepValue != 10090L) {
                delay(10)
            }
            repository.getSensorStateDirect()
        }
        assertEquals(10090L, finalState?.lastSensorStepValue)
        assertEquals(90, repository.getDailyRecordDirect(fakeCurrentDate)?.steps)

        controller.destroy()
    }

    @Test
    fun actionStop_preventsGhostNotificationsFromLateUpdates(): Unit = runBlocking {
        val shadowApp = shadowOf(application)
        shadowApp.grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)

        val controller = Robolectric.buildService(StepCounterService::class.java)
        controller.create()
        val service = controller.get()
        service.currentDateProvider = { fakeCurrentDate }
        service.onStartCommand(
            Intent(application, StepCounterService::class.java).apply {
                action = StepCounterService.ACTION_START
            },
            0,
            1
        )
        withTimeout(5000) {
            while (repository.getDailyRecordDirect(fakeCurrentDate) == null) {
                delay(10)
            }
        }

        val stopResult = service.onStartCommand(
            Intent(application, StepCounterService::class.java).apply {
                action = StepCounterService.ACTION_STOP
            },
            0,
            2
        )

        assertEquals(Service.START_NOT_STICKY, stopResult)
        assertTrue(shadowOf(service).isStoppedBySelf)

        val notificationManager = application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowNm = shadowOf(notificationManager)
        notificationManager.cancelAll()

        // Attempt late notification update after STOP; must be ignored cleanly
        service.updateNotification(999, 6000, false)
        assertEquals(0, shadowNm.allNotifications.size)

        controller.destroy()
    }

    @Test
    fun dateChangedBroadcast_refreshesNotificationForNewDay(): Unit = runBlocking {
        val shadowApp = shadowOf(application)
        shadowApp.grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)

        val controller = Robolectric.buildService(StepCounterService::class.java)
        controller.create()
        val service = controller.get()
        service.currentDateProvider = { fakeCurrentDate }

        service.onStartCommand(
            Intent(application, StepCounterService::class.java).apply {
                action = StepCounterService.ACTION_START
            },
            0,
            1
        )

        // Day 1 has 500 steps
        repository.processHardwareSensorReading(5000L, fakeCurrentDate, 20)
        repository.processHardwareSensorReading(5500L, fakeCurrentDate, 21)
        service.updateNotificationForCurrentState()

        // Roll over date and broadcast ACTION_DATE_CHANGED
        fakeCurrentDate = "2026-11-11"
        fakeNowMillis = ZonedDateTime.of(2026, 11, 11, 0, 1, 0, 0, ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
        application.sendBroadcast(Intent(Intent.ACTION_DATE_CHANGED).setPackage(application.packageName))
        shadowOf(Looper.getMainLooper()).idle()

        val notificationManager = application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Wait until the Channel actor initializes the new day and refreshes the notification
        val title = withTimeout(5000) {
            var latestTitle = ""
            while (true) {
                val notif = shadowOf(notificationManager).getNotification(StepCounterService.NOTIFICATION_ID)
                latestTitle = notif?.extras?.getString(android.app.Notification.EXTRA_TITLE) ?: ""
                if (latestTitle.startsWith("0 ")) break
                delay(10)
            }
            latestTitle
        }
        assertTrue("Expected notification to refresh to 0 steps, got: '$title'", title.startsWith("0 "))

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
