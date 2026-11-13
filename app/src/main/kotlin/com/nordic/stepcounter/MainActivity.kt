package com.nordic.stepcounter

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.nordic.stepcounter.data.local.StepDatabase
import com.nordic.stepcounter.data.preferences.ProfilePreferences
import com.nordic.stepcounter.data.repository.StepRepository
import com.nordic.stepcounter.service.StepCounterService
import com.nordic.stepcounter.ui.StepViewModel
import com.nordic.stepcounter.ui.screens.HistoryScreen
import com.nordic.stepcounter.ui.screens.ProfileScreen
import com.nordic.stepcounter.ui.screens.TodayScreen
import com.nordic.stepcounter.ui.theme.LocalStepPalette
import com.nordic.stepcounter.ui.theme.NordicStepTheme

class MainActivity : ComponentActivity() {

    private val viewModel: StepViewModel by viewModels {
        val db = StepDatabase.getInstance(applicationContext)
        val prefs = ProfilePreferences.getInstance(applicationContext)
        StepViewModel.Factory(StepRepository(db.stepDao(), prefs))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val state by viewModel.uiState.collectAsState()
            var currentTab by remember { mutableIntStateOf(0) }

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) {
                startStepServiceIfPermitted()
            }

            LaunchedEffect(Unit) {
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
                } else {
                    startStepServiceIfPermitted()
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
                                icon = { Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = "Today") },
                                label = { Text("TODAY") }
                            )
                            NavigationBarItem(
                                selected = currentTab == 1,
                                onClick = { currentTab = 1 },
                                icon = { Icon(Icons.Default.BarChart, contentDescription = "History") },
                                label = { Text("HISTORY") }
                            )
                            NavigationBarItem(
                                selected = currentTab == 2,
                                onClick = { currentTab = 2 },
                                icon = { Icon(Icons.Default.Person, contentDescription = "Profile") },
                                label = { Text("PROFILE") }
                            )
                        }
                    }
                ) { innerPadding ->
                    when (currentTab) {
                        0 -> TodayScreen(
                            state = state,
                            onTogglePause = viewModel::togglePause,
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
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
                }
            }
        }
    }

    private fun startStepServiceIfPermitted() {
        if (StepCounterService.hasActivityRecognitionPermission(this)) {
            val intent = Intent(this, StepCounterService::class.java)
            ContextCompat.startForegroundService(this, intent)
        }
    }
}
