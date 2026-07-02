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
        if (intent.action in bootActions) {
            UiLogger.log("BOOT", "Received ${intent.action} — starting service")
            val serviceIntent = Intent(context, TelegramService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
