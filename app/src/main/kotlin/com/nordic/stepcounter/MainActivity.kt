package com.nordic.stepcounter

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nordic.stepcounter.service.StepCounterService
import com.nordic.stepcounter.ui.StepViewModel
import com.nordic.stepcounter.ui.screens.HistoryScreen
import com.nordic.stepcounter.ui.screens.ProfileScreen
import com.nordic.stepcounter.ui.screens.TodayScreen
import com.nordic.stepcounter.ui.theme.LocalStepPalette
import com.nordic.stepcounter.ui.theme.NordicStepTheme

class MainActivity : ComponentActivity() {

    private val viewModel: StepViewModel by viewModels {
        StepViewModel.provideFactory(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            var currentTab by rememberSaveable { mutableIntStateOf(0) }
            var hasPermission by remember {
                mutableStateOf(StepCounterService.hasActivityRecognitionPermission(this@MainActivity))
            }
            var hasRequestedPermissionOnce by rememberSaveable { mutableStateOf(false) }
            val hasStepSensor = remember {
                StepCounterService.hasStepCounterSensor(this@MainActivity)
            }

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) {
                hasRequestedPermissionOnce = true
                hasPermission = StepCounterService.hasActivityRecognitionPermission(this@MainActivity)
                if (hasPermission) {
                    startStepServiceIfPermitted()
                }
            }

            val requestPermissionsAction: () -> Unit = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    hasRequestedPermissionOnce &&
                    !shouldShowRequestPermissionRationale(Manifest.permission.ACTIVITY_RECOGNITION)
                ) {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null)
                    )
                    startActivity(intent)
                } else {
                    val permissionsToRequest = buildList {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            add(Manifest.permission.ACTIVITY_RECOGNITION)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    if (permissionsToRequest.isNotEmpty()) {
                        permissionLauncher.launch(permissionsToRequest.toTypedArray())
                    }
                }
            }

            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                hasPermission = StepCounterService.hasActivityRecognitionPermission(this@MainActivity)
                if (hasPermission) {
                    startStepServiceIfPermitted()
                }
            }

            LaunchedEffect(Unit) {
                if (!hasPermission) {
                    requestPermissionsAction()
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
                                icon = { Icon(painterResource(R.drawable.ic_tab_today), contentDescription = "Today") },
                                label = { Text("TODAY") }
                            )
                            NavigationBarItem(
                                selected = currentTab == 1,
                                onClick = { currentTab = 1 },
                                icon = { Icon(painterResource(R.drawable.ic_tab_history), contentDescription = "History") },
                                label = { Text("HISTORY") }
                            )
                            NavigationBarItem(
                                selected = currentTab == 2,
                                onClick = { currentTab = 2 },
                                icon = { Icon(painterResource(R.drawable.ic_tab_profile), contentDescription = "Profile") },
                                label = { Text("PROFILE") }
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
                                    ContextCompat.startForegroundService(this@MainActivity, intent)
                                } else {
                                    viewModel.togglePause()
                                }
                            },
                            hasPermission = hasPermission,
                            hasStepSensor = hasStepSensor,
                            onRequestPermission = requestPermissionsAction,
                            modifier = Modifier.padding(innerPadding)
                        )
                        1 -> HistoryScreen(
                            state = state,
                            modifier = Modifier.padding(innerPadding)
                        )
                        2 -> ProfileScreen(
                            state = state,
                            onSelectGoal = viewModel::updateGoal,
                            onSelectTheme = viewModel::selectTheme,
                            onUpdatePhysicalProfile = viewModel::updatePhysicalProfile,
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
                }
            }
        }
    }

    private fun startStepServiceIfPermitted() {
        if (StepCounterService.hasActivityRecognitionPermission(this) &&
            StepCounterService.hasStepCounterSensor(this)
        ) {
            val intent = Intent(this, StepCounterService::class.java)
            ContextCompat.startForegroundService(this, intent)
        }
    }
}
