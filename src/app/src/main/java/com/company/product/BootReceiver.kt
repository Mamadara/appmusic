package com.company.product

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {

    private val bootActions = setOf(
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_LOCKED_BOOT_COMPLETED,
        "android.intent.action.QUICKBOOT_POWERON",
        "com.htc.intent.action.QUICKBOOT_POWERON",
        Intent.ACTION_MY_PACKAGE_REPLACED
    )

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            in bootActions -> {
                UiLogger.log("BOOT", "Received ${intent.action} — starting service")
                TelegramService.scheduleWatchdog(context)
                startService(context)
            }
            TelegramService.ACTION_WATCHDOG -> {
                UiLogger.log("WATCHDOG", "Fired — checking service")
                TelegramService.scheduleWatchdog(context)
                if (!TelegramService.isRunning) {
                    UiLogger.log("WATCHDOG", "Service dead — restarting")
                    startService(context)
                }
            }
        }
    }

    private fun startService(context: Context) {
        val serviceIntent = Intent(context, TelegramService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
