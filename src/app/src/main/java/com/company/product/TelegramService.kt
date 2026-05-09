package com.company.product

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import kotlinx.coroutines.*

class TelegramService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var deviceManager: DeviceManager
    private var pollingJob: Job? = null
    private var currentCallbackMsgId: Long = 0
    private var currentCallbackChatId: Long = 0

    override fun onCreate() {
        super.onCreate()
        deviceManager = DeviceManager.getInstance(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Telegram Bot · ${deviceManager.deviceId}")
            .setContentText("En écoute...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        startPolling()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pollingJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            deviceManager.lastUpdateId = deviceManager.lastUpdateId
            while (isActive) {
                try {
                    val updates = withContext(Dispatchers.IO) {
                        TelegramBot.getUpdates(deviceManager.lastUpdateId)
                    }
                    for (update in updates) {
                        processUpdate(update)
                        deviceManager.lastUpdateId = update.updateId + 1
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    delay(5_000)
                }
            }
        }
    }

    private fun processUpdate(update: TelegramBot.Update) {
        val msg = update.message
        val cb = update.callbackQuery

        // Handle callback query (inline button press)
        if (cb != null) {
            val data = cb.data ?: ""
            when {
                data.startsWith("select:") -> {
                    val targetDevice = data.removePrefix("select:")
                    if (targetDevice == deviceManager.deviceId) {
                        deviceManager.isActive = true
                        TelegramBot.answerCallbackQuery(cb.id, "Appareil actif")
                        TelegramBot.editMessageReplyMarkup(cb.chatId, cb.messageId, null)
                        TelegramBot.sendMessage(cb.chatId, "Appareil actif : ${deviceManager.deviceId}")
                    } else {
                        deviceManager.isActive = false
                        TelegramBot.answerCallbackQuery(cb.id, "Appareil changé")
                        TelegramBot.editMessageReplyMarkup(cb.chatId, cb.messageId, null)
                    }
                }
            }
            return
        }

        // Handle text message
        if (msg == null || msg.text == null || msg.text.isEmpty()) return

        val chatId = msg.chatId
        val text = msg.text.trim()

        // Save owner chat_id on first contact
        if (deviceManager.ownerChatId == 0L) {
            deviceManager.ownerChatId = chatId
        }

        // Only respond to the owner
        if (chatId != deviceManager.ownerChatId) return

        when {
            text == "/start" -> {
                deviceManager.ownerChatId = chatId
                TelegramBot.sendMessage(chatId, "Appareil enregistré : ${deviceManager.deviceId}\n\nTape /devices pour gérer tes appareils.")
            }

            text == "/devices" -> {
                val isActive = if (deviceManager.isActive) " (actif)" else ""
                val keyboard = org.json.JSONObject().apply {
                    put("inline_keyboard", org.json.JSONArray().apply {
                        put(org.json.JSONArray().apply {
                            put(org.json.JSONObject().apply {
                                put("text", "Sélectionner ${deviceManager.deviceId}")
                                put("callback_data", "select:${deviceManager.deviceId}")
                            })
                        })
                    })
                }
                TelegramBot.sendMessage(
                    chatId,
                    "Appareil : ${deviceManager.deviceId}$isActive\nAndroid ${Build.VERSION.RELEASE} | ${Build.MANUFACTURER}",
                    keyboard
                )
            }

            text == "/ping" && deviceManager.isActive -> {
                TelegramBot.sendMessage(chatId, "Pong depuis ${deviceManager.deviceId}")
            }

            text == "/info" && deviceManager.isActive -> {
                val info = buildString {
                    appendLine("Appareil : ${deviceManager.deviceId}")
                    appendLine("Modèle : ${Build.MODEL} (${Build.MANUFACTURER})")
                    appendLine("Android : ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                }
                TelegramBot.sendMessage(chatId, info)
            }

            text.startsWith("/") && !deviceManager.isActive -> {
                TelegramBot.sendMessage(
                    chatId,
                    "Aucun appareil sélectionné. Tape /devices pour choisir un appareil."
                )
            }

            text == "/ping" && !deviceManager.isActive -> {
                // Ignored — not the active device
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Telegram Bot",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Service de connexion Telegram" }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "telegram_bot"
        private const val NOTIFICATION_ID = 420
    }
}
