package com.nordic.stepcounter

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nordic.stepcounter.service.StepCounterService
import com.nordic.stepcounter.ui.StepViewModel
import com.nordic.stepcounter.ui.screens.HistoryScreen
import com.nordic.stepcounter.ui.screens.ProfileScreen
import com.nordic.stepcounter.ui.screens.TodayScreen
import com.nordic.stepcounter.ui.theme.LocalStepPalette
import com.nordic.stepcounter.ui.theme.NordicStepTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: StepViewModel by viewModels {
        StepViewModel.provideFactory(applicationContext)
    }

    @Volatile
    private var isUiStateLoaded = false
    private var serviceBinder: StepCounterService.LocalBinder? = null
    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? StepCounterService.LocalBinder
            serviceBinder = binder
            binder?.setUiForeground(true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBinder = null
            isServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { !isUiStateLoaded }

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { uiState ->
                    if (uiState.isLoaded) {
                        isUiStateLoaded = true
                    }
                }
            }
        }

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            if (state.isLoaded) {
                isUiStateLoaded = true
            }

            var currentTab by rememberSaveable { mutableIntStateOf(0) }
            var hasPermission by remember {
                mutableStateOf(StepCounterService.hasActivityRecognitionPermission(this@MainActivity))
            }
            var hasNotificationPermission by remember {
                mutableStateOf(StepCounterService.hasNotificationPermission(this@MainActivity))
            }
            var hasAttemptedInitialPermissionPrompt by rememberSaveable { mutableStateOf(false) }
            var hasRequestedActivityPermission by rememberSaveable { mutableStateOf(false) }
            var hasRequestedNotificationPermission by rememberSaveable { mutableStateOf(false) }
            val hasStepSensor = remember {
                StepCounterService.hasStepCounterSensor(this@MainActivity)
            }

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { results ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    results.containsKey(Manifest.permission.ACTIVITY_RECOGNITION)
                ) {
                    hasRequestedActivityPermission = true
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    results.containsKey(Manifest.permission.POST_NOTIFICATIONS)
                ) {
                    hasRequestedNotificationPermission = true
                }

                hasPermission = StepCounterService.hasActivityRecognitionPermission(this@MainActivity)
                hasNotificationPermission = StepCounterService.hasNotificationPermission(this@MainActivity)
                if (hasPermission) {
                    startAndBindStepServiceIfPermitted()
                }
            }

            val requestActivityPermissionAction: () -> Unit = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    hasRequestedActivityPermission &&
                    !shouldShowRequestPermissionRationale(Manifest.permission.ACTIVITY_RECOGNITION)
                ) {
                    openAppDetailsSettings()
                } else {
                    val permissionsToRequest = buildList {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            add(Manifest.permission.ACTIVITY_RECOGNITION)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            !hasNotificationPermission
                        ) {
                            add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    if (permissionsToRequest.isNotEmpty()) {
                        permissionLauncher.launch(permissionsToRequest.toTypedArray())
                    }
                }
            }

            val requestNotificationPermissionAction: () -> Unit = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (hasRequestedNotificationPermission &&
                        !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
                    ) {
                        openNotificationSettings()
                    } else {
                        permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                    }
                }
            }

            DisposableEffect(Unit) {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        viewModel.refreshCurrentDate()
                    }
                }
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_DATE_CHANGED)
                    addAction(Intent.ACTION_TIME_CHANGED)
                    addAction(Intent.ACTION_TIMEZONE_CHANGED)
                }
                ContextCompat.registerReceiver(
                    this@MainActivity,
                    receiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                onDispose {
                    runCatching { unregisterReceiver(receiver) }
                }
            }

            LifecycleEventEffect(Lifecycle.Event.ON_START) {
                viewModel.refreshCurrentDate()
                hasPermission = StepCounterService.hasActivityRecognitionPermission(this@MainActivity)
                hasNotificationPermission = StepCounterService.hasNotificationPermission(this@MainActivity)
                if (hasPermission && hasStepSensor) {
                    startAndBindStepServiceIfPermitted()
                }
            }

            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                viewModel.refreshCurrentDate()
                hasPermission = StepCounterService.hasActivityRecognitionPermission(this@MainActivity)
                hasNotificationPermission = StepCounterService.hasNotificationPermission(this@MainActivity)
                if (hasPermission && hasStepSensor) {
                    val binder = serviceBinder
                    if (binder != null) {
                        binder.setUiForeground(true)
                    } else {
                        startAndBindStepServiceIfPermitted()
                    }
                }
            }

            LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
                unbindStepService()
            }

            LaunchedEffect(Unit) {
                if (!hasPermission && !hasAttemptedInitialPermissionPrompt) {
                    hasAttemptedInitialPermissionPrompt = true
                    requestActivityPermissionAction()
                }
            }

            NordicStepTheme(appTheme = state.appTheme) {
                val palette = LocalStepPalette.current
                Scaffold(
                    containerColor = palette.background,
                    bottomBar = {
                        NavigationBar(containerColor = palette.surface) {
                            NavigationBarItem(
                                selected = currentTab == 0,
                                onClick = { currentTab = 0 },
                                icon = {
                                    Icon(
                                        painterResource(R.drawable.ic_tab_today),
                                        contentDescription = stringResource(R.string.nav_today_cd)
                                    )
                                },
                                label = { Text(stringResource(R.string.nav_today_label)) }
                            )
                            NavigationBarItem(
                                selected = currentTab == 1,
                                onClick = { currentTab = 1 },
                                icon = {
                                    Icon(
                                        painterResource(R.drawable.ic_tab_history),
                                        contentDescription = stringResource(R.string.nav_history_cd)
                                    )
                                },
                                label = { Text(stringResource(R.string.nav_history_label)) }
                            )
                            NavigationBarItem(
                                selected = currentTab == 2,
                                onClick = { currentTab = 2 },
                                icon = {
                                    Icon(
                                        painterResource(R.drawable.ic_tab_profile),
                                        contentDescription = stringResource(R.string.nav_profile_cd)
                                    )
                                },
                                label = { Text(stringResource(R.string.nav_profile_label)) }
                            )
                        }
                    }
                ) { innerPadding ->
                    when (currentTab) {
                        0 -> TodayScreen(
                            state = state,
                            onTogglePause = {
                                if (hasPermission && hasStepSensor) {
                                    val intent = Intent(this@MainActivity, StepCounterService::class.java).apply {
                                        action = StepCounterService.ACTION_TOGGLE_PAUSE
                                    }
                                    if (!startStepForegroundServiceSafely(intent)) {
                                        viewModel.togglePause()
                                    }
                                } else {
                                    viewModel.togglePause()
                                }
                            },
                            hasPermission = hasPermission,
                            hasNotificationPermission = hasNotificationPermission,
                            hasStepSensor = hasStepSensor,
                            onRequestPermission = requestActivityPermissionAction,
                            onRequestNotificationPermission = requestNotificationPermissionAction,
                            onDismissDowntimeNotice = viewModel::dismissDowntimeNotice,
                            modifier = Modifier.padding(innerPadding)
                        )
                        1 -> HistoryScreen(
                            state = state,
                            modifier = Modifier.padding(innerPadding)
                        )
                        2 -> ProfileScreen(
                            state = state,
                            onSelectGoal = viewModel::updateDailyGoal,
                            onSelectTheme = viewModel::updateAppTheme,
                            onUpdatePhysicalProfile = viewModel::updatePhysicalProfile,
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
                }
            }
        }
    }

    private fun startStepForegroundServiceSafely(intent: Intent): Boolean {
        return try {
            ContextCompat.startForegroundService(this, intent)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalStateException) {
            false
        }
    }

    private fun startAndBindStepServiceIfPermitted() {
        if (!StepCounterService.hasActivityRecognitionPermission(this) ||
            !StepCounterService.hasStepCounterSensor(this)
        ) {
            return
        }
        val intent = Intent(this, StepCounterService::class.java)
        val started = startStepForegroundServiceSafely(intent)
        if (started && !isServiceBound) {
            runCatching {
                isServiceBound = bindService(intent, serviceConnection, 0)
            }
        }
    }

    private fun unbindStepService() {
        serviceBinder?.setUiForeground(false)
        if (isServiceBound) {
            runCatching { unbindService(serviceConnection) }
            isServiceBound = false
        }
        serviceBinder = null
    }

    private fun openAppDetailsSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
        runCatching { startActivity(intent) }
    }

    private fun openNotificationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
        openAppDetailsSettings()
    }
}
