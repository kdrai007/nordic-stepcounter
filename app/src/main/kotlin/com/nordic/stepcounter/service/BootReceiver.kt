package com.nordic.stepcounter.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * BroadcastReceiver responsible for restarting the [StepCounterService] upon device reboot
 * or app package update. Verifies ACTIVITY_RECOGNITION runtime permission before launching
 * the Foreground Service to comply with Android 14/15 Foreground Service launch restrictions.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (StepCounterService.hasActivityRecognitionPermission(context) &&
                StepCounterService.hasStepCounterSensor(context)
            ) {
                val serviceIntent = Intent(context, StepCounterService::class.java).apply {
                    this.action = StepCounterService.ACTION_START
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}
